package io.onedev.agent;

import static io.onedev.agent.AgentUtils.callWithRegistryLogins;
import static io.onedev.agent.AgentUtils.changeOwner;
import static io.onedev.agent.AgentUtils.encodeBase64Error;
import static io.onedev.agent.AgentUtils.getHostPath;
import static io.onedev.agent.AgentUtils.getOsIds;
import static io.onedev.agent.AgentUtils.newDocker;
import static io.onedev.agent.AgentUtils.newDockerKiller;
import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static io.onedev.agent.AgentUtils.testCommands;
import static io.onedev.agent.job.JobUtils.buildImage;
import static io.onedev.agent.job.JobUtils.createNetwork;
import static io.onedev.agent.job.JobUtils.deleteNetwork;
import static io.onedev.agent.job.JobUtils.getBuildDir;
import static io.onedev.agent.job.JobUtils.getEntrypointArgs;
import static io.onedev.agent.job.JobUtils.pruneBuilderCache;
import static io.onedev.agent.job.JobUtils.runImagetools;
import static io.onedev.agent.job.JobUtils.runStep;
import static io.onedev.agent.job.JobUtils.startService;
import static io.onedev.agent.workspace.WorkspaceUtils.awaitContainerReady;
import static io.onedev.agent.workspace.WorkspaceUtils.getPublishedPorts;
import static io.onedev.agent.workspace.WorkspaceUtils.setupRepository;
import static io.onedev.agent.workspace.WorkspaceUtils.setupShellProvisioned;
import static io.onedev.agent.workspace.WorkspaceUtils.testTmuxAvailability;
import static io.onedev.agent.workspace.WorkspaceUtils.upload;
import static io.onedev.k8shelper.JobHelper.BUILD_PATH;
import static io.onedev.k8shelper.JobHelper.downloadDependencies;
import static io.onedev.k8shelper.JobHelper.runServerStep;
import static io.onedev.k8shelper.JobHelper.stringifyStepPosition;
import static io.onedev.k8shelper.KubernetesHelper.buildRestClient;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.initRepository;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.setupGitCerts;
import static io.onedev.k8shelper.RegistryLoginFacade.merge;
import static io.onedev.k8shelper.WorkspaceHelper.CONTAINER_READY_FILE;
import static io.onedev.k8shelper.WorkspaceHelper.WORKSPACE_PATH;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.Client;
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

import com.google.common.base.Splitter;

import io.onedev.agent.job.DockerJobData;
import io.onedev.agent.job.JobResumeData;
import io.onedev.agent.job.LogRequest;
import io.onedev.agent.job.ShellJobData;
import io.onedev.agent.job.TestDockerJobData;
import io.onedev.agent.job.TestShellJobData;
import io.onedev.agent.shell.JobShellInputRequest;
import io.onedev.agent.shell.JobShellOpenData;
import io.onedev.agent.shell.JobShellOutputRequest;
import io.onedev.agent.shell.JobShellResizeRequest;
import io.onedev.agent.shell.JobShellSession;
import io.onedev.agent.shell.ShellOutputRequest;
import io.onedev.agent.shell.ShellSession;
import io.onedev.agent.shell.WorkspaceShellInputRequest;
import io.onedev.agent.shell.WorkspaceShellResizeRequest;
import io.onedev.agent.shell.WorkspaceShellSession;
import io.onedev.agent.workspace.DockerProvisionedShellOpenData;
import io.onedev.agent.workspace.FileData;
import io.onedev.agent.workspace.GitExecutionResult;
import io.onedev.agent.workspace.ProvisionDockerWorkspaceData;
import io.onedev.agent.workspace.ProvisionShellWorkspaceData;
import io.onedev.agent.workspace.ShellProvisionedShellOpenData;
import io.onedev.agent.workspace.StopWorkspaceRequest;
import io.onedev.agent.workspace.TestDockerWorkspaceData;
import io.onedev.agent.workspace.TestShellWorkspaceData;
import io.onedev.agent.workspace.WorkspaceAwaitRequest;
import io.onedev.agent.workspace.WorkspaceDeleteRequest;
import io.onedev.agent.workspace.WorkspaceFileDataRequest;
import io.onedev.agent.workspace.WorkspaceGitCommandRequest;
import io.onedev.agent.workspace.WorkspaceProvisioned;
import io.onedev.agent.workspace.WorkspaceShellOpenData;
import io.onedev.agent.workspace.WorkspaceUtils;
import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.k8shelper.BuildImageFacade;
import io.onedev.k8shelper.CacheAvailability;
import io.onedev.k8shelper.CacheConfigFacade;
import io.onedev.k8shelper.CacheProvisioner;
import io.onedev.k8shelper.CheckoutFacade;
import io.onedev.k8shelper.CloneInfo;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.ConfigFileProvisioner;
import io.onedev.k8shelper.JobHelper;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.LeafFacade;
import io.onedev.k8shelper.LeafHandler;
import io.onedev.k8shelper.PruneBuilderCacheFacade;
import io.onedev.k8shelper.RunContainerFacade;
import io.onedev.k8shelper.RunImagetoolsFacade;
import io.onedev.k8shelper.ServerSideFacade;
import io.onedev.k8shelper.SetupCacheFacade;
import io.onedev.k8shelper.UserDataFacade;
import io.onedev.k8shelper.UserDataProvisioner;
import io.onedev.k8shelper.WorkspaceHelper;

@WebSocket
public class AgentSocket implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(AgentSocket.class);
	
	private static final Map<String, Thread> jobThreads = new ConcurrentHashMap<>();

	private static final Map<String, JobShellSession> jobShellSessions = new ConcurrentHashMap<>();

	private static final Map<String, LeafFacade> runningSteps = new ConcurrentHashMap<>();
	
	private static final Map<String, String> jobContainerNames = new ConcurrentHashMap<>();
	
	private static final Map<String, AwaitableFutureTask<?>> workspaceProvisionTasks = new ConcurrentHashMap<>();

	private static final Map<String, AwaitableFutureTask<?>> workspaceServeTasks = new ConcurrentHashMap<>();
	
	private static final Map<String, WorkspaceShellSession> workspaceShellSessions = new ConcurrentHashMap<>();
			
	private Session session;
	
	private volatile Thread thread;
	
	private volatile boolean stopped;

	@OnWebSocketConnect
	public void onConnect(Session session) throws IOException {
		logger.info("Connected to server");
		this.session = session;
		thread = new Thread(this);
		thread.start();
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
	    			Client client = buildRestClient(Agent.sslFactory);
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
	    	case DELETE_WORKSPACE:
	    		WorkspaceDeleteRequest deleteRequest = SerializationUtils.deserialize(messageData);
	    		deleteWorkspace(deleteRequest);
	    		break;
	    	case RESUME_JOB: 
	    		JobResumeData jobResumeData = SerializationUtils.deserialize(messageData);
	    		resumeJob(jobResumeData);
	    		break;
	    	case JOB_SHELL_OPEN:
	    		JobShellOpenData jobShellOpenData = SerializationUtils.deserialize(messageData);
	    		String sessionId = jobShellOpenData.getSessionId();
	    		jobToken = jobShellOpenData.getJobToken();

				if (jobShellOpenData.isRunInContainer()) {
					String containerName = jobContainerNames.get(jobToken);
					if (containerName != null) {
						Commandline docker = newDocker(jobShellOpenData.getDockerSock());
						docker.addArgs("exec", "-it", containerName);
						LeafFacade runningStep = runningSteps.get(jobToken);
						if (runningStep instanceof CommandFacade) {
							CommandFacade commandStep = (CommandFacade) runningStep;
							docker.addArgs(commandStep.getExecutable());
						} else {
							docker.addArgs("sh");
						}
						jobShellSessions.put(sessionId, new JobShellSession(sessionId, session, docker));
					} else {
						sendOutput(session, new JobShellOutputRequest(sessionId, encodeBase64Error("Container not running")));
					}
				} else {
					File buildDir = getBuildDir(Agent.getTempDir(), jobShellOpenData.getProjectId(), 
							jobShellOpenData.getBuildNumber(), jobShellOpenData.getSubmitSequence());
					if (buildDir.exists()) {
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
						jobShellSessions.put(sessionId, new JobShellSession(sessionId, session, shell));
					} else {
						sendOutput(session, new JobShellOutputRequest(sessionId, encodeBase64Error("Job not running")));
					}	
				}
	    		break;
	    	case JOB_SHELL_TERMINATE:
	    		sessionId = new String(messageData, UTF_8);
	    		ShellSession shellSession = jobShellSessions.remove(sessionId);
	    		if (shellSession != null)
	    			shellSession.exit();
	    		break;
	    	case JOB_SHELL_INPUT:
	    		JobShellInputRequest jobShellInputRequest = SerializationUtils.deserialize(messageData);
	    		shellSession = jobShellSessions.get(jobShellInputRequest.getSessionId());
	    		if (shellSession != null)
	    			shellSession.writeToStdin(jobShellInputRequest.getData());
	    		break;
	    	case JOB_SHELL_RESIZE:
	    		JobShellResizeRequest jobShellResizeRequest = SerializationUtils.deserialize(messageData);
	    		shellSession = jobShellSessions.get(jobShellResizeRequest.getSessionId());
	    		if (shellSession != null)
	    			shellSession.resize(jobShellResizeRequest.getRows(), jobShellResizeRequest.getCols());
	    		break;
	    	case WORKSPACE_SHELL_OPEN:
	    		WorkspaceShellOpenData shellOpenData = SerializationUtils.deserialize(messageData);
				sessionId = shellOpenData.getSessionId();
				if (shellOpenData instanceof DockerProvisionedShellOpenData dockerProvisionedShellOpenData) {
					var containerName = getWorkspaceContainerName(
							dockerProvisionedShellOpenData.getProvisionerName(), 
							dockerProvisionedShellOpenData.getProjectId(), 
							dockerProvisionedShellOpenData.getWorkspaceNumber());
					Commandline docker = newDocker(dockerProvisionedShellOpenData.getDockerSock());
					var shellExecutable = shellOpenData.getShellExecutable();
					docker.addArgs("exec", "-it", "--detach-keys=ctrl-z,z", "-w",
							WORKSPACE_PATH + "/work", containerName, 
							"tmux", "new-session", shellExecutable);
					workspaceShellSessions.put(sessionId, new WorkspaceShellSession(sessionId, session, docker));
				} else {					
					var tmuxExecutable = ((ShellProvisionedShellOpenData) shellOpenData).getTmuxExecutable();
					if (tmuxExecutable == null)
						tmuxExecutable = "tmux";
					var tmux = new Commandline(tmuxExecutable);
					var workspaceDir = getWorkspaceDir(shellOpenData.getProjectId(), shellOpenData.getWorkspaceNumber());
					tmux.addArgs("new-session")
							.addArgs(shellOpenData.getShellExecutable())
							.workingDir(new File(workspaceDir, "work"));
					workspaceShellSessions.put(sessionId, new WorkspaceShellSession(sessionId, session, tmux));
				}
	    		break;
	    	case WORKSPACE_SHELL_TERMINATE:
	    		sessionId = new String(messageData, UTF_8);
	    		ShellSession workspaceShellSession = workspaceShellSessions.remove(sessionId);
	    		if (workspaceShellSession != null)
	    			workspaceShellSession.exit();
	    		break;
	    	case WORKSPACE_SHELL_INPUT:
	    		WorkspaceShellInputRequest workspaceShellInputRequest = SerializationUtils.deserialize(messageData);
	    		workspaceShellSession = workspaceShellSessions.get(workspaceShellInputRequest.getSessionId());
	    		if (workspaceShellSession != null)
	    			workspaceShellSession.writeToStdin(workspaceShellInputRequest.getData());
	    		break;
	    	case WORKSPACE_SHELL_RESIZE:
	    		WorkspaceShellResizeRequest workspaceShellResizeRequest = SerializationUtils.deserialize(messageData);
	    		workspaceShellSession = workspaceShellSessions.get(workspaceShellResizeRequest.getSessionId());
	    		if (workspaceShellSession != null)
	    			workspaceShellSession.resize(workspaceShellResizeRequest.getRows(), workspaceShellResizeRequest.getCols());
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

	public static void onStopping() {
		logger.info("Exiting workspace shells...");
		for (var shellSession: workspaceShellSessions.values())
			shellSession.exit();
		
		logger.info("Exiting job shells...");
		for (var shellSession: jobShellSessions.values())
			shellSession.exit();
		
		logger.info("Cancelling running jobs...");
		for (var jobThread: jobThreads.values())
			jobThread.interrupt();

		logger.info("Stopping workspaces...");
		for (var task: workspaceProvisionTasks.values()) {
			task.cancel(true);
			task.await();
		}		
		for (var task: workspaceServeTasks.values()) {
			task.cancel(true);
			task.await();
		}
	}
	
	private void cancelJob(String jobToken) {
		Thread thread = jobThreads.get(jobToken);
		if (thread != null)
			thread.interrupt();
	}
		
	private void resumeJob(JobResumeData jobResumeData) {
		File buildDir = getBuildDir(Agent.getTempDir(), jobResumeData.getProjectId(), 
				jobResumeData.getBuildNumber(), jobResumeData.getSubmitSequence());
		synchronized (buildDir) {
			if (buildDir.exists())
				FileUtils.touchFile(new File(buildDir, "continue"));
		}
	}
		
	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		WebsocketUtils.onClose(session);

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
		File buildDir = getBuildDir(Agent.getTempDir(), jobData.getProjectId(), 
				jobData.getBuildNumber(), jobData.getSubmitSequence());
		File workDir = new File(buildDir, "work");
		
		File attributesDir = new File(buildDir, KubernetesHelper.ATTRIBUTES);
		FileUtils.createDir(attributesDir);

		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), UTF_8);
		}
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		SecretMasker.push(jobData.getSecretMasker());
		try {
			TaskLogger jobLogger = newTaskLogger(session, jobData.getJobToken());

			FileUtils.createDir(workDir);

			var cacheProvisioners = new ArrayList<CacheProvisioner>();

			jobLogger.log("Downloading job dependencies...");
			
			JobHelper.downloadDependencies(Agent.serverUrl,  jobData.getJobToken(),
					workDir, Agent.sslFactory);
						
			String messageData = jobData.getJobToken() + ":" + workDir.getAbsolutePath();
			new Message(MessageTypes.REPORT_JOB_WORKDIR, messageData).sendBy(session);

			CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
			var cacheConfigIndex = new AtomicInteger(1);
			var successful = entryFacade.execute(new LeafHandler() {

				@Override
				public boolean execute(LeafFacade facade, List<Integer> position) {
					return runStep(entryFacade, position, jobLogger, () -> {
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

						git.clearArgs();
						var cloneInfo = checkoutFacade.getCloneInfo();
						var trustCertsFile = new File(buildDir, "trust-certs.pem");
						setupGitCerts(git, Agent.getTrustCertsDir(), trustCertsFile,
								trustCertsFile.getAbsolutePath(), infoLogger, warningLogger);
						cloneInfo.setupGitAuth(git, buildDir, buildDir.getAbsolutePath(), 
								infoLogger, warningLogger);
						
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
						var cacheConfig = setupCacheFacade.getCacheConfig();
						var cacheProvisioner = newCacheProvisioner(jobData.getJobToken(), cacheConfig, cacheConfigIndex.getAndIncrement());
						cacheProvisioner.download(buildDir, jobLogger);
						cacheProvisioners.add(cacheProvisioner);
					} else if (facade instanceof ServerSideFacade) {
						ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
						return runServerStep(Agent.sslFactory,
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

			if (successful) {
				for (var cacheProvisioner : cacheProvisioners) 
					cacheProvisioner.upload(buildDir, jobLogger);
			}

			return successful;
		} finally {
			SecretMasker.pop();
			jobThreads.remove(jobData.getJobToken());
						
			synchronized (buildDir) {
				FileUtils.deleteDir(buildDir);
			}
		}
	}

	private boolean executeDockerJob(Session session, DockerJobData jobData) {
		File hostBuildDir = getBuildDir(Agent.getTempDir(), jobData.getProjectId(), 
				jobData.getBuildNumber(), jobData.getSubmitSequence());
		File hostAttributesDir = new File(hostBuildDir, KubernetesHelper.ATTRIBUTES);
		FileUtils.createDir(hostAttributesDir);

		for (Map.Entry<String, String> entry: Agent.attributes.entrySet())
			FileUtils.writeFile(new File(hostAttributesDir, entry.getKey()), entry.getValue(), UTF_8);
		var dockerSettings = jobData.getDockerSettings();
		var dockerSock = dockerSettings.getDockerSock();

		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		SecretMasker.push(jobData.getSecretMasker());
		TaskLogger jobLogger = newTaskLogger(session, jobData.getJobToken());
		try {
			String network = jobData.getExecutorName() + "-" + jobData.getProjectId() + "-"
					+ jobData.getBuildNumber() + "-" + jobData.getSubmitSequence();
			jobLogger.log("Creating docker network '" + network + "'...");
			
			createNetwork(newDocker(dockerSock), network, dockerSettings.getNetworkOptions(), jobLogger);
			try {
				var docker = newDocker(dockerSock);
				for (var jobService: jobData.getServices()) {
					var registryLogins = merge(jobService.getRegistryLogins(), dockerSettings.getRegistryLogins());
					callWithRegistryLogins(docker, registryLogins, () -> {
						startService(docker, network, jobService, dockerSettings.getCpuLimit(),
								dockerSettings.getMemoryLimit(), jobLogger);
						return null;
					});
				}

				File hostWorkDir = new File(hostBuildDir, "work");
				FileUtils.createDir(hostWorkDir);

				var cacheProvisioners = new ArrayList<CacheProvisioner>();

				jobLogger.log("Downloading job dependencies...");

				downloadDependencies(Agent.serverUrl, jobData.getJobToken(),
						hostWorkDir, Agent.sslFactory);
				
				var containerBuildDirPath = BUILD_PATH;
				var containerWorkDirPath = BUILD_PATH + "/work";
				var containerTrustCertsFilePath = BUILD_PATH + "/trust-certs.pem";
				
				String messageData = jobData.getJobToken() + ":" + containerWorkDirPath;
				new Message(MessageTypes.REPORT_JOB_WORKDIR, messageData).sendBy(session);

				CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
				var osIds = getOsIds(jobLogger);
				var cacheConfigIndex = new AtomicInteger(1);
				var successful = entryFacade.execute(new LeafHandler() {

					private int runStepContainer(Commandline docker, String image, String runAs,
													@Nullable String entrypoint, List<String> arguments,
													Map<String, String> environments, @Nullable String workingDir,
													Map<String, String> volumeMounts, List<Integer> position,
													boolean useTTY) {
						String containerName = network + "-step-" + stringifyStepPosition(position);
						jobContainerNames.put(jobData.getJobToken(), containerName);
						try {
							docker.args("run", "--name=" + containerName, "--network=" + network);
							if (dockerSettings.isAlwaysPullImage())
								docker.addArgs("--pull=always");
							docker.addArgs("--user", runAs);

							if (dockerSettings.getCpuLimit() != null)
								docker.addArgs("--cpus", dockerSettings.getCpuLimit());
							if (dockerSettings.getMemoryLimit() != null)
								docker.addArgs("--memory", dockerSettings.getMemoryLimit());
							if (dockerSettings.getRunOptions() != null)
								docker.addArgs(StringUtils.parseQuoteTokens(dockerSettings.getRunOptions()));

							docker.addArgs("-v", getHostPath(hostBuildDir.getAbsolutePath(), dockerSock) + ":" + containerBuildDirPath);

							for (Map.Entry<String, String> entry: volumeMounts.entrySet()) {
								if (entry.getKey().contains(".."))
									throw new ExplicitException("Volume mount source path should not contain '..'");
								String hostPath = getHostPath(new File(hostWorkDir, entry.getKey()).getAbsolutePath(), dockerSock);
								docker.addArgs("-v", hostPath + ":" + entry.getValue());
							}

							for (var cacheProvisioner : cacheProvisioners) 
								cacheProvisioner.mountVolumes(docker, hostBuildDir, path -> getHostPath(path, dockerSock));

							if (entrypoint != null)
								docker.addArgs("-w", containerWorkDirPath);
							else if (workingDir != null)
								docker.addArgs("-w", workingDir);

							if (dockerSettings.isMountDockerSock()) {
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
							jobContainerNames.remove(jobData.getJobToken());
						}
					}

					@Override
					public boolean execute(LeafFacade facade, List<Integer> position) {
						return runStep(entryFacade, position, jobLogger, () -> {
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

							var registryLogins = merge(commandFacade.getRegistryLogins(), dockerSettings.getRegistryLogins());
							
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
							var registryLogins = merge(buildImageFacade.getRegistryLogins(), dockerSettings.getRegistryLogins());
							var docker = newDocker(dockerSock);
							callWithRegistryLogins(docker, registryLogins, () -> {
								buildImage(docker, dockerSettings.getDockerBuilder(), buildImageFacade, hostBuildDir,
										dockerSettings.isAlwaysPullImage(), jobLogger);
								return null;
							});
						} else if (facade instanceof RunImagetoolsFacade) {
							var runImagetoolsFacade = (RunImagetoolsFacade) facade;
							var registryLogins = merge(runImagetoolsFacade.getRegistryLogins(), dockerSettings.getRegistryLogins());
							var docker = newDocker(dockerSock);
							callWithRegistryLogins(docker, registryLogins, () -> {
								runImagetools(docker, runImagetoolsFacade, hostBuildDir, jobLogger);
								return null;
							});
						} else if (facade instanceof PruneBuilderCacheFacade) {
							var pruneBuilderCacheFacade = (PruneBuilderCacheFacade) facade;
							var docker = newDocker(dockerSock);
							callWithRegistryLogins(docker, new ArrayList<>(), () -> {
								pruneBuilderCache(docker, dockerSettings.getDockerBuilder(), pruneBuilderCacheFacade,
										hostBuildDir, jobLogger);
								return null;
							});
						} else if (facade instanceof RunContainerFacade) {
							RunContainerFacade runContainerFacade = ((RunContainerFacade) facade).replacePlaceholders(hostBuildDir);

							List<String> arguments = new ArrayList<>();
							if (runContainerFacade.getArgs() != null)
								arguments.addAll(Arrays.asList(StringUtils.parseQuoteTokens(runContainerFacade.getArgs())));

							var docker = newDocker(dockerSock);
							var registryLogins = merge(runContainerFacade.getRegistryLogins(), dockerSettings.getRegistryLogins());
							var runAs = runContainerFacade.getRunAs();
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

							git.clearArgs();
							setupGitCerts(git, Agent.getTrustCertsDir(),
									new File(hostBuildDir, "trust-certs.pem"), 
									containerTrustCertsFilePath, infoLogger, warningLogger);

							CloneInfo cloneInfo = checkoutFacade.getCloneInfo();
							cloneInfo.setupGitAuth(git, hostBuildDir, containerBuildDirPath, infoLogger, warningLogger);

							int cloneDepth = checkoutFacade.getCloneDepth();

							String cloneUrl = checkoutFacade.getCloneInfo().getCloneUrl();
							String refName = jobData.getRefName();
							String commitHash = jobData.getCommitHash();

							cloneRepository(git, cloneUrl, cloneUrl, refName, commitHash,
									checkoutFacade.isWithLfs(), checkoutFacade.isWithSubmodules(), cloneDepth,
									infoLogger, warningLogger);
						} else if (facade instanceof SetupCacheFacade) {
							SetupCacheFacade setupCacheFacade = (SetupCacheFacade) facade;
							var cacheConfig = setupCacheFacade.getCacheConfig();
							var cacheProvisioner = newCacheProvisioner(jobData.getJobToken(), cacheConfig, cacheConfigIndex.getAndIncrement());
							cacheProvisioner.download(hostBuildDir, jobLogger);
							cacheProvisioners.add(cacheProvisioner);
						} else if (facade instanceof ServerSideFacade) {
							ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
							return runServerStep(Agent.sslFactory,
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

				if (successful) {
					for (var cacheProvisioner : cacheProvisioners) 
						cacheProvisioner.upload(hostBuildDir, jobLogger);
				}

				return successful;
			} finally {
				deleteNetwork(newDocker(dockerSock), network, jobLogger);
			}
		} finally {
			SecretMasker.pop();
			jobThreads.remove(jobData.getJobToken());
			
			synchronized (hostBuildDir) {
				FileUtils.deleteDir(hostBuildDir);
			}
		}
	}

	private void testK8sResource(TaskLogger logger, Client client, String token) {
		logger.log(String.format("Connecting to server '%s'...", Agent.serverUrl));
		WebTarget target = client.target(Agent.serverUrl)
				.path("~api/k8s/test")
				.queryParam("token", token);
		Invocation.Builder builder = target.request();
		try (Response response = builder.get()) {
			checkStatus(response);
		}
	}

	private void testShellExecutor(Session session, TestShellJobData jobData) {
		Client client = buildRestClient(Agent.sslFactory);
		jobThreads.put(jobData.getToken(), Thread.currentThread());
		try {
			TaskLogger jobLogger = newTaskLogger(session, jobData.getToken());
			testK8sResource(jobLogger, client, jobData.getToken());
			testCommands(jobLogger);
		} finally {
			jobThreads.remove(jobData.getToken());
			client.close();
		}		
	}

	private CacheProvisioner newCacheProvisioner(String jobToken, CacheConfigFacade config, int configIndex) {
		return new CacheProvisioner(config, configIndex) {

			private static final String API_PATH = "~api/k8s/job-cache";

			@Override
			protected CacheAvailability download(String key, @Nullable String checksum,
					String path, File pathDir) {
				return KubernetesHelper.downloadCache(Agent.serverUrl, API_PATH, jobToken,
						key, checksum, path, pathDir, Agent.sslFactory);
			}

			@Override
			protected boolean upload(CacheConfigFacade config, String path, File pathDir) {
				return KubernetesHelper.uploadCache(Agent.serverUrl, API_PATH, jobToken, 
						config, path, pathDir, Agent.sslFactory);
			}

		};
	}
	
	private void testDockerExecutor(Session session, TestDockerJobData jobData) {
		var dockerSock = jobData.getDockerSock();
		Commandline docker = newDocker(dockerSock);
		Client client = buildRestClient(Agent.sslFactory);
		jobThreads.put(jobData.getToken(), Thread.currentThread());
		try {
			TaskLogger jobLogger = newTaskLogger(session, jobData.getToken());
			testK8sResource(jobLogger, client, jobData.getToken());
			AgentUtils.testDocker(docker, jobData, path -> getHostPath(path, dockerSock), jobLogger);
		} finally {
			jobThreads.remove(jobData.getToken());
			client.close();
		}
	}

	private void testDockerWorkspace(Session session, TestDockerWorkspaceData workspaceData) {
		var dockerSock = workspaceData.getDockerSock();
		Commandline docker = newDocker(dockerSock);
		Client client = buildRestClient(Agent.sslFactory);
		try {
			TaskLogger workspaceLogger = newTaskLogger(session, workspaceData.getToken());
			testK8sResource(workspaceLogger, client, workspaceData.getToken());
			AgentUtils.testDocker(docker, workspaceData, path -> getHostPath(path, dockerSock), workspaceLogger);
		} finally {
			client.close();
		}
	}

	private void testShellWorkspace(Session session, TestShellWorkspaceData workspaceData) {
		Client client = buildRestClient(Agent.sslFactory);
		try {
			TaskLogger workspaceLogger = newTaskLogger(session, workspaceData.getToken());
			testK8sResource(workspaceLogger, client, workspaceData.getToken());
			testCommands(workspaceLogger);

			var tmuxExecutable = workspaceData.getTmuxExecutable();
			if (tmuxExecutable == null)
				tmuxExecutable = "tmux";
			testTmuxAvailability(new Commandline(tmuxExecutable), workspaceLogger);
		} finally {
			client.close();
		}
	}

	private String getWorkspaceContainerName(String provisionerName, Long projectId, Long workspaceNumber) {
		return "workspace-" + provisionerName + "-" + projectId + "-" + workspaceNumber;
	}
	
	private WorkspaceProvisioned provisionDockerWorkspace(Session session, ProvisionDockerWorkspaceData data) {
		var provisionTask = new AwaitableFutureTask<WorkspaceProvisioned>(() -> {
			var token = data.getWorkspaceToken();
			var dockerSettings = data.getDockerSettings();
	
			var workspaceLogger = newTaskLogger(session, token);
			var workspaceDir = getWorkspaceDir(data.getProjectId(), data.getWorkspaceNumber());
			FileUtils.createDir(workspaceDir);

			var dockerSock = dockerSettings.getDockerSock();

			var osIds = getOsIds(workspaceLogger);

			if (SystemUtils.IS_OS_LINUX && !osIds.equals("0:0")) {
				workspaceLogger.log("Changing owner of workspace directory to host user...");
				var docker = newDocker(dockerSock);
				changeOwner(docker, osIds, workspaceDir, osIds, workspaceLogger);
			}

			var allRegistryLogins = merge(new ArrayList<>(), dockerSettings.getRegistryLogins());

			setupRepository(workspaceDir, data, workspaceLogger);

			var envVars = WorkspaceHelper.buildEnvVars(dockerSettings.getEnvVars(),
					Agent.serverUrl, data.getWorkspaceToken(), WORKSPACE_PATH + "/work");

			var cacheProvisioners = new ArrayList<CacheProvisioner>();
			var cacheConfigIndex = 1;
			for (var cacheConfig : data.getCacheConfigs()) {
				var cacheProvisioner = KubernetesHelper.newCacheProvisioner(Agent.serverUrl,
						"~api/k8s/workspace-cache", data.getWorkspaceToken(),
						cacheConfig, Agent.getTrustCertsDir(), cacheConfigIndex++);
				cacheProvisioner.download(workspaceDir, workspaceLogger);
				cacheProvisioners.add(cacheProvisioner);
			}

			var userDataProvisioner = newUserDataProvisioner(data.getWorkspaceToken(), data.getUserDatas());
			userDataProvisioner.download(workspaceDir, workspaceLogger);

			var configFileProvisioner = new ConfigFileProvisioner(data.getConfigFiles());
			configFileProvisioner.provision(workspaceDir, workspaceLogger);

			var setupScriptConfig = data.getSetupScriptConfig();
			var entrypointArgs = WorkspaceHelper.buildEntrypointArgs(setupScriptConfig, true);
			if (setupScriptConfig != null)
				WorkspaceHelper.writeSetupScript(workspaceDir, setupScriptConfig);

			var containerReadyFile = new File(workspaceDir, CONTAINER_READY_FILE);
			if (containerReadyFile.exists())
				FileUtils.deleteFile(containerReadyFile);

			var runAs = dockerSettings.getRunAs();
			var docker = newDocker(dockerSock);
			if (SystemUtils.IS_OS_LINUX && !runAs.equals("0:0")) {
				workspaceLogger.log("Changing owner of workspace directory to container user...");
				changeOwner(docker, runAs, workspaceDir, osIds, workspaceLogger);
			}

			try {
				var containerName = getWorkspaceContainerName(data.getProvisionerName(), data.getProjectId(), data.getWorkspaceNumber());

				var userDataInitEntrypointArgs = data.getUserDataInitEntrypointArgs();
				if (userDataInitEntrypointArgs != null) {
					workspaceLogger.log("Initializing new user data...");
					callWithRegistryLogins(docker, allRegistryLogins, () -> {
						var initContainerName = containerName + "-user-data-init";
						WorkspaceUtils.deleteContainerIfExist(newDocker(dockerSock), initContainerName, workspaceLogger);

						WorkspaceUtils.setCommonDockerRunOptions(docker, initContainerName, runAs, dockerSettings.isAlwaysPullImage(),
								dockerSettings.getCpuLimit(), dockerSettings.getMemoryLimit());
						docker.processKiller(newDockerKiller(newDocker(dockerSock), initContainerName, workspaceLogger));
						docker.addArgs("-v", getHostPath(workspaceDir.getAbsolutePath(), dockerSock) + ":" + WORKSPACE_PATH);
						docker.addArgs("--entrypoint", "sh", dockerSettings.getImage(), "-c", userDataInitEntrypointArgs);
						docker.execute(newInfoLogger(workspaceLogger), newWarningLogger(workspaceLogger)).checkReturnCode();
						return null;
					});
				}

				var serveTask = new AwaitableFutureTask<Void>(() -> {
					try {
						workspaceLogger.log("Starting docker container...");
						callWithRegistryLogins(docker, allRegistryLogins, () -> {
							WorkspaceUtils.deleteContainerIfExist(newDocker(dockerSock), containerName, workspaceLogger);

							WorkspaceUtils.setCommonDockerRunOptions(docker, containerName, runAs, dockerSettings.isAlwaysPullImage(),
									dockerSettings.getCpuLimit(), dockerSettings.getMemoryLimit());
							docker.processKiller(newDockerKiller(newDocker(dockerSock), containerName, workspaceLogger));

							if (!dockerSettings.getContainerPorts().isEmpty()) {
								for (var containerPort : dockerSettings.getContainerPorts())
									docker.addArgs("--expose", String.valueOf(containerPort));
								docker.addArgs("-P");
							}
							docker.addArgs("-v", getHostPath(workspaceDir.getAbsolutePath(), dockerSock) + ":" + WORKSPACE_PATH);

							for (var cacheProvisioner : cacheProvisioners)
								cacheProvisioner.mountVolumes(docker, workspaceDir, path -> getHostPath(path, dockerSock));

							userDataProvisioner.mountVolumes(docker, workspaceDir, path -> getHostPath(path, dockerSock));
							
							configFileProvisioner.mountVolumes(docker, workspaceDir, path -> getHostPath(path, dockerSock));

							if (dockerSettings.isMountDockerSock()) {
								if (dockerSock != null)
									docker.addArgs("-v", dockerSock + ":/var/run/docker.sock");
								else
									docker.addArgs("-v", "/var/run/docker.sock:/var/run/docker.sock");
							}
							if (dockerSettings.getRunOptions() != null)
								docker.addArgs(StringUtils.parseQuoteTokens(dockerSettings.getRunOptions()));

							for (var entry : envVars.entrySet())
								docker.addArgs("-e", entry.getKey() + "=" + entry.getValue());

							docker.addArgs("--entrypoint", "sh", dockerSettings.getImage(), "-c", entrypointArgs);

							var result = docker.execute(newInfoLogger(workspaceLogger), newWarningLogger(workspaceLogger));
							if (result.getReturnCode() != 0)
								throw new ExplicitException(
										"Docker container exited with code " + result.getReturnCode());
							return null;
						});
						return null;
					} finally {
						workspaceServeTasks.remove(data.getWorkspaceToken());
						if (SystemUtils.IS_OS_LINUX && !osIds.equals("0:0") && workspaceDir.exists()) 
							changeOwner(newDocker(dockerSock), osIds, workspaceDir, osIds, workspaceLogger);	
						upload(workspaceDir, userDataProvisioner, cacheProvisioners, workspaceLogger);
					}
				});
				workspaceServeTasks.put(data.getWorkspaceToken(), serveTask);
				Bootstrap.executorService.execute(serveTask);

				try {
					awaitContainerReady(serveTask, containerReadyFile);

					Map<Integer, Integer> portMappings;
					if (!dockerSettings.getContainerPorts().isEmpty())
						portMappings = getPublishedPorts(newDocker(dockerSock), containerName, dockerSettings.getContainerPorts());
					else
						portMappings = new HashMap<>();
					return new WorkspaceProvisioned(data.getWorkspaceToken(), Agent.ipAddress, portMappings);
				} catch (Throwable t) {
					serveTask.cancel(true);
					throw t;
				}
			} catch (Throwable t) {
				if (SystemUtils.IS_OS_LINUX && !osIds.equals("0:0")) 
					changeOwner(newDocker(dockerSock), osIds, workspaceDir, osIds, workspaceLogger);
				throw t;
			}
		});

		workspaceProvisionTasks.put(data.getWorkspaceToken(), provisionTask);
		try {
			Bootstrap.executorService.execute(provisionTask);
			provisionTask.await();
			return provisionTask.get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		} finally {
			workspaceProvisionTasks.remove(data.getWorkspaceToken());
		}
	}

	private void provisionShellWorkspace(Session session, ProvisionShellWorkspaceData data) {
		var token = data.getWorkspaceToken();

		var provisionTask = new AwaitableFutureTask<Void>(() -> {
			var workspaceLogger = newTaskLogger(session, token);
			var workspaceDir = getWorkspaceDir(data.getProjectId(), data.getWorkspaceNumber());
			FileUtils.createDir(workspaceDir);

			setupRepository(workspaceDir, data, workspaceLogger);

			var envVars = WorkspaceHelper.buildEnvVars(data.getEnvVars(),
					Agent.serverUrl, data.getWorkspaceToken(), 
					new File(workspaceDir, "work").getAbsolutePath());

			var cacheProvisioners = new ArrayList<CacheProvisioner>();
			var cacheConfigIndex = 1;
			for (var cacheConfig : data.getCacheConfigs()) {
				for (var path : cacheConfig.getPaths()) {
					if (FilenameUtils.getPrefixLength(path) > 0)
						throw new ExplicitException("Shell provisioner does not allow absolute cache path: " + path);
				}	
				var cacheProvisioner = KubernetesHelper.newCacheProvisioner(Agent.serverUrl,
						"~api/k8s/workspace-cache", data.getWorkspaceToken(),
						cacheConfig, Agent.getTrustCertsDir(), cacheConfigIndex++);
				cacheProvisioner.download(workspaceDir, workspaceLogger);
				cacheProvisioners.add(cacheProvisioner);
			}

			if (data.getSetupScriptConfig() != null)
				setupShellProvisioned(data.getSetupScriptConfig(), workspaceDir, envVars, workspaceLogger);

			var serveTask = new AwaitableFutureTask<Void>(() -> {
				try {
					new CountDownLatch(1).await();
					return null;
				} finally {
					workspaceServeTasks.remove(token);
					for (var cacheProvisioner : cacheProvisioners)
						cacheProvisioner.upload(workspaceDir, workspaceLogger);
				}
			});
			workspaceServeTasks.put(token, serveTask);
			Bootstrap.executorService.execute(serveTask);
			return null;
		});
		workspaceProvisionTasks.put(token, provisionTask);
		try {
			Bootstrap.executorService.execute(provisionTask);
			provisionTask.await();
		} finally {
			workspaceProvisionTasks.remove(token);
		}
	}

	private void awaitWorkspace(String token) {
		var task = workspaceServeTasks.get(token);
		if (task != null) {
			task.await();
			try {
				task.get();
			} catch (InterruptedException | ExecutionException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private TaskLogger newTaskLogger(Session session, String token) {
		return new TaskLogger() {
			@Override
			public void log(String message, String sessionId) {
				try {
					Agent.log(session, token, message, sessionId);
				} catch (Exception ignored) {
				}
			}
		};
	}

	private GitExecutionResult executeWorkspaceGit(WorkspaceGitCommandRequest request) {
		if (request.isDocker()) {
			var containerName = getWorkspaceContainerName(request.getProvisionerName(), request.getProjectId(), request.getWorkspaceNumber());
			if (containerName == null)
				throw new ExplicitException("Workspace container not running");
			return WorkspaceUtils.executeGit(request.getDockerSock(), containerName, request.getGitArgs());
		} else {
			return WorkspaceUtils.executeGit(
					new File(getWorkspaceDir(request.getProjectId(), request.getWorkspaceNumber()), "work"),
					request.getGitArgs());
		}
	}

	private FileData readWorkspaceFileData(WorkspaceFileDataRequest request) {
		var workspaceDir = getWorkspaceDir(request.getProjectId(), request.getWorkspaceNumber());
		return WorkspaceUtils.readFileData(workspaceDir, request.getPath());
	}

	private void stopWorkspace(String token) {
		var task = workspaceProvisionTasks.get(token);
		if (task != null) {
			task.cancel(true);
			task.await();
		}
		task = workspaceServeTasks.get(token);
		if (task != null) {
			task.cancel(true);
			task.await();
		}
	}

	private void deleteWorkspace(WorkspaceDeleteRequest request) {
		var workspaceDir = getWorkspaceDir(request.getProjectId(), request.getWorkspaceNumber());
		if (workspaceDir.exists())
			FileUtils.deleteDir(workspaceDir);
	}

	private static File getWorkspaceDir(Long projectId, Long workspaceNumber) {
		return new File(new File(Agent.getWorkDir(), "workspaces"),
				projectId + "/" + workspaceNumber);
	}

	private UserDataProvisioner newUserDataProvisioner(String workspaceToken,
			List<UserDataFacade> userDatas) {
		return new UserDataProvisioner(userDatas) {

			@Override
			protected void download(String key, String path, File pathFile) {
				WorkspaceHelper.downloadUserData(Agent.serverUrl, "~api/k8s/workspace-user-data",
						workspaceToken, key, path, pathFile, Agent.sslFactory);
			}

			@Override
			protected void upload(String key, String path, File pathFile) {
				WorkspaceHelper.uploadUserData(Agent.serverUrl, "~api/k8s/workspace-user-data",
						workspaceToken, key, path, pathFile, Agent.sslFactory);
			}

			@Override
			protected void notifyUploaded(String key) {
				WorkspaceHelper.notifyUserDataUploaded(Agent.serverUrl, "~api/k8s/workspace-user-data",
						workspaceToken, key, Agent.sslFactory);
			}

		};
	}

	private Serializable service(Serializable request) {
		try {
			if (request instanceof LogRequest) { 
				return (Serializable) LogRequest.readLog(new File(Agent.installDir, "logs/agent.log"));
			} else if (request instanceof DockerJobData dockerJobData) { 
				return executeDockerJob(session, dockerJobData);
			} else if (request instanceof TestDockerJobData testDockerJobData) {
				testDockerExecutor(session, testDockerJobData);
				return true;
			} else if (request instanceof TestDockerWorkspaceData testDockerWorkspaceData) {
				testDockerWorkspace(session, testDockerWorkspaceData);
				return true;
			} else if (request instanceof TestShellWorkspaceData testShellWorkspaceData) {
				testShellWorkspace(session, testShellWorkspaceData);
				return true;
			} else if (request instanceof ShellJobData shellJobData) { 
				return executeShellJob(session, shellJobData);
			} else if (request instanceof TestShellJobData testShellJobData) {
				testShellExecutor(session, testShellJobData);
				return true;
			} else if (request instanceof ProvisionDockerWorkspaceData provisionWorkspaceData) {
				return provisionDockerWorkspace(session, provisionWorkspaceData);
			} else if (request instanceof ProvisionShellWorkspaceData provisionWorkspaceData) {
				provisionShellWorkspace(session, provisionWorkspaceData);
				return null;
			} else if (request instanceof WorkspaceAwaitRequest awaitRequest) {
				awaitWorkspace(awaitRequest.getToken());
				return null;
			} else if (request instanceof StopWorkspaceRequest stopRequest) {
				stopWorkspace(stopRequest.getToken());
				return null;
			} else if (request instanceof WorkspaceGitCommandRequest gitRequest) {
				return executeWorkspaceGit(gitRequest);
			} else if (request instanceof WorkspaceFileDataRequest fileDataRequest) {
				return readWorkspaceFileData(fileDataRequest);
			} else { 
				throw new ExplicitException("Unknown request: " + request.getClass());
			}
		} catch (Throwable e) {
			if (ExceptionUtils.find(e, CancellationException.class) == null)
				logger.error("Error handling websocket request", e);
			return e;
		}
	}

	public static void sendOutput(Session agentSession, ShellOutputRequest shellOutputRequest) {
		MessageTypes messageType;
		if (shellOutputRequest instanceof JobShellOutputRequest) 
			messageType = MessageTypes.JOB_SHELL_OUTPUT;
		else 
			messageType = MessageTypes.WORKSPACE_SHELL_OUTPUT;
		new Message(messageType, shellOutputRequest).sendBy(agentSession);
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
			} catch (Throwable e) {
				logger.error("Error pinging server", e);
				try {
					session.disconnect();
				} catch (Throwable e2) {
				}
			}
		}
		stopped = true;
	}
	
}
