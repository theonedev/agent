package io.onedev.agent;

import static io.onedev.k8shelper.KubernetesHelper.GIT_TRUST_ALL_DIRS;
import static io.onedev.k8shelper.KubernetesHelper.formatDuration;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.stringifyStepPosition;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.SystemUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;

import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.WordUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.k8shelper.BuildImageFacade;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.OsInfo;
import io.onedev.k8shelper.PruneBuilderCacheFacade;
import io.onedev.k8shelper.RegistryLoginFacade;
import io.onedev.k8shelper.RunImagetoolsFacade;
import io.onedev.k8shelper.ServiceFacade;

public class AgentUtils {

	private static final Logger logger = LoggerFactory.getLogger(AgentUtils.class);

	public static LineConsumer newInfoLogger(TaskLogger jobLogger) {
		return new LineConsumer(UTF_8.name()) {
	
			private String sessionId = UUID.randomUUID().toString();
			
			@Override
			public void consume(String line) {
				jobLogger.log(line, sessionId);
			}
			
		};
	}

	public static LineConsumer newWarningLogger(TaskLogger jobLogger) {
		return new LineConsumer(UTF_8.name()) {
	
			@Override
			public void consume(String line) {
				jobLogger.warning(line);
			}
			
		};
	}

	public static LineConsumer newErrorLogger(TaskLogger jobLogger) {
		return new LineConsumer(UTF_8.name()) {
	
			@Override
			public void consume(String line) {
				jobLogger.error(line);
			}
			
		};
	}
	
	public static OsInfo getOsInfo() {
		String osName;
		String osVersion;
		if (SystemUtils.IS_OS_WINDOWS) {
			osName = "Windows";
			
			logger.info("Checking Windows OS version...");
			
			Commandline systemInfo = new Commandline("cmd").addArgs("/c", "ver");
			
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			systemInfo.execute(baos, new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.error(line);
				}
				
			}).checkReturnCode();
			
			String output = baos.toString();
			osVersion = StringUtils.substringBeforeLast(output, ".");
			osVersion = StringUtils.substringAfterLast(osVersion, " ");
			logger.info("Windows OS version: " + osVersion);
		} else {
			osName = System.getProperty("os.name");
			osVersion = System.getProperty("os.version");
		}

		return new OsInfo(osName, osVersion, System.getProperty("os.arch"));
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
				logger.error(getErrorMessage(e));
				return false;
			} else {
				throw ExceptionUtils.unchecked(e);
			}
		}
	}

    public static void testCommands(String commands, TaskLogger jobLogger) {
    	var commandFacade = new CommandFacade(null, "0:0", new ArrayList<>(), new HashMap<>(), true, commands);
    	Commandline cmdline = new Commandline(commandFacade.getExecutable());
		cmdline.addArgs(commandFacade.getScriptOptions());
    	File buildDir = FileUtils.createTempDir("onedev-build");
    	try {
    		jobLogger.log("Running specified commands...");
    		
    		File testScriptFile = new File(buildDir, "test" + commandFacade.getScriptExtension());
    		FileUtils.writeStringToFile(testScriptFile, commandFacade.normalizeCommands(commands), UTF_8);
    		File workDir = new File(buildDir, "work");
    		FileUtils.createDir(workDir);
    		cmdline.workingDir(workDir).addArgs(testScriptFile.getAbsolutePath());
    		cmdline.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger)).checkReturnCode();    
    	} catch (IOException e) {
    		throw new RuntimeException(e);
    	} finally {
    		FileUtils.deleteDir(buildDir);
    	}
    }

	public static String getErrorMessage(Exception exception) {
		ExplicitException explicitException = ExceptionUtils.find(exception, ExplicitException.class);
		if (explicitException == null)
			return Throwables.getStackTraceAsString(exception);
		else
			return explicitException.getMessage();
	}

	private static List<String> parseDockerOptions(File hostBuildDir, String optionString) {
		var options = new ArrayList<String>();
		for (var option: StringUtils.splitAndTrim(replacePlaceholders(optionString, hostBuildDir), " ")) {
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
		buildImageFacade.getOutput().execute(docker, hostBuildDir, newInfoLogger(jobLogger), newWarningLogger(jobLogger));
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
		var result = docker.execute(newInfoLogger(jobLogger), new LineConsumer(UTF_8.name()) {

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
		docker.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger)).checkReturnCode();
	}

	public static ProcessKiller newDockerKiller(Commandline docker, String containerName, TaskLogger jobLogger) {
		return (process, executionId) -> {
			jobLogger.log("Stopping container '" + containerName + "'...");
			docker.args("stop", containerName);
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
				+ "/onedev-build/command/" + stepScriptFile.getName());
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

	public static String getOsIds(TaskLogger logger) {
		return getId("-u", logger) + ":" + getId("-g", logger);
	}

	private static int getId(String flag, TaskLogger logger) {
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

	public static void changeOwner(Commandline docker, String owner, File dir, TaskLogger logger) {
		changeOwner(docker, owner, Set.of(dir), logger);
	}

	public static void changeOwner(Commandline docker, String owner, Collection<File> dirs,
									  TaskLogger logger) {
		if (Bootstrap.isInDocker()) {
			KubernetesHelper.changeOwner(dirs, owner);
		} else {
			docker.addArgs("run");
			int index = 1;
			for (var dir: dirs) {
				docker.addArgs("-v", dir.getAbsolutePath() + ":/dir-to-change-owner" + index);
				index++;
			}
			docker.addArgs("--rm", "busybox", "sh", "-c");

			var builder = new StringBuilder("chown -R " + owner);
			for (int i=1; i<=dirs.size(); i++) {
				builder.append(" ").append("/dir-to-change-owner").append(i);
			}
			docker.addArgs(builder.toString());

			docker.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
		}
	}

	public static void deleteDir(File dir, Commandline docker, boolean runInDocker, TaskLogger logger) {
		if (runInDocker) 
			FileUtils.deleteDir(dir);
		else 
			deleteDirWithDocker(dir, docker, logger);
	}

	public static void deleteDirWithDocker(File dir, Commandline docker, TaskLogger logger) {
		docker.addArgs("run", "-v", dir.getParentFile().getAbsolutePath() + ":/parent-of-dir-to-delete", "--rm", "busybox", "sh", "-c",
				"rm -rf /parent-of-dir-to-delete/" + dir.getName());
		docker.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
	}

	public static Map<String, Object> buildAuthConfig(Collection<RegistryLoginFacade> registryLogins) {
		Map<String, Object> authsMap = new HashMap<>();
		for (var login: registryLogins) {
			Map<Object, Object> authMap = new HashMap<>();
			authMap.put("auth", Base64.getEncoder().encodeToString((login.getUserName() + ":" + login.getPassword()).getBytes(UTF_8)));
			authsMap.put(login.getRegistryUrl(), authMap);
		}
		return authsMap;
	}

	public static <T> T callWithDockerConfig(Commandline docker, Map<String, Object> configMap, Callable<T> callable) {
		var tempConfigDir = FileUtils.createTempDir("docker");
		docker.envs().put("DOCKER_CONFIG", tempConfigDir.getAbsolutePath());
		try {
			var config = new ObjectMapper().writeValueAsString(configMap);
			FileUtils.writeStringToFile(new File(tempConfigDir, "config.json"), config, UTF_8);

			var configDirPath = System.getenv("DOCKER_CONFIG");
			if (configDirPath == null)
				configDirPath = System.getProperty("user.home") + "/.docker";
			var configDir = new File(configDirPath);
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
			return callable.call();
		} catch (Exception e) {
			throw ExceptionUtils.unchecked(e);
		} finally {
			docker.envs().remove("DOCKER_CONFIG");
			FileUtils.deleteDir(tempConfigDir);
		}										
	}	

	public static <T> T callWithRegistryLogins(Commandline docker, Collection<RegistryLoginFacade> registryLogins, 
				Callable<T> callable) {
		var configMap = new HashMap<String, Object>();
		configMap.put("auths", buildAuthConfig(registryLogins));
		return callWithDockerConfig(docker, configMap, callable);
	}

	public static void useDockerSock(Commandline docker, @Nullable String dockerSockPath) {
		if (dockerSockPath != null) 
			docker.envs().put("DOCKER_HOST", "unix://" + dockerSockPath);
	}

	public static String getDockerExecutable(@Nullable String dockerExecutable) {
		if (dockerExecutable != null)
			return dockerExecutable;
		else if (SystemUtils.IS_OS_MAC_OSX && new File("/usr/local/bin/docker").exists())
			return "/usr/local/bin/docker";
		else
			return "docker";
	}

	public static void createNetwork(Commandline docker, String network, @Nullable String options, TaskLogger jobLogger) {
		docker.clearArgs();
		docker.addArgs("network", "create");
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
			return new OsInfo(osName, osVersion, fields.get(2));
		}
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
				docker.addArgs("exec", containerName, "sh", "-c", jobService.getReadinessCheckCommand());

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
		
	public static String encodeBase64Error(String error) {
		return Base64.getEncoder().encodeToString(("\r\n\033[31m" + error + "\033[0m").getBytes(UTF_8));
	}

}
