package io.onedev.agent.workspace;

import static io.onedev.agent.AgentUtils.newErrorLogger;
import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.Agent;
import io.onedev.agent.AgentUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.CacheProvisioner;
import io.onedev.k8shelper.SetupScriptConfig;
import io.onedev.k8shelper.UserDataProvisioner;
import io.onedev.k8shelper.WorkspaceHelper;

public class WorkspaceUtils {

	public static GitExecutionResult executeGit(
			@Nullable String dockerSock, String containerName, String[] gitArgs) {
		return executeGit(Agent.dockerPath, dockerSock, containerName, "/onedev-workspace/work", gitArgs);
	}

	public static GitExecutionResult executeGit(File workDir, String[] gitArgs) {
		var git = new Commandline(Agent.gitPath);
		git.workingDir(workDir);
		git.args(gitArgs);

		var stdoutStream = new ByteArrayOutputStream();
		var stderrStream = new ByteArrayOutputStream();
		var returnCode = git.execute(stdoutStream, stderrStream).getReturnCode();
		return new GitExecutionResult(stdoutStream.toByteArray(), stderrStream.toByteArray(), returnCode);
	}

	public static GitExecutionResult executeGit(@Nullable String dockerExecutable,
			@Nullable String dockerSock, String containerName, String containerWorkDirPath,
			String[] gitArgs) {
		var docker = AgentUtils.newDocker(dockerExecutable, dockerSock);
		docker.addArgs("exec", "-w", containerWorkDirPath, containerName, "git");
		docker.addArgs(gitArgs);

		var stdoutStream = new ByteArrayOutputStream();
		var stderrStream = new ByteArrayOutputStream();
		var returnCode = docker.execute(stdoutStream, stderrStream).getReturnCode();
		return new GitExecutionResult(stdoutStream.toByteArray(), stderrStream.toByteArray(), returnCode);
	}

	@Nullable
	public static FileData readFileData(File workspaceDir, String path) {
		try {
			return FileData.from(new File(new File(workspaceDir, "work"), path));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static void setCommonDockerRunOptions(Commandline docker, String containerName, String runAs,
			boolean alwaysPullImage, @Nullable String cpuLimit, @Nullable String memoryLimit) {
		docker.args("run", "--rm", "--name=" + containerName);
		if (alwaysPullImage)
			docker.addArgs("--pull=always");
		docker.addArgs("--user", runAs);
		if (cpuLimit != null)
			docker.addArgs("--cpus", cpuLimit);
		if (memoryLimit != null)
			docker.addArgs("--memory", memoryLimit);
	}

	public static void awaitContainerReady(Future<?> containerFuture, File successfulFile) {
		while (true) {
			if (containerFuture.isDone()) {
				try {
					containerFuture.get();
					throw new ExplicitException("Docker container stopped unexpectedly");
				} catch (InterruptedException | ExecutionException e) {
					throw new RuntimeException(e);
				}
			} else if (successfulFile.exists()) {
				break;
			}
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

	public static void deleteContainerIfExist(Commandline docker, String containerName, TaskLogger logger) {
		docker.args("rm", "-f", containerName);
		docker.execute(newInfoLogger(logger), new LineConsumer() {
			@Override
			public void consume(String line) {
				if (!line.equals(containerName) && !line.contains("No such container"))
					logger.warning(line);
			}
		});
	}

	public static Map<Integer, Integer> getPublishedPorts(Commandline docker, String containerName, Collection<Integer> containerPorts) {
		docker.args("port", containerName);

		var stdout = new ByteArrayOutputStream();
		var stderr = new ByteArrayOutputStream();
		var result = docker.execute(stdout, stderr);
		if (result.getReturnCode() != 0)
			throw new ExplicitException(new String(stderr.toByteArray(), UTF_8).trim());

		var output = new String(stdout.toByteArray(), UTF_8).trim();
		var portMappings = new HashMap<Integer, Integer>();
		for (var line : output.split("\\R")) {
			var fields = line.split("\\s+->\\s+", 2);
			if (fields.length == 2) {
				var slashIndex = fields[0].indexOf('/');
				var colonIndex = fields[1].lastIndexOf(':');
				try {
					if (slashIndex != -1 && colonIndex != -1
							&& fields[0].substring(slashIndex + 1).equals("tcp")) {
						var containerPort = Integer.parseInt(fields[0].substring(0, slashIndex));
						if (containerPorts.contains(containerPort)
								&& !portMappings.containsKey(containerPort))
							portMappings.put(containerPort,
									Integer.parseInt(fields[1].substring(colonIndex + 1)));
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}
		for (int containerPort : containerPorts) {
			if (!portMappings.containsKey(containerPort))
				throw new ExplicitException(
						"Unable to determine host port mapped to container port " + containerPort);
		}
		return portMappings;
	}

	public static void setupShellProvisioned(SetupScriptConfig setupScriptConfig, File workspaceDir, 
			Map<String, String> envVars, TaskLogger logger) {
		logger.log("Running setup commands...");
		var commandDir = new File(workspaceDir, "command");
		FileUtils.createDir(commandDir);
		var scriptFile = new File(commandDir, "setup" + setupScriptConfig.getScriptExtension());
		try {
			FileUtils.writeStringToFile(scriptFile, setupScriptConfig.getSetupCommands(), UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		var cmdline = new Commandline(setupScriptConfig.getScriptExecutable());
		cmdline.addArgs(setupScriptConfig.getScriptOptions());
		cmdline.workingDir(new File(workspaceDir, "work")).envs(envVars);
		cmdline.addArgs(scriptFile.getAbsolutePath());
		cmdline.execute(newInfoLogger(logger), newErrorLogger(logger)).checkReturnCode();
	}

	public static void setupRepository(File workspaceDir, GitSettings gitSettings,
			String runtimeWorkspaceDirPath, TaskLogger logger) {
		var cloneInfo = gitSettings.getCloneInfo();
		var cloneUrl = cloneInfo.getCloneUrl();

		WorkspaceHelper.setupRepository(workspaceDir, new Commandline(Agent.gitPath),
				gitSettings.getUserName(), gitSettings.getUserEmail(), cloneInfo, 
				gitSettings.getCommitHash(), gitSettings.getBranch(), gitSettings.isRetrieveLfs(),
				Agent.getTrustCertsDir(), runtimeWorkspaceDirPath, cloneUrl,
				newInfoLogger(logger), newWarningLogger(logger));
	}

	public static void testTmuxAvailability(Commandline tmux, TaskLogger logger) {
		logger.log("Checking if tmux exists...");

		tmux.args("-V");

		var result = tmux.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
			}
			
		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.error(line);
			}
			
		});
		if (result.getReturnCode() == 0) {
			logger.log("tmux found");
		} else {
			throw new ExplicitException("tmux not found");
		}
	}

	public static void upload(File workspaceDir, UserDataProvisioner userDataProvisioner, 
				List<CacheProvisioner> cacheProvisioners, TaskLogger logger) {
		userDataProvisioner.upload(workspaceDir, logger);		
		for (var cacheProvisioner : cacheProvisioners) 
			cacheProvisioner.upload(workspaceDir, logger);
	}

}
