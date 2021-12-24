package io.onedev.agent;

import static io.onedev.k8shelper.KubernetesHelper.BEARER;
import static io.onedev.k8shelper.KubernetesHelper.checkCacheAllocations;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.getCacheInstances;
import static io.onedev.k8shelper.KubernetesHelper.installGitCert;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jetty.websocket.api.Session;

import com.google.common.base.Throwables;

import io.onedev.agent.job.FailedException;
import io.onedev.agent.job.ShellJobData;
import io.onedev.agent.job.TestShellJobData;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.PathUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.CacheAllocationRequest;
import io.onedev.k8shelper.CacheInstance;
import io.onedev.k8shelper.CheckoutExecutable;
import io.onedev.k8shelper.CloneInfo;
import io.onedev.k8shelper.CommandExecutable;
import io.onedev.k8shelper.CompositeExecutable;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.LeafExecutable;
import io.onedev.k8shelper.LeafHandler;
import io.onedev.k8shelper.ServerExecutable;

public class ShellExecutorUtils {

	private static final Map<String, Thread> jobThreads = new ConcurrentHashMap<>();
	
	static void executeJob(Session session, ShellJobData jobData) {
		File buildDir = FileUtils.createTempDir("onedev-build");
		File attributesDir = new File(buildDir, KubernetesHelper.ATTRIBUTES);
		for (Map.Entry<String, String> entry: Agent.attributes.entrySet()) {
			FileUtils.writeFile(new File(attributesDir, entry.getKey()), 
					entry.getValue(), StandardCharsets.UTF_8.name());
		}
		Client client = ClientBuilder.newClient();
		jobThreads.put(jobData.getJobToken(), Thread.currentThread());
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
			
			File workspaceCache = null;
			for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
				if (PathUtils.isCurrent(entry.getValue())) {
					workspaceCache = entry.getKey().getDirectory(cacheHomeDir);
					break;
				}
			}
			
			File workspaceDir;
			if (workspaceCache != null) {
				workspaceDir = workspaceCache;
			} else { 
				workspaceDir = new File(buildDir, "workspace");
				FileUtils.createDir(workspaceDir);
			}
			
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
			
			CompositeExecutable entryExecutable = new CompositeExecutable(jobData.getActions());
			
			boolean successful = entryExecutable.execute(new LeafHandler() {

				@Override
				public boolean execute(LeafExecutable executable, List<Integer> position) {
					String stepNames = entryExecutable.getNamesAsString(position);
					jobLogger.notice("Running step \"" + stepNames + "\"...");
					
					if (executable instanceof CommandExecutable) {
						CommandExecutable commandExecutable = (CommandExecutable) executable;
						
						if (commandExecutable.getImage() != null) {
							throw new ExplicitException("This step should be executed by server docker executor, "
									+ "remote docker executor, or kubernetes executor");
						}
						
						File jobScriptFile = new File(buildDir, "job-commands" + commandExecutable.getScriptExtension());
						try {
							FileUtils.writeLines(
									jobScriptFile, 
									new ArrayList<>(replacePlaceholders(commandExecutable.getCommands(), buildDir)), 
									commandExecutable.getEndOfLine());
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
							}
						}
						
						Commandline interpreter = commandExecutable.getInterpreter();
						Map<String, String> environments = new HashMap<>();
						environments.put("GIT_HOME", userDir.getAbsolutePath());
						interpreter.workingDir(workspaceDir).environments(environments);
						interpreter.addArgs(jobScriptFile.getAbsolutePath());
						
						ExecutionResult result = interpreter.execute(newInfoLogger(jobLogger), newErrorLogger(jobLogger));
						if (result.getReturnCode() != 0) {
							jobLogger.error("Step \"" + stepNames + "\" is failed: Command failed with exit code " + result.getReturnCode());
							return false;
						} 
					} else if (executable instanceof CheckoutExecutable) {
						try {
							CheckoutExecutable checkoutExecutable = (CheckoutExecutable) executable;
							jobLogger.log("Checking out code...");
							Commandline git = new Commandline(Agent.gitPath);	
							git.workingDir(workspaceDir);
							Map<String, String> environments = new HashMap<>();
							environments.put("HOME", userDir.getAbsolutePath());
							git.environments(environments);

							CloneInfo cloneInfo = checkoutExecutable.getCloneInfo();
							
							cloneInfo.writeAuthData(userDir, git, newInfoLogger(jobLogger), newErrorLogger(jobLogger));
							
							List<String> trustCertContent = jobData.getTrustCertContent();
							if (!trustCertContent.isEmpty()) {
								installGitCert(new File(userDir, "trust-cert.pem"), trustCertContent, 
										git, newInfoLogger(jobLogger), newErrorLogger(jobLogger));
							}

							int cloneDepth = checkoutExecutable.getCloneDepth();
							
							cloneRepository(git, cloneInfo.getCloneUrl(), cloneInfo.getCloneUrl(), jobData.getCommitHash(), 
									checkoutExecutable.isWithLfs(), checkoutExecutable.isWithSubmodules(),
									cloneDepth, newInfoLogger(jobLogger), newErrorLogger(jobLogger));
						} catch (Exception e) {
							jobLogger.error("Step \"" + stepNames + "\" is failed: " + getErrorMessage(e));
							return false;
						}
					} else {
						ServerExecutable serverExecutable = (ServerExecutable) executable;
						
						try {
							KubernetesHelper.runServerStep(Agent.serverUrl, jobData.getJobToken(), position, 
									serverExecutable.getIncludeFiles(), serverExecutable.getExcludeFiles(), 
									serverExecutable.getPlaceholders(), buildDir, workspaceDir, jobLogger);
						} catch (Exception e) {
							jobLogger.error("Step \"" + stepNames + "\" is failed: " + getErrorMessage(e));
							return false;
						}
					}
					jobLogger.success("Step \"" + stepNames + "\" is successful");
					return true;
				}

				@Override
				public void skip(LeafExecutable executable, List<Integer> position) {
					jobLogger.notice("Step \"" + entryExecutable.getNamesAsString(position) + "\" is skipped");
				}
				
			}, new ArrayList<>());

			if (!successful)
				throw new FailedException();
		} finally {
			jobThreads.remove(jobData.getJobToken());
			client.close();
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
	
	static void cancelJob(String jobToken) {
		Thread thread = jobThreads.get(jobToken);
		if (thread != null)
			thread.interrupt();
	}
		
	public static LineConsumer newInfoLogger(TaskLogger jobLogger) {
		return new LineConsumer(StandardCharsets.UTF_8.name()) {

			private String sessionId = UUID.randomUUID().toString();
			
			@Override
			public void consume(String line) {
				jobLogger.log(line, sessionId);
			}
			
		};
	}
	
	public static LineConsumer newErrorLogger(TaskLogger jobLogger) {
		return new LineConsumer(StandardCharsets.UTF_8.name()) {

			@Override
			public void consume(String line) {
				jobLogger.warning(line);
			}
			
		};
	}
	
	public static File resolveCachePath(File workspaceDir, String cachePath) {
		File cacheDir = new File(cachePath);
		if (cacheDir.isAbsolute()) 
			throw new ExplicitException("Shell executor does not support absolute cache path: " + cachePath);
		else 
			return new File(workspaceDir, cachePath);
	}
	
	public static void testCommands(Commandline git, List<String> commands, TaskLogger jobLogger) {
		CommandExecutable executable = new CommandExecutable(null, commands, true);
		Commandline interpreter = executable.getInterpreter();
		File buildDir = FileUtils.createTempDir("onedev-build");
		try {
			jobLogger.log("Running specified commands...");
			
			File jobScriptFile = new File(buildDir, "job-commands" + executable.getScriptExtension());
			FileUtils.writeLines(jobScriptFile, commands, executable.getEndOfLine());
			File workspaceDir = new File(buildDir, "workspace");
			FileUtils.createDir(workspaceDir);
			interpreter.workingDir(workspaceDir).addArgs(jobScriptFile.getAbsolutePath());
			interpreter.execute(newInfoLogger(jobLogger), newErrorLogger(jobLogger)).checkReturnCode();

			KubernetesHelper.testGitLfsAvailability(git, jobLogger);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			FileUtils.deleteDir(buildDir);
		}
	}
	
	static void testRemoteExecutor(Session session, TestShellJobData jobData) {
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
			WebTarget target = client.target(Agent.serverUrl).path("api/k8s/test");
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

}
