package io.onedev.agent;

import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.text.WordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.k8shelper.BuildImageFacade;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.OsExecution;
import io.onedev.k8shelper.OsInfo;

public class DockerExecutorUtils extends ExecutorUtils {

	private static final Logger logger = LoggerFactory.getLogger(DockerExecutorUtils.class);

	public static String getErrorMessage(Exception exception) {
		ExplicitException explicitException = ExceptionUtils.find(exception, ExplicitException.class);
		if (explicitException == null)
			return Throwables.getStackTraceAsString(exception);
		else
			return explicitException.getMessage();
	}

	public static void buildImage(Commandline docker, BuildImageFacade buildImageFacade, File hostBuildHome,
			TaskLogger jobLogger) {
		String[] parsedTags = StringUtils
				.parseQuoteTokens(replacePlaceholders(buildImageFacade.getTags(), hostBuildHome));

		docker.clearArgs();
		docker.addArgs("build");

		for (String tag : parsedTags)
			docker.addArgs("-t", tag);
		
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

	public static void login(Commandline docker, @Nullable String registryUrl, String userName, String password,
			TaskLogger jobLogger) {
		if (registryUrl != null)
			jobLogger.log(String.format("Login to docker registry '%s'...", registryUrl));
		else
			jobLogger.log("Login to official docker registry...");
		docker.addArgs("login", "-u", userName, "--password-stdin");
		if (registryUrl != null)
			docker.addArgs(registryUrl);
		ByteArrayInputStream input;
		try {
			input = new ByteArrayInputStream(password.getBytes(UTF_8.name()));
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException(e);
		}
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

		}, input).checkReturnCode();
	}

	public static void createNetwork(Commandline docker, String network, TaskLogger jobLogger) {
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

			List<String> fields = StringUtils.splitAndTrim(osInfoString.get(), "%");
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
	public static void startService(Commandline docker, String network, Map<String, Serializable> jobService,
			OsInfo nodeOsInfo, @Nullable String cpuLimit, @Nullable String memoryLimit, TaskLogger jobLogger) {
		String image = (String) jobService.get("image");
		docker.clearArgs();
		boolean useProcessIsolation = isUseProcessIsolation(docker, image, nodeOsInfo, jobLogger);

		jobLogger.log("Creating service container...");

		String containerName = network + "-service-" + jobService.get("name");

		docker.clearArgs();
		docker.addArgs("run", "-d", "--name=" + containerName, "--network=" + network,
				"--network-alias=" + jobService.get("name"));

		if (cpuLimit != null)
			docker.addArgs("--cpus", cpuLimit);
		if (memoryLimit != null)
			docker.addArgs("--memory", memoryLimit);

		for (Map.Entry<String, String> entry : ((Map<String, String>) jobService.get("envVars")).entrySet())
			docker.addArgs("--env", entry.getKey() + "=" + entry.getValue());
		if (useProcessIsolation)
			docker.addArgs("--isolation=process");
		docker.addArgs(image);
		if (jobService.get("arguments") != null) {
			for (String token : StringUtils.parseQuoteTokens((String) jobService.get("arguments")))
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
					docker.addArgs("cmd", "/c", (String) jobService.get("readinessCheckCommand"));
				else
					docker.addArgs("sh", "-c", (String) jobService.get("readinessCheckCommand"));

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
						String.format("Service '" + jobService.get("name") + "' is stopped unexpectedly"));
			}

			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
	}

}
