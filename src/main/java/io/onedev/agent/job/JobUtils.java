package io.onedev.agent.job;

import static io.onedev.k8shelper.JobHelper.BUILD_PATH;
import static io.onedev.k8shelper.JobHelper.resolveBuildPath;
import static io.onedev.k8shelper.JobHelper.FINALIZATION;
import static io.onedev.k8shelper.JobHelper.INITIALIZATION;
import static io.onedev.k8shelper.JobHelper.PHASE_PREFIX;
import static io.onedev.k8shelper.JobHelper.buildStepEndMessage;
import static io.onedev.k8shelper.JobHelper.buildStepStartMessage;
import static io.onedev.k8shelper.JobHelper.stringifyStepPosition;
import static io.onedev.k8shelper.KubernetesHelper.GIT_TRUST_ALL_DIRS;
import static io.onedev.k8shelper.KubernetesHelper.buildRestClient;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;

import io.onedev.agent.Agent;
import io.onedev.agent.AgentUtils;
import io.onedev.agent.DockerSettings;
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
import io.onedev.k8shelper.CacheProvisioner;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.JobHelper.StepEventKind;
import io.onedev.k8shelper.PruneBuilderCacheFacade;
import io.onedev.k8shelper.ServiceFacade;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import nl.altindag.ssl.SSLFactory;

public class JobUtils {

    private static final Logger logger = LoggerFactory.getLogger(JobUtils.class);

	public static String buildPhaseMessage(String phase) {
		if (!INITIALIZATION.equals(phase) && !FINALIZATION.equals(phase))
			throw new IllegalArgumentException("Unknown log phase: " + phase);
		return PHASE_PREFIX + phase;
	}

	@Nullable
	public static String parsePhaseMessage(String message) {
		if (message.equals(buildPhaseMessage(INITIALIZATION)))
			return INITIALIZATION;
		if (message.equals(buildPhaseMessage(FINALIZATION)))
			return FINALIZATION;
		return null;
	}

	public static StepEventKind getStepOutcome(Throwable t) {
		return ExceptionUtils.find(t, InterruptedException.class) != null
				|| ExceptionUtils.find(t, CancellationException.class) != null
				? StepEventKind.CANCELLED : StepEventKind.FAILED;
	}

	/** Emit outcomes where the step position is known, without wrapping or retaining the logger. */
	public static boolean runStep(List<Integer> position, TaskLogger logger, Callable<Boolean> task) {
		boolean successful;
		try {
			logger.log(buildStepStartMessage(position));
			successful = task.call();
		} catch (Throwable t) {
			var outcome = getStepOutcome(t);
			if (outcome == StepEventKind.FAILED) {
				var explicit = ExceptionUtils.find(t, ExplicitException.class);
				logger.error(explicit != null ? explicit.getMessage() : Throwables.getStackTraceAsString(t));
			}
			logger.log(buildStepEndMessage(position, outcome));
			if (t instanceof Error error)
				throw error;
			if (outcome == StepEventKind.CANCELLED)
				throw ExceptionUtils.unchecked(t);
			return false;
		}
		logger.log(buildStepEndMessage(position, successful ? StepEventKind.SUCCESSFUL : StepEventKind.FAILED));
		return successful;
	}

	public static File getBuildDir(File baseDir, long projectId, long buildNumber, long submitSequence) {
		return new File(baseDir, "onedev-build-" + projectId + "-" + buildNumber + "-" + submitSequence);
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
								  File hostBuildDir, boolean pullAlways, boolean imageBuildEnabled,
								  String executorName, TaskLogger jobLogger) {
		if (!imageBuildEnabled) {
			throw new ExplicitException("Image build is disabled in executor '" + executorName
					+ "'. Enable Buildx Image Build in executor Security Settings to allow this step");					
		}

		createBuilder(docker, builder, jobLogger);

		docker.args("buildx", "build", "--builder", builder);
		if (pullAlways)
			docker.addArgs("--pull");
		if (buildImageFacade.getPlatforms() != null)
			docker.addArgs("--platform", replacePlaceholders(buildImageFacade.getPlatforms(), hostBuildDir));

		if (buildImageFacade.getMoreOptions() != null)
			docker.addArgs(StringUtils.parseQuoteTokens(replacePlaceholders(buildImageFacade.getMoreOptions(), hostBuildDir)));

		// No need to perform unauthorized host file access check here as this step should only be executed by trust projects
		
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
										 File hostBuildDir, boolean builderCachePruneEnabled,
										 String executorName, TaskLogger jobLogger) {
		if (!builderCachePruneEnabled) {
			throw new ExplicitException("Builder cache prune is disabled in executor '" + executorName
					+ "'. Enable Builder Cache Prune in executor Security Settings to allow this step");
		}

		createBuilder(docker, builder, jobLogger);

		// No need to perform unauthorized host file access check here as this step should only be executed by trust projects
		docker.args("buildx", "prune", "--builder", builder, "-f");
		if (pruneBuilderCacheFacade.getOptions() != null)
			docker.addArgs(StringUtils.parseQuoteTokens(replacePlaceholders(pruneBuilderCacheFacade.getOptions(), hostBuildDir)));
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

	public static boolean isJobRunning(String serverUrl, String token, @Nullable SSLFactory sslFactory) {
		Client client = buildRestClient(sslFactory);
		try {
			WebTarget target = client.target(serverUrl)
					.path("~api/worker/job-running")
					.queryParam("token", token);
			Invocation.Builder builder = target.request();
			try (Response response = builder.get()) {
				checkStatus(response);
				return response.readEntity(boolean.class);
			}
		} finally {
			client.close();
		}
	}

	/** Job-scoped inputs shared by command and container steps. Collections remain live across steps. */
	public record StepContainerContext(DockerSettings settings, String network, File hostBuildDir,
			List<CacheProvisioner> cacheProvisioners, Set<String> pulledImages,
			Function<String, String> hostPathResolver, Supplier<Commandline> dockerSupplier,
			TaskLogger jobLogger) {
	}

	public static int runStepContainer(StepContainerContext context, Commandline docker, String containerName,
			String image, String runAs, @Nullable String entrypoint, List<String> arguments,
			Map<String, String> environments, @Nullable String workingDir,
			Map<String, String> volumeMounts, boolean useTTY) {
		var settings = context.settings();
		var hostBuildDir = context.hostBuildDir();
		var hostPathResolver = context.hostPathResolver();
		var containerWorkDirPath = BUILD_PATH + "/work";
		var jobLogger = context.jobLogger();

		docker.args("run", "--stop-timeout=30", "--name=" + containerName, "--network=" + context.network());
		if (settings.isAlwaysPullImage() && context.pulledImages().add(image))
			docker.addArgs("--pull=always");
		docker.addArgs("--user", runAs);

		if (settings.getCpuLimit() != null)
			docker.addArgs("--cpus", settings.getCpuLimit());
		if (settings.getMemoryLimit() != null)
			docker.addArgs("--memory", settings.getMemoryLimit());
		docker.addArgs(DockerRunOptions.parse(settings.getRunOptions(), hostBuildDir));

		docker.addArgs("-v", hostPathResolver.apply(hostBuildDir.getAbsolutePath()) + ":" + BUILD_PATH);
		for (var entry : volumeMounts.entrySet()) {
			if (entry.getKey().contains(".."))
				throw new ExplicitException("Volume mount source path should not contain '..'");
			var hostPath = hostPathResolver.apply(resolveBuildPath(hostBuildDir, "work/" + entry.getKey()).getAbsolutePath());
			docker.addArgs("-v", hostPath + ":" + entry.getValue());
		}

		for (var cacheProvisioner : context.cacheProvisioners())
			cacheProvisioner.mountVolumes(docker, hostBuildDir, hostPathResolver);

		if (entrypoint != null)
			docker.addArgs("-w", containerWorkDirPath);
		else if (workingDir != null)
			docker.addArgs("-w", workingDir);

		if (settings.isMountDockerSock()) {
			var dockerSock = settings.getDockerSock();
			if (dockerSock != null)
				docker.addArgs("-v", dockerSock + ":/var/run/docker.sock");
			else
				docker.addArgs("-v", "/var/run/docker.sock:/var/run/docker.sock");
		}

		for (var entry : environments.entrySet())
			docker.addArgs("-e", entry.getKey() + "=" + entry.getValue());
		docker.addArgs("-e", "ONEDEV_WORKDIR=" + containerWorkDirPath);

		if (useTTY)
			docker.addArgs("-t");
		if (entrypoint != null)
			docker.addArgs("--entrypoint=" + entrypoint);

		docker.addArgs("--", image);
		docker.addArgs(arguments.toArray(new String[0]));
		docker.processKiller(AgentUtils.newDockerKiller(context.dockerSupplier().get(), containerName, jobLogger));
		return docker.execute(AgentUtils.newInfoLogger(jobLogger), AgentUtils.newWarningLogger(jobLogger), null)
				.getReturnCode();
	}

	public static void startService(Commandline docker, String network, ServiceFacade jobService,
									@Nullable String cpuLimit, @Nullable String memoryLimit,
									TaskLogger jobLogger) {
		String image = jobService.getImage();
		jobLogger.log("Starting service (name: " + jobService.getName() + ", image: " + image + ")...");

		jobLogger.log("Creating service container...");

		String containerName = network + "-service-" + jobService.getName();

		docker.args("run", "-d", "--stop-timeout=30", "--name=" + containerName, "--network=" + network,
				"--network-alias=" + jobService.getName(), "--user", jobService.getRunAs());

		if (cpuLimit != null)
			docker.addArgs("--cpus", cpuLimit);
		if (memoryLimit != null)
			docker.addArgs("--memory", memoryLimit);

		for (var entry : jobService.getEnvs().entrySet())
			docker.addArgs("--env", entry.getKey() + "=" + entry.getValue());
		docker.addArgs("--", image);
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
		var commandDir = resolveBuildPath(hostBuildDir, "command");
		FileUtils.createDir(commandDir);
		File stepScriptFile = resolveBuildPath(hostBuildDir, "command/step-" + stringifyStepPosition(stepPosition)
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
