package io.onedev.agent;

import static io.onedev.k8shelper.KubernetesHelper.BEARER;
import static io.onedev.k8shelper.KubernetesHelper.checkCacheAllocations;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static io.onedev.k8shelper.KubernetesHelper.cloneRepository;
import static io.onedev.k8shelper.KubernetesHelper.getCacheInstances;
import static io.onedev.k8shelper.KubernetesHelper.installGitCert;
import static io.onedev.k8shelper.KubernetesHelper.replacePlaceholders;
import static io.onedev.k8shelper.KubernetesHelper.stringifyPosition;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Throwables;

import io.onedev.agent.job.DockerJobData;
import io.onedev.agent.job.FailedException;
import io.onedev.agent.job.TestDockerJobData;
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
import io.onedev.commons.utils.command.ProcessKiller;
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

public class DockerExecutorUtils {

	private static final Logger logger = LoggerFactory.getLogger(DockerExecutorUtils.class);

	private static final Map<String, Thread> jobThreads = new ConcurrentHashMap<>();
	
	static void executeJob(Session session, DockerJobData jobData) {
		File hostBuildHome = FileUtils.createTempDir("onedev-build");
		File attributesDir = new File(hostBuildHome, KubernetesHelper.ATTRIBUTES);
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
					DockerExecutorUtils.cleanDirAsRoot(dir, new Commandline(Agent.dockerPath), false);
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
					startService(new Commandline(Agent.dockerPath), network, jobService, jobLogger);
				}
				
				AtomicReference<File> workspaceCache = new AtomicReference<>(null);
				for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
					if (PathUtils.isCurrent(entry.getValue())) {
						workspaceCache.set(entry.getKey().getDirectory(hostCacheHome));
						break;
					}
				}
				
				File hostWorkspace;
				if (workspaceCache.get() != null) {
					hostWorkspace = workspaceCache.get();
				} else { 
					hostWorkspace = new File(hostBuildHome, "workspace");
					FileUtils.createDir(hostWorkspace);
				}
				
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
					String containerEntryPoint;
					if (SystemUtils.IS_OS_WINDOWS) {
						containerBuildHome = "C:\\onedev-build";
						containerWorkspace = "C:\\onedev-build\\workspace";
						containerEntryPoint = "cmd";
					} else {
						containerBuildHome = "/onedev-build";
						containerWorkspace = "/onedev-build/workspace";
						containerEntryPoint = "sh";
					}
					
					String messageData = jobData.getJobToken() + ":" + containerWorkspace;
					new Message(MessageType.REPORT_JOB_WORKSPACE, messageData).sendBy(session);

					CompositeExecutable entryExecutable = new CompositeExecutable(jobData.getActions());

					boolean successful = entryExecutable.execute(new LeafHandler() {

						@Override
						public boolean execute(LeafExecutable executable, List<Integer> position) {
							String stepNames = entryExecutable.getNamesAsString(position);
							jobLogger.notice("Running step \"" + stepNames + "\"...");
							
							if (executable instanceof CommandExecutable) {
								CommandExecutable commandExecutable = (CommandExecutable) executable;
								String[] containerCommand;
								if (SystemUtils.IS_OS_WINDOWS) {
									if (hostAuthInfoHome.get() != null)
										containerCommand = new String[] {"/c", "xcopy /Y /S /K /Q /H /R C:\\Users\\%USERNAME%\\onedev\\* C:\\Users\\%USERNAME% > nul && C:\\onedev-build\\job-commands.bat"};						
									else
										containerCommand = new String[] {"/c", "C:\\onedev-build\\job-commands.bat"};						
									File scriptFile = new File(hostBuildHome, "job-commands.bat");
									try {
										FileUtils.writeLines(
												scriptFile, 
												new ArrayList<>(replacePlaceholders(commandExecutable.getCommands(), hostBuildHome)), 
												"\r\n");
									} catch (IOException e) {
										throw new RuntimeException(e);
									}
								} else {
									if (hostAuthInfoHome.get() != null)
										containerCommand = new String[] {"-c", "cp -r -f -p /root/auth-info/. /root && sh /onedev-build/job-commands.sh"};
									else
										containerCommand = new String[] {"/onedev-build/job-commands.sh"};
									File scriptFile = new File(hostBuildHome, "job-commands.sh");
									try {
										FileUtils.writeLines(
												scriptFile, 
												new ArrayList<>(replacePlaceholders(commandExecutable.getCommands(), hostBuildHome)), 
												"\n");
									} catch (IOException e) {
										throw new RuntimeException(e);
									}
								}
								
								String containerName = network + "-step-" + stringifyPosition(position);
								Commandline docker = new Commandline(Agent.dockerPath);
								docker.clearArgs();
								docker.addArgs("run", "--name=" + containerName, "--network=" + network);
								if (jobData.getDockerOptions() != null)
									docker.addArgs(StringUtils.parseQuoteTokens(jobData.getDockerOptions()));
								
								docker.addArgs("-v", hostBuildHome.getAbsolutePath() + ":" + containerBuildHome);
								if (workspaceCache.get() != null)
									docker.addArgs("-v", workspaceCache.get().getAbsolutePath() + ":" + containerWorkspace);
								for (Map.Entry<CacheInstance, String> entry: cacheAllocations.entrySet()) {
									if (!PathUtils.isCurrent(entry.getValue())) {
										String hostCachePath = entry.getKey().getDirectory(hostCacheHome).getAbsolutePath();
										for (String each: KubernetesHelper.resolveCachePath(containerWorkspace, entry.getValue()))
											docker.addArgs("-v", hostCachePath + ":" + each);
									}
								}
								
								if (SystemUtils.IS_OS_LINUX) 
									docker.addArgs("-v", "/var/run/docker.sock:/var/run/docker.sock");
								
								if (hostAuthInfoHome.get() != null) {
									String outerPath = hostAuthInfoHome.get().getAbsolutePath();
									if (SystemUtils.IS_OS_WINDOWS) {
										docker.addArgs("-v",  outerPath + ":C:\\Users\\ContainerAdministrator\\onedev");
										docker.addArgs("-v",  outerPath + ":C:\\Users\\ContainerUser\\onedev");
									} else { 
										docker.addArgs("-v", outerPath + ":/root/auth-info");
									}
								}

								if (commandExecutable.isUseTTY())
									docker.addArgs("-t");
								docker.addArgs("-w", containerWorkspace, "--entrypoint=" + containerEntryPoint);
								
								docker.addArgs(commandExecutable.getImage());
								docker.addArgs(containerCommand);
								
								ExecutionResult result = docker.execute(newInfoLogger(jobLogger), newErrorLogger(jobLogger), null, 
										newDockerKiller(new Commandline(Agent.dockerPath), containerName, jobLogger));
								if (result.getReturnCode() != 0) {
									jobLogger.error("Step \"" + stepNames + "\" is failed: Command failed with exit code " + result.getReturnCode());
									return false;
								}
							} else if (executable instanceof CheckoutExecutable) {
								try {
									CheckoutExecutable checkoutExecutable = (CheckoutExecutable) executable;
									jobLogger.log("Checking out code...");
									
									if (hostAuthInfoHome.get() == null)
										hostAuthInfoHome.set(FileUtils.createTempDir());
									
									Commandline git = new Commandline(Agent.gitPath);	
									git.workingDir(hostWorkspace).environments().put("HOME", hostAuthInfoHome.get().getAbsolutePath());

									CloneInfo cloneInfo = checkoutExecutable.getCloneInfo();
									
									cloneInfo.writeAuthData(hostAuthInfoHome.get(), git, newInfoLogger(jobLogger), newErrorLogger(jobLogger));
									
									List<String> trustCertContent = jobData.getTrustCertContent();
									if (!trustCertContent.isEmpty()) {
										installGitCert(new File(hostAuthInfoHome.get(), "trust-cert.pem"), trustCertContent, 
												git, newInfoLogger(jobLogger), newErrorLogger(jobLogger));
									}

									int cloneDepth = checkoutExecutable.getCloneDepth();

									String cloneUrl = checkoutExecutable.getCloneInfo().getCloneUrl();
									String commitHash = jobData.getCommitHash();
									cloneRepository(git, cloneUrl, cloneUrl, commitHash, 
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
											serverExecutable.getPlaceholders(), hostBuildHome, hostWorkspace, jobLogger);
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
					
					jobLogger.log("Reporting job caches...");
					
					target = client.target(Agent.serverUrl).path("api/k8s/report-job-caches");
					builder = target.request();
					builder.header(HttpHeaders.AUTHORIZATION, BEARER + " " + jobData.getJobToken());
					StringBuilder toStringBuilder = new StringBuilder();
					for (CacheInstance instance: getCacheInstances(hostCacheHome).keySet()) 
						toStringBuilder.append(instance.toString()).append(";");
					Response response = builder.post(Entity.entity(toStringBuilder.toString(), MediaType.APPLICATION_OCTET_STREAM));
					try {
						checkStatus(response);
					} finally {
						response.close();
					}
				} catch (IOException e) {
					throw new RuntimeException(e);
				} finally {
					if (hostAuthInfoHome.get() != null)
						FileUtils.deleteDir(hostAuthInfoHome.get());
				}
			} finally {
				deleteNetwork(new Commandline(Agent.dockerPath), network, jobLogger);
			}
		} finally {
			jobThreads.remove(jobData.getJobToken());
			client.close();
			
			cleanDirAsRoot(hostBuildHome, new Commandline(Agent.dockerPath), false);
			FileUtils.deleteDir(hostBuildHome);
		}
	}
	
	public static String getErrorMessage(Exception exception) {
		ExplicitException explicitException = ExceptionUtils.find(exception, ExplicitException.class);
		if (explicitException == null) 
			return Throwables.getStackTraceAsString(exception);
		else
			return explicitException.getMessage();
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
	
	public static void cleanDirAsRoot(File dir, Commandline docker, boolean runInDocker) {
		if (SystemUtils.IS_OS_WINDOWS || runInDocker) {
			FileUtils.cleanDir(dir);
		} else {
			String containerPath = "/dir-to-clean";
			docker.addArgs("run", "-v", dir.getAbsolutePath() + ":" + containerPath, "--rm", 
					"busybox", "sh", "-c", "rm -rf " + containerPath + "/*");			
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
		
		for (String container: containerIds) {
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
			docker.addArgs("container", "rm", container);
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
	
	@SuppressWarnings({ "resource", "unchecked" })
	public static void startService(Commandline docker, String network, Map<String, Serializable> jobService, TaskLogger jobLogger) {
		jobLogger.log("Creating service container...");
		
		String containerName = network + "-service-" + jobService.get("name");
		
		docker.clearArgs();
		docker.addArgs("run", "-d", "--name=" + containerName, "--network=" + network, 
				"--network-alias=" + jobService.get("name"));
		for (Map.Entry<String, String> entry: ((Map<String, String>)jobService.get("envVars")).entrySet()) 
			docker.addArgs("--env", entry.getKey() + "=" + entry.getValue());
		docker.addArgs((String)jobService.get("image"));
		if (jobService.get("arguments") != null) {
			for (String token: StringUtils.parseQuoteTokens((String) jobService.get("arguments")))
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
					docker.addArgs("cmd", "/c", (String)jobService.get("readinessCheckCommand"));
				else 
					docker.addArgs("sh", "-c", (String)jobService.get("readinessCheckCommand"));
				
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
				
				throw new ExplicitException(String.format("Service '" + jobService.get("name") + "' is stopped unexpectedly"));
			}
			
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}		
	}

	public static void testRemoteExecutor(Session session, TestDockerJobData jobData) {
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
			docker.addArgs("-v", workspaceDir.getAbsolutePath() + ":" + containerWorkspacePath);
			docker.addArgs("-v", cacheDir.getAbsolutePath() + ":" + containerCachePath);
			
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

}
