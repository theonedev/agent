package io.onedev.agent;

import static io.onedev.agent.AgentUtils.buildImage;
import static io.onedev.agent.AgentUtils.callWithRegistryLogins;
import static io.onedev.agent.AgentUtils.changeOwner;
import static io.onedev.agent.AgentUtils.createNetwork;
import static io.onedev.agent.AgentUtils.deleteDir;
import static io.onedev.agent.AgentUtils.deleteNetwork;
import static io.onedev.agent.AgentUtils.getEntrypointArgs;
import static io.onedev.agent.AgentUtils.getOsIds;
import static io.onedev.agent.AgentUtils.newDockerKiller;
import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static io.onedev.agent.AgentUtils.pruneBuilderCache;
import static io.onedev.agent.AgentUtils.runImagetools;
import static io.onedev.agent.AgentUtils.startService;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.downloadDependencies;
import static io.onedev.k8shelper.KubernetesHelper.initRepository;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.setupGitCerts;
import static io.onedev.k8shelper.KubernetesHelper.stringifyStepPosition;
import static io.onedev.k8shelper.RegistryLoginFacade.merge;
import static java.lang.System.lineSeparator;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.SystemUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

import io.onedev.agent.job.DockerJobData;
import io.onedev.agent.job.LogRequest;
import io.onedev.agent.job.ShellJobData;
import io.onedev.agent.job.TestDockerJobData;
import io.onedev.agent.job.TestShellJobData;
import io.onedev.agent.shell.ShellInputRequest;
import io.onedev.agent.shell.ShellOutputRequest;
import io.onedev.agent.shell.ShellResizeRequest;
import io.onedev.agent.shell.ShellSession;
import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.BuildImageFacade;
import io.onedev.k8shelper.CheckoutFacade;
import io.onedev.k8shelper.CloneInfo;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.LeafFacade;
import io.onedev.k8shelper.LeafHandler;
import io.onedev.k8shelper.PruneBuilderCacheFacade;
import io.onedev.k8shelper.RunContainerFacade;
import io.onedev.k8shelper.RunImagetoolsFacade;
import io.onedev.k8shelper.ServerSideFacade;
import io.onedev.k8shelper.SetupCacheFacade;

@WebSocket
public class AgentSocket implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(AgentSocket.class);
	
	private static final Map<String, Thread> jobThreads = new ConcurrentHashMap<>();
	
	private static final Map<String, File> buildDirs = new ConcurrentHashMap<>();

	private static final Map<String, String> dockerSocks = new ConcurrentHashMap<>();

	private static final Map<String, ShellSession> shellSessions = new ConcurrentHashMap<>();
	
	private static final Map<String, LeafFacade> runningSteps = new ConcurrentHashMap<>();
	
	private static final Map<String, String> containerNames = new ConcurrentHashMap<>();
	
	private Session session;
	
	private volatile Thread thread;
	
	private volatile boolean stopped;

	private static volatile String hostWorkPath;
	
	@OnWebSocketConnect
	public void onConnect(Session session) throws IOException {
		logger.info("Connected to server");
		this.session = session;
		thread = new Thread(this);
		thread.start();
	}

	private String getHostPath(String path, @Nullable String dockerSock) {
		String workPath = Agent.getWorkDir().getAbsolutePath();
		Preconditions.checkState(path.startsWith(workPath + "/") || path.startsWith(workPath + "\\"));
		if (hostWorkPath == null) {
			if (Agent.isInDocker()) 
				hostWorkPath = AgentUtils.getHostPath(newDocker(dockerSock), workPath);
			else 
				hostWorkPath = workPath;
		}
		return hostWorkPath + path.substring(workPath.length());
	}
	
	@OnWebSocketMessage
	public void onMessage(byte[] bytes, int offset, int length) {
		Message message = Message.of(bytes, offset, length); 
    	byte[] messageData = message.getData();
		try {
	    	switch (message.getType()) {
	    	case UPDATE:
	    		String versionAtServer = new String(messageData, UTF_8);
				File wrapperConfFile = new File(Agent.installDir, "conf/wrapper.conf");
	    		if (!versionAtServer.equals(Agent.version)) {
	    			logger.info("Updating agent to version " + versionAtServer + "...");
	    			Client client = ClientBuilder.newClient();
	    			try {
	    				WebTarget target = client.target(Agent.serverUrl).path("~downloads/agent-lib");
	    				Invocation.Builder builder =  target.request();
	    				builder.header(HttpHeaders.AUTHORIZATION, KubernetesHelper.BEARER + " " + Agent.token);
	    				
	    				try (Response response = builder.get()){
	    					KubernetesHelper.checkStatus(response);
	    					
	    					File newLibDir = new File(Agent.installDir, "lib/" + versionAtServer);
	    					FileUtils.cleanDir(newLibDir);
	    					try (InputStream is = response.readEntity(InputStream.class)) {
	    						TarUtils.untar(is, newLibDir, false);
	    					} 
	    					
	    					String wrapperConf = FileUtils.readFileToString(wrapperConfFile, UTF_8);
	    					wrapperConf = wrapperConf.replace("../lib/" + Agent.version + "/", "../lib/" + versionAtServer + "/");
	    					wrapperConf = wrapperConf.replace("-XX:+IgnoreUnrecognizedVMOptions", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
	    					
	    					if (!wrapperConf.contains("java.base/jdk.internal.ref=ALL-UNNAMED")) {
	    						wrapperConf += ""
	    								+ "\r\nwrapper.java.additional.30=--add-modules=java.se"
	    								+ "\r\nwrapper.java.additional.31=--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED" 
	    								+ "\r\nwrapper.java.additional.32=--add-opens=java.management/sun.management=ALL-UNNAMED"
	    								+ "\r\nwrapper.java.additional.33=--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED";
	    					}
							if (!wrapperConf.contains("java.base/sun.nio.fs=ALL-UNNAMED")) {
								wrapperConf += "\r\nwrapper.java.additional.50=--add-opens=java.base/sun.nio.fs=ALL-UNNAMED";
							}										
	    					if (!wrapperConf.contains("wrapper.disable_console_input")) 
	    						wrapperConf += "\r\nwrapper.disable_console_input=TRUE";

							wrapperConf = wrapperConf.replaceAll("\r\n(\r\n)+\r\n", "\r\n\r\n");
							wrapperConf = wrapperConf.replaceAll("\n(\n)+\n", "\n\n");
							wrapperConf = wrapperConf.replace(
									"wrapperConfwrapper.java.additional.30=--add-modules=java.se",
									"wrapper.java.additional.30=--add-modules=java.se");

	    					FileUtils.writeStringToFile(wrapperConfFile, wrapperConf, UTF_8);

	    					File logbackConfigFile = new File(Agent.installDir, "conf/logback.xml");
	    					String logbackConfig = FileUtils.readFileToString(logbackConfigFile, UTF_8);
	    					if (!logbackConfig.contains("MaskingPatternLayout")) {
	    						logbackConfig = StringUtils.replace(logbackConfig, 
	    								"ch.qos.logback.classic.encoder.PatternLayoutEncoder",
	    								"ch.qos.logback.core.encoder.LayoutWrappingEncoder");
	    						logbackConfig = StringUtils.replace(logbackConfig, 
	    								"<pattern>", 
	    								"<layout class=\"io.onedev.commons.bootstrap.MaskingPatternLayout\">\n				<pattern>");
	    						logbackConfig = StringUtils.replace(logbackConfig, 
	    								"</pattern>", 
	    								"</pattern>\n			</layout>");
	    						FileUtils.writeStringToFile(logbackConfigFile, logbackConfig, UTF_8);
	    					}
	    					
	    				} 
	    			} finally {
	    				client.close();
	    			}
	        		Agent.restart();
	    		} else {
					if (wrapperConfFile.exists()) {
						var confChanged = false;
						String wrapperConf = FileUtils.readFileToString(wrapperConfFile, UTF_8);
						var lines = Splitter.on('\n').trimResults().splitToList(wrapperConf);
						if (lines.stream().noneMatch(it -> it.contains("-XX:MaxRAMPercentage"))) {
							confChanged = true;
							lines = new ArrayList<>(lines);
							lines.removeIf(line -> line.contains("Maximum Java Heap Size (in MB)") || line.contains("wrapper.java.maxmemory"));

							int appendIndex = lines.size();
							for (int i = 0; i < lines.size(); i++) {
								if (lines.get(i).contains("wrapper.java.additional.50")) {
									appendIndex = i + 1;
									break;
								}
							}
							lines.add(appendIndex, "set.default.max_memory_percent=50");
							lines.add(appendIndex, "");
							lines.add(appendIndex, "wrapper.java.additional.100=-XX:MaxRAMPercentage=%max_memory_percent%");
							wrapperConf = StringUtils.join(lineSeparator(), lines);
						}
						if (!wrapperConf.contains("-Djdk.io.File.allowDeleteReadOnlyFiles=true")) {
							confChanged = true;
							wrapperConf += lineSeparator() + "wrapper.java.additional.150=-Djdk.io.File.allowDeleteReadOnlyFiles=true" + lineSeparator();
						}
						if (wrapperConf.contains("wrapper.java.version.min=11")) {
							confChanged = true;
							wrapperConf = wrapperConf.replace( "wrapper.java.version.min=11", "wrapper.java.version.min=17");
							wrapperConf = wrapperConf.replace("Java version 11", "Java version 17");
							wrapperConf = wrapperConf.replace("Java 11 or higher", "Java 17 or higher");
						}

						if (confChanged) {
							FileUtils.writeStringToFile(wrapperConfFile, wrapperConf, UTF_8);
							Agent.restart();
							break;
						}
					}
					AgentData agentData = new AgentData(Agent.token, Agent.osInfo,
							Agent.name, Agent.ipAddress, Agent.cpuCount, Agent.attributes);
					new Message(MessageTypes.AGENT_DATA, agentData).sendBy(session);
	    		}
	    		break;
	    	case UPDATE_ATTRIBUTES:
	    		Map<String, String> attributes = SerializationUtils.deserialize(messageData);
	    		Agent.attributes = attributes;
	    		Properties props = new Properties();
	    		props.putAll(attributes);
	    		try (var os = new BufferedOutputStream(new FileOutputStream(new File(Agent.installDir, "conf/attributes.properties")))) {
		    		props.store(os, null);
	    		}
	    		break;
	    	case RESTART:
				logger.info("Request to restart by server");
	    		Agent.restart();
	    		break;
	    	case STOP:
				logger.info("Request to stop by server");
	    		Agent.stop();
	    		break;
	    	case ERROR:
	    		throw new RuntimeException(new String(messageData, UTF_8));
	    	case REQUEST:
	    		Bootstrap.executorService.execute(() -> {
					try {
						CallData request = SerializationUtils.deserialize(messageData);
						CallData response = new CallData(request.getUuid(), service(request.getPayload()));
						new Message(MessageTypes.RESPONSE, response).sendBy(session);
					} catch (Exception e) {
						logger.error("Error handling websocket request", e);
					}
				});
	    		break;
	    	case RESPONSE:
	    		WebsocketUtils.onResponse(SerializationUtils.deserialize(messageData));
	    		break;
	    	case CANCEL_JOB:
	    		String jobToken = new String(messageData, UTF_8);
	    		cancelJob(jobToken);
	    		break;
	    	case RESUME_JOB: 
	    		jobToken = new String(messageData, UTF_8);
	    		resumeJob(jobToken);
	    		break;
	    	case SHELL_OPEN:
	    		String openData = new String(messageData, UTF_8);
	    		String sessionId = StringUtils.substringBefore(openData, ":");
	    		jobToken = StringUtils.substringAfter(openData, ":");

	    		String containerName = containerNames.get(jobToken);
	    		File buildDir;
	    		if (containerName != null) {
					Commandline docker = newDocker(dockerSocks.get(jobToken));
	    			docker.addArgs("exec", "-it", containerName);
	    			LeafFacade runningStep = runningSteps.get(jobToken);
	    			if (runningStep instanceof CommandFacade) {
	    				CommandFacade commandStep = (CommandFacade) runningStep;
	    				docker.addArgs(commandStep.getExecutable());
	    			} else {
	    				docker.addArgs("sh");
	    			}
	    			shellSessions.put(sessionId, new ShellSession(sessionId, session, docker));
	    		} else if ((buildDir = buildDirs.get(jobToken)) != null) {
	    			Commandline shell;
	    			LeafFacade runningStep = runningSteps.get(jobToken);
	    			if (runningStep instanceof CommandFacade) {
	    				CommandFacade commandStep = (CommandFacade) runningStep;
	    				shell = new Commandline(commandStep.getExecutable());
	    			} else if (SystemUtils.IS_OS_WINDOWS) {
	    				shell = new Commandline("cmd");
	    			} else {
	    				shell = new Commandline("sh");
	    			}
	    			shell.workingDir(new File(buildDir, "work"));
	    			shellSessions.put(sessionId, new ShellSession(sessionId, session, shell));
	    		} else {
	    			sendOutput(sessionId, session, Base64.getEncoder().encodeToString("Shell not ready".getBytes(UTF_8)));
	    		}
	    		break;
	    	case SHELL_TERMINATE:
	    		sessionId = new String(messageData, UTF_8);
	    		ShellSession shellSession = shellSessions.remove(sessionId);
	    		if (shellSession != null)
	    			shellSession.exit();
	    		break;
	    	default:
	    	}
		} catch (Exception e) {
			if (!Agent.logExpectedError(e, logger))
				logger.error("Error processing websocket message", e);
			try {
				session.disconnect();
			} catch (IOException e2) {
			}
		}
	}
	
	private void cancelJob(String jobToken) {
		Thread thread = jobThreads.get(jobToken);
		if (thread != null)
			thread.interrupt();
	}
		
	private void resumeJob(String jobToken) {
		File buildDir = buildDirs.get(jobToken);
		if (buildDir != null) synchronized (buildDir) {
			if (buildDir.exists())
				FileUtils.touchFile(new File(buildDir, "continue"));
		}
	}
		
	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		if (reason != null)
			logger.debug("Websocket closed (status code: {}, reason: {})", statusCode, reason);
		else
			logger.debug("Websocket closed (status code: {})", statusCode);
		Agent.reconnect = true;
		while (!stopped) {
			thread.interrupt();
			try {
				Thread.sleep(1000); 
			} catch (InterruptedException e) {
			}
		}
	}
	
	private boolean executeShellJob(Session session, ShellJobData jobData) {
		checkShellApplicable();
		File buildDir = new File(Agent.getTempDir(),
				"onedev-build-" + jobData.getProjectId() + "-" + jobData.getBuildNumber() + "-" + jobData.getSubmitSequence());
		File workDir = new File(buildDir, "work");
		
		File attributesDir = new File(buildDir, KubernetesHelper.ATTRIBUTES);
		FileUtils.createDir(attributesDir);

		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), UTF_8);
		}
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		buildDirs.put(jobData.getJobToken(), buildDir);
		SecretMasker.push(jobData.getSecretMasker());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};

			FileUtils.createDir(workDir);

			var cacheProvisioner = new AgentCacheProvisioner(jobData.getJobToken(), buildDir, jobLogger);

			jobLogger.log("Downloading job dependencies...");
			
			downloadDependencies(Agent.serverUrl,  jobData.getJobToken(),
					workDir, Agent.sslFactory);
						
			String messageData = jobData.getJobToken() + ":" + workDir.getAbsolutePath();
			new Message(MessageTypes.REPORT_JOB_WORKDIR, messageData).sendBy(session);

			CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
			var successful = entryFacade.execute(new LeafHandler() {

				@Override
				public boolean execute(LeafFacade facade, List<Integer> position) {
					return AgentUtils.runStep(entryFacade, position, jobLogger, () -> {
						runningSteps.put(jobData.getJobToken(), facade);
						try {
							return doExecute(facade, position);
						} finally {
							runningSteps.remove(jobData.getJobToken());
						}
					});
				}

				private boolean doExecute(LeafFacade facade, List<Integer> position) {
					if (facade instanceof CommandFacade) {
						CommandFacade commandFacade = (CommandFacade) facade;
						if (commandFacade.getImage() != null) {
							throw new ExplicitException("This step can only be executed by server docker executor, "
									+ "remote docker executor, or kubernetes executor");
						}

						commandFacade.generatePauseCommand(buildDir);
						var commandDir = new File(buildDir, "command");
						FileUtils.createDir(commandDir);
						File stepScriptFile = new File(commandDir, "step-" + stringifyStepPosition(position) + commandFacade.getScriptExtension());
						try {
							FileUtils.writeStringToFile(
									stepScriptFile,
									commandFacade.normalizeCommands(replacePlaceholders(commandFacade.getCommands(), buildDir)),
									UTF_8);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}

						var cmdline = new Commandline(commandFacade.getExecutable());
						cmdline.addArgs(commandFacade.getScriptOptions());
						cmdline.addArgs(stepScriptFile.getAbsolutePath());

						Map<String, String> envs = new HashMap<>();
						envs.put("ONEDEV_WORKDIR", workDir.getAbsolutePath());
						envs.putAll(commandFacade.getEnvMap());
						cmdline.workingDir(workDir).envs(envs);

						var result = cmdline.execute(
								AgentUtils.newInfoLogger(jobLogger), AgentUtils.newWarningLogger(jobLogger));
						if (result.getReturnCode() != 0) {
							jobLogger.error("Command exited with code " + result.getReturnCode());
							return false;
						}
					} else if (facade instanceof BuildImageFacade || facade instanceof RunContainerFacade
							|| facade instanceof RunImagetoolsFacade || facade instanceof PruneBuilderCacheFacade) {
						throw new ExplicitException("This step can only be executed by server docker executor and remote docker executor");
					} else if (facade instanceof CheckoutFacade) {
						CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
						jobLogger.log("Checking out code...");

						Commandline git = new Commandline(Agent.gitPath);
						checkoutFacade.setupWorkingDir(git, workDir);

						var infoLogger = AgentUtils.newInfoLogger(jobLogger);
						var warningLogger = AgentUtils.newWarningLogger(jobLogger);

						initRepository(git, infoLogger, warningLogger);

						var cloneInfo = checkoutFacade.getCloneInfo();
						var trustCertsFile = new File(buildDir, "trust-certs.pem");

						var remoteAccessArgs = new ArrayList<String>();
						remoteAccessArgs.addAll(setupGitCerts(git, Bootstrap.getTrustCertsDir(), trustCertsFile,
								trustCertsFile.getAbsolutePath(), infoLogger, warningLogger));
						remoteAccessArgs.addAll(cloneInfo.setupGitAuth(git, buildDir, buildDir.getAbsolutePath(), 
								infoLogger, warningLogger));

						git.args(remoteAccessArgs);

						int cloneDepth = checkoutFacade.getCloneDepth();
						cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(),
								jobData.getRefName(), jobData.getCommitHash(), checkoutFacade.isWithLfs(),
								checkoutFacade.isWithSubmodules(), cloneDepth,
								AgentUtils.newInfoLogger(jobLogger), AgentUtils.newWarningLogger(jobLogger));
					} else if (facade instanceof SetupCacheFacade) {
						SetupCacheFacade setupCacheFacade = (SetupCacheFacade) facade;
						for (var path: setupCacheFacade.getCacheConfig().getPaths()) {
							if (FilenameUtils.getPrefixLength(path) > 0)
								throw new ExplicitException("Shell executor does not allow absolute cache path: " + path);
						}
						cacheProvisioner.setupCache(setupCacheFacade.getCacheConfig());
					} else if (facade instanceof ServerSideFacade) {
						ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
						return KubernetesHelper.runServerStep(Agent.sslFactory,
								Agent.serverUrl, jobData.getJobToken(), position,
								serverSideFacade, buildDir, jobLogger);
					} else {
						throw new ExplicitException("Unexpected step type: " + facade.getClass());
					}
					return true;
				}

				@Override
				public void skip(LeafFacade facade, List<Integer> position) {
					jobLogger.notice("Step \"" + entryFacade.getPathAsString(position) + "\" is skipped");
				}
				
			}, new ArrayList<>());

			if (successful)
				cacheProvisioner.uploadCaches();

			return successful;
		} finally {
			SecretMasker.pop();
			jobThreads.remove(jobData.getJobToken());
			buildDirs.remove(jobData.getJobToken());
			
			// Fix https://code.onedev.io/onedev/server/~issues/597
			if (SystemUtils.IS_OS_WINDOWS && workDir.exists())
				FileUtils.deleteDir(workDir);
			
			synchronized (buildDir) {
				FileUtils.deleteDir(buildDir);
			}
		}
	}

	private Commandline newDocker(@Nullable String dockerSock) {
		var docker = new Commandline(Agent.dockerPath);
		AgentUtils.useDockerSock(docker, dockerSock);
		return docker;
	}

	private boolean executeDockerJob(Session session, DockerJobData jobData) {
		File hostBuildDir = new File(Agent.getTempDir(),
				"onedev-build-" + jobData.getProjectId() + "-" + jobData.getBuildNumber() + "-" + jobData.getSubmitSequence());
		File hostAttributesDir = new File(hostBuildDir, KubernetesHelper.ATTRIBUTES);
		FileUtils.createDir(hostAttributesDir);

		for (Map.Entry<String, String> entry: Agent.attributes.entrySet())
			FileUtils.writeFile(new File(hostAttributesDir, entry.getKey()), entry.getValue(), UTF_8);
		var dockerSock = jobData.getDockerSock();

		Client client = ClientBuilder.newClient();
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		buildDirs.put(jobData.getJobToken(), hostBuildDir);
		if (dockerSock != null)
			dockerSocks.put(jobData.getJobToken(), dockerSock);
		SecretMasker.push(jobData.getSecretMasker());
		TaskLogger jobLogger = new TaskLogger() {

			@Override
			public void log(String message, String sessionId) {
				Agent.log(session, jobData.getJobToken(), message, sessionId);
			}
			
		};
		try {
			String network = jobData.getExecutorName() + "-" + jobData.getProjectId() + "-"
					+ jobData.getBuildNumber() + "-" + jobData.getSubmitSequence();
			jobLogger.log("Creating docker network '" + network + "'...");
			
			createNetwork(newDocker(dockerSock), network, jobData.getNetworkOptions(), jobLogger);
			try {
				var docker = newDocker(dockerSock);
				for (var jobService: jobData.getServices()) {
					var registryLogins = merge(jobService.getRegistryLogins(), jobData.getRegistryLogins());
					callWithRegistryLogins(docker, registryLogins, () -> {
						startService(docker, network, jobService, jobData.getCpuLimit(), jobData.getMemoryLimit(), jobLogger);
						return null;
					});
				}

				File hostWorkDir = new File(hostBuildDir, "work");
				FileUtils.createDir(hostWorkDir);

				var cacheProvisioner = new AgentCacheProvisioner(jobData.getJobToken(), hostBuildDir, jobLogger);

				try {
					jobLogger.log("Downloading job dependencies...");

					downloadDependencies(Agent.serverUrl, jobData.getJobToken(),
							hostWorkDir, Agent.sslFactory);
					
					var containerBuildDirPath = "/onedev-build";
					var containerWorkDirPath = "/onedev-build/work";
					var containerTrustCertsFilePath = "/onedev-build/trust-certs.pem";
					
					String messageData = jobData.getJobToken() + ":" + containerWorkDirPath;
					new Message(MessageTypes.REPORT_JOB_WORKDIR, messageData).sendBy(session);

					CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
					var osIds = getOsIds(jobLogger);
					var successful = entryFacade.execute(new LeafHandler() {

						private int runStepContainer(Commandline docker, String image, @Nullable String runAs,
													 @Nullable String entrypoint, List<String> arguments,
													 Map<String, String> environments, @Nullable String workingDir,
													 Map<String, String> volumeMounts, List<Integer> position,
													 boolean useTTY) {
							String containerName = network + "-step-" + stringifyStepPosition(position);
							containerNames.put(jobData.getJobToken(), containerName);
							try {
								docker.args("run", "--name=" + containerName, "--network=" + network);
								if (jobData.isAlwaysPullImage())
									docker.addArgs("--pull=always");
								if (runAs != null)
									docker.addArgs("--user", runAs);
								else 
									docker.addArgs("--user", "0:0");

								if (jobData.getCpuLimit() != null)
									docker.addArgs("--cpus", jobData.getCpuLimit());
								if (jobData.getMemoryLimit() != null)
									docker.addArgs("--memory", jobData.getMemoryLimit());
								if (jobData.getDockerOptions() != null)
									docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));

								docker.addArgs("-v", getHostPath(hostBuildDir.getAbsolutePath(), dockerSock) + ":" + containerBuildDirPath);

								for (Map.Entry<String, String> entry: volumeMounts.entrySet()) {
									if (entry.getKey().contains(".."))
										throw new ExplicitException("Volume mount source path should not contain '..'");
									String hostPath = getHostPath(new File(hostWorkDir, entry.getKey()).getAbsolutePath(), dockerSock);
									docker.addArgs("-v", hostPath + ":" + entry.getValue());
								}

								for (var allocation: cacheProvisioner.getAllocations()) {
									for (var entry: allocation.getPathMap().entrySet()) {
										if (FilenameUtils.getPrefixLength(entry.getKey()) > 0)
											docker.addArgs("-v", getHostPath(entry.getValue().getAbsolutePath(), dockerSock) + ":" + entry.getKey());
									}
								}

								if (entrypoint != null)
									docker.addArgs("-w", containerWorkDirPath);
								else if (workingDir != null)
									docker.addArgs("-w", workingDir);

								if (jobData.isMountDockerSock()) {
									if (dockerSock != null) 
										docker.addArgs("-v", dockerSock + ":/var/run/docker.sock");
									else 
										docker.addArgs("-v", "/var/run/docker.sock:/var/run/docker.sock");
								}

								for (Map.Entry<String, String> entry: environments.entrySet())
									docker.addArgs("-e", entry.getKey() + "=" + entry.getValue());

								docker.addArgs("-e", "ONEDEV_WORKDIR=" + containerWorkDirPath);

								if (useTTY)
									docker.addArgs("-t");

								if (entrypoint != null)
									docker.addArgs("--entrypoint=" + entrypoint);

								docker.addArgs(image);
								docker.addArgs(arguments.toArray(new String[arguments.size()]));
								docker.processKiller(newDockerKiller(newDocker(dockerSock), containerName, jobLogger));
								var result = docker.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger),
										null);
								return result.getReturnCode();
							} finally {
								containerNames.remove(jobData.getJobToken());
							}
						}

						@Override
						public boolean execute(LeafFacade facade, List<Integer> position) {
							return AgentUtils.runStep(entryFacade, position, jobLogger, () -> {
								runningSteps.put(jobData.getJobToken(), facade);
								try {
									return doExecute(facade, position);
								} finally {
									runningSteps.remove(jobData.getJobToken());
								}
							});
						}

						private boolean doExecute(LeafFacade facade, List<Integer> position) {
							if (facade instanceof CommandFacade) {
								CommandFacade commandFacade = ((CommandFacade) facade).replacePlaceholders(hostBuildDir);
								if (commandFacade.getImage() == null) {
									throw new ExplicitException("This step can only be executed by server shell "
											+ "executor or remote shell executor");
								}
								var entrypointArgs = getEntrypointArgs(hostBuildDir, commandFacade, position);
								var docker = newDocker(dockerSock);

								var registryLogins = merge(commandFacade.getRegistryLogins(), jobData.getRegistryLogins());
								
								var runAs = commandFacade.getRunAs();
								if (SystemUtils.IS_OS_LINUX && !runAs.equals("0:0")) {
									jobLogger.log("Changing owner of build directory to container user...");
									changeOwner(docker, runAs, hostBuildDir, osIds, jobLogger);
								}
								try {
									int exitCode = callWithRegistryLogins(docker, registryLogins, () -> {
										return runStepContainer(docker, commandFacade.getImage(), runAs,
												"sh", entrypointArgs, commandFacade.getEnvMap(),
												null, new HashMap<>(), position, commandFacade.isUseTTY());
									});
									if (exitCode != 0) {
										jobLogger.error("Command exited with code " + exitCode);
										return false;
									}
								} finally {
									if (SystemUtils.IS_OS_LINUX && !osIds.equals("0:0")) {
										jobLogger.log("Changing owner of build directory to host user...");
										changeOwner(newDocker(dockerSock), osIds, hostBuildDir, osIds, jobLogger);
									}
								}
							} else if (facade instanceof BuildImageFacade) {
								var buildImageFacade = (BuildImageFacade) facade;
								var registryLogins = merge(buildImageFacade.getRegistryLogins(), jobData.getRegistryLogins());
								var docker = newDocker(dockerSock);
								callWithRegistryLogins(docker, registryLogins, () -> {
									buildImage(docker, jobData.getDockerBuilder(), buildImageFacade, hostBuildDir,
											jobData.isAlwaysPullImage(), jobLogger);
									return null;
								});
							} else if (facade instanceof RunImagetoolsFacade) {
								var runImagetoolsFacade = (RunImagetoolsFacade) facade;
								var registryLogins = merge(runImagetoolsFacade.getRegistryLogins(), jobData.getRegistryLogins());
								var docker = newDocker(dockerSock);
								callWithRegistryLogins(docker, registryLogins, () -> {
									runImagetools(docker, runImagetoolsFacade, hostBuildDir, jobLogger);
									return null;
								});
							} else if (facade instanceof PruneBuilderCacheFacade) {
								var pruneBuilderCacheFacade = (PruneBuilderCacheFacade) facade;
								var docker = newDocker(dockerSock);
								callWithRegistryLogins(docker, new ArrayList<>(), () -> {
									pruneBuilderCache(docker, jobData.getDockerBuilder(), pruneBuilderCacheFacade,
											hostBuildDir, jobLogger);
									return null;
								});
							} else if (facade instanceof RunContainerFacade) {
								RunContainerFacade runContainerFacade = ((RunContainerFacade) facade).replacePlaceholders(hostBuildDir);

								List<String> arguments = new ArrayList<>();
								if (runContainerFacade.getArgs() != null)
									arguments.addAll(Arrays.asList(StringUtils.parseQuoteTokens(runContainerFacade.getArgs())));

								var docker = newDocker(dockerSock);
								var registryLogins = merge(runContainerFacade.getRegistryLogins(), jobData.getRegistryLogins());
								var runAs = runContainerFacade.getRunAs() != null ? runContainerFacade.getRunAs() : "0:0";
								if (SystemUtils.IS_OS_LINUX && !runAs.equals("0:0")) {
									jobLogger.log("Changing owner of build directory to container user...");
									changeOwner(docker, runAs, hostBuildDir, osIds, jobLogger);
								}
								try {
									int exitCode = callWithRegistryLogins(docker, registryLogins, () -> {
										return runStepContainer(docker, runContainerFacade.getImage(), runAs, null, arguments,
												runContainerFacade.getEnvMap(), runContainerFacade.getWorkingDir(), runContainerFacade.getVolumeMounts(),
												position, runContainerFacade.isUseTTY());
									});
									if (exitCode != 0) {
										jobLogger.error("Container exited with code " + exitCode);
										return false;
									}
								} finally {
									if (SystemUtils.IS_OS_LINUX && !osIds.equals("0:0")) {
										jobLogger.log("Changing owner of build directory to host user...");
										changeOwner(newDocker(dockerSock), osIds, hostBuildDir, osIds, jobLogger);
									}
								}
							} else if (facade instanceof CheckoutFacade) {
								CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
								jobLogger.log("Checking out code...");

								Commandline git = new Commandline(Agent.gitPath);
								checkoutFacade.setupWorkingDir(git, hostWorkDir);

								var infoLogger = AgentUtils.newInfoLogger(jobLogger);
								var warningLogger = AgentUtils.newWarningLogger(jobLogger);

								initRepository(git, infoLogger, warningLogger);

								var remoteAccessArgs = new ArrayList<String>();
								remoteAccessArgs.addAll(setupGitCerts(git, Agent.getTrustCertsDir(),
										new File(hostBuildDir, "trust-certs.pem"), 
										containerTrustCertsFilePath, infoLogger, warningLogger));

								CloneInfo cloneInfo = checkoutFacade.getCloneInfo();
								remoteAccessArgs.addAll(cloneInfo.setupGitAuth(git, hostBuildDir, 
										containerBuildDirPath, infoLogger, warningLogger));

								int cloneDepth = checkoutFacade.getCloneDepth();

								String cloneUrl = checkoutFacade.getCloneInfo().getCloneUrl();
								String refName = jobData.getRefName();
								String commitHash = jobData.getCommitHash();

								git.args(remoteAccessArgs);

								cloneRepository(git, cloneUrl, cloneUrl, refName, commitHash,
										checkoutFacade.isWithLfs(), checkoutFacade.isWithSubmodules(), cloneDepth,
										infoLogger, warningLogger);
							} else if (facade instanceof SetupCacheFacade) {
								SetupCacheFacade setupCacheFacade = (SetupCacheFacade) facade;
								cacheProvisioner.setupCache(setupCacheFacade.getCacheConfig());
							} else if (facade instanceof ServerSideFacade) {
								ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
								return KubernetesHelper.runServerStep(Agent.sslFactory,
										Agent.serverUrl, jobData.getJobToken(), position,
										serverSideFacade, hostBuildDir, jobLogger);
							} else {
								throw new ExplicitException("Unexpected step type: " + facade.getClass());
							}
							return true;
						}

						@Override
						public void skip(LeafFacade facade, List<Integer> position) {
							jobLogger.notice("Step \"" + entryFacade.getPathAsString(position) + "\" is skipped");
						}
						
					}, new ArrayList<>());

					if (successful)
						cacheProvisioner.uploadCaches();

					return successful;
				} finally {
					// Fix https://code.onedev.io/onedev/server/~issues/597
					if (SystemUtils.IS_OS_WINDOWS)
						FileUtils.deleteDir(hostWorkDir);
				}
			} finally {
				deleteNetwork(newDocker(dockerSock), network, jobLogger);
			}
		} finally {
			SecretMasker.pop();
			jobThreads.remove(jobData.getJobToken());
			buildDirs.remove(jobData.getJobToken());
			if (dockerSock != null)
				dockerSocks.remove(jobData.getJobToken());
			client.close();
			
			synchronized (hostBuildDir) {
				deleteDir(hostBuildDir, newDocker(dockerSock), Agent.isInDocker(), jobLogger);
			}
		}
	}

	private void checkShellApplicable() {
		if (Agent.isInDocker()) {
			throw new ExplicitException("Remote shell executor can only execute jobs on agents running "
					+ "directly on bare metal/virtual machine");
		}
	}

	private void testShellExecutor(Session session, TestShellJobData jobData) {
		checkShellApplicable();
		Client client = ClientBuilder.newClient();
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};
			
			jobLogger.log(String.format("Connecting to server '%s'...", Agent.serverUrl));
			WebTarget target = client.target(Agent.serverUrl)
					.path("~api/k8s/test")
					.queryParam("jobToken", jobData.getJobToken());
			Invocation.Builder builder =  target.request();
			try (Response response = builder.get()) {
				checkStatus(response);
			} 
			
			var git = new Commandline(Agent.gitPath);
			KubernetesHelper.addMacUsrLocalBinToPath(git);
			AgentUtils.testCommands(jobData.getCommands(), jobLogger);
    		KubernetesHelper.testGitLfsAvailability(git, jobLogger);
		} finally {
			jobThreads.remove(jobData.getJobToken());
			client.close();
		}		
	}
	
	private void testDockerExecutor(Session session, TestDockerJobData jobData) {
		var dockerSock = jobData.getDockerSock();
		Commandline docker = newDocker(dockerSock);
		callWithRegistryLogins(docker, jobData.getRegistryLogins(), () -> {
			File workDir = null;
			File authInfoDir = null;

			Client client = ClientBuilder.newClient();
			jobThreads.put(jobData.getJobToken(), Thread.currentThread());
			try {
				TaskLogger jobLogger = new TaskLogger() {

					@Override
					public void log(String message, String sessionId) {
						Agent.log(session, jobData.getJobToken(), message, sessionId);
					}

				};

				workDir = FileUtils.createTempDir("work");
				authInfoDir = FileUtils.createTempDir();

				jobLogger.log(String.format("Connecting to server '%s'...", Agent.serverUrl));
				WebTarget target = client.target(Agent.serverUrl)
						.path("~api/k8s/test")
						.queryParam("jobToken", jobData.getJobToken());
				Invocation.Builder builder =  target.request();
				try (Response response = builder.get()) {
					checkStatus(response);
				}

				jobLogger.log("Testing specified docker image...");
				docker.args("run", "--rm");
				if (jobData.getDockerOptions() != null)
					docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));

				var containerWorkDirPath = "/onedev-build/work";
				docker.addArgs("-v", getHostPath(workDir.getAbsolutePath(), dockerSock) + ":" + containerWorkDirPath);

				docker.addArgs("-w", containerWorkDirPath);
				docker.addArgs(jobData.getDockerImage(), "sh", "-c", "echo hello from container");

				docker.execute(new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}

				}, new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}

				}).checkReturnCode();

				jobLogger.log("Checking busybox availability...");
				docker.args("run", "--rm", "busybox", "sh", "-c", "echo hello from busybox");
				docker.execute(new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}

				}, new LineConsumer() {

					@Override
					public void consume(String line) {
						jobLogger.log(line);
					}

				}).checkReturnCode();

				var git = new Commandline(Agent.gitPath);
				KubernetesHelper.addMacUsrLocalBinToPath(git);
				KubernetesHelper.testGitLfsAvailability(git, jobLogger);
			} finally {
				jobThreads.remove(jobData.getJobToken());
				client.close();
				if (authInfoDir != null)
					FileUtils.deleteDir(authInfoDir);
				if (workDir != null)
					FileUtils.deleteDir(workDir);
			}
			return null;
		});
	}
	
	private Serializable service(Serializable request) {
		try {
			if (request instanceof LogRequest) { 
				return (Serializable) LogRequest.readLog(new File(Agent.installDir, "logs/agent.log"));
			} else if (request instanceof ShellInputRequest shellInputRequest) {
				var shellSession = shellSessions.get(shellInputRequest.getSessionId());
				if (shellSession != null)
					shellSession.writeToStdin(shellInputRequest.getData());
				return null;
			} else if (request instanceof ShellResizeRequest shellResizeRequest) {
				var shellSession = shellSessions.get(shellResizeRequest.getSessionId());
				if (shellSession != null)
					shellSession.resize(shellResizeRequest.getRows(), shellResizeRequest.getCols());
				return null;
			} else if (request instanceof DockerJobData dockerJobData) { 
				try {
					return executeDockerJob(session, dockerJobData);
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof TestDockerJobData testDockerJobData) {
				try {
					testDockerExecutor(session, testDockerJobData);
					return true;
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof ShellJobData shellJobData) { 
				try {
					return executeShellJob(session, shellJobData);
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof TestShellJobData testShellJobData) {
				try {
					testShellExecutor(session, testShellJobData);
					return true;
				} catch (Exception e) {
					return e;
				}
			} else { 
				throw new ExplicitException("Unknown request: " + request.getClass());
			}
		} catch (Exception e) {
			logger.error("Error servicing websocket request", e);
			return e;
		}
	}
	
	public static void sendOutput(String sessionId, Session agentSession, String base64Data) {
		try {
			WebsocketUtils.call(agentSession, new ShellOutputRequest(sessionId, base64Data), 0);
		} catch (InterruptedException | TimeoutException e) {
			throw new RuntimeException(e);
		}
	}

    @OnWebSocketError
    public void onError(Throwable t) {
    	if (!Agent.logExpectedError(t, logger))
    		logger.error("Websocket error", t);
    	Agent.reconnect = true;
    }
    
	@Override
	public void run() {
		Message message = new Message(MessageTypes.HEART_BEAT, new byte[0]);
		while (true) {
			try {
				Thread.sleep(Agent.SOCKET_IDLE_TIMEOUT/3);
			} catch (InterruptedException e) {
				break;
			}
			try {
				message.sendBy(session);
			} catch (Exception e) {
				logger.error("Error pinging server", e);
				try {
					session.disconnect();
				} catch (Exception e2) {
				}
			}
		}
		stopped = true;
	}
	
}
