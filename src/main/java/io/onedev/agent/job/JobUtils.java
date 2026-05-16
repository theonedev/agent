package io.onedev.agent.job;

import static io.onedev.k8shelper.JobHelper.BUILD_PATH;
import static io.onedev.k8shelper.JobHelper.stringifyStepPosition;
import static io.onedev.k8shelper.KubernetesHelper.GIT_TRUST_ALL_DIRS;
import static io.onedev.k8shelper.KubernetesHelper.formatDuration;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;

import io.onedev.agent.Agent;
import io.onedev.agent.AgentUtils;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.BuildImageFacade;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.PruneBuilderCacheFacade;
import io.onedev.k8shelper.RunImagetoolsFacade;
import io.onedev.k8shelper.ServiceFacade;

public class JobUtils {

    private static final Logger logger = LoggerFactory.getLogger(JobUtils.class);

	public static File getBuildDir(File baseDir, long projectId, long buildNumber, long submitSequence) {
		return new File(baseDir, "onedev-build-" + projectId + "-" + buildNumber + "-" + submitSequence);
	}

    public static boolean runStep(CompositeFacade entry, List<Integer> position,
								  TaskLogger logger, Callable<Boolean> task) {
		String stepPath = entry.getPathAsString(position);
		logger.notice("Running step \"" + stepPath + "\"...");
		try {
			long time = System.currentTimeMillis();
			var successful = task.call();
			var duration = formatDuration(System.currentTimeMillis() - time);
			if (successful)
				logger.success("Step \"" + stepPath + "\" is successful (" + duration + ")");
			else
				logger.error("Step \"" + stepPath + "\" is failed (" + duration + ")");
			return successful;
		} catch (Exception e) {
			if (ExceptionUtils.find(e, InterruptedException.class) == null) {
				logger.error(AgentUtils.getErrorMessage(e));
				return false;
			} else {
				throw ExceptionUtils.unchecked(e);
			}
		}
	}
    
	private static List<String> parseDockerOptions(File hostBuildDir, String optionString) {
		var options = new ArrayList<String>();
		for (var option: StringUtils.splitAndTrim(KubernetesHelper.replacePlaceholders(optionString, hostBuildDir), " ")) {
			if (option.startsWith("-") && option.contains("=")) {
				options.add(StringUtils.substringBefore(option, "="));
				options.add(StringUtils.substringAfter(option, "="));
			} else {
				options.add(option);
			}
		}
		return options;
	}

	private static void checkBuildImageCachePath(String path) {
		if (!PathUtils.isSubPath(path))
			throw new ExplicitException("Cache path of build image step should be a relative path not containing '..'");
	}

	private static void createBuilder(Commandline docker, String builder, TaskLogger jobLogger) {
		docker.args("buildx", "create", "--name", builder);
		var builderExists = new AtomicBoolean(false);
		var result = docker.execute(new LineConsumer() {
			@Override
			public void consume(String line) {
				if (!line.equals(builder))
					jobLogger.log(line);
			}
		}, new LineConsumer() {
			@Override
			public void consume(String line) {
				if (line.toLowerCase().startsWith("error: existing instance"))
					builderExists.set(true);
				else
					jobLogger.error(line);
			}
		});
		if (!builderExists.get())
			result.checkReturnCode();
	}

	public static void buildImage(Commandline docker, String builder, BuildImageFacade buildImageFacade,
								  File hostBuildDir, boolean pullAlways, TaskLogger jobLogger) {
		createBuilder(docker, builder, jobLogger);

		docker.args("buildx", "build", "--builder", builder);
		if (pullAlways)
			docker.addArgs("--pull");
		if (buildImageFacade.getPlatforms() != null)
			docker.addArgs("--platform", replacePlaceholders(buildImageFacade.getPlatforms(), hostBuildDir));

		if (buildImageFacade.getMoreOptions() != null) {
			var options = parseDockerOptions(hostBuildDir, buildImageFacade.getMoreOptions());
			var it = options.iterator();
			while (it.hasNext()) {
				var option = it.next();
				switch (option) {
					case "--add-host":
					case "--allow":
					case "--build-arg":
					case "--label":
					case "--network":
					case "--no-cache-filter":
					case "--progress":
					case "--target":
					case "--provenance":
						docker.addArgs(option);
						if (it.hasNext())
							docker.addArgs(it.next());
						break;
					case "--cache-from":
					case "--cache-to":
						docker.addArgs(option);
						if (it.hasNext()) {
							var arg = it.next();
							for (var splitted: Splitter.on(',').split(arg)) {
								var index = splitted.indexOf('=');
								if (index == -1)
									checkBuildImageCachePath(splitted);
								else if (splitted.substring(0, index).equals("dest"))
									checkBuildImageCachePath(splitted.substring(index+1));
							}
							docker.addArgs(arg);
						}
						break;
					case "--secret":
						docker.addArgs(option);
						if (it.hasNext()) {
							var arg = it.next();
							for (var splitted: Splitter.on(',').split(arg)) {
								if (splitted.startsWith("src=")) {
									var path = splitted.substring("src=".length());
									if (!PathUtils.isSubPath(path))
										throw new ExplicitException("Secret source path of build image step should be a relative path not containing '..'");
								}
							}
							docker.addArgs(arg);
						}
						break;
					case "--build-context":
						docker.addArgs(option);
						if (it.hasNext()) {
							var arg = it.next();
							var path = StringUtils.substringAfter(arg, "=");
							if (!PathUtils.isSubPath(path))
								throw new ExplicitException("Build context path of build image step should be a relative path not containing '..'");
							docker.addArgs(arg);
						}
						break;
					case "--iidfile":
					case "--metadata-file":
						docker.addArgs(option);
						if (it.hasNext()) {
							var path = it.next();
							if (!PathUtils.isSubPath(path)) {
								if (option.equals("--iidfile"))
									throw new ExplicitException("Image id file path of build image step should be a relative path not containing '..'");
								else
									throw new ExplicitException("Metadata file path of build image step should be a relative path not containing '..'");
							}
							docker.addArgs(path);
						}
						break;
					case "--no-cache":
					case "-q":
					case "--quiet":
						docker.addArgs(option);
						break;
					case "--builder":
						throw new ExplicitException("--builder in more options is no longer supported. Builder can only be configured via job executor now");
					case "--platform":
						throw new ExplicitException("--platform in more options is no longer supported. Please specify platforms property directly");
					default:
						throw new ExplicitException("Option '" + option + "' is not supported for build image step");
				}
			}
		}

		var workDir = new File(hostBuildDir, "work");
		if (buildImageFacade.getBuildPath() != null) {
			String buildPath = replacePlaceholders(buildImageFacade.getBuildPath(), hostBuildDir);
			if (!PathUtils.isSubPath(buildPath))
				throw new ExplicitException("Build path of build image step should be a relative path not containing '..'");
			docker.addArgs(buildPath);
		} else {
			docker.addArgs(".");
		}

		if (buildImageFacade.getDockerfile() != null) {
			String dockerFile = replacePlaceholders(buildImageFacade.getDockerfile(), hostBuildDir);
			if (!PathUtils.isSubPath(dockerFile))
				throw new ExplicitException("Dockerfile of build image step should be a relative path not containing '..'");
			docker.addArgs("-f", dockerFile);
		}

		docker.workingDir(workDir);
		buildImageFacade.getOutput().execute(docker, hostBuildDir, AgentUtils.newInfoLogger(jobLogger), AgentUtils.newWarningLogger(jobLogger));
	}

	public static void pruneBuilderCache(Commandline docker, String builder,
										 PruneBuilderCacheFacade pruneBuilderCacheFacade,
										 File hostBuildDir, TaskLogger jobLogger) {
		createBuilder(docker, builder, jobLogger);

		docker.args("buildx", "prune", "--builder", builder, "-f");
		if (pruneBuilderCacheFacade.getOptions() != null) {
			var options = parseDockerOptions(hostBuildDir, pruneBuilderCacheFacade.getOptions());
			docker.addArgs(options.toArray(new String[0]));
		}
		docker.workingDir(new File(hostBuildDir, "work"));

		var containerNotFound = new AtomicBoolean(false);
		var result = docker.execute(AgentUtils.newInfoLogger(jobLogger), new LineConsumer(UTF_8.name()) {

			@Override
			public void consume(String line) {
				if (line.contains("No such container:"))
					containerNotFound.set(true);
				else
					jobLogger.warning(line);
			}

		});
		if (!containerNotFound.get())
			result.checkReturnCode();
	}

	public static void runImagetools(Commandline docker, RunImagetoolsFacade runImagetoolsFacade,
									 File hostBuildDir, TaskLogger jobLogger) {
		docker.args("buildx", "imagetools");
		var options = parseDockerOptions(hostBuildDir, runImagetoolsFacade.getArguments());
		var it = options.iterator();
		while (it.hasNext()) {
			var option = it.next();
			docker.addArgs(option);
			if ((option.startsWith("--file") || option.startsWith("-f")) && it.hasNext()) {
				var path = it.next();
				if (!PathUtils.isSubPath(path))
					throw new ExplicitException("Source descriptor path of imagetools step should be a relative path not containing '..'");
				docker.addArgs(path);
			}
		}

		docker.workingDir(new File(hostBuildDir, "work"));
		docker.execute(AgentUtils.newInfoLogger(jobLogger), AgentUtils.newWarningLogger(jobLogger)).checkReturnCode();
	}

	public static void startService(Commandline docker, String network, ServiceFacade jobService,
									@Nullable String cpuLimit, @Nullable String memoryLimit,
									TaskLogger jobLogger) {
		String image = jobService.getImage();
		jobLogger.log("Starting service (name: " + jobService.getName() + ", image: " + image + ")...");

		jobLogger.log("Creating service container...");

		String containerName = network + "-service-" + jobService.getName();

		docker.args("run", "-d", "--name=" + containerName, "--network=" + network,
				"--network-alias=" + jobService.getName(), "--user", jobService.getRunAs());

		if (cpuLimit != null)
			docker.addArgs("--cpus", cpuLimit);
		if (memoryLimit != null)
			docker.addArgs("--memory", memoryLimit);

		for (var entry : jobService.getEnvs().entrySet())
			docker.addArgs("--env", entry.getKey() + "=" + entry.getValue());
		docker.addArgs(image);
		if (jobService.getArguments() != null) {
			for (String token : StringUtils.parseQuoteTokens(jobService.getArguments()))
				docker.addArgs(token);
		}

		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
			}

		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}

		}).checkReturnCode();

		jobLogger.log("Waiting for service to be ready...");

		while (true) {
			StringBuilder builder = new StringBuilder();
			docker.args("inspect", containerName);
			docker.execute(new LineConsumer(UTF_8.name()) {

				@Override
				public void consume(String line) {
					builder.append(line).append("\n");
				}

			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					jobLogger.log(line);
				}

			}).checkReturnCode();

			JsonNode stateNode;
			try {
				stateNode = Agent.objectMapper.readTree(builder.toString()).iterator().next().get("State");
			} catch (IOException e) {
				throw new RuntimeException(e);
			}

			if (stateNode.get("Status").asText().equals("running")) {
				docker.args("exec", containerName, "sh", "-c", jobService.getReadinessCheckCommand());

				ExecutionResult result = docker.execute(new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log("Service readiness check: " + line);
					}

				}, new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log("Service readiness check: " + line);
					}

				});
				if (result.getReturnCode() == 0) {
					jobLogger.log("Service is ready");
					break;
				}
			} else if (stateNode.get("Status").asText().equals("exited")) {
				if (stateNode.get("OOMKilled").asText().equals("true"))
					jobLogger.error("Out of memory");
				else if (stateNode.get("Error").asText().length() != 0)
					jobLogger.error(stateNode.get("Error").asText());

				docker.args("logs", containerName);
				docker.execute(new LineConsumer(UTF_8.name()) {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}

				}, new LineConsumer(UTF_8.name()) {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}

				}).checkReturnCode();

				throw new ExplicitException(
						String.format("Service '" + jobService.getName() + "' is stopped unexpectedly"));
			}

			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}
		    
	public static List<String> getEntrypointArgs(File hostBuildDir, CommandFacade commandFacade, List<Integer> stepPosition) {		
		commandFacade.generatePauseCommand(hostBuildDir);

		/*
		 * Use different file for different step although steps are executed sequentially, as otherwise
		 * we will encounter odd issues on Mac running successive command steps
		 */
		var commandDir = new File(hostBuildDir, "command");
		FileUtils.createDir(commandDir);
		File stepScriptFile = new File(commandDir, "step-" + stringifyStepPosition(stepPosition)
				+ commandFacade.getScriptExtension());
		FileUtils.writeFile(stepScriptFile,
				commandFacade.normalizeCommands(replacePlaceholders(commandFacade.getCommands(), hostBuildDir)));

		return List.of("-c", GIT_TRUST_ALL_DIRS + " && " + commandFacade.getExecutable() + " "
				+ stream(commandFacade.getScriptOptions()).map(it -> it + " ").collect(joining())
				+ BUILD_PATH + "/command/" + stepScriptFile.getName());
	}

	public static void createNetwork(Commandline docker, String network, @Nullable String options, TaskLogger jobLogger) {
		docker.args("network", "create");
		if (options != null) {
			for (var option: StringUtils.parseQuoteTokens(options))
				docker.addArgs(option);
		}
		docker.addArgs(network);
		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.debug(line);
			}

		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}

		}).checkReturnCode();
	}

	public static void deleteNetwork(Commandline docker, String network, TaskLogger jobLogger) {
		int retried = 0;
		while (true) {
			try {
				AtomicBoolean networkExists = new AtomicBoolean(false);
				docker.args("network", "ls", "-q", "--filter", "name=" + network);
				docker.execute(new LineConsumer() {

					@Override
					public void consume(String line) {
						networkExists.set(true);
					}

				}, new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}

				}).checkReturnCode();

				if (networkExists.get()) {
					List<String> containerIds = new ArrayList<>();
					docker.args("ps", "-a", "-q", "--filter", "network=" + network);
					docker.execute(new LineConsumer() {

						@Override
						public void consume(String line) {
							containerIds.add(line);
						}

					}, new LineConsumer() {

						@Override
						public void consume(String line) {
							jobLogger.log(line);
						}

					}).checkReturnCode();

					for (String container : containerIds) {
						docker.args("container", "stop", container);
						docker.execute(new LineConsumer() {

							@Override
							public void consume(String line) {
								logger.debug(line);
							}

						}, new LineConsumer() {

							@Override
							public void consume(String line) {
								jobLogger.log(line);
							}

						}).checkReturnCode();

						docker.args("container", "rm", "-v", container);
						docker.execute(new LineConsumer() {

							@Override
							public void consume(String line) {
								logger.debug(line);
							}

						}, new LineConsumer() {

							@Override
							public void consume(String line) {
								jobLogger.log(line);
							}

						}).checkReturnCode();
					}

					docker.args("network", "rm", network);
					docker.execute(new LineConsumer() {

						@Override
						public void consume(String line) {
							logger.debug(line);
						}

					}, new LineConsumer() {

						@Override
						public void consume(String line) {
							jobLogger.log(line);
						}

					}).checkReturnCode();
				}
				break;
			} catch (Exception e) {
				var errorMessage = "Error deleting network '" + network + "'";
				if (retried < 3) {
					jobLogger.error(errorMessage + ", will retry later");
					try {
						Thread.sleep(5 * (long) (Math.pow(2, retried)) * 1000L);
					} catch (InterruptedException e2) {
						throw new RuntimeException(e2);
					}
					retried++;
				} else {
					throw new RuntimeException(errorMessage, e);
				}
			}
		}
	}

}