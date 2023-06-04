package io.onedev.agent;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import io.onedev.agent.job.*;
import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.utils.*;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.*;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.commons.lang3.SystemUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static io.onedev.agent.DockerExecutorUtils.*;
import static io.onedev.agent.ShellExecutorUtils.testCommands;
import static io.onedev.k8shelper.KubernetesHelper.*;
import static java.nio.charset.StandardCharsets.UTF_8;

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
	
	static final ExecutorService executorService = Executors.newCachedThreadPool();
	
	private static transient volatile String hostWorkPath;
	
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
	    		String versionAtServer = new String(messageData, StandardCharsets.UTF_8);
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
	    						FileUtils.untar(is, newLibDir, false);
	    					} 
	    					
	    					File wrapperConfFile = new File(Agent.installDir, "conf/wrapper.conf");
	    					String wrapperConf = FileUtils.readFileToString(wrapperConfFile, StandardCharsets.UTF_8);
	    					wrapperConf = wrapperConf.replace("../lib/" + Agent.version + "/", "../lib/" + versionAtServer + "/");
	    					wrapperConf = wrapperConf.replace("-XX:+IgnoreUnrecognizedVMOptions", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
	    					
	    					if (!wrapperConf.contains("java.base/jdk.internal.ref=ALL-UNNAMED")) {
	    						wrapperConf += ""
	    								+ "\r\nwrapperConfwrapper.java.additional.30=--add-modules=java.se" 
	    								+ "\r\nwrapper.java.additional.31=--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED" 
	    								+ "\r\nwrapper.java.additional.32=--add-opens=java.management/sun.management=ALL-UNNAMED"
	    								+ "\r\nwrapper.java.additional.33=--add-opens=jdk.management/com.sun.management.internal=ALL-UNNAMED";
	    					}
							if (!wrapperConf.contains("java.base/sun.nio.fs=ALL-UNNAMED")) {
								wrapperConf += "\r\nwrapper.java.additional.50=--add-opens=java.base/sun.nio.fs=ALL-UNNAMED";
							}

	    					if (!wrapperConf.contains("wrapper.disable_console_input")) 
	    						wrapperConf += "\r\nwrapper.disable_console_input=TRUE";
	    					
	    					FileUtils.writeStringToFile(wrapperConfFile, wrapperConf, StandardCharsets.UTF_8);

	    					File logbackConfigFile = new File(Agent.installDir, "conf/logback.xml");
	    					String logbackConfig = FileUtils.readFileToString(logbackConfigFile, StandardCharsets.UTF_8);
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
	    						FileUtils.writeStringToFile(logbackConfigFile, logbackConfig, StandardCharsets.UTF_8);
	    					}
	    					
	    				} 
	    			} finally {
	    				client.close();
	    			}
	        		Agent.restart();
	    		} else {
	    			AgentData agentData = new AgentData(Agent.token, Agent.osInfo,
	    					Agent.name, Agent.ipAddress, Agent.cpus,
							Agent.temporal, Agent.attributes);
	    			new Message(MessageTypes.AGENT_DATA, agentData).sendBy(session);
	    		}
	    		break;
	    	case UPDATE_ATTRIBUTES:
	    		Map<String, String> attributes = SerializationUtils.deserialize(messageData);
	    		Agent.attributes = attributes;
	    		Properties props = new Properties();
	    		props.putAll(attributes);
	    		try (OutputStream os = new FileOutputStream(new File(Agent.installDir, "conf/attributes.properties"))) {
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
	    		throw new RuntimeException(new String(messageData, StandardCharsets.UTF_8));
	    	case REQUEST:
	    		executorService.execute(() -> {
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
	    		String jobToken = new String(messageData, StandardCharsets.UTF_8);
	    		cancelJob(jobToken);
	    		break;
	    	case RESUME_JOB: 
	    		jobToken = new String(messageData, StandardCharsets.UTF_8);
	    		resumeJob(jobToken);
	    		break;
	    	case SHELL_OPEN:
	    		String openData = new String(messageData, StandardCharsets.UTF_8);
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
	    		sessionId = new String(messageData, StandardCharsets.UTF_8);
	    		ShellSession shellSession = shellSessions.remove(sessionId);
	    		if (shellSession != null)
	    			shellSession.exit();
	    		break;
	    	case SHELL_INPUT:
	    		String inputData = new String(messageData, StandardCharsets.UTF_8);
	    		sessionId = StringUtils.substringBefore(inputData, ":");
	    		String input = StringUtils.substringAfter(inputData, ":");
	    		shellSession = shellSessions.get(sessionId);
	    		if (shellSession != null)
	    			shellSession.sendInput(input);
	    		break;
	    	case SHELL_RESIZE:
	    		String resizeData = new String(messageData, StandardCharsets.UTF_8);
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
	
	private String getErrorMessage(Exception exception) {
		ExplicitException explicitException = ExceptionUtils.find(exception, ExplicitException.class);
		if (explicitException == null) 
			return Throwables.getStackTraceAsString(exception);
		else
			return explicitException.getMessage();
	}

	private void executeShellJob(Session session, ShellJobData jobData) {
		if (Agent.isInDocker()) {
			throw new ExplicitException("Remote shell executor can only execute jobs on agents running "
					+ "directly on bare metal/virtual machine");
		}
		
		File buildHome = FileUtils.createTempDir("onedev-build");
		File workspaceDir = new File(buildHome, "workspace");
		
		File attributesDir = new File(buildHome, KubernetesHelper.ATTRIBUTES);
		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), StandardCharsets.UTF_8.name());
		}
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		buildHomes.put(jobData.getJobToken(), buildHome);
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};
			
			File cacheHomeDir = Agent.getCacheHome(jobData.getExecutorName());
			
			jobLogger.log("Setting up job cache...") ;
			
			JobCache cache = new JobCache(cacheHomeDir) {

				@Override
				protected Map<CacheInstance, String> allocate(CacheAllocationRequest request) {
					return KubernetesHelper.allocateCaches(Agent.sslFactory, Agent.serverUrl,
							jobData.getJobToken(), request);
				}

				@Override
				protected void delete(File cacheDir) {
					FileUtils.cleanDir(cacheDir);					
				}
				
			};
			
			cache.init(true);
			
			FileUtils.createDir(workspaceDir);
			cache.installSymbolinks(workspaceDir);
			
			jobLogger.log("Downloading job dependencies...");
			
			KubernetesHelper.downloadDependencies(Agent.sslFactory, jobData.getJobToken(),
					Agent.serverUrl, workspaceDir);
			
			File userHome = new File(buildHome, "user");
			FileUtils.createDir(userHome);
			
			String messageData = jobData.getJobToken() + ":" + workspaceDir.getAbsolutePath();
			new Message(MessageTypes.REPORT_JOB_WORKSPACE, messageData).sendBy(session);

			CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
			boolean successful = entryFacade.execute(new LeafHandler() {

				@Override
				public boolean execute(LeafFacade facade, List<Integer> position) {
					runningSteps.put(jobData.getJobToken(), facade);
					try {
						String stepNames = entryFacade.getNamesAsString(position);
						jobLogger.notice("Running step \"" + stepNames + "\"...");

						long time = System.currentTimeMillis();
						if (facade instanceof CommandFacade) {
							CommandFacade commandFacade = (CommandFacade) facade;
							OsExecution execution = commandFacade.getExecution(Agent.osInfo);
							if (execution.getImage() != null) {
								throw new ExplicitException("This step can only be executed by server docker executor, "
										+ "remote docker executor, or kubernetes executor");
							}
							
							commandFacade.generatePauseCommand(buildHome);
							
							File jobScriptFile = new File(buildHome, "job-commands" + commandFacade.getScriptExtension());
							try {
								FileUtils.writeLines(
										jobScriptFile, 
										new ArrayList<>(replacePlaceholders(execution.getCommands(), buildHome)),
										commandFacade.getEndOfLine());
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
							
							Commandline interpreter = commandFacade.getScriptInterpreter();
							Map<String, String> environments = new HashMap<>();
							environments.put("GIT_HOME", userHome.getAbsolutePath());
							environments.put("ONEDEV_WORKSPACE", workspaceDir.getAbsolutePath());
							interpreter.workingDir(workspaceDir).environments(environments);
							interpreter.addArgs(jobScriptFile.getAbsolutePath());
							
							ExecutionResult result = interpreter.execute(
									ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
							if (result.getReturnCode() != 0) {
								long duration = System.currentTimeMillis() - time;
								jobLogger.error("Step \"" + stepNames + "\" is failed (" + formatDuration(duration) + "): Command exited with code " + result.getReturnCode());
								return false;
							} 
						} else if (facade instanceof BuildImageFacade || facade instanceof RunContainerFacade) {
							throw new ExplicitException("This step can only be executed by server docker executor, "
									+ "remote docker executor, or kubernetes executor");
						} else if (facade instanceof CheckoutFacade) {
							try {
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
							} catch (Exception e) {
								long duration = System.currentTimeMillis() - time;
								jobLogger.error("Step \"" + stepNames + "\" is failed (" + formatDuration(duration) + "): " + getErrorMessage(e));
								return false;
							}
						} else {
							ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
							
							try {
								KubernetesHelper.runServerStep(Agent.sslFactory, Agent.serverUrl,
										jobData.getJobToken(), position, serverSideFacade.getSourcePath(),
										serverSideFacade.getIncludeFiles(), serverSideFacade.getExcludeFiles(),
										serverSideFacade.getPlaceholders(), buildHome, jobLogger);
							} catch (Exception e) {
								long duration = System.currentTimeMillis() - time;
								jobLogger.error("Step \"" + stepNames + "\" is failed (" + formatDuration(duration) + "): " + getErrorMessage(e));
								return false;
							}
						}
						long duration = System.currentTimeMillis() - time;
						jobLogger.success("Step \"" + stepNames + "\" is successful (" + formatDuration(duration) + ")");
						return true;
					} finally {
						runningSteps.remove(jobData.getJobToken());
					}
				}

				@Override
				public void skip(LeafFacade facade, List<Integer> position) {
					jobLogger.notice("Step \"" + entryFacade.getNamesAsString(position) + "\" is skipped");
				}
				
			}, new ArrayList<>());

			if (!successful)
				throw new FailedException();
		} finally {
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
		
	private void executeDockerJob(Session session, DockerJobData jobData) {
		File hostBuildHome = FileUtils.createTempDir("onedev-build");
		File attributesDir = new File(hostBuildHome, KubernetesHelper.ATTRIBUTES);
		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), StandardCharsets.UTF_8.name());
		}
		var dockerSock = jobData.getDockerSock();

		Client client = ClientBuilder.newClient();
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
		buildHomes.put(jobData.getJobToken(), hostBuildHome);
		if (dockerSock != null)
			dockerSocks.put(jobData.getJobToken(), dockerSock);
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};
			
			File hostCacheHome = Agent.getCacheHome(jobData.getExecutorName());
			
			jobLogger.log("Allocating job cache...") ;

			JobCache cache = new JobCache(hostCacheHome) {

				@Override
				protected Map<CacheInstance, String> allocate(CacheAllocationRequest request) {
					return KubernetesHelper.allocateCaches(Agent.sslFactory, Agent.serverUrl,
							jobData.getJobToken(), request);
				}

				@Override
				protected void delete(File cacheDir) {
					DockerExecutorUtils.deleteDir(
							cacheDir, 
							newDocker(dockerSock), Agent.isInDocker());
				}
				
			};
			cache.init(false);
			
			for (var registryLogin: jobData.getRegistryLogins())
				login(newDocker(dockerSock), registryLogin, jobLogger);

			String network = jobData.getExecutorName() + "-" + jobData.getProjectId() + "-" 
					+ jobData.getBuildNumber() + "-" + jobData.getRetried();
			jobLogger.log("Creating docker network '" + network + "'...");
			
			createNetwork(newDocker(dockerSock), network, jobData.getNetworkOptions(), jobLogger);
			try {
				for (Map<String, Serializable> jobService: jobData.getServices()) {
					jobLogger.log("Starting service (name: " + jobService.get("name")
							+ ", image: " + jobService.get("image") + ")...");
					startService(newDocker(dockerSock), network, jobService,
							Agent.osInfo, jobData.getCpuLimit(), jobData.getMemoryLimit(), jobLogger);
				}
				
				File hostWorkspace = new File(hostBuildHome, "workspace");
				FileUtils.createDir(hostWorkspace);
				cache.installSymbolinks(hostWorkspace);
				
				AtomicReference<File> hostAuthInfoDir = new AtomicReference<>(null);
				try {						
					jobLogger.log("Downloading job dependencies...");

					KubernetesHelper.downloadDependencies(Agent.sslFactory, jobData.getJobToken(),
							Agent.serverUrl, hostWorkspace);
					
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

					boolean successful = entryFacade.execute(new LeafHandler() {

						private int runStepContainer(String image, @Nullable String entrypoint, 
								List<String> arguments, Map<String, String> environments, 
								@Nullable String workingDir, Map<String, String> volumeMounts, 
								List<Integer> position, boolean useTTY, boolean kaniko) {
							// Docker can not process symbol links well
							cache.uninstallSymbolinks(hostWorkspace);

							String containerName = network + "-step-" + stringifyStepPosition(position);
							containerNames.put(jobData.getJobToken(), containerName);
							try {
								Commandline docker = newDocker(dockerSock);
								docker.addArgs("run", "--name=" + containerName, "--network=" + network);

								if (jobData.getCpuLimit() != null)
									docker.addArgs("--cpus", jobData.getCpuLimit());
								if (jobData.getMemoryLimit() != null)
									docker.addArgs("--memory", jobData.getMemoryLimit());
								if (jobData.getDockerOptions() != null)
									docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));
								
								docker.addArgs("-v", getHostPath(hostBuildHome.getAbsolutePath(), dockerSock) + ":" + containerBuildHome);

								if (kaniko) {
									var dockerConfigFile = new File(hostBuildHome, "kaniko/.docker/config.json");
									FileUtils.writeFile(dockerConfigFile, buildDockerConfig(jobData.getRegistryLogins()), UTF_8.name());
									String hostPath = getHostPath(dockerConfigFile.getAbsolutePath(), dockerSock);
									docker.addArgs("-v", hostPath + ":/kaniko/.docker/config.json");
								}

								for (Map.Entry<String, String> entry: volumeMounts.entrySet()) {
									if (entry.getKey().contains(".."))
										throw new ExplicitException("Volume mount source path should not contain '..'");
									String hostPath = getHostPath(new File(hostWorkspace, entry.getKey()).getAbsolutePath(), dockerSock);
									docker.addArgs("-v", hostPath + ":" + entry.getValue());
								}
								
								if (entrypoint != null) { 
									docker.addArgs("-w", containerWorkspace);
								} else if (workingDir != null) {
									if (workingDir.contains(".."))
										throw new ExplicitException("Container working dir should not contain '..'");
									docker.addArgs("-w", workingDir);
								}
								
								for (Map.Entry<CacheInstance, String> entry: cache.getAllocations().entrySet()) {
									String hostCachePath = new File(hostCacheHome, entry.getKey().toString()).getAbsolutePath();
									String containerCachePath = PathUtils.resolve(containerWorkspace, entry.getValue());
									docker.addArgs("-v", getHostPath(hostCachePath, dockerSock) + ":" + containerCachePath);
								}
								
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
								
								if (hostAuthInfoDir.get() != null) {
									String hostPath = getHostPath(hostAuthInfoDir.get().getAbsolutePath(), dockerSock);
									if (SystemUtils.IS_OS_WINDOWS) {
										docker.addArgs("-v",  hostPath + ":C:\\Users\\ContainerAdministrator\\auth-info");
										docker.addArgs("-v",  hostPath + ":C:\\Users\\ContainerUser\\auth-info");
									} else { 
										docker.addArgs("-v", hostPath + ":/root/auth-info");
									}
								}
	
								for (Map.Entry<String, String> entry: environments.entrySet()) 
									docker.addArgs("-e", entry.getKey() + "=" + entry.getValue());
								
								docker.addArgs("-e", "ONEDEV_WORKSPACE=" + containerWorkspace);
								
								if (useTTY)
									docker.addArgs("-t");
								
								if (entrypoint != null)
									docker.addArgs("--entrypoint=" + entrypoint);
								
								if (isUseProcessIsolation(newDocker(dockerSock), image, Agent.osInfo, jobLogger))
									docker.addArgs("--isolation=process");
								
								docker.addArgs(image);
								docker.addArgs(arguments.toArray(new String[arguments.size()]));
								docker.processKiller(newDockerKiller(newDocker(dockerSock), containerName, jobLogger));
								ExecutionResult result = docker.execute(newInfoLogger(jobLogger), newWarningLogger(jobLogger),
										null);
								return result.getReturnCode();
							} finally {
								containerNames.remove(jobData.getJobToken());
								cache.installSymbolinks(hostWorkspace);
							}
						}
						
						@Override
						public boolean execute(LeafFacade facade, List<Integer> position) {
							runningSteps.put(jobData.getJobToken(), facade);
							try {
								String stepNames = entryFacade.getNamesAsString(position);
								jobLogger.notice("Running step \"" + stepNames + "\"...");

								long time = System.currentTimeMillis();
								if (facade instanceof CommandFacade) {
									CommandFacade commandFacade = (CommandFacade) facade;
									OsExecution execution = commandFacade.getExecution(Agent.osInfo);
									if (execution.getImage() == null) {
										throw new ExplicitException("This step can only be executed by server shell "
												+ "executor or remote shell executor");
									}
									
									Commandline entrypoint = getEntrypoint(hostBuildHome, commandFacade, 
											Agent.osInfo, hostAuthInfoDir.get() != null);
									int exitCode = runStepContainer(execution.getImage(), entrypoint.executable(), 
											entrypoint.arguments(), new HashMap<>(), null, new HashMap<>(), 
											position, commandFacade.isUseTTY(), false);
									
									if (exitCode != 0) {
										long duration = System.currentTimeMillis() - time;
										jobLogger.error("Step \"" + stepNames + "\" is failed (" + formatDuration(duration) + "): Command exited with code " + exitCode);
										return false;
									}
								} else if (facade instanceof BuildImageFacade) {
									DockerExecutorUtils.buildImage(newDocker(dockerSock), (BuildImageFacade) facade,
											hostBuildHome, jobLogger);
								} else if (facade instanceof RunContainerFacade) {
									RunContainerFacade runContainerFacade = (RunContainerFacade) facade;
	
									OsContainer container = runContainerFacade.getContainer(Agent.osInfo); 
									List<String> arguments = new ArrayList<>();
									if (container.getArgs() != null)
										arguments.addAll(Arrays.asList(StringUtils.parseQuoteTokens(container.getArgs())));
									int exitCode = runStepContainer(container.getImage(), null, arguments, 
											container.getEnvMap(), container.getWorkingDir(), container.getVolumeMounts(),
											position, runContainerFacade.isUseTTY(), runContainerFacade.isKaniko());
									if (exitCode != 0) {
										long duration = System.currentTimeMillis() - time;
										jobLogger.error("Step \"" + stepNames + "\" is failed (" + formatDuration(duration) + "): Container exit with code " + exitCode);
										return false;
									} 
								} else if (facade instanceof CheckoutFacade) {
									try {
										CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
										jobLogger.log("Checking out code...");
										
										Commandline git = new Commandline(Agent.gitPath);

										if (hostAuthInfoDir.get() == null)
											hostAuthInfoDir.set(FileUtils.createTempDir());
										git.environments().put("HOME", hostAuthInfoDir.get().getAbsolutePath());

										checkoutFacade.setupWorkingDir(git, hostWorkspace);
										if (!Bootstrap.isInDocker()) {
											checkoutFacade.setupSafeDirectory(git, containerWorkspace,
													newInfoLogger(jobLogger), newErrorLogger(jobLogger));
										}

										File trustCertsFile = new File(hostBuildHome, "trust-certs.pem");
										installGitCert(git, Agent.getTrustCertsDir(),
												trustCertsFile, containerTrustCerts,
												ExecutorUtils.newInfoLogger(jobLogger),
												ExecutorUtils.newWarningLogger(jobLogger));

										CloneInfo cloneInfo = checkoutFacade.getCloneInfo();
										cloneInfo.writeAuthData(hostAuthInfoDir.get(), git, true,
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
									} catch (Exception e) {
										long duration = System.currentTimeMillis() - time;
										jobLogger.error("Step \"" + stepNames + "\" is failed (" + formatDuration(duration) + "): " + getErrorMessage(e));
										return false;
									}
								} else {
									ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
									
									try {
										KubernetesHelper.runServerStep(Agent.sslFactory, Agent.serverUrl,
												jobData.getJobToken(), position, serverSideFacade.getSourcePath(),
												serverSideFacade.getIncludeFiles(), serverSideFacade.getExcludeFiles(),
												serverSideFacade.getPlaceholders(), hostBuildHome, jobLogger);
									} catch (Exception e) {
										long duration = System.currentTimeMillis() - time;
										jobLogger.error("Step \"" + stepNames + "\" is failed (" + formatDuration(duration) + "): " + getErrorMessage(e));
										return false;
									}
								}
								long duration = System.currentTimeMillis() - time;
								jobLogger.success("Step \"" + stepNames + "\" is successful (" + formatDuration(duration) + ")");
								return true;
							} finally {
								runningSteps.remove(jobData.getJobToken());
							}
						}

						@Override
						public void skip(LeafFacade facade, List<Integer> position) {
							jobLogger.notice("Step \"" + entryFacade.getNamesAsString(position) + "\" is skipped");
						}
						
					}, new ArrayList<>());
					
					if (!successful)
						throw new FailedException();
				} finally {
					cache.uninstallSymbolinks(hostWorkspace);
					
					// Fix https://code.onedev.io/onedev/server/~issues/597
					if (SystemUtils.IS_OS_WINDOWS)
						FileUtils.deleteDir(hostWorkspace);
					if (hostAuthInfoDir.get() != null)
						FileUtils.deleteDir(hostAuthInfoDir.get());
				}
			} finally {
				deleteNetwork(newDocker(dockerSock), network, jobLogger);
			}
		} finally {
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
		
	private void testShellExecutor(Session session, TestShellJobData jobData) {
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
			WebTarget target = client.target(Agent.serverUrl).path("~api/k8s/test");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());
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
		File workspaceDir = null;
		File cacheDir = null;
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
			
			workspaceDir = Bootstrap.createTempDir("workspace");
			cacheDir = new File(Agent.getCacheHome(jobData.getExecutorName()), UUID.randomUUID().toString());
			FileUtils.createDir(cacheDir);
			authInfoDir = FileUtils.createTempDir();
			
			jobLogger.log(String.format("Connecting to server '%s'...", Agent.serverUrl));
			WebTarget target = client.target(Agent.serverUrl).path("~api/k8s/test");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());
			try (Response response = builder.get()) {
				checkStatus(response);
			} 

			var dockerSock = jobData.getDockerSock();
			for (var registryLogin: jobData.getRegistryLogins())
				login(newDocker(dockerSock), registryLogin, jobLogger);

			jobLogger.log("Testing specified docker image...");
			Commandline docker = newDocker(dockerSock);
			docker.addArgs("run", "--rm");
			if (jobData.getDockerOptions() != null)
				docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));
			
			String containerWorkspacePath;
			String containerCachePath;
			if (SystemUtils.IS_OS_WINDOWS) {
				containerWorkspacePath = "C:\\onedev-build\\workspace";
				containerCachePath = "C:\\onedev-build\\cache";
			} else {
				containerWorkspacePath = "/onedev-build/workspace";
				containerCachePath = "/onedev-build/cache";
			}
			docker.addArgs("-v", getHostPath(workspaceDir.getAbsolutePath(), dockerSock) + ":" + containerWorkspacePath);
			docker.addArgs("-v", getHostPath(cacheDir.getAbsolutePath(), dockerSock) + ":" + containerCachePath);
			
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
				docker = newDocker(dockerSock);
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
			if (cacheDir != null)
				FileUtils.deleteDir(cacheDir);
		}		
	}
	
	private Serializable service(Serializable request) {
		try {
			if (request instanceof LogRequest) { 
				return (Serializable) LogRequest.readLog(new File(Agent.installDir, "logs/agent.log"));
			} else if (request instanceof DockerJobData) { 
				try {
					executeDockerJob(session, (DockerJobData) request);
					return null;
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof TestDockerJobData) {
				try {
					testDockerExecutor(session, (TestDockerJobData) request);
					return null;
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof ShellJobData) { 
				try {
					executeShellJob(session, (ShellJobData) request);
					return null;
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof TestShellJobData) {
				try {
					testShellExecutor(session, (TestShellJobData) request);
					return null;
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