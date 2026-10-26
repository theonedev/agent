package io.onedev.agent.job;

import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static org.apache.commons.compress.archivers.tar.TarArchiveEntry.DEFAULT_FILE_MODE;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.SandboxSettings;
import io.onedev.agent.SandboxUtils;
import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.commons.utils.command.ProcessTreeKiller;
import io.onedev.k8shelper.Action;
import io.onedev.k8shelper.BuildImageFacade;
import io.onedev.k8shelper.CheckoutFacade;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.JobHelper;
import io.onedev.k8shelper.PowerShellInterpreterFacade;
import io.onedev.k8shelper.PruneBuilderCacheFacade;
import io.onedev.k8shelper.RunContainerFacade;
import io.onedev.k8shelper.RunImagetoolsFacade;
import io.onedev.k8shelper.ServerSideFacade;
import io.onedev.k8shelper.ServiceFacade;
import io.onedev.k8shelper.SetupCacheFacade;

/** Only lifecycle and transport run on the host. The runner owns the entire job in the sandbox. */
public class SandboxJobController {

	public static final String BUILD_DIR = "/onedev-build";

	private final String executable;

	private final SandboxSettings settings;

	private String name;

	private volatile boolean ready;

	public SandboxJobController(String executable, SandboxSettings settings) {
		this.executable = executable;
		this.settings = settings;
	}

	private Commandline newSbxExecCommand(String... args) {
		return new Commandline(executable).addArgs("exec").addArgs(args);
	}

	private ProcessKiller newSandboxKiller(TaskLogger logger) {
		return (process, executionId) -> {
			try {
				SandboxUtils.stopSandbox(executable, name, logger);
			} finally {
				new ProcessTreeKiller().kill(process, executionId);
			}
		};
	}

	public boolean runJob(JobData data, List<ServiceFacade> services, File trustCertsDir,
						  Map<String, String> attributes, TaskLogger logger) {
		name = "build-" + data.getExecutorName() + "-" + data.getProjectId() + "-"
				+ data.getBuildNumber() + "-" + data.getSubmitSequence();
		var manifest = new HashMap<String, Object>();
		manifest.put("serverUrl", settings.serverUrl());
		manifest.put("token", data.getJobToken());
		manifest.put("refName", data.getRefName());
		manifest.put("commitHash", data.getCommitHash());
		manifest.put("attributes", attributes);
		manifest.put("services", services);
		manifest.put("actions", actions(data.getActions()));
		SecretMasker.push(data.getSecretMasker());
		try {
			return runJob(manifest, trustCertsDir, logger);
		} finally {
			SecretMasker.pop();
		}
	}

	public void testSandbox(@Nullable String image, File trustCertsDir, TaskLogger logger) {
		var token = UUID.randomUUID().toString();
		name = "sandbox-test-" + token.substring(0, 8);
		var manifest = new HashMap<String, Object>();
		manifest.put("test", true);
		manifest.put("testImage", image);
		manifest.put("serverUrl", settings.serverUrl());
		manifest.put("token", token);
		if (!runJob(manifest, trustCertsDir, logger))
			throw new ExplicitException("Sandbox executor test failed");
	}

	private boolean runJob(Map<String, Object> manifest, File trustCertsDir, TaskLogger logger) {
		manifest.put("alwaysPullContainerImage", settings.alwaysPullContainerImage());
		manifest.put("runOptions", settings.runOptions());
		manifest.put("networkOptions", settings.networkOptions());
		manifest.put("registryLogins", settings.registryLogins());
		boolean attempted = false;
		Throwable failure = null;
		try {
			var archive = SandboxUtils.buildRuntimeArchive("job", manifest, trustCertsDir, DEFAULT_FILE_MODE);

			logger.log("Creating sandbox '" + name + "'...");
			attempted = true;
			SandboxUtils.createSandbox(executable, name, settings, List.of(), logger);
			SandboxUtils.initializeDirectory(executable, name, BUILD_DIR, newSandboxKiller(logger), logger);
			newSbxExecCommand("-i", name, "tar", "-xf", "-", "-C", BUILD_DIR)
					.processKiller(newSandboxKiller(logger))
					.execute(newInfoLogger(logger), newWarningLogger(logger), new ByteArrayInputStream(archive))
					.checkReturnCode();
			ready = true;
			return executeRunner(logger);
		} catch (IOException e) {
			failure = e;
			throw new RuntimeException(e);
		} catch (RuntimeException | Error e) {
			failure = e;
			throw e;
		} finally {
			ready = false;
			boolean interrupted = Thread.interrupted();
			try {
				if (attempted) {
					try {
						SandboxUtils.deleteSandbox(executable, name, logger);
					} catch (RuntimeException e) {
						if (failure != null) {
							failure.addSuppressed(e);
							logger.error("Unable to delete sandbox '" + name + "': " + e.getMessage());
						} else {
							throw e;
						}
					}
				}
			} finally {
				if (interrupted)
					Thread.currentThread().interrupt();
			}
		}
	}

	private boolean executeRunner(TaskLogger logger) {
		var runningStep = new AtomicReference<String>();
		var output = new LineConsumer("UTF-8") {
			private final String sessionId = UUID.randomUUID().toString();

			private boolean pendingEmptyLine;

			@Override
			public void consume(String line) {
				var event = JobHelper.parseStepEventMessage(line);
				// Hold back one empty line to consume the separator inserted by log_marker.
				if (pendingEmptyLine && event == null && JobUtils.parsePhaseMessage(line) == null)
					logger.log("", sessionId);
				pendingEmptyLine = line.isEmpty();
				if (pendingEmptyLine)
					return;
				if (event != null) {
					if (event.kind() == JobHelper.StepEventKind.START)
						runningStep.set(event.step());
					else
						runningStep.set(null);
				}
				logger.log(line, sessionId);
			}

			@Override
			public void close() throws IOException {
				super.close();
				if (pendingEmptyLine) {
					pendingEmptyLine = false;
					logger.log("", sessionId);
				}
			}
		};
		var outcome = JobHelper.StepEventKind.FAILED;
		try {
			var exitCode = newSbxExecCommand(name, "python3", "-u", BUILD_DIR + "/sandbox-job.py", BUILD_DIR)
					.processKiller(newSandboxKiller(logger))
					.execute(output, newWarningLogger(logger)).getReturnCode();
			if (runningStep.get() != null) {
				logger.error("Sandbox job runner exited before the current step completed (sandbox: " + name
						+ ", exit code: " + exitCode + "). The sandbox may have been stopped externally.");
				return false;
			}
			return exitCode == 0;
		} catch (RuntimeException e) {
			outcome = JobUtils.getStepOutcome(e);
			throw e;
		} finally {
			// If the Python runner stops before reporting the current step's outcome,
			// report it here and mark the start of finalization.
			if (runningStep.get() != null) {
				logger.log(JobHelper.buildStepEventMessage(outcome, runningStep.get()));
				logger.log(JobUtils.buildPhaseMessage(JobHelper.FINALIZATION));
			}
		}
	}

	public void resume(TaskLogger logger) {
		if (ready) {
			newSbxExecCommand(name, "sh", "-c",
					"user=$(id -u); if [ \"$user\" = 0 ] || [ \"$(stat -c %u \"$1\")\" = \"$user\" ]; "
						+ "then exec touch \"$2\"; else sudo -n -- touch \"$2\" || { code=$?; "
						+ "echo 'Unable to resume sandbox job using sudo. Ensure sudo is installed in the template image "
						+ "and its default user has passwordless sudo access; OneDev will not prompt for a password.' >&2; "
						+ "exit \"$code\"; }; fi",
					"sh", BUILD_DIR, BUILD_DIR + "/continue")
					.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
		}
	}

	public Commandline openShell() {
		if (!ready)
			throw new ExplicitException("Sandbox not running");
		// Elevate only while another user owns the build directory. The dispatcher restores
		// the template identity when opening a shell outside a container.
		return newSbxExecCommand("-it", "-e", "ONEDEV_WORKDIR=" + BUILD_DIR + "/work", name, "python3", "-c", """
				import os, sys
				args = [sys.executable, *sys.argv[1:], str(os.geteuid()), str(os.getegid()), ','.join(map(str, os.getgroups()))]
				if os.geteuid() != 0 and os.stat(sys.argv[2]).st_uid != os.geteuid():
				    args = ['sudo', '-n', '--', 'env', *[k + '=' + v for k, v in os.environ.items()], *args]
				try:
				    os.execvp(args[0], args)
				except FileNotFoundError:
				    if args[0] == 'sudo':
				        sys.exit('Unable to open build terminal: sudo is not installed in the sandbox. Install sudo in the template image and grant its default user passwordless sudo access.')
				    raise
				""", BUILD_DIR + "/sandbox-job.py", BUILD_DIR, "--shell");
	}

	static List<Map<String, Object>> actions(List<Action> actions) {
		var result = new ArrayList<Map<String, Object>>();
		for (var action : actions) {
			var node = new HashMap<String, Object>();
			node.put("condition", action.getCondition().name());
			node.put("optional", action.isOptional());
			var step = action.getExecutable();
			if (step instanceof CompositeFacade composite) {
				node.put("type", "composite");
				node.put("actions", actions(composite.getActions()));
			} else if (step instanceof CommandFacade command) {
				node.put("type", "command");
				node.put("image", command.getImage());
				node.put("commands", command.normalizeCommands(command.getCommands()));
				node.put("executable", command.getExecutable());
				node.put("options", command.getScriptOptions());
				node.put("extension", command.getScriptExtension());
				node.put("env", command.getEnvMap());
				node.put("runAs", command.getRunAs());
				node.put("registryLogins", command.getRegistryLogins());
				node.put("useTTY", command.isUseTTY());
				node.put("pauseCommand", command.getInterpreter() instanceof PowerShellInterpreterFacade
						? "& sh \"$env:ONEDEV_WORKDIR/../pause.sh\"" : "sh \"$ONEDEV_WORKDIR/../pause.sh\"");
			} else if (step instanceof RunContainerFacade container) {
				node.put("type", "container");
				node.put("image", container.getImage());
				node.put("runAs", container.getRunAs());
				node.put("args", container.getArgs());
				node.put("env", container.getEnvMap());
				node.put("workingDir", container.getWorkingDir());
				node.put("volumeMounts", container.getVolumeMounts());
				node.put("registryLogins", container.getRegistryLogins());
				node.put("useTTY", container.isUseTTY());
			} else if (step instanceof BuildImageFacade image) {
				node.put("type", "build-image");
				node.put("buildPath", image.getBuildPath());
				node.put("dockerfile", image.getDockerfile());
				node.put("platforms", image.getPlatforms());
				node.put("moreOptions", image.getMoreOptions());
				node.put("registryLogins", image.getRegistryLogins());
				if (image.getOutput() instanceof BuildImageFacade.RegistryOutput registry)
					node.put("tags", registry.getTags());
				else if (image.getOutput() instanceof BuildImageFacade.OCIOutput oci)
					node.put("destPath", oci.getDestPath());
				else
					throw new ExplicitException("Unsupported image build output: " + image.getOutput().getClass().getSimpleName());
			} else if (step instanceof RunImagetoolsFacade imagetools) {
				node.put("type", "imagetools");
				node.put("arguments", imagetools.getArguments());
				node.put("registryLogins", imagetools.getRegistryLogins());
			} else if (step instanceof PruneBuilderCacheFacade pruneBuilderCache) {
				node.put("type", "prune-builder-cache");
				node.put("options", pruneBuilderCache.getOptions());
			} else if (step instanceof CheckoutFacade checkout) {
				node.put("type", "checkout");
				node.put("path", checkout.getCheckoutPath());
				node.put("cloneInfo", checkout.getCloneInfo().toString());
				node.put("depth", checkout.getCloneDepth());
				node.put("lfs", checkout.isWithLfs());
				node.put("submodules", checkout.isWithSubmodules());
			} else if (step instanceof SetupCacheFacade cache) {
				node.put("type", "cache");
				var config = cache.getCacheConfig();
				node.put("key", config.getKey());
				node.put("entries", config.getEntries());
				node.put("strategy", config.getUploadStrategy().name());
				node.put("projectPath", config.getUploadProjectPath());
				node.put("accessToken", config.getUploadAccessToken());
				if (config.getChecksumFiles() != null) {
					node.put("checksumIncludes", config.getChecksumFiles().getLeft());
					node.put("checksumExcludes", config.getChecksumFiles().getRight());
				}
			} else if (step instanceof ServerSideFacade server) {
				node.put("type", "server");
				node.put("path", server.getSourcePath());
				node.put("includes", server.getIncludeFiles());
				node.put("excludes", server.getExcludeFiles());
				node.put("placeholders", server.getPlaceholders());
			} else {
				node.put("type", "unsupported");
				node.put("description", step.getClass().getSimpleName());
			}
			result.add(node);
		}
		return result;
	}
}
