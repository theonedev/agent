package io.onedev.agent;

import static io.onedev.agent.DockerExecutorUtils.buildImage;
import static io.onedev.agent.DockerExecutorUtils.callWithDockerConfig;
import static io.onedev.agent.DockerExecutorUtils.changeOwner;
import static io.onedev.agent.DockerExecutorUtils.createNetwork;
import static io.onedev.agent.DockerExecutorUtils.deleteDir;
import static io.onedev.agent.DockerExecutorUtils.deleteNetwork;
import static io.onedev.agent.DockerExecutorUtils.getEntrypoint;
import static io.onedev.agent.DockerExecutorUtils.getOwner;
import static io.onedev.agent.DockerExecutorUtils.isUseProcessIsolation;
import static io.onedev.agent.DockerExecutorUtils.newDockerKiller;
import static io.onedev.agent.DockerExecutorUtils.pruneBuilderCache;
import static io.onedev.agent.DockerExecutorUtils.runImagetools;
import static io.onedev.agent.DockerExecutorUtils.startService;
import static io.onedev.agent.ExecutorUtils.newErrorLogger;
import static io.onedev.agent.ExecutorUtils.newInfoLogger;
import static io.onedev.agent.ExecutorUtils.newWarningLogger;
import static io.onedev.agent.ShellExecutorUtils.testCommands;
import static io.onedev.commons.bootstrap.Bootstrap.isInDocker;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.downloadDependencies;
import static io.onedev.k8shelper.KubernetesHelper.installGitCert;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jspecify.annotations.Nullable;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.SystemUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

import io.onedev.agent.job.DockerJobData;
import io.onedev.agent.job.LogRequest;
import io.onedev.agent.job.ShellJobData;
import io.onedev.agent.job.TestDockerJobData;
import io.onedev.agent.job.TestShellJobData;
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
	
	private static final Map<String, File> buildHomes = new ConcurrentHashMap<>();

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
				hostWorkPath = DockerExecutorUtils.getHostPath(newDocker(dockerSock), workPath);
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
	    		File buildHome;
	    		if (containerName != null) {
					Commandline docker = newDocker(dockerSocks.get(jobToken));
	    			docker.addArgs("exec", "-it", containerName);
	    			LeafFacade runningStep = runningSteps.get(jobToken);
	    			if (runningStep instanceof CommandFacade) {
	    				CommandFacade commandStep = (CommandFacade) runningStep;
	    				docker.addArgs(commandStep.getShell(SystemUtils.IS_OS_WINDOWS, null));
	    			} else if (SystemUtils.IS_OS_WINDOWS) {
	    				docker.addArgs("cmd");
	    			} else {
	    				docker.addArgs("sh");
	    			}
	    			shellSessions.put(sessionId, new ShellSession(sessionId, session, docker));
	    		} else if ((buildHome = buildHomes.get(jobToken)) != null) {
	    			Commandline shell;
	    			LeafFacade runningStep = runningSteps.get(jobToken);
	    			if (runningStep instanceof CommandFacade) {
	    				CommandFacade commandStep = (CommandFacade) runningStep;
	    				shell = new Commandline(commandStep.getShell(SystemUtils.IS_OS_WINDOWS, null)[0]);
	    			} else if (SystemUtils.IS_OS_WINDOWS) {
	    				shell = new Commandline("cmd");
	    			} else {
	    				shell = new Commandline("sh");
	    			}
	    			shell.workingDir(new File(buildHome, "workspace"));
	    			shellSessions.put(sessionId, new ShellSession(sessionId, session, shell));
	    		} else {
	    			sendError(sessionId, session, "Shell not ready");
	    		}
	    		break;
	    	case SHELL_EXIT:
	    		sessionId = new String(messageData, UTF_8);
	    		ShellSession shellSession = shellSessions.remove(sessionId);
	    		if (shellSession != null)
	    			shellSession.exit();
	    		break;
	    	case SHELL_INPUT:
	    		String inputData = new String(messageData, UTF_8);
	    		sessionId = StringUtils.substringBefore(inputData, ":");
	    		String input = StringUtils.substringAfter(inputData, ":");
	    		shellSession = shellSessions.get(sessionId);
	    		if (shellSession != null)
	    			shellSession.sendInput(input);
	    		break;
	    	case SHELL_RESIZE:
	    		String resizeData = new String(messageData, UTF_8);
	    		sessionId = StringUtils.substringBefore(resizeData, ":");
	    		String rowsAndCols = StringUtils.substringAfter(resizeData, ":");
	    		int rows = Integer.parseInt(StringUtils.substringBefore(rowsAndCols, ":"));
	    		int cols = Integer.parseInt(StringUtils.substringAfter(rowsAndCols, ":"));
	    		shellSession = shellSessions.get(sessionId);
	    		if (shellSession != null)
	    			shellSession.resize(rows, cols);
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
		File buildHome = buildHomes.get(jobToken);
		if (buildHome != null) synchronized (buildHome) {
			if (buildHome.exists())
				FileUtils.touchFile(new File(buildHome, "continue"));
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
		File buildHome = new File(Agent.getTempDir(),
				"onedev-build-" + jobData.getProjectId() + "-" + jobData.getBuildNumber() + "-" + jobData.getSubmitSequence());
		FileUtils.createDir(buildHome);
		File workspaceDir = new File(buildHome, "workspace");
		
		File attributesDir = new File(buildHome, KubernetesHelper.ATTRIBUTES);
		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), UTF_8);
		}
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		buildHomes.put(jobData.getJobToken(), buildHome);
		SecretMasker.push(jobData.getSecretMasker());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};

			FileUtils.createDir(workspaceDir);

			var cacheHelper = new AgentCacheHelper(jobData.getJobToken(), buildHome, jobLogger);

			jobLogger.log("Downloading job dependencies...");
			
			downloadDependencies(Agent.serverUrl,  jobData.getJobToken(),
					workspaceDir, Agent.sslFactory);
			
			File userHome = new File(buildHome, "user");
			FileUtils.createDir(userHome);
			
			String messageData = jobData.getJobToken() + ":" + workspaceDir.getAbsolutePath();
			new Message(MessageTypes.REPORT_JOB_WORKSPACE, messageData).sendBy(session);

			CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
			var successful = entryFacade.execute(new LeafHandler() {

				@Override
				public boolean execute(LeafFacade facade, List<Integer> position) {
					return ExecutorUtils.runStep(entryFacade, position, jobLogger, () -> {
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

						commandFacade.generatePauseCommand(buildHome);
						var commandDir = new File(buildHome, "command");
						FileUtils.createDir(commandDir);
						File stepScriptFile = new File(commandDir, "step-" + stringifyStepPosition(position) + commandFacade.getScriptExtension());
						try {
							FileUtils.writeStringToFile(
									stepScriptFile,
									commandFacade.normalizeCommands(replacePlaceholders(commandFacade.getCommands(), buildHome)),
									UTF_8);
						} catch (IOException e) {
							throw new RuntimeException(e);
						}

						Commandline interpreter = commandFacade.getScriptInterpreter();
						Map<String, String> environments = new HashMap<>();
						environments.put("GIT_HOME", userHome.getAbsolutePath());
						environments.put("ONEDEV_WORKSPACE", workspaceDir.getAbsolutePath());
						environments.putAll(commandFacade.getEnvMap());
						interpreter.workingDir(workspaceDir).environments(environments);
						interpreter.addArgs(stepScriptFile.getAbsolutePath());

						var result = interpreter.execute(
								ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
						if (result.getReturnCode() != 0) {
							jobLogger.error("Command exited with code " + result.getReturnCode());
							return false;
						}
					} else if (facade instanceof BuildImageFacade || facade instanceof RunContainerFacade
							|| facade instanceof RunImagetoolsFacade || facade instanceof PruneBuilderCacheFacade) {
						throw new ExplicitException("This step can only be executed by server docker executor, "
								+ "remote docker executor");
					} else if (facade instanceof CheckoutFacade) {
						CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
						jobLogger.log("Checking out code...");

						Commandline git = new Commandline(Agent.gitPath);

						Map<String, String> environments = new HashMap<>();
						environments.put("HOME", userHome.getAbsolutePath());
						git.environments(environments);

						checkoutFacade.setupWorkingDir(git, workspaceDir);

						File trustCertsFile = new File(buildHome, "trust-certs.pem");
						installGitCert(git, Agent.getTrustCertsDir(), trustCertsFile,
								trustCertsFile.getAbsolutePath(), newInfoLogger(jobLogger),
								newWarningLogger(jobLogger));

						CloneInfo cloneInfo = checkoutFacade.getCloneInfo();

						cloneInfo.writeAuthData(userHome, git, false,
								ExecutorUtils.newInfoLogger(jobLogger),
								ExecutorUtils.newWarningLogger(jobLogger));

						int cloneDepth = checkoutFacade.getCloneDepth();
						cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(),
								jobData.getRefName(), jobData.getCommitHash(), checkoutFacade.isWithLfs(),
								checkoutFacade.isWithSubmodules(), cloneDepth,
								ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
					} else if (facade instanceof SetupCacheFacade) {
						SetupCacheFacade setupCacheFacade = (SetupCacheFacade) facade;
						for (var cachePath: setupCacheFacade.getPaths()) {
							if (new File(cachePath).isAbsolute())
								throw new ExplicitException("Shell executor does not allow absolute cache path: " + cachePath);
						}
						cacheHelper.setupCache(setupCacheFacade);
					} else if (facade instanceof ServerSideFacade) {
						ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
						return KubernetesHelper.runServerStep(Agent.sslFactory,
								Agent.serverUrl, jobData.getJobToken(), position,
								serverSideFacade, buildHome, jobLogger);
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

			cacheHelper.buildFinished(successful);

			return successful;
		} finally {
			SecretMasker.pop();
			jobThreads.remove(jobData.getJobToken());
			buildHomes.remove(jobData.getJobToken());
			
			// Fix https://code.onedev.io/onedev/server/~issues/597
			if (SystemUtils.IS_OS_WINDOWS && workspaceDir.exists())
				FileUtils.deleteDir(workspaceDir);
			
			synchronized (buildHome) {
				FileUtils.deleteDir(buildHome);
			}
		}
	}

	private Commandline newDocker(@Nullable String dockerSock) {
		var docker = new Commandline(Agent.dockerPath);
		DockerExecutorUtils.useDockerSock(docker, dockerSock);
		return docker;
	}

	private boolean executeDockerJob(Session session, DockerJobData jobData) {
		File hostBuildHome = new File(Agent.getTempDir(),
				"onedev-build-" + jobData.getProjectId() + "-" + jobData.getBuildNumber() + "-" + jobData.getSubmitSequence());
		FileUtils.createDir(hostBuildHome);
		File attributesDir = new File(hostBuildHome, KubernetesHelper.ATTRIBUTES);
		for (Map.Entry<String, String> entry: Agent.attributes.entrySet())
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), entry.getValue(), UTF_8);
		var dockerSock = jobData.getDockerSock();

		Client client = ClientBuilder.newClient();
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		buildHomes.put(jobData.getJobToken(), hostBuildHome);
		if (dockerSock != null)
			dockerSocks.put(jobData.getJobToken(), dockerSock);
		SecretMasker.push(jobData.getSecretMasker());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};

			String network = jobData.getExecutorName() + "-" + jobData.getProjectId() + "-"
					+ jobData.getBuildNumber() + "-" + jobData.getSubmitSequence();
			jobLogger.log("Creating docker network '" + network + "'...");
			
			createNetwork(newDocker(dockerSock), network, jobData.getNetworkOptions(), jobLogger);
			try {
				var docker = newDocker(dockerSock);
				for (var jobService: jobData.getServices()) {
					var registryLogins = merge(jobService.getRegistryLogins(), jobData.getRegistryLogins());
					callWithDockerConfig(docker, registryLogins, () -> {
						startService(docker, network, jobService, Agent.osInfo, jobData.getCpuLimit(),
								jobData.getMemoryLimit(), jobLogger);
						return null;
					});
				}

				File hostWorkspace = new File(hostBuildHome, "workspace");
				File hostUserHome = new File(hostBuildHome, "user");
				FileUtils.createDir(hostWorkspace);
				FileUtils.createDir(hostUserHome);

				var cacheHelper = new AgentCacheHelper(jobData.getJobToken(), hostBuildHome, jobLogger);

				try {
					jobLogger.log("Downloading job dependencies...");

					downloadDependencies(Agent.serverUrl, jobData.getJobToken(),
							hostWorkspace, Agent.sslFactory);
					
					String containerBuildHome;
					String containerWorkspace;
					String containerTrustCerts;
					if (SystemUtils.IS_OS_WINDOWS) {
						containerBuildHome = "C:\\onedev-build";
						containerWorkspace = "C:\\onedev-build\\workspace";
						containerTrustCerts = "C:\\onedev-build\\trust-certs.pem";
					} else {
						containerBuildHome = "/onedev-build";
						containerWorkspace = "/onedev-build/workspace";
						containerTrustCerts = "/onedev-build/trust-certs.pem";
					}
					
					String messageData = jobData.getJobToken() + ":" + containerWorkspace;
					new Message(MessageTypes.REPORT_JOB_WORKSPACE, messageData).sendBy(session);

					CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
					var ownerChanged = new AtomicBoolean(false);
					var successful = entryFacade.execute(new LeafHandler() {

						private int runStepContainer(Commandline docker, String image, @Nullable String runAs,
													 @Nullable String entrypoint, List<String> arguments,
													 Map<String, String> environments, @Nullable String workingDir,
													 Map<String, String> volumeMounts, List<Integer> position,
													 boolean useTTY) {
							String containerName = network + "-step-" + stringifyStepPosition(position);
							containerNames.put(jobData.getJobToken(), containerName);
							try {
								var useProcessIsolation = isUseProcessIsolation(newDocker(dockerSock), image, Agent.osInfo, jobLogger);
								docker.clearArgs();

								docker.addArgs("run", "--name=" + containerName, "--network=" + network);
								if (jobData.isAlwaysPullImage())
									docker.addArgs("--pull=always");
								if (runAs != null)
									docker.addArgs("--user", runAs);
								else if (!SystemUtils.IS_OS_WINDOWS)
									docker.addArgs("--user", "0:0");

								if (jobData.getCpuLimit() != null)
									docker.addArgs("--cpus", jobData.getCpuLimit());
								if (jobData.getMemoryLimit() != null)
									docker.addArgs("--memory", jobData.getMemoryLimit());
								if (jobData.getDockerOptions() != null)
									docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));

								docker.addArgs("-v", getHostPath(hostBuildHome.getAbsolutePath(), dockerSock) + ":" + containerBuildHome);

								for (Map.Entry<String, String> entry: volumeMounts.entrySet()) {
									if (entry.getKey().contains(".."))
										throw new ExplicitException("Volume mount source path should not contain '..'");
									String hostPath = getHostPath(new File(hostWorkspace, entry.getKey()).getAbsolutePath(), dockerSock);
									docker.addArgs("-v", hostPath + ":" + entry.getValue());
								}

								cacheHelper.mountVolumes(docker, it -> getHostPath(it, dockerSock));

								if (entrypoint != null)
									docker.addArgs("-w", containerWorkspace);
								else if (workingDir != null)
									docker.addArgs("-w", workingDir);

								if (jobData.isMountDockerSock()) {
									if (dockerSock != null) {
										if (SystemUtils.IS_OS_WINDOWS)
											docker.addArgs("-v", dockerSock + "://./pipe/docker_engine");
										else
											docker.addArgs("-v", dockerSock + ":/var/run/docker.sock");
									} else {
										if (SystemUtils.IS_OS_WINDOWS)
											docker.addArgs("-v", "//./pipe/docker_engine://./pipe/docker_engine");
										else
											docker.addArgs("-v", "/var/run/docker.sock:/var/run/docker.sock");
									}
								}

								for (Map.Entry<String, String> entry: environments.entrySet())
									docker.addArgs("-e", entry.getKey() + "=" + entry.getValue());

								docker.addArgs("-e", "ONEDEV_WORKSPACE=" + containerWorkspace);

								if (useTTY)
									docker.addArgs("-t");

								if (entrypoint != null)
									docker.addArgs("--entrypoint=" + entrypoint);

								if (useProcessIsolation)
									docker.addArgs("--isolation=process");

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
							return ExecutorUtils.runStep(entryFacade, position, jobLogger, () -> {
								runningSteps.put(jobData.getJobToken(), facade);
								try {
									return doExecute(facade, position);
								} finally {
									runningSteps.remove(jobData.getJobToken());
								}
							});
						}

						private boolean doExecute(LeafFacade facade, List<Integer> position) {
							if (ownerChanged.get() && !isInDocker()) {
								changeOwner(hostBuildHome, getOwner(), newDocker(dockerSock), false);
								ownerChanged.set(false);
							}

							if (facade instanceof CommandFacade) {
								CommandFacade commandFacade = (CommandFacade) facade;
								if (commandFacade.getImage() == null) {
									throw new ExplicitException("This step can only be executed by server shell "
											+ "executor or remote shell executor");
								}
								Commandline entrypoint = getEntrypoint(hostBuildHome, commandFacade, position);
								var docker = newDocker(dockerSock);
								if (changeOwner(hostBuildHome, commandFacade.getRunAs(), docker, isInDocker()))
									ownerChanged.set(true);

								var registryLogins = merge(commandFacade.getRegistryLogins(), jobData.getRegistryLogins());
								docker.clearArgs();
								int exitCode = callWithDockerConfig(docker, registryLogins, () -> {
									return runStepContainer(docker, commandFacade.getImage(), commandFacade.getRunAs(),
											entrypoint.executable(), entrypoint.arguments(), commandFacade.getEnvMap(),
											null, new HashMap<>(), position, commandFacade.isUseTTY());
								});
								if (exitCode != 0) {
									jobLogger.error("Command exited with code " + exitCode);
									return false;
								}
							} else if (facade instanceof BuildImageFacade) {
								var buildImageFacade = (BuildImageFacade) facade;
								var registryLogins = merge(buildImageFacade.getRegistryLogins(), jobData.getRegistryLogins());
								var docker = newDocker(dockerSock);
								callWithDockerConfig(docker, registryLogins, () -> {
									buildImage(docker, jobData.getDockerBuilder(), buildImageFacade, hostBuildHome,
											jobData.isAlwaysPullImage(), jobLogger);
									return null;
								});
							} else if (facade instanceof RunImagetoolsFacade) {
								var runImagetoolsFacade = (RunImagetoolsFacade) facade;
								var registryLogins = merge(runImagetoolsFacade.getRegistryLogins(), jobData.getRegistryLogins());
								var docker = newDocker(dockerSock);
								callWithDockerConfig(docker, registryLogins, () -> {
									runImagetools(docker, runImagetoolsFacade, hostBuildHome, jobLogger);
									return null;
								});
							} else if (facade instanceof PruneBuilderCacheFacade) {
								var pruneBuilderCacheFacade = (PruneBuilderCacheFacade) facade;
								var docker = newDocker(dockerSock);
								callWithDockerConfig(docker, new ArrayList<>(), () -> {
									pruneBuilderCache(docker, jobData.getDockerBuilder(), pruneBuilderCacheFacade,
											hostBuildHome, jobLogger);
									return null;
								});
							} else if (facade instanceof RunContainerFacade) {
								RunContainerFacade runContainerFacade = (RunContainerFacade) facade;

								List<String> arguments = new ArrayList<>();
								if (runContainerFacade.getArgs() != null)
									arguments.addAll(Arrays.asList(StringUtils.parseQuoteTokens(runContainerFacade.getArgs())));

								var docker = newDocker(dockerSock);
								if (changeOwner(hostBuildHome, runContainerFacade.getRunAs(), docker, Bootstrap.isInDocker()))
									ownerChanged.set(true);

								var registryLogins = merge(runContainerFacade.getRegistryLogins(), jobData.getRegistryLogins());
								docker.clearArgs();
								int exitCode = callWithDockerConfig(docker, registryLogins, () -> {
									return runStepContainer(docker, runContainerFacade.getImage(), runContainerFacade.getRunAs(),null, arguments,
											runContainerFacade.getEnvMap(), runContainerFacade.getWorkingDir(), runContainerFacade.getVolumeMounts(),
											position, runContainerFacade.isUseTTY());
								});
								if (exitCode != 0) {
									jobLogger.error("Container exited with code " + exitCode);
									return false;
								}
							} else if (facade instanceof CheckoutFacade) {
								CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
								jobLogger.log("Checking out code...");

								Commandline git = new Commandline(Agent.gitPath);

								git.environments().put("HOME", hostUserHome.getAbsolutePath());

								checkoutFacade.setupWorkingDir(git, hostWorkspace);

								if (!isInDocker()) {
									checkoutFacade.setupSafeDirectory(git, containerWorkspace,
											newInfoLogger(jobLogger), newErrorLogger(jobLogger));
								}

								File trustCertsFile = new File(hostBuildHome, "trust-certs.pem");
								installGitCert(git, Agent.getTrustCertsDir(),
										trustCertsFile, containerTrustCerts,
										ExecutorUtils.newInfoLogger(jobLogger),
										ExecutorUtils.newWarningLogger(jobLogger));

								CloneInfo cloneInfo = checkoutFacade.getCloneInfo();
								cloneInfo.writeAuthData(hostUserHome, git, true,
										ExecutorUtils.newInfoLogger(jobLogger),
										ExecutorUtils.newWarningLogger(jobLogger));

								if (trustCertsFile.exists())
									git.addArgs("-c", "http.sslCAInfo=" + trustCertsFile.getAbsolutePath());

								int cloneDepth = checkoutFacade.getCloneDepth();

								String cloneUrl = checkoutFacade.getCloneInfo().getCloneUrl();
								String refName = jobData.getRefName();
								String commitHash = jobData.getCommitHash();
								cloneRepository(git, cloneUrl, cloneUrl, refName, commitHash,
										checkoutFacade.isWithLfs(), checkoutFacade.isWithSubmodules(), cloneDepth,
										ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
							} else if (facade instanceof SetupCacheFacade) {
								SetupCacheFacade setupCacheFacade = (SetupCacheFacade) facade;
								cacheHelper.setupCache(setupCacheFacade);
							} else if (facade instanceof ServerSideFacade) {
								ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
								return KubernetesHelper.runServerStep(Agent.sslFactory,
										Agent.serverUrl, jobData.getJobToken(), position,
										serverSideFacade, hostBuildHome, jobLogger);
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

					cacheHelper.buildFinished(successful);

					return successful;
				} finally {
					// Fix https://code.onedev.io/onedev/server/~issues/597
					if (SystemUtils.IS_OS_WINDOWS)
						FileUtils.deleteDir(hostWorkspace);
				}
			} finally {
				deleteNetwork(newDocker(dockerSock), network, jobLogger);
			}
		} finally {
			SecretMasker.pop();
			jobThreads.remove(jobData.getJobToken());
			buildHomes.remove(jobData.getJobToken());
			if (dockerSock != null)
				dockerSocks.remove(jobData.getJobToken());
			client.close();
			
			synchronized (hostBuildHome) {
				deleteDir(hostBuildHome, newDocker(dockerSock), Agent.isInDocker());
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
			
			testCommands(new Commandline(Agent.gitPath), jobData.getCommands(), jobLogger);
		} finally {
			jobThreads.remove(jobData.getJobToken());
			client.close();
		}		
	}
	
	private void testDockerExecutor(Session session, TestDockerJobData jobData) {
		var dockerSock = jobData.getDockerSock();
		Commandline docker = newDocker(dockerSock);
		callWithDockerConfig(docker, jobData.getRegistryLogins(), () -> {
			File workspaceDir = null;
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

				workspaceDir = FileUtils.createTempDir("workspace");
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
				docker.addArgs("run", "--rm");
				if (jobData.getDockerOptions() != null)
					docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));

				String containerWorkspacePath;
				if (SystemUtils.IS_OS_WINDOWS)
					containerWorkspacePath = "C:\\onedev-build\\workspace";
				else
					containerWorkspacePath = "/onedev-build/workspace";
				docker.addArgs("-v", getHostPath(workspaceDir.getAbsolutePath(), dockerSock) + ":" + containerWorkspacePath);

				docker.addArgs("-w", containerWorkspacePath);
				docker.addArgs(jobData.getDockerImage());

				if (SystemUtils.IS_OS_WINDOWS)
					docker.addArgs("cmd", "/c", "echo hello from container");
				else
					docker.addArgs("sh", "-c", "echo hello from container");

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

				if (!SystemUtils.IS_OS_WINDOWS) {
					jobLogger.log("Checking busybox availability...");
					docker.clearArgs();
					docker.addArgs("run", "--rm", "busybox", "sh", "-c", "echo hello from busybox");
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
				}

				KubernetesHelper.testGitLfsAvailability(new Commandline(Agent.gitPath), jobLogger);
			} finally {
				jobThreads.remove(jobData.getJobToken());
				client.close();
				if (authInfoDir != null)
					FileUtils.deleteDir(authInfoDir);
				if (workspaceDir != null)
					FileUtils.deleteDir(workspaceDir);
			}
			return null;
		});
	}
	
	private Serializable service(Serializable request) {
		try {
			if (request instanceof LogRequest) { 
				return (Serializable) LogRequest.readLog(new File(Agent.installDir, "logs/agent.log"));
			} else if (request instanceof DockerJobData) { 
				try {
					return executeDockerJob(session, (DockerJobData) request);
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof TestDockerJobData) {
				try {
					testDockerExecutor(session, (TestDockerJobData) request);
					return true;
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof ShellJobData) { 
				try {
					return executeShellJob(session, (ShellJobData) request);
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof TestShellJobData) {
				try {
					testShellExecutor(session, (TestShellJobData) request);
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
	
	static void sendOutput(String sessionId, Session agentSession, String output) {
		new Message(MessageTypes.SHELL_OUTPUT, sessionId + ":" + output).sendBy(agentSession);
	}

	static void sendError(String sessionId, Session agentSession, String error) {
		new Message(MessageTypes.SHELL_ERROR, sessionId + ":" + error).sendBy(agentSession);
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