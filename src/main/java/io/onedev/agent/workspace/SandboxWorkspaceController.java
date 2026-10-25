package io.onedev.agent.workspace;

import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static io.onedev.k8shelper.WorkspaceHelper.WORKSPACE_PATH;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.onedev.agent.AwaitableFutureTask;
import io.onedev.agent.SandboxSettings;
import io.onedev.agent.SandboxUtils;
import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ProcessTreeKiller;
import io.onedev.k8shelper.ScriptConfig;
import io.onedev.k8shelper.WorkspaceHelper;

/** Workspace processes and files live directly in the sandbox. The host only controls its lifecycle. */
public class SandboxWorkspaceController {

	private final String executable;

	private final ProvisionSandboxWorkspaceData data;

	private final TaskLogger logger;

	private volatile boolean ready;

	private AwaitableFutureTask<Void> runnerTask;

	public SandboxWorkspaceController(String executable, ProvisionSandboxWorkspaceData data, TaskLogger logger) {
		this.executable = executable;
		this.data = data;
		this.logger = logger;
	}

	public static String getSandboxName(Long projectId, Long workspaceNumber) {
		return "workspace-" + projectId + "-" + workspaceNumber;
	}

	private Commandline buildSbxCommand(String... args) {
		return new Commandline(executable).addArgs(args);
	}

	private Commandline buildSbxExecCommand(String... args) {
		return buildSbxCommand("exec", data.sandboxName()).addArgs(args);
	}

	private Commandline buildRunnerCommand(String... args) {
		return buildSbxExecCommand("python3", "-u", WORKSPACE_PATH + "/sandbox-workspace.py", WORKSPACE_PATH).addArgs(args);
	}

	private void runCommand(Commandline command) {
		command.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
	}

	private String readCommandOutput(Commandline command) {
		var stdout = new ByteArrayOutputStream();
		command.execute(stdout, newWarningLogger(logger)).checkReturnCode();
		return stdout.toString(UTF_8).trim();
	}

	public static void deleteSandbox(String executable, String name, TaskLogger logger) {
		SandboxUtils.deleteSandbox(executable, name, logger);
	}

	public static void testSandbox(String executable, SandboxSettings settings, @Nullable File trustCertsDir, TaskLogger logger) {
		var token = UUID.randomUUID().toString();
		var data = new ProvisionSandboxWorkspaceData(token, "sandbox-test-" + token.substring(0, 8), settings,
				null, List.of(), List.of(), List.of(), new ScriptConfig(null, null, ".sh", "sh", new String[0]),
				Map.of(), List.of());
		var controller = new SandboxWorkspaceController(executable, data, logger);
		try {
			controller.prepareSandbox(trustCertsDir);
			controller.runCommand(controller.buildRunnerCommand("--test"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			boolean interrupted = Thread.interrupted();
			try {
				deleteSandbox(executable, data.sandboxName(), logger);
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}
	}

	private void prepareSandbox(@Nullable File trustCertsDir) throws IOException {
		if (!SandboxUtils.isSandboxExists(executable, data.sandboxName(), logger)) {
			logger.log("Creating sandbox '" + data.sandboxName() + "'...");
			SandboxUtils.createSandbox(executable, data.sandboxName(), data.settings(), data.ports(), logger);
		} else {
			logger.log("Resuming sandbox '" + data.sandboxName() + "'...");
			// Stop processes left behind if the previous host/agent died without cleaning up.
			SandboxUtils.stopSandbox(executable, data.sandboxName(), logger);
			SandboxUtils.allowAllNetworkAccess(executable, data.sandboxName(), logger);
		}
		SandboxUtils.initializeDirectory(executable, data.sandboxName(), WORKSPACE_PATH, new ProcessTreeKiller(), logger);
		// Only replace session credentials and lifecycle markers.
		runCommand(buildSbxExecCommand("sh", "-c", "mkdir -p " + WORKSPACE_PATH + "; rm -rf " + WORKSPACE_PATH
				+ "/trust-certs; rm -f " + WORKSPACE_PATH + "/trust-certs.pem " + WORKSPACE_PATH
				+ "/.shutdown " + WORKSPACE_PATH + "/.workspace-ready"));
		sendConfiguration(trustCertsDir);
	}

	public WorkspaceProvisioned provisionWorkspace(@Nullable File trustCertsDir, String portHost) {
		try {
			prepareSandbox(trustCertsDir);
			runnerTask = new AwaitableFutureTask<>(() -> {
				runCommand(buildRunnerCommand());
				return null;
			});
			Bootstrap.executorService.execute(runnerTask);
			while (true) {
				if (runnerTask.isDone()) {
					runnerTask.get();
					throw new ExplicitException("Workspace stopped before setup completed");
				}
				if (readCommandOutput(buildSbxExecCommand("sh", "-c", "test -f " + WORKSPACE_PATH + "/.workspace-ready && echo ready || true")).equals("ready"))
					break;
				Thread.sleep(1000);
			}
			var mappings = getPublishedPorts();
			String tailscaleIp = null;
			var setup = data.scriptConfig().getSetupCommands();
			if (!mappings.isEmpty() && setup != null && setup.contains("tailscale")) {
				var stdout = new ByteArrayOutputStream();
				if (buildSbxExecCommand("tailscale", "ip").execute(stdout, new ByteArrayOutputStream()).getReturnCode() == 0)
					tailscaleIp = stdout.toString(UTF_8).lines().map(String::trim).filter(it -> !it.isEmpty()).findFirst().orElse(null);
			}
			ready = true;
			return new WorkspaceProvisioned(data.token(), portHost, tailscaleIp, mappings);
		} catch (Throwable e) {
			try {
				stopSandbox();
			} catch (Throwable cleanup) {
				e.addSuppressed(cleanup);
			}
			if (e instanceof InterruptedException)
				Thread.currentThread().interrupt();
			throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
		}
	}

	private void sendConfiguration(@Nullable File trustCertsDir) throws IOException {
		var manifest = new HashMap<String, Object>();
		manifest.put("token", data.token());
		manifest.put("serverUrl", data.settings().serverUrl());
		var git = data.gitSettings();
		if (git != null) {
			manifest.put("cloneInfo", git.getCloneInfo().toString());
			manifest.put("commitHash", git.getCommitHash());
			manifest.put("refName", git.getBranch() != null ? "refs/heads/" + git.getBranch() : "");
			manifest.put("userName", git.getUserName());
			manifest.put("userEmail", git.getUserEmail());
			manifest.put("lfs", git.isRetrieveLfs());
			manifest.put("submodules", git.isRetrieveSubmodules());
		}
		manifest.put("caches", data.cacheConfigs().stream().map(config -> {
			var cache = new HashMap<String, Object>();
			cache.put("key", config.getKey());
			cache.put("entries", config.getEntries());
			cache.put("strategy", config.getUploadStrategy().name());
			cache.put("projectPath", config.getUploadProjectPath());
			cache.put("accessToken", config.getUploadAccessToken());
			if (config.getChecksumFiles() != null) {
				cache.put("checksumIncludes", config.getChecksumFiles().getLeft());
				cache.put("checksumExcludes", config.getChecksumFiles().getRight());
			}
			return cache;
		}).toList());
		manifest.put("userDatas", data.userDatas());
		manifest.put("configFiles", data.configFiles());
		manifest.put("script", data.scriptConfig());
		manifest.put("env", WorkspaceHelper.buildEnvVars(data.envVars(), data.settings().serverUrl(), data.token(),
				trustCertsDir != null && trustCertsDir.exists() ? WORKSPACE_PATH + "/trust-certs.pem" : null,
				WORKSPACE_PATH + "/work"));
		var bytes = SandboxUtils.buildRuntimeArchive("workspace", manifest, trustCertsDir, 0600);
		buildSbxCommand("exec", "-i", data.sandboxName(), "tar", "-xf", "-", "-C", WORKSPACE_PATH)
				.execute(newInfoLogger(logger), newWarningLogger(logger), new ByteArrayInputStream(bytes))
				.checkReturnCode();
	}

	private Map<Integer, Integer> getPublishedPorts() throws IOException {
		// Exposed ports belong to the persistent sandbox. sbx reassigns their host ports
		// on startup, so read the current mappings without changing the original exposure.
		return parsePorts(new ObjectMapper().readTree(readCommandOutput(buildSbxCommand("ports", data.sandboxName(), "--json"))));
	}

	static Map<Integer, Integer> parsePorts(JsonNode result) {
		var mappings = new HashMap<Integer, Integer>();
		for (var port : result) {
			int sandboxPort = port.path("sandbox_port").asInt();
			int hostPort = port.path("host_port").asInt();
			if (sandboxPort > 0 && hostPort > 0)
				mappings.put(sandboxPort, hostPort);
		}
		return mappings;
	}

	public GitExecutionResult executeGit(String[] args) {
		checkReady();
		var stdout = new ByteArrayOutputStream();
		var stderr = new ByteArrayOutputStream();
		int code = buildSbxExecCommand("git", "-C", WORKSPACE_PATH + "/work", "-c", "safe.directory=*").addArgs(args)
				.execute(stdout, stderr).getReturnCode();
		return new GitExecutionResult(stdout.toByteArray(), stderr.toByteArray(), code);
	}

	@Nullable
	public FileData readFile(String path) {
		checkReady();
		var stdout = new ByteArrayOutputStream();
		if (buildSbxExecCommand("cat", "--", WORKSPACE_PATH + "/work/" + path).execute(stdout, new ByteArrayOutputStream()).getReturnCode() != 0)
			return null;
		return new FileData(path, stdout.toByteArray(), stdout.size());
	}

	public Commandline openShell(String shellId, String shellExecutable) {
		checkReady();
		return buildSbxCommand("exec", "-it", data.sandboxName(), "python3",
				WORKSPACE_PATH + "/sandbox-workspace.py", WORKSPACE_PATH, "--shell", shellId, shellExecutable);
	}

	private void checkReady() {
		if (!ready)
			throw new ExplicitException("Sandbox workspace not running");
	}

	public void closeShell(String shellId) {
		if (ready)
			runCommand(buildSbxExecCommand("tmux", "-L", "onedev-" + shellId, "kill-server"));
	}

	public void awaitRunner() {
		try {
			runnerTask.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		} finally {
			stopSandbox();
		}
	}

	public void stopSandbox() {
		ready = false;
		boolean interrupted = Thread.interrupted();
		try {
			if (runnerTask != null && !runnerTask.isDone()) {
				runCommand(buildSbxExecCommand("touch", WORKSPACE_PATH + "/.shutdown"));
				try {
					runnerTask.get(120, TimeUnit.SECONDS);
				} catch (ExecutionException e) {
					logger.error("Workspace cleanup failed", e.getCause());
				} catch (TimeoutException e) {
					throw new ExplicitException("Timed out waiting for workspace teardown");
				} catch (InterruptedException e) {
					interrupted = true;
					throw new RuntimeException(e);
				}
			}
		} finally {
			try {
				if (runnerTask != null) {
					runnerTask.cancel(true);
					runnerTask.await();
				}
			} finally {
				try {
					if (SandboxUtils.isSandboxExists(executable, data.sandboxName(), logger))
						SandboxUtils.stopSandbox(executable, data.sandboxName(), logger);
				} finally {
					if (interrupted)
						Thread.currentThread().interrupt();
				}
			}
		}
	}
}
