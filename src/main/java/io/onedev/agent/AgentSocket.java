package io.onedev.agent;

import static io.onedev.agent.DockerExecutorUtils.cleanDirAsRoot;
import static io.onedev.agent.DockerExecutorUtils.createNetwork;
import static io.onedev.agent.DockerExecutorUtils.deleteNetwork;
import static io.onedev.agent.DockerExecutorUtils.getEntrypoint;
import static io.onedev.agent.DockerExecutorUtils.isUseProcessIsolation;
import static io.onedev.agent.DockerExecutorUtils.login;
import static io.onedev.agent.DockerExecutorUtils.newDockerKiller;
import static io.onedev.agent.DockerExecutorUtils.startService;
import static io.onedev.agent.ShellExecutorUtils.resolveCachePath;
import static io.onedev.agent.ShellExecutorUtils.testCommands;
import static io.onedev.k8shelper.KubernetesHelper.BEARER;
import static io.onedev.k8shelper.KubernetesHelper.checkCacheAllocations;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.getCacheInstances;
import static io.onedev.k8shelper.KubernetesHelper.installGitCert;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.stringifyPosition;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.annotation.Nullable;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
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
import com.google.common.base.Throwables;

import io.onedev.agent.job.DockerJobData;
import io.onedev.agent.job.FailedException;
import io.onedev.agent.job.LogRequest;
import io.onedev.agent.job.ShellJobData;
import io.onedev.agent.job.TestDockerJobData;
import io.onedev.agent.job.TestShellJobData;
import io.onedev.commons.bootstrap.Bootstrap;
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
import io.onedev.k8shelper.CacheAllocationRequest;
import io.onedev.k8shelper.CacheInstance;
import io.onedev.k8shelper.CheckoutFacade;
import io.onedev.k8shelper.CloneInfo;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.LeafFacade;
import io.onedev.k8shelper.LeafHandler;
import io.onedev.k8shelper.OsContainer;
import io.onedev.k8shelper.OsExecution;
import io.onedev.k8shelper.RunContainerFacade;
import io.onedev.k8shelper.ServerSideFacade;

@WebSocket
public class AgentSocket implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(AgentSocket.class);
	
	private static final Map<String, Thread> dockerJobThreads = new ConcurrentHashMap<>();
	
	private static final Map<String, Thread> shellJobThreads = new ConcurrentHashMap<>();
	
	private Session session;
	
	private volatile Thread thread;
	
	private volatile boolean stopped;
	
	private static final ExecutorService executorService = Executors.newCachedThreadPool();
	
	private static transient volatile String hostWorkPath;
	
	@OnWebSocketConnect
	public void onConnect(Session session) throws IOException {
		logger.info("Connected to server");
		this.session = session;
		thread = new Thread(this);
		thread.start();
	}

	private String getHostPath(String path) {
		String workPath = Agent.getWorkDir().getAbsolutePath();
		Preconditions.checkState(path.startsWith(workPath + "/") || path.startsWith(workPath + "\\"));
		if (hostWorkPath == null) {
			if (Agent.isInDocker()) 
				hostWorkPath = DockerExecutorUtils.getHostPath(new Commandline(Agent.dockerPath), workPath);
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
	    				WebTarget target = client.target(Agent.serverUrl).path("downloads/agent-lib");
	    				Invocation.Builder builder =  target.request();
	    				builder.header(HttpHeaders.AUTHORIZATION, Agent.BEARER + " " + Agent.token);
	    				
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
	    					FileUtils.writeStringToFile(wrapperConfFile, wrapperConf, StandardCharsets.UTF_8);
	    				} 
	    			} finally {
	    				client.close();
	    			}
	        		Agent.restart();
	    		} else {
	    			AgentData agentData = new AgentData(Agent.token, Agent.osInfo,
	    					Agent.name, Agent.ipAddress, Agent.cpu, Agent.memory, 
	    					Agent.temporal, Agent.attributes);
	    			new Message(MessageType.AGENT_DATA, agentData).sendBy(session);
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
	    		Agent.restart();
	    		break;
	    	case STOP:
	    		Agent.stop();
	    		break;
	    	case ERROR:
	    		throw new RuntimeException(new String(messageData, StandardCharsets.UTF_8));
	    	case REQUEST:
	    		executorService.execute(new Runnable() {

					@Override
					public void run() {
						try {
				    		CallData request = SerializationUtils.deserialize(messageData);
				    		CallData response = new CallData(request.getUuid(), service(request.getPayload()));
				    		new Message(MessageType.RESPONSE, response).sendBy(session);
						} catch (Exception e) {
							logger.error("Error handling websocket request", e);
						}
					}
	    			
	    		});
	    		break;
	    	case RESPONSE:
	    		WebsocketUtils.onResponse(SerializationUtils.deserialize(messageData));
	    		break;
	    	case CANCEL_JOB:
	    		String jobToken = new String(messageData, StandardCharsets.UTF_8);
	    		cancelDockerJob(jobToken);
	    		cancelShellJob(jobToken);
	    		break;
	    	default:
	    	}
		} catch (Exception e) {
			logger.error("Error processing websocket message", e);
			try {
				session.disconnect();
			} catch (IOException e2) {
			}
		}
	}
	
	private void cancelShellJob(String jobToken) {
		Thread thread = shellJobThreads.get(jobToken);
		if (thread != null)
			thread.interrupt();
	}
		
	private void cancelDockerJob(String jobToken) {
		Thread thread = dockerJobThreads.get(jobToken);
		if (thread != null)
			thread.interrupt();
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
		File buildDir = FileUtils.createTempDir("onedev-build");
		File workspaceDir = new File(buildDir, "workspace");
		
		File attributesDir = new File(buildDir, KubernetesHelper.ATTRIBUTES);
		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), StandardCharsets.UTF_8.name());
		}
		Client client = ClientBuilder.newClient();
		shellJobThreads.put(jobData.getJobToken(), Thread.currentThread());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};
			
			File cacheHomeDir = Agent.getCacheHome();
			
			jobLogger.log("Allocating job caches...") ;
			
			WebTarget target = client.target(Agent.serverUrl).path("api/k8s/allocate-job-caches");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());

			Map<CacheInstance, String> cacheAllocations;
			try (Response response = builder.post(Entity.entity(
					new CacheAllocationRequest(new Date(), getCacheInstances(cacheHomeDir)).toString(),
					MediaType.APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				cacheAllocations = SerializationUtils.deserialize(response.readEntity(byte[].class));
			}
			
			checkCacheAllocations(cacheHomeDir, cacheAllocations, new Consumer<File>() {

				@Override
				public void accept(File dir) {
					FileUtils.cleanDir(dir);
				}
				
			});
			
			FileUtils.createDir(workspaceDir);
			
			jobLogger.log("Downloading job dependencies...");
			
			target = client.target(Agent.serverUrl).path("api/k8s/download-dependencies");
			builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());
			
			try (Response response = builder.get()){
				checkStatus(response);
				try (InputStream is = response.readEntity(InputStream.class)) {
					FileUtils.untar(is, workspaceDir, false);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			
			File userDir = new File(buildDir, "user");
			FileUtils.createDir(userDir);
			
			String messageData = jobData.getJobToken() + ":" + workspaceDir.getAbsolutePath();
			new Message(MessageType.REPORT_JOB_WORKSPACE, messageData).sendBy(session);

			CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());
			boolean successful = entryFacade.execute(new LeafHandler() {

				@Override
				public boolean execute(LeafFacade facade, List<Integer> position) {
					String stepNames = entryFacade.getNamesAsString(position);
					jobLogger.notice("Running step \"" + stepNames + "\"...");
					
					if (facade instanceof CommandFacade) {
						CommandFacade commandFacade = (CommandFacade) facade;
						OsExecution execution = commandFacade.getExecution(Agent.osInfo);
						if (execution.getImage() != null) {
							throw new ExplicitException("This step can only be executed by server docker executor, "
									+ "remote docker executor, or kubernetes executor");
						}
						
						File jobScriptFile = new File(buildDir, "job-commands" + commandFacade.getScriptExtension());
						try {
							FileUtils.writeLines(
									jobScriptFile, 
									new ArrayList<>(replacePlaceholders(execution.getCommands(), buildDir)), 
									commandFacade.getEndOfLine());
						} catch (IOException e) {
							throw new RuntimeException(e);
						}
						
						for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
							if (!PathUtils.isCurrent(entry.getValue())) {
								File sourceDir = entry.getKey().getDirectory(cacheHomeDir);
								File destDir = resolveCachePath(workspaceDir, entry.getValue());
								if (destDir.exists())
									FileUtils.deleteDir(destDir);
								else
									FileUtils.createDir(destDir.getParentFile());
								try {
									Files.createSymbolicLink(destDir.toPath(), sourceDir.toPath());
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
							} else {
								throw new ExplicitException("Invalid cache path: " + entry.getValue());
							}
						}
						
						Commandline interpreter = commandFacade.getInterpreter();
						Map<String, String> environments = new HashMap<>();
						environments.put("GIT_HOME", userDir.getAbsolutePath());
						interpreter.workingDir(workspaceDir).environments(environments);
						interpreter.addArgs(jobScriptFile.getAbsolutePath());
						
						ExecutionResult result = interpreter.execute(
								ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
						if (result.getReturnCode() != 0) {
							jobLogger.error("Step \"" + stepNames + "\" is failed: Command exited with code " + result.getReturnCode());
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
							git.workingDir(workspaceDir);
							Map<String, String> environments = new HashMap<>();
							environments.put("HOME", userDir.getAbsolutePath());
							git.environments(environments);

							CloneInfo cloneInfo = checkoutFacade.getCloneInfo();
							
							cloneInfo.writeAuthData(userDir, git, 
									ExecutorUtils.newInfoLogger(jobLogger), 
									ExecutorUtils.newWarningLogger(jobLogger));
							
							List<String> trustCertContent = jobData.getTrustCertContent();
							if (!trustCertContent.isEmpty()) {
								installGitCert(new File(userDir, "trust-cert.pem"), trustCertContent, git, 
										ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
							}

							int cloneDepth = checkoutFacade.getCloneDepth();
							
							cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(), 
									jobData.getCommitHash(), checkoutFacade.isWithLfs(), 
									checkoutFacade.isWithSubmodules(), cloneDepth, 
									ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
						} catch (Exception e) {
							jobLogger.error("Step \"" + stepNames + "\" is failed: " + getErrorMessage(e));
							return false;
						}
					} else {
						ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
						
						try {
							KubernetesHelper.runServerSideStep(Agent.serverUrl, jobData.getJobToken(), position, 
									serverSideFacade.getIncludeFiles(), serverSideFacade.getExcludeFiles(), 
									serverSideFacade.getPlaceholders(), buildDir, workspaceDir, jobLogger);
						} catch (Exception e) {
							jobLogger.error("Step \"" + stepNames + "\" is failed: " + getErrorMessage(e));
							return false;
						}
					}
					jobLogger.success("Step \"" + stepNames + "\" is successful");
					return true;
				}

				@Override
				public void skip(LeafFacade facade, List<Integer> position) {
					jobLogger.notice("Step \"" + entryFacade.getNamesAsString(position) + "\" is skipped");
				}
				
			}, new ArrayList<>());

			if (!successful)
				throw new FailedException();
		} finally {
			shellJobThreads.remove(jobData.getJobToken());
			client.close();
			
			// Fix https://code.onedev.io/projects/160/issues/597
			if (SystemUtils.IS_OS_WINDOWS && workspaceDir.exists())
				FileUtils.deleteDir(workspaceDir);
			FileUtils.deleteDir(buildDir);
		}
	}
		
	private void executeDockerJob(Session session, DockerJobData jobData) {
		File hostBuildHome = FileUtils.createTempDir("onedev-build");
		File attributesDir = new File(hostBuildHome, KubernetesHelper.ATTRIBUTES);
		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), StandardCharsets.UTF_8.name());
		}
		Client client = ClientBuilder.newClient();
		dockerJobThreads.put(jobData.getJobToken(), Thread.currentThread());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};
			
			File hostCacheHome = Agent.getCacheHome();
			
			jobLogger.log("Allocating job caches...") ;
			
			WebTarget target = client.target(Agent.serverUrl).path("api/k8s/allocate-job-caches");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());

			Map<CacheInstance, String> cacheAllocations;
			try (Response response = builder.post(Entity.entity(
					new CacheAllocationRequest(new Date(), getCacheInstances(hostCacheHome)).toString(),
					MediaType.APPLICATION_OCTET_STREAM))) {
				checkStatus(response);
				cacheAllocations = SerializationUtils.deserialize(response.readEntity(byte[].class));
			}
			
			checkCacheAllocations(hostCacheHome, cacheAllocations, new Consumer<File>() {

				@Override
				public void accept(File dir) {
					DockerExecutorUtils.cleanDirAsRoot(dir, new Commandline(Agent.dockerPath), Agent.isInDocker());
				}
				
			});
			
			for (Map<String, String> each: jobData.getRegistryLogins()) {
				login(new Commandline(Agent.dockerPath), each.get("url"), 
						each.get("userName"), each.get("password"), jobLogger);
			}
			
			String network = jobData.getExecutorName() + "-" + jobData.getProjectId() + "-" 
					+ jobData.getBuildNumber() + "-" + jobData.getRetried();
			jobLogger.log("Creating docker network '" + network + "'...");
			
			createNetwork(new Commandline(Agent.dockerPath), network, jobLogger);
			try {
				for (Map<String, Serializable> jobService: jobData.getServices()) {
					jobLogger.log("Starting service (name: " + jobService.get("name") + ", image: " + jobService.get("image") + ")...");
					startService(new Commandline(Agent.dockerPath), network, jobService, Agent.osInfo, jobLogger);
				}
				
				File hostWorkspace = new File(hostBuildHome, "workspace");
				FileUtils.createDir(hostWorkspace);
				
				AtomicReference<File> hostAuthInfoHome = new AtomicReference<>(null);
				try {						
					jobLogger.log("Downloading job dependencies...");
					
					target = client.target(Agent.serverUrl).path("api/k8s/download-dependencies");
					builder =  target.request();
					builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());
					
					try (Response response = builder.get()){
						checkStatus(response);
						try (InputStream is = response.readEntity(InputStream.class)) {
							FileUtils.untar(is, hostWorkspace, false);
						}
					}
					
					String containerBuildHome;
					String containerWorkspace;
					if (SystemUtils.IS_OS_WINDOWS) {
						containerBuildHome = "C:\\onedev-build";
						containerWorkspace = "C:\\onedev-build\\workspace";
					} else {
						containerBuildHome = "/onedev-build";
						containerWorkspace = "/onedev-build/workspace";
					}
					
					String messageData = jobData.getJobToken() + ":" + containerWorkspace;
					new Message(MessageType.REPORT_JOB_WORKSPACE, messageData).sendBy(session);

					CompositeFacade entryFacade = new CompositeFacade(jobData.getActions());

					boolean successful = entryFacade.execute(new LeafHandler() {

						private int runStepContainer(String image, @Nullable String entrypoint, 
								List<String> arguments, Map<String, String> environments, 
								@Nullable String workingDir, List<Integer> position, boolean useTTY) {
							String containerName = network + "-step-" + stringifyPosition(position);
							Commandline docker = new Commandline(Agent.dockerPath);
							docker.addArgs("run", "--name=" + containerName, "--network=" + network);
							if (jobData.getDockerOptions() != null)
								docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));
							
							docker.addArgs("-v", getHostPath(hostBuildHome.getAbsolutePath()) + ":" + containerBuildHome);
							if (workingDir != null) {
								docker.addArgs("-v", getHostPath(hostWorkspace.getAbsolutePath()) + ":" + workingDir);
								docker.addArgs("-w", workingDir);
							} else {
								docker.addArgs("-w", containerWorkspace);
							}
							
							for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
								if (!PathUtils.isCurrent(entry.getValue())) {
									String hostCachePath = entry.getKey().getDirectory(hostCacheHome).getAbsolutePath();
									String containerCachePath = PathUtils.resolve(containerWorkspace, entry.getValue());
									docker.addArgs("-v", getHostPath(hostCachePath) + ":" + containerCachePath);
								} else {
									throw new ExplicitException("Invalid cache path: " + entry.getValue());
								}
							}
							
							if (SystemUtils.IS_OS_WINDOWS) 
								docker.addArgs("-v", "//./pipe/docker_engine://./pipe/docker_engine");
							else
								docker.addArgs("-v", "/var/run/docker.sock:/var/run/docker.sock");
							
							if (hostAuthInfoHome.get() != null) {
								String hostPath = getHostPath(hostAuthInfoHome.get().getAbsolutePath());
								if (SystemUtils.IS_OS_WINDOWS) {
									docker.addArgs("-v",  hostPath + ":C:\\Users\\ContainerAdministrator\\auth-info");
									docker.addArgs("-v",  hostPath + ":C:\\Users\\ContainerUser\\auth-info");
								} else { 
									docker.addArgs("-v", hostPath + ":/root/auth-info");
								}
							}

							for (Map.Entry<String, String> entry: environments.entrySet()) 
								docker.addArgs("-e", entry.getKey() + "=" + entry.getValue());
							
							if (useTTY)
								docker.addArgs("-t");
							
							if (entrypoint != null)
								docker.addArgs("--entrypoint=" + entrypoint);
							
							if (isUseProcessIsolation(new Commandline(Agent.dockerPath), image, Agent.osInfo, jobLogger))
								docker.addArgs("--isolation=process");
							
							docker.addArgs(image);
							docker.addArgs(arguments.toArray(new String[arguments.size()]));
							
							ExecutionResult result = docker.execute(
									ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger), 
									null, newDockerKiller(new Commandline(Agent.dockerPath), containerName, jobLogger));
							return result.getReturnCode();
						}
						
						@Override
						public boolean execute(LeafFacade facade, List<Integer> position) {
							String stepNames = entryFacade.getNamesAsString(position);
							jobLogger.notice("Running step \"" + stepNames + "\"...");
							
							if (facade instanceof CommandFacade) {
								CommandFacade commandFacade = (CommandFacade) facade;
								OsExecution execution = commandFacade.getExecution(Agent.osInfo);
								if (execution.getImage() == null) {
									throw new ExplicitException("This step can only be executed by server shell "
											+ "executor or remote shell executor");
								}
								
								Commandline entrypoint = getEntrypoint(hostBuildHome, commandFacade, 
										Agent.osInfo, hostAuthInfoHome.get() != null);
								int exitCode = runStepContainer(execution.getImage(), entrypoint.executable(), 
										entrypoint.arguments(), new HashMap<>(), null, position, commandFacade.isUseTTY());
								
								if (exitCode != 0) {
									jobLogger.error("Step \"" + stepNames + "\" is failed: Command exited with code " + exitCode);
									return false;
								}
							} else if (facade instanceof BuildImageFacade) {
								DockerExecutorUtils.buildImage(new Commandline(Agent.dockerPath), 
										(BuildImageFacade) facade, hostWorkspace, jobLogger);
							} else if (facade instanceof RunContainerFacade) {
								RunContainerFacade runContainerFacade = (RunContainerFacade) facade;

								OsContainer container = runContainerFacade.getContainer(Agent.osInfo); 
								List<String> arguments = new ArrayList<>();
								if (container.getArgs() != null)
									arguments.addAll(Arrays.asList(StringUtils.parseQuoteTokens(container.getArgs())));
								int exitCode = runStepContainer(container.getImage(), null, arguments, 
										container.getEnvMap(), container.getWorkingDir(), 
										position, runContainerFacade.isUseTTY());
								if (exitCode != 0) {
									jobLogger.error("Step \"" + stepNames + "\" is failed: Container exit with code " + exitCode);
									return false;
								} 
							} else if (facade instanceof CheckoutFacade) {
								try {
									CheckoutFacade checkoutFacade = (CheckoutFacade) facade;
									jobLogger.log("Checking out code...");
									
									if (hostAuthInfoHome.get() == null)
										hostAuthInfoHome.set(FileUtils.createTempDir());
									
									Commandline git = new Commandline(Agent.gitPath);	
									git.workingDir(hostWorkspace).environments().put("HOME", hostAuthInfoHome.get().getAbsolutePath());

									CloneInfo cloneInfo = checkoutFacade.getCloneInfo();
									
									cloneInfo.writeAuthData(hostAuthInfoHome.get(), git, 
											ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
									try {
										List<String> trustCertContent = jobData.getTrustCertContent();
										if (!trustCertContent.isEmpty()) {
											installGitCert(new File(hostAuthInfoHome.get(), "trust-cert.pem"), trustCertContent, git, 
													ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
										}
	
										int cloneDepth = checkoutFacade.getCloneDepth();
	
										String cloneUrl = checkoutFacade.getCloneInfo().getCloneUrl();
										String commitHash = jobData.getCommitHash();
										cloneRepository(git, cloneUrl, cloneUrl, commitHash, 
												checkoutFacade.isWithLfs(), checkoutFacade.isWithSubmodules(), cloneDepth, 
												ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
									} finally {
										git.clearArgs();
										git.addArgs("config", "--global", "--unset", "core.sshCommand");
										ExecutionResult result = git.execute(ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger));
										if (result.getReturnCode() != 5 && result.getReturnCode() != 0)
											result.checkReturnCode();
									}
								} catch (Exception e) {
									jobLogger.error("Step \"" + stepNames + "\" is failed: " + getErrorMessage(e));
									return false;
								}
							} else {
								ServerSideFacade serverSideFacade = (ServerSideFacade) facade;
								
								try {
									KubernetesHelper.runServerSideStep(Agent.serverUrl, jobData.getJobToken(), position, 
											serverSideFacade.getIncludeFiles(), serverSideFacade.getExcludeFiles(), 
											serverSideFacade.getPlaceholders(), hostBuildHome, hostWorkspace, jobLogger);
								} catch (Exception e) {
									jobLogger.error("Step \"" + stepNames + "\" is failed: " + getErrorMessage(e));
									return false;
								}
							}
							jobLogger.success("Step \"" + stepNames + "\" is successful");
							return true;
						}

						@Override
						public void skip(LeafFacade facade, List<Integer> position) {
							jobLogger.notice("Step \"" + entryFacade.getNamesAsString(position) + "\" is skipped");
						}
						
					}, new ArrayList<>());
					
					if (!successful)
						throw new FailedException();
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					// Fix https://code.onedev.io/projects/160/issues/597
					if (SystemUtils.IS_OS_WINDOWS)
						FileUtils.deleteDir(hostWorkspace);
					if (hostAuthInfoHome.get() != null)
						FileUtils.deleteDir(hostAuthInfoHome.get());
				}
			} finally {
				deleteNetwork(new Commandline(Agent.dockerPath), network, jobLogger);
			}
		} finally {
			dockerJobThreads.remove(jobData.getJobToken());
			client.close();
			
			cleanDirAsRoot(hostBuildHome, new Commandline(Agent.dockerPath), Agent.isInDocker());
			FileUtils.deleteDir(hostBuildHome);
		}
	}
		
	private void testShellExecutor(Session session, TestShellJobData jobData) {
		Client client = ClientBuilder.newClient();
		shellJobThreads.put(jobData.getJobToken(), Thread.currentThread());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};
			
			jobLogger.log(String.format("Connecting to server '%s'...", Agent.serverUrl));
			WebTarget target = client.target(Agent.serverUrl).path("api/k8s/test");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());
			try (Response response = builder.get()) {
				checkStatus(response);
			} 
			
			testCommands(new Commandline(Agent.gitPath), jobData.getCommands(), jobLogger);
		} finally {
			shellJobThreads.remove(jobData.getJobToken());
			client.close();
		}		
	}
	
	private void testDockerExecutor(Session session, TestDockerJobData jobData) {
		File workspaceDir = null;
		File cacheDir = null;
		File authInfoDir = null;
		
		Client client = ClientBuilder.newClient();
		dockerJobThreads.put(jobData.getJobToken(), Thread.currentThread());
		try {
			TaskLogger jobLogger = new TaskLogger() {

				@Override
				public void log(String message, String sessionId) {
					Agent.log(session, jobData.getJobToken(), message, sessionId);
				}
				
			};
			
			workspaceDir = Bootstrap.createTempDir("workspace");
			cacheDir = new File(Agent.getCacheHome(), UUID.randomUUID().toString());
			FileUtils.createDir(cacheDir);
			authInfoDir = FileUtils.createTempDir();
			
			jobLogger.log(String.format("Connecting to server '%s'...", Agent.serverUrl));
			WebTarget target = client.target(Agent.serverUrl).path("api/k8s/test");
			Invocation.Builder builder =  target.request();
			builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());
			try (Response response = builder.get()) {
				checkStatus(response);
			} 
			
			for (Map<String, String> each: jobData.getRegistryLogins()) {
				login(new Commandline(Agent.dockerPath), each.get("url"), 
						each.get("userName"), each.get("password"), jobLogger);
			}

			jobLogger.log("Testing specified docker image...");
			Commandline docker = new Commandline(Agent.dockerPath);
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
			docker.addArgs("-v", getHostPath(workspaceDir.getAbsolutePath()) + ":" + containerWorkspacePath);
			docker.addArgs("-v", getHostPath(cacheDir.getAbsolutePath()) + ":" + containerCachePath);
			
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
				docker = new Commandline(Agent.dockerPath);
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
			dockerJobThreads.remove(jobData.getJobToken());
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

    @OnWebSocketError
    public void onError(Throwable t) {
    	logger.error("Websocket error", t);
    	Agent.reconnect = true;
    }

	@Override
	public void run() {
		Message message = new Message(MessageType.HEART_BEAT, new byte[0]);
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