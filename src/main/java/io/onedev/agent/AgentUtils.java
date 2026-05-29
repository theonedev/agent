package io.onedev.agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.apache.commons.lang3.SystemUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.k8shelper.DefaultShellFacility;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.OsInfo;
import io.onedev.k8shelper.RegistryLoginFacade;

public class AgentUtils {

	private static final Logger logger = LoggerFactory.getLogger(AgentUtils.class);

	private static volatile Map<String, String> mountVolumes;

	public static LineConsumer newInfoLogger(TaskLogger taskLogger) {
		return new LineConsumer(UTF_8.name()) {
	
			private String sessionId = UUID.randomUUID().toString();
			
			@Override
			public void consume(String line) {
				taskLogger.log(line, sessionId);
			}
			
		};
	}

	public static LineConsumer newWarningLogger(TaskLogger taskLogger) {
		return new LineConsumer(UTF_8.name()) {
	
			@Override
			public void consume(String line) {
				taskLogger.warning(line);
			}
			
		};
	}

	public static LineConsumer newErrorLogger(TaskLogger taskLogger) {
		return new LineConsumer(UTF_8.name()) {
	
			@Override
			public void consume(String line) {
				taskLogger.error(line);
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

	public static String getErrorMessage(Throwable exception) {
		ExplicitException explicitException = ExceptionUtils.find(exception, ExplicitException.class);
		if (explicitException == null)
			return Throwables.getStackTraceAsString(exception);
		else
			return explicitException.getMessage();
	}

	public static ProcessKiller newDockerKiller(Commandline docker, String containerName, TaskLogger taskLogger) {
		return (process, executionId) -> {
			taskLogger.log("Stopping container '" + containerName + "'...");
			docker.args("stop", containerName);
			docker.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.debug(line);
				}

			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					taskLogger.log(line);
				}

			}).checkReturnCode();
		};
	}

	public static String getOsIds(TaskLogger taskLogger) {
		return getId("-u", taskLogger) + ":" + getId("-g", taskLogger);
	}

	private static int getId(String flag, TaskLogger taskLogger) {
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

	public static void changeOwner(Commandline docker, String owner, File dirOrFile,
									  String osIds, TaskLogger taskLogger) {
		if (osIds.equals("0:0")) {
			KubernetesHelper.changeOwner(dirOrFile, owner);
		} else {
			docker.args("run");
			docker.addArgs("-v", dirOrFile.getAbsolutePath() + ":/dir-to-change-owner");
			docker.addArgs("--rm", "busybox", "sh", "-c", "chown -R " + owner + " /dir-to-change-owner");
			docker.execute(newInfoLogger(taskLogger), newWarningLogger(taskLogger)).checkReturnCode();
		}
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

	public static void testDocker(Commandline docker, Collection<RegistryLoginFacade> registryLogins,
								  String dockerImage, @Nullable String cpuLimit,
								  @Nullable String memoryLimit, @Nullable String dockerOptions,
								  Function<String, String> hostPathResolver,
								  TaskLogger taskLogger) {
		callWithRegistryLogins(docker, registryLogins, () -> {
			File testDir = FileUtils.createTempDir("docker-test");
			try {
				taskLogger.log("Testing specified docker image...");
				docker.args("run", "--rm");
				if (cpuLimit != null)
					docker.addArgs("--cpus", cpuLimit);
				if (memoryLimit != null)
					docker.args("--memory", memoryLimit);
				if (dockerOptions != null)
					docker.addArgs(StringUtils.parseQuoteTokens(dockerOptions));
				docker.addArgs("-v", hostPathResolver.apply(testDir.getAbsolutePath()) + ":" + "/onedev-docker-test");
				docker.addArgs("-w", "/onedev-docker-test");
				docker.addArgs(dockerImage, "sh", "-c", "echo hello from container");
				docker.execute(newInfoLogger(taskLogger), newWarningLogger(taskLogger)).checkReturnCode();

				taskLogger.log("Checking busybox availability...");
				docker.args("run", "--rm", "busybox", "sh", "-c", "echo hello from busybox");
				docker.execute(newInfoLogger(taskLogger), newWarningLogger(taskLogger)).checkReturnCode();
			} finally {
				FileUtils.deleteDir(testDir);
			}
			return null;
		});
	}

	public static void testDocker(Commandline docker, TestDockerData testData,
								  Function<String, String> hostPathResolver,
								  TaskLogger taskLogger) {
		testDocker(docker, testData.getRegistryLogins(), testData.getDockerImage(), testData.getCpuLimit(),
				testData.getMemoryLimit(), testData.getDockerOptions(), hostPathResolver,
				taskLogger);
	}

	public static void useDockerSock(Commandline docker, @Nullable String dockerSockPath) {
		if (dockerSockPath != null) 
			docker.envs().put("DOCKER_HOST", "unix://" + dockerSockPath);
	}

	public static Commandline newDocker(@Nullable String dockerExecutable, @Nullable String dockerSockPath) {
		if (dockerExecutable == null)
			dockerExecutable = "docker";
		var docker = new Commandline(dockerExecutable);
		useDockerSock(docker, dockerSockPath);
		return docker;
	}

	public static Map<String, String> getMountVolumes(Commandline docker) {
		if (mountVolumes == null) {
			var tempMountVolumes = new HashMap<String, String>();

			String anchorPathString;
			if (Files.exists(Paths.get("/opt/onedev"))) {
				anchorPathString = "/opt/onedev";
			} else if (Files.exists(Paths.get("/agent/work"))) {
				anchorPathString = "/agent/work";
			} else {
				throw new ExplicitException("Neither /opt/onedev nor /agent/work is present: "
						+ "unable to determine current container's mount volumes");
			}
			Path anchorPath = Paths.get(anchorPathString);

			logger.info("Discovering mount volumes of current container (anchor: " + anchorPathString + ")...");
			long time = System.currentTimeMillis();

			List<String> containerIds = new ArrayList<>();
			docker.args("ps", "--filter", "volume=" + anchorPathString, "--format={{.ID}}");
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

			if (containerIds.isEmpty())
				throw new ExplicitException("Unable to identify current container's mount volumes");

			String inspectFormat = "{{range .Mounts}}{{.Destination}}\t{{.Source}}{{println}}{{end}}";
			Map<String, Map<String, String>> candidates = new LinkedHashMap<>();
			for (String containerId : containerIds) {
				Map<String, String> mounts = new LinkedHashMap<>();
				docker.args("container", "inspect", "-f", inspectFormat, containerId);
				docker.execute(new LineConsumer() {

					@Override
					public void consume(String line) {						
						int sepIdx = line.indexOf('\t');
						if (sepIdx != -1)
							mounts.put(line.substring(0, sepIdx), line.substring(sepIdx + 1));
					}

				}, new LineConsumer() {

					@Override
					public void consume(String line) {
						logger.error(line);
					}

				}).checkReturnCode();

				candidates.put(containerId, mounts);
			}

			if (candidates.isEmpty()) {
				throw new ExplicitException("Unable to identify current container's mount volumes");
			} else if (candidates.size() == 1) {
				tempMountVolumes.putAll(candidates.values().iterator().next());
			} else {
				String expectedFingerprint;
				try {
					Map<String, Object> attrs = Files.readAttributes(anchorPath, "unix:dev,ino");
					expectedFingerprint = attrs.get("dev") + ":" + attrs.get("ino");
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				for (Map<String, String> mounts : candidates.values()) {
					String candidateAnchorHostPathString = null;
					for (var entry : mounts.entrySet()) {
						if (anchorPath.equals(Paths.get(entry.getKey()))) {
							candidateAnchorHostPathString = entry.getValue();
							break;
						}
					}
					if (candidateAnchorHostPathString == null)
						continue;

					docker.args("run", "--rm", "-v", candidateAnchorHostPathString + ":" + anchorPathString, "busybox",
							"stat", "-c", "%d:%i", anchorPathString);
					AtomicReference<String> actualFingerprint = new AtomicReference<>();
					docker.execute(new LineConsumer() {

						@Override
						public void consume(String line) {
							actualFingerprint.set(line.trim());
						}

					}, new LineConsumer() {

						@Override
						public void consume(String line) {
							logger.error(line);
						}

					}).checkReturnCode();

					if (expectedFingerprint.equals(actualFingerprint.get())) {
						tempMountVolumes.putAll(mounts);
						break;
					}
				}

				if (tempMountVolumes.isEmpty())
					throw new ExplicitException("Unable to identify current container's mount volumes");
			}

			logger.info("Discovered " + tempMountVolumes.size() + " mount volume(s) (took "
					+ (System.currentTimeMillis() - time) + "ms)");
			mountVolumes = tempMountVolumes;
		}
		return mountVolumes;
	}

	public static String getHostPath(String path, @Nullable String dockerSock) {
		if (Agent.isInDocker()) 
			return getHostPath(newDocker(dockerSock), path);
		else 
			return path;
	}

	public static String getHostPath(Commandline docker, String containerPathString) {
		Path containerPath = Paths.get(containerPathString).normalize();
		Path bestDestinationPath = null;
		Path bestSourcePath = null;
		for (var entry : getMountVolumes(docker).entrySet()) {
			Path destinationPath = Paths.get(entry.getKey());
			if (containerPath.startsWith(destinationPath)) {
				if (bestDestinationPath == null || destinationPath.getNameCount() > bestDestinationPath.getNameCount()) {
					bestDestinationPath = destinationPath;
					bestSourcePath = Paths.get(entry.getValue());
				}
			}
		}

		if (bestDestinationPath == null) {
			throw new ExplicitException("No container mount found containing path '" + containerPathString
					+ "': please make sure to use bind mount to launch OneDev server/agent");
		}

		return bestSourcePath.resolve(bestDestinationPath.relativize(containerPath)).toString();
	}

	public static String encodeBase64Error(String error) {
		return Base64.getEncoder().encodeToString(("\r\n\033[31m" + error + "\033[0m").getBytes(UTF_8));
	}

	public static Commandline newDocker(@Nullable String dockerSock) {
		var docker = new Commandline(Agent.dockerPath);
		useDockerSock(docker, dockerSock);
		return docker;
	}

	public static void testCommands(TaskLogger taskLogger) {
		var testDir = FileUtils.createTempDir("commands-test");
		try {
			taskLogger.log("Running test commands...");

			var shellFacility = new DefaultShellFacility();
			var cmdline = new Commandline(shellFacility.getExecutable());
			cmdline.addArgs(shellFacility.getScriptOptions());
			
			var testScriptFile = new File(testDir, "test" + shellFacility.getScriptExtension());
			FileUtils.writeStringToFile(testScriptFile, "echo 'hello from shell'", UTF_8);
			cmdline.workingDir(testDir).addArgs(testScriptFile.getAbsolutePath());
			cmdline.execute(newInfoLogger(taskLogger), newWarningLogger(taskLogger)).checkReturnCode();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			FileUtils.deleteDir(testDir);
		}
	}

}
