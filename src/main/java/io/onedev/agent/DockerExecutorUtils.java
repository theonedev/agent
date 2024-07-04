package io.onedev.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.onedev.agent.job.ImageMappingFacade.map;
import static io.onedev.commons.utils.StringUtils.parseQuoteTokens;
import static io.onedev.commons.utils.StringUtils.splitAndTrim;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.stringifyStepPosition;
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

	private static List<String> parseDockerOptions(File hostBuildHome, String optionString) {
		var options = new ArrayList<String>();
		for (var option: parseQuoteTokens(replacePlaceholders(optionString, hostBuildHome))) {
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
		docker.clearArgs();
		docker.addArgs("buildx", "create", "--name", builder);
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
				if (line.startsWith("ERROR: existing instance"))
					builderExists.set(true);
				else
					jobLogger.error(line);
			}
		});
		if (!builderExists.get())
			result.checkReturnCode();
	}

	public static void buildImage(Commandline docker, String builder, BuildImageFacade buildImageFacade,
								  File hostBuildHome, boolean pullAlways, TaskLogger jobLogger) {
		createBuilder(docker, builder, jobLogger);

		docker.clearArgs();
		docker.addArgs("buildx", "build", "--builder", builder);
		if (pullAlways)
			docker.addArgs("--pull");
		if (buildImageFacade.getPlatforms() != null)
			docker.addArgs("--platform", replacePlaceholders(buildImageFacade.getPlatforms(), hostBuildHome));

		if (buildImageFacade.getMoreOptions() != null) {
			var options = parseDockerOptions(hostBuildHome, buildImageFacade.getMoreOptions());
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

		var workspaceDir = new File(hostBuildHome, "workspace");
		if (buildImageFacade.getBuildPath() != null) {
			String buildPath = replacePlaceholders(buildImageFacade.getBuildPath(), hostBuildHome);
			if (!PathUtils.isSubPath(buildPath))
				throw new ExplicitException("Build path of build image step should be a relative path not containing '..'");
			docker.addArgs(buildPath);
		} else {
			docker.addArgs(".");
		}

		if (buildImageFacade.getDockerfile() != null) {
			String dockerFile = replacePlaceholders(buildImageFacade.getDockerfile(), hostBuildHome);
			if (!PathUtils.isSubPath(dockerFile))
				throw new ExplicitException("Dockerfile of build image step should be a relative path not containing '..'");
			docker.addArgs("-f", dockerFile);
		}

		docker.workingDir(workspaceDir);
		buildImageFacade.getOutput().execute(docker, hostBuildHome, newInfoLogger(jobLogger), newWarningLogger(jobLogger));
	}

	public static void pruneBuilderCache(Commandline docker, String builder,
										 PruneBuilderCacheFacade pruneBuilderCacheFacade,
										 File hostBuildHome, TaskLogger jobLogger) {
		createBuilder(docker, builder, jobLogger);

		docker.clearArgs();
		docker.addArgs("buildx", "prune", "--builder", builder, "-f");
		if (pruneBuilderCacheFacade.getOptions() != null) {
			var options = parseDockerOptions(hostBuildHome, pruneBuilderCacheFacade.getOptions());
			docker.addArgs(options.toArray(new String[0]));
		}
		docker.workingDir(new File(hostBuildHome, "workspace"));

		var containerNotFound = new AtomicBoolean(false);
		var result = docker.execute(newInfoLogger(jobLogger), new LineConsumer(StandardCharsets.UTF_8.name()) {

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
									 File hostBuildHome, TaskLogger jobLogger) {
		docker.clearArgs();
		docker.addArgs("buildx", "imagetools");
		var options = parseDockerOptions(hostBuildHome, runImagetoolsFacade.getArguments());
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

		docker.workingDir(new File(hostBuildHome, "workspace"));
		docker.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger)).checkReturnCode();
	}

	public static ProcessKiller newDockerKiller(Commandline docker, String containerName, TaskLogger jobLogger) {
		return (process, executionId) -> {
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
		};
	}

	public static Commandline getEntrypoint(File hostBuildHome, CommandFacade commandFacade,
											OsInfo osInfo, List<Integer> stepPosition) {
		Commandline interpreter = commandFacade.getScriptInterpreter();
		String entrypointExecutable;
		String[] entrypointArgs;
		
		commandFacade.generatePauseCommand(hostBuildHome);

		/*
		 * Use different file for different step although steps are executed sequentially, as otherwise
		 * we will encounter odd issues on Mac running successive command steps
		 */
		var commandDir = new File(hostBuildHome, "command");
		FileUtils.createDir(commandDir);
		File stepScriptFile = new File(commandDir, "step-" + stringifyStepPosition(stepPosition)
				+ commandFacade.getScriptExtension());
		OsExecution execution = commandFacade.getExecution(osInfo);
		FileUtils.writeFile(stepScriptFile,
				commandFacade.normalizeCommands(replacePlaceholders(execution.getCommands(), hostBuildHome)));

		if (SystemUtils.IS_OS_WINDOWS) {
			entrypointExecutable = "cmd";
			entrypointArgs = new String[] { "/c",
					"xcopy /Y /S /K /Q /H /R C:\\onedev-build\\user\\* C:\\Users\\%USERNAME% > nul && "
							+ interpreter + " C:\\onedev-build\\command\\" + stepScriptFile.getName() };
		} else {
			entrypointExecutable = "sh";
			entrypointArgs = new String[] { "-c", "test -w $HOME && cp -r -f -p /onedev-build/user/. $HOME || export HOME=/onedev-build/user && " + interpreter
					+ " /onedev-build/command/" + stepScriptFile.getName() };
		}

		return new Commandline(entrypointExecutable).addArgs(entrypointArgs);
	}

	public static void writeFile(File file, String content, Commandline docker, boolean runInDocker) {
		if (runInDocker) {
			FileUtils.writeFile(file, content, UTF_8);
		} else {
			var tempFile = FileUtils.createTempFile();
			try {
				FileUtils.writeFile(tempFile, content, UTF_8);
				docker.clearArgs();
				docker.addArgs("run", "-v", tempFile.getAbsolutePath() + ":/copy-from", "-v", file.getParentFile().getAbsolutePath() + ":/parent-of-copy-to", "--rm", "busybox",
						"sh", "-c", "cp /copy-from /parent-of-copy-to/" + file.getName());
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
			} finally {
				FileUtils.deleteFile(tempFile);
			}
		}
	}

	public static String getOwner() {
		return getId("-u") + ":" + getId("-g");
	}

	private static int getId(String flag) {
		var cmd = new Commandline("id");
		cmd.addArgs(flag);
		AtomicInteger id = new AtomicInteger(0);
		cmd.execute(new LineConsumer() {
			@Override
			public void consume(String line) {
				id.set(Integer.parseInt(line));
			}
		}, new LineConsumer() {
			@Override
			public void consume(String line) {
				logger.error(line);
			}
		}).checkReturnCode();
		return id.get();
	}

	public static boolean changeOwner(File dir, @Nullable String owner, Commandline docker, boolean runInDocker) {
		if (owner != null) {
			if (runInDocker) {
				KubernetesHelper.changeOwner(dir, owner);
			} else {
				docker.addArgs("run", "-v", dir.getAbsolutePath() + ":/dir-to-change-owner", "--rm", "busybox", "sh", "-c",
						"chown -R " + owner + " /dir-to-change-owner");
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
			return true;
		} else {
			return false;
		}
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

	public static String buildDockerConfig(Collection<RegistryLoginFacade> registryLogins,
										   @Nullable BuiltInRegistryLogin builtInRegistryLogin) {
		Map<Object, Object> configMap = new HashMap<>();
		Map<Object, Object> authsMap = new HashMap<>();
		for (var login: registryLogins) {
			Map<Object, Object> authMap = new HashMap<>();
			authMap.put("auth", getEncoder().encodeToString((login.getUserName() + ":" + login.getPassword()).getBytes(UTF_8)));
			authsMap.put(login.getRegistryUrl(), authMap);
		}
		if (builtInRegistryLogin != null) {
			Map<Object, Object> authMap = new HashMap<>();
			authMap.put("auth", getEncoder().encodeToString((builtInRegistryLogin.getAuth()).getBytes(UTF_8)));
			authsMap.put(builtInRegistryLogin.getUrl(), authMap);
		}
		configMap.put("auths", authsMap);
		try {
			return new ObjectMapper().writeValueAsString(configMap);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	public static <T> T callWithDockerConfig(Commandline docker, Collection<RegistryLoginFacade> registryLogins,
											 @Nullable BuiltInRegistryLogin builtInRegistryLogin,
											 Callable<T> callable) {
		var tempConfigDir = FileUtils.createTempDir("docker");
		docker.environments().put("DOCKER_CONFIG", tempConfigDir.getAbsolutePath());
		try {
			var configHome = System.getenv("DOCKER_CONFIG");
			if (configHome == null)
				configHome = System.getProperty("user.home") + "/.docker";
			var configDir = new File(configHome);
			try {
				if (new File(configDir, "buildx").exists()) {
					FileUtils.copyDirectory(
							new File(configDir, "buildx"),
							new File(tempConfigDir, "buildx"));
				}
				if (new File(configDir, "contexts").exists()) {
					FileUtils.copyDirectory(
							new File(configDir, "contexts"),
							new File(tempConfigDir, "contexts"));
				}
				if (new File(configDir, "cli-plugins").exists()) {
					Files.createSymbolicLink(
							new File(tempConfigDir, "cli-plugins").toPath(),
							new File(configDir, "cli-plugins").toPath());
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			var config = buildDockerConfig(registryLogins, builtInRegistryLogin);
			FileUtils.writeStringToFile(new File(tempConfigDir, "config.json"), config, UTF_8);
			return callable.call();
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		} finally {
			docker.environments().remove("DOCKER_CONFIG");
			FileUtils.deleteDir(tempConfigDir);
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
			throw new IllegalStateException("No container mounting host path found: please make sure to use bind mount to launch OneDev server/agent");
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

		if (jobService.getRunAs() != null)
			docker.addArgs("--user", jobService.getRunAs());
		else if (!SystemUtils.IS_OS_WINDOWS)
			docker.addArgs("--user", "0:0");

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
