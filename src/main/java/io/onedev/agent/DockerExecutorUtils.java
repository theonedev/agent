package io.onedev.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import io.onedev.agent.job.ImageMappingFacade;
import io.onedev.agent.job.RegistryLoginFacade;
import io.onedev.commons.utils.*;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.k8shelper.*;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.onedev.agent.job.ImageMappingFacade.map;
import static io.onedev.commons.utils.StringUtils.parseQuoteTokens;
import static io.onedev.commons.utils.StringUtils.splitAndTrim;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Base64.getEncoder;

public class DockerExecutorUtils extends ExecutorUtils {

	private static final Logger logger = LoggerFactory.getLogger(DockerExecutorUtils.class);

	public static String getErrorMessage(Exception exception) {
		ExplicitException explicitException = ExceptionUtils.find(exception, ExplicitException.class);
		if (explicitException == null)
			return Throwables.getStackTraceAsString(exception);
		else
			return explicitException.getMessage();
	}

	public static void buildImage(Commandline docker, BuildImageFacade buildImageFacade, File hostBuildHome, TaskLogger jobLogger) {
		String[] parsedTags = parseQuoteTokens(replacePlaceholders(buildImageFacade.getTags(), hostBuildHome));

		docker.clearArgs();
		docker.addArgs("build");

		for (String tag : parsedTags)
			docker.addArgs("-t", tag);

		if (buildImageFacade.getMoreOptions() != null) {
			for (var option : splitAndTrim(buildImageFacade.getMoreOptions(), " "))
				docker.addArgs(option);
		}

		if (buildImageFacade.getBuildPath() != null) {
			String buildPath = replacePlaceholders(buildImageFacade.getBuildPath(), hostBuildHome);
			if (buildPath.contains(".."))
				throw new ExplicitException("Build path should not contain '..'");
			docker.addArgs(buildPath);
		} else {
			docker.addArgs(".");
		}

		if (buildImageFacade.getDockerfile() != null) {
			String dockerFile = replacePlaceholders(buildImageFacade.getDockerfile(), hostBuildHome);
			if (dockerFile.contains(".."))
				throw new ExplicitException("Dockerfile path should not contain '..'");
			docker.addArgs("-f", dockerFile);
		}

		docker.workingDir(new File(hostBuildHome, "workspace"));
		docker.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger)).checkReturnCode();

		if (buildImageFacade.isPublish()) {
			for (String tag : parsedTags) {
				docker.clearArgs();
				docker.addArgs("push", tag);
				docker.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger)).checkReturnCode();
			}
		}
		if (buildImageFacade.isRemoveDanglingImages()) {
			docker.clearArgs();
			docker.addArgs("image", "prune", "-f");
			docker.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger)).checkReturnCode();
		}
	}

	public static ProcessKiller newDockerKiller(Commandline docker, String containerName, TaskLogger jobLogger) {
		return new ProcessKiller() {

			@Override
			public void kill(Process process, String executionId) {
				jobLogger.log("Stopping container '" + containerName + "'...");
				docker.clearArgs();
				docker.addArgs("stop", containerName);
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

		};
	}

	public static Commandline getEntrypoint(File hostBuildHome, CommandFacade commandFacade, OsInfo osInfo,
			boolean withHostAuthInfo) {
		Commandline interpreter = commandFacade.getScriptInterpreter();
		String entrypointExecutable;
		String[] entrypointArgs;
		
		commandFacade.generatePauseCommand(hostBuildHome);
		
		File scriptFile = new File(hostBuildHome, "job-commands" + commandFacade.getScriptExtension());
		try {
			OsExecution execution = commandFacade.getExecution(osInfo);
			FileUtils.writeLines(scriptFile,
					new ArrayList<>(replacePlaceholders(execution.getCommands(), hostBuildHome)),
					commandFacade.getEndOfLine());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		
		if (SystemUtils.IS_OS_WINDOWS) {
			if (withHostAuthInfo) {
				entrypointExecutable = "cmd";
				entrypointArgs = new String[] { "/c",
						"xcopy /Y /S /K /Q /H /R C:\\Users\\%USERNAME%\\auth-info\\* C:\\Users\\%USERNAME% > nul && "
								+ interpreter + " C:\\onedev-build\\" + scriptFile.getName() };
			} else {
				entrypointExecutable = interpreter.executable();
				List<String> interpreterArgs = new ArrayList<>(interpreter.arguments());
				interpreterArgs.add("C:\\onedev-build\\" + scriptFile.getName());
				entrypointArgs = interpreterArgs.toArray(new String[0]);
			}
		} else {
			if (withHostAuthInfo) {
				entrypointExecutable = "sh";
				entrypointArgs = new String[] { "-c", "cp -r -f -p /root/auth-info/. /root && " + interpreter
						+ " /onedev-build/" + scriptFile.getName() };
			} else {
				entrypointExecutable = interpreter.executable();
				List<String> interpreterArgs = new ArrayList<>(interpreter.arguments());
				interpreterArgs.add("/onedev-build/" + scriptFile.getName());
				entrypointArgs = interpreterArgs.toArray(new String[0]);
			}
		}

		return new Commandline(entrypointExecutable).addArgs(entrypointArgs);
	}

	public static void deleteDir(File dir, Commandline docker, boolean runInDocker) {
		if (SystemUtils.IS_OS_WINDOWS || runInDocker) {
			FileUtils.deleteDir(dir);
		} else {
			docker.addArgs("run", "-v", dir.getParentFile().getAbsolutePath() + ":/parent-of-dir-to-delete", "--rm", "busybox", "sh", "-c",
					"rm -rf /parent-of-dir-to-delete/" + dir.getName());
			docker.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.info(line);
				}

			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.contains("Error response from daemon"))
						logger.error(line);
					else
						logger.info(line);
				}

			}).checkReturnCode();
		}
	}

	public static String buildDockerConfig(@Nullable String jobToken,
										   Collection<RegistryLoginFacade> registryLogins,
										   String builtInRegistryUrl,
										   @Nullable String builtInRegistryAccessToken) {
		Map<Object, Object> configMap = new HashMap<>();
		Map<Object, Object> authsMap = new HashMap<>();
		for (var login: registryLogins) {
			Map<Object, Object> authMap = new HashMap<>();
			authMap.put("auth", getEncoder().encodeToString((login.getUserName() + ":" + login.getPassword()).getBytes(UTF_8)));
			authsMap.put(login.getRegistryUrl(), authMap);
		}
		String builtInRegistryAuth;
		if (jobToken != null && builtInRegistryAccessToken != null)
			builtInRegistryAuth = jobToken + " " + builtInRegistryAccessToken;
		else if (jobToken != null)
			builtInRegistryAuth = jobToken;
		else if (builtInRegistryAccessToken != null)
			builtInRegistryAuth = builtInRegistryAccessToken;
		else
			builtInRegistryAuth = null;
		if (builtInRegistryAuth != null) {
			Map<Object, Object> authMap = new HashMap<>();
			authMap.put("auth", getEncoder().encodeToString(("onedev:" + builtInRegistryAuth).getBytes(UTF_8)));
			authsMap.put(builtInRegistryUrl, authMap);
		}
		configMap.put("auths", authsMap);
		try {
			return new ObjectMapper().writeValueAsString(configMap);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public static <T> T callWithDockerAuth(Commandline docker, @Nullable String jobToken,
										   Collection<RegistryLoginFacade> registryLogins,
										   String builtInRegistryUrl,
										   @Nullable String builtInRegistryAccessToken,
										   Callable<T> callable) {
		var dockerHome = FileUtils.createTempDir("docker");
		var prevHome = docker.environments().put("HOME", dockerHome.getAbsolutePath());
		try {
			var config = buildDockerConfig(jobToken, registryLogins,
					builtInRegistryUrl, builtInRegistryAccessToken);
			FileUtils.writeStringToFile(new File(dockerHome, ".docker/config.json"), config, UTF_8);
			return callable.call();
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		} finally {
			if (prevHome != null)
				docker.environments().put("HOME", prevHome);
			else
				docker.environments().remove("HOME");
			FileUtils.deleteDir(dockerHome);
		}
	}

	public static void useDockerSock(Commandline docker, @Nullable String dockerSock) {
		if (dockerSock != null) {
			if (SystemUtils.IS_OS_WINDOWS)
				docker.environments().put("DOCKER_HOST", "npipe://" + dockerSock);
			else
				docker.environments().put("DOCKER_HOST", "unix://" + dockerSock);
		}
	}

	public static void createNetwork(Commandline docker, String network, @Nullable String options, TaskLogger jobLogger) {
		docker.clearArgs();
		AtomicBoolean networkExists = new AtomicBoolean(false);
		docker.addArgs("network", "ls", "-q", "--filter", "name=" + network);
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
			clearNetwork(docker, network, jobLogger);
		} else {
			docker.clearArgs();
			docker.addArgs("network", "create");
			if (SystemUtils.IS_OS_WINDOWS)
				docker.addArgs("-d", "nat");
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
	}

	public static void deleteNetwork(Commandline docker, String network, TaskLogger jobLogger) {
		clearNetwork(docker, network, jobLogger);

		docker.clearArgs();
		docker.addArgs("network", "rm", network);
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

	public static void clearNetwork(Commandline docker, String network, TaskLogger jobLogger) {
		List<String> containerIds = new ArrayList<>();
		docker.clearArgs();
		docker.addArgs("ps", "-a", "-q", "--filter", "network=" + network);
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
			docker.clearArgs();
			docker.addArgs("container", "stop", container);
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

			docker.clearArgs();
			docker.addArgs("container", "rm", "-v", container);
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
	}

	private static void pullImage(Commandline docker, String image, TaskLogger jobLogger) {
		docker.clearArgs();
		docker.addArgs("pull", image);

		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.log(line);
			}

		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				jobLogger.error(line);
			}

		}).checkReturnCode();
	}

	public static OsInfo getOsInfo(Commandline docker, String image, TaskLogger jobLogger, boolean pullIfNotExist) {
		docker.clearArgs();
		docker.addArgs("image", "inspect", image, "--format={{.Os}}%{{.OsVersion}}%{{.Architecture}}");

		AtomicReference<String> imageNotExistError = new AtomicReference<>();
		AtomicReference<String> osInfoString = new AtomicReference<>(null);
		ExecutionResult result = docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.contains("%"))
					osInfoString.set(line);
			}

		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				if (line.startsWith("Error: No such image:"))
					imageNotExistError.set(line);
				else
					jobLogger.error(line);
			}

		});

		if (imageNotExistError.get() != null) {
			if (pullIfNotExist) {
				pullImage(docker, image, jobLogger);
				return getOsInfo(docker, image, jobLogger, false);
			} else {
				throw new ExplicitException(imageNotExistError.get());
			}
		} else {
			result.checkReturnCode();

			List<String> fields = splitAndTrim(osInfoString.get(), "%");
			String osName = WordUtils.capitalize(fields.get(0));
			String osVersion = fields.get(1);
			if (osName.equals("Windows"))
				osVersion = StringUtils.substringBeforeLast(osVersion, ".");
			return new OsInfo(osName, osVersion, fields.get(2));
		}
	}

	public static boolean isUseProcessIsolation(Commandline docker, String image, OsInfo nodeOsInfo,
			TaskLogger jobLogger) {
		if (SystemUtils.IS_OS_WINDOWS) {
			jobLogger.log("Checking image OS info...");
			OsInfo imageOsInfo = getOsInfo(docker, image, jobLogger, true);
			String imageWinVersion = OsInfo.WINDOWS_VERSIONS.get(imageOsInfo.getWindowsBuild());
			String osWinVersion = OsInfo.WINDOWS_VERSIONS.get(nodeOsInfo.getWindowsBuild());
			if (imageWinVersion != null && osWinVersion != null && imageWinVersion.equals(osWinVersion))
				return true;
		}
		return false;
	}

	public static String getHostPath(Commandline docker, String mountPath) {
		logger.info("Finding host path mounted to '" + mountPath + "'...");

		List<String> containerIds = new ArrayList<>();
		docker.clearArgs();
		docker.addArgs("ps", "--format={{.ID}}", "-f", "volume=" + mountPath);
		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				containerIds.add(line);
			}

		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.error(line);
			}

		}).checkReturnCode();

		if (containerIds.isEmpty()) { // podman has a bug not being able to filter by volume
			docker.clearArgs();
			docker.addArgs("ps", "--format={{.ID}}");
			docker.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					containerIds.add(line);
				}

			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.error(line);
				}

			}).checkReturnCode();
		}
		
		if (containerIds.isEmpty())
			throw new IllegalStateException("Unable to find any running container");

		docker.clearArgs();
		String inspectFormat = String.format("{{range .Mounts}}{{if eq .Destination \"%s\"}}{{.Source}}{{end}}{{end}}",
				mountPath);
		docker.addArgs("container", "inspect", "-f", inspectFormat);

		for (String containerId : containerIds)
			docker.addArgs(containerId);

		List<String> possibleHostInstallPaths = new ArrayList<>();
		docker.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
				if (StringUtils.isNotBlank(line))
					possibleHostInstallPaths.add(line);
			}

		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.error(line);
			}

		}).checkReturnCode();

		String hostInstallPath = null;

		if (possibleHostInstallPaths.isEmpty()) {
			throw new IllegalStateException("No any mounting container found");
		} else if (possibleHostInstallPaths.size() > 1) {
			File testFile = new File(mountPath, UUID.randomUUID().toString());
			FileUtils.touchFile(testFile);
			try {
				for (String possibleHostInstallPath : possibleHostInstallPaths) {
					docker.clearArgs();
					docker.addArgs("run", "--rm", "-v", possibleHostInstallPath + ":" + mountPath, "busybox", "ls",
							mountPath + "/" + testFile.getName());
					AtomicBoolean fileNotExist = new AtomicBoolean(false);
					ExecutionResult result = docker.execute(new LineConsumer() {

						@Override
						public void consume(String line) {
						}

					}, new LineConsumer() {

						@Override
						public void consume(String line) {
							if (line.contains("No such file or directory"))
								fileNotExist.set(true);
							else
								logger.error(line);
						}

					});
					if (fileNotExist.get()) {
						continue;
					} else {
						result.checkReturnCode();
						hostInstallPath = possibleHostInstallPath;
						break;
					}
				}
			} finally {
				FileUtils.deleteFile(testFile);
			}
		} else {
			hostInstallPath = possibleHostInstallPaths.iterator().next();
		}
		if (hostInstallPath != null)
			logger.info("Found host path: " + hostInstallPath);
		else
			throw new ExplicitException("Unable to find host path");

		return hostInstallPath;
	}

	@SuppressWarnings({ "resource", "unchecked" })
	public static void startService(Commandline docker, String network, ServiceFacade jobService,
									OsInfo nodeOsInfo, List<ImageMappingFacade> imageMappings,
									@Nullable String cpuLimit, @Nullable String memoryLimit,
									TaskLogger jobLogger) {
		String image = map(imageMappings, jobService.getImage());
		jobLogger.log("Starting service (name: " + jobService.getName() + ", image: " + image + ")...");

		docker.clearArgs();
		boolean useProcessIsolation = isUseProcessIsolation(docker, image, nodeOsInfo, jobLogger);

		jobLogger.log("Creating service container...");

		String containerName = network + "-service-" + jobService.getName();

		docker.clearArgs();
		docker.addArgs("run", "-d", "--name=" + containerName, "--network=" + network,
				"--network-alias=" + jobService.getName());

		if (cpuLimit != null)
			docker.addArgs("--cpus", cpuLimit);
		if (memoryLimit != null)
			docker.addArgs("--memory", memoryLimit);

		for (var entry : jobService.getEnvs().entrySet())
			docker.addArgs("--env", entry.getKey() + "=" + entry.getValue());
		if (useProcessIsolation)
			docker.addArgs("--isolation=process");
		docker.addArgs(image);
		if (jobService.getArguments() != null) {
			for (String token : parseQuoteTokens(jobService.getArguments()))
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
			docker.clearArgs();
			docker.addArgs("inspect", containerName);
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
				docker.clearArgs();
				docker.addArgs("exec", containerName);

				if (SystemUtils.IS_OS_WINDOWS)
					docker.addArgs("cmd", "/c", jobService.getReadinessCheckCommand());
				else
					docker.addArgs("sh", "-c", jobService.getReadinessCheckCommand());

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

				docker.clearArgs();
				docker.addArgs("logs", containerName);
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

}
