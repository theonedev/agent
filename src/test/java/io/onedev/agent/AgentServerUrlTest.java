package io.onedev.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import io.onedev.agent.job.DockerJobData;
import io.onedev.agent.job.HostJobData;
import io.onedev.agent.job.JobDockerSettings;
import io.onedev.agent.job.TestDockerJobData;
import io.onedev.agent.job.TestHostJobData;
import io.onedev.agent.shell.JobShellSession;
import io.onedev.agent.shell.WorkspaceShellSession;
import io.onedev.agent.workspace.GitSettings;
import io.onedev.agent.workspace.ProvisionHostWorkspaceData;
import io.onedev.agent.workspace.TestDockerWorkspaceData;
import io.onedev.agent.workspace.TestHostWorkspaceData;
import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.Action;
import io.onedev.k8shelper.CacheAvailability;
import io.onedev.k8shelper.CacheConfigFacade;
import io.onedev.k8shelper.CacheEntryFacade;
import io.onedev.k8shelper.DefaultCloneInfo;
import io.onedev.k8shelper.ExecuteCondition;
import io.onedev.k8shelper.ScriptConfig;
import io.onedev.k8shelper.ServerSideFacade;
import io.onedev.k8shelper.ServerStepResult;
import io.onedev.k8shelper.SetupCacheFacade;
import io.onedev.k8shelper.UploadStrategy;
import io.onedev.k8shelper.UserDataEntryFacade;
import io.onedev.k8shelper.UserDataFacade;
import io.onedev.k8shelper.UserDataProvisioner;
import io.onedev.k8shelper.WorkspaceHelper;

@EnabledOnOs({OS.LINUX, OS.MAC})
class AgentServerUrlTest {

	@TempDir
	Path temp;

	private final String originalServerUrl = Agent.serverUrl;
	private final String originalGitPath = Agent.gitPath;
	private final String originalDockerPath = Agent.dockerPath;
	private final java.io.File originalInstallDir = Agent.installDir;
	private final Map<String, String> originalAttributes = Agent.attributes;
	private final nl.altindag.ssl.SSLFactory originalSslFactory = Agent.sslFactory;
	private final List<String> requests = new CopyOnWriteArrayList<>();
	private final List<String> connectionRequests = new CopyOnWriteArrayList<>();
	private HttpServer systemServer;
	private HttpServer connectionServer;
	private String serverUrl;
	private AgentSocket socket;
	private volatile boolean active = true;
	private final TaskLogger logger = new TaskLogger() {
		@Override
		public void log(String message, String sessionId) {
		}
	};

	@BeforeEach
	void setUp() throws Exception {
		systemServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		systemServer.createContext("/system/~api/worker/", exchange -> {
			try (exchange) {
				String path = exchange.getRequestURI().getPath();
				String method = exchange.getRequestMethod();
				requests.add(method + " " + path);
				exchange.getRequestBody().readAllBytes();
				byte[] body = new byte[0];
				if (path.endsWith("dependencies"))
					body = new byte[1024]; // Empty tar archive.
				else if (path.endsWith("-cache") && method.equals("GET"))
					body = new byte[] {(byte) CacheAvailability.NOT_FOUND.ordinal()};
				else if (path.endsWith("workspace-user-data") && method.equals("GET"))
					body = new byte[4]; // No saved user data.
				else if (path.endsWith("run-server-step")) {
					var result = SerializationUtils.serialize(new ServerStepResult(true));
					body = ByteBuffer.allocate(8 + result.length).putInt(0).putInt(result.length).put(result).array();
				} else if (path.endsWith("job-running") || path.endsWith("workspace-active")) {
					body = Boolean.toString(active).getBytes(UTF_8);
					exchange.getResponseHeaders().set("Content-Type", "application/json");
				}
				exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
				if (body.length != 0)
					exchange.getResponseBody().write(body);
			}
		});
		systemServer.start();
		serverUrl = "http://127.0.0.1:" + systemServer.getAddress().getPort() + "/system";
		connectionServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		connectionServer.createContext("/", exchange -> {
			try (exchange) {
				connectionRequests.add(exchange.getRequestURI().toString());
				exchange.sendResponseHeaders(503, -1);
			}
		});
		connectionServer.start();
		Agent.serverUrl = "http://127.0.0.1:" + connectionServer.getAddress().getPort();
		Agent.installDir = temp.toFile();
		Agent.attributes = Map.of();
		Agent.sslFactory = null;
		Agent.gitPath = executable("git");
		Agent.dockerPath = executable("docker");
		socket = new AgentSocket();
		var session = AgentSocket.class.getDeclaredField("session");
		session.setAccessible(true);
		session.set(socket, mock(Session.class));
	}

	@AfterEach
	void tearDown() {
		if (systemServer != null)
			systemServer.stop(0);
		if (connectionServer != null)
			connectionServer.stop(0);
		Agent.serverUrl = originalServerUrl;
		Agent.gitPath = originalGitPath;
		Agent.dockerPath = originalDockerPath;
		Agent.installDir = originalInstallDir;
		Agent.attributes = originalAttributes;
		Agent.sslFactory = originalSslFactory;
		assertEquals(List.of(), connectionRequests, "Work APIs must not use the agent connection URL");
	}

	private String executable(String name) throws Exception {
		var file = temp.resolve(name);
		Files.writeString(file, "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$0.calls\"\n"
				+ (name.equals("docker") ? "case \"$*\" in *curlimages/curl:*) printf 200 ;; esac\n" : "")
				+ "exit 0\n");
		assertTrue(file.toFile().setExecutable(true));
		return file.toString();
	}

	private List<String> calls(String executable) throws Exception {
		var file = temp.resolve(executable + ".calls");
		return Files.exists(file) ? Files.readAllLines(file) : List.of();
	}

	private Object service(Serializable request) throws Exception {
		var method = AgentSocket.class.getDeclaredMethod("service", Serializable.class);
		method.setAccessible(true);
		var result = method.invoke(socket, SerializationUtils.clone(request));
		if (result instanceof Throwable error)
			throw new AssertionError(error);
		return result;
	}

	private CacheConfigFacade cache() {
		return new CacheConfigFacade("cache", null, List.of(new CacheEntryFacade("cache", null)),
				UploadStrategy.UPLOAD_IF_NOT_EXACT_MATCH, null, null);
	}

	@SuppressWarnings("unchecked")
	private <T> T state(String name) throws Exception {
		var field = AgentSocket.class.getDeclaredField(name);
		field.setAccessible(true);
		return (T) field.get(null);
	}

	@Test
	void hostAndDockerConfigurationTestsUseTheSuppliedUrl() throws Exception {
		for (TestData data : List.of(new TestHostJobData("host-job"),
				new TestHostWorkspaceData("host-workspace", executable("tmux")),
				new TestDockerJobData("docker", "docker-job", "test-image", null, List.of(), null, null, null),
				new TestDockerWorkspaceData("docker", "docker-workspace", "test-image", null, List.of(), null, null, null))) {
			for (var command : List.of("git", "docker", "tmux"))
				Files.deleteIfExists(temp.resolve(command + ".calls"));
			data.setServerUrl(serverUrl);
			assertEquals(true, service(data));
			assertEquals(List.of(), calls("git"));
			assertEquals(data instanceof TestHostWorkspaceData ? List.of("-V") : List.of(), calls("tmux"));
			var dockerCalls = calls("docker");
			if (data instanceof TestDockerData) {
				assertEquals(data instanceof TestDockerWorkspaceData ? 3 : 2, dockerCalls.size());
				var containerCommand = dockerCalls.get(0);
				assertTrue(containerCommand.contains("test-image sh -c "));
				assertEquals(data instanceof TestDockerWorkspaceData, containerCommand.contains("git --version"));
				assertEquals(data instanceof TestDockerWorkspaceData, containerCommand.contains("git lfs version"));
				assertEquals(data instanceof TestDockerWorkspaceData, containerCommand.contains("tmux -V"));
				assertEquals("run --rm busybox sh -c echo hello from busybox", dockerCalls.get(1));
				if (data instanceof TestDockerWorkspaceData) {
					var connectionCommand = dockerCalls.get(2);
					assertTrue(connectionCommand.contains("curlimages/curl:"));
					assertTrue(connectionCommand.endsWith(serverUrl + "/~api/worker/test?token=" + data.getToken()));
				}
			} else {
				assertTrue(dockerCalls.isEmpty());
			}
		}
		assertEquals(java.util.Collections.nCopies(4, "GET /system/~api/worker/test"), requests);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void hostAndDockerJobsUseTheSuppliedUrlForDependenciesCachesAndServerSteps(boolean docker) throws Exception {
		var actions = List.of(new Action("cache", new SetupCacheFacade(cache()), ExecuteCondition.ALWAYS, false),
				new Action("server step", new ServerSideFacade(null, null, Set.of(), Set.of(), List.of()), ExecuteCondition.ALWAYS, false));
		var masker = SecretMasker.create(List.of(), "***");
		var settings = new JobDockerSettings(false, null, null, null, null, List.of(), false, null, null, false, false, false);
		Serializable data = docker
				? new DockerJobData(serverUrl, "job", "docker", "project", 1L, "refs/heads/main", "commit", 1L, 1L,
						actions, masker, List.of(), settings)
				: new HostJobData(serverUrl, "job", "host", "project", 1L, "refs/heads/main", "commit", 1L, 1L,
						actions, masker);
		assertEquals(true, service(data));
		assertEquals(List.of("GET /system/~api/worker/dependencies", "GET /system/~api/worker/job-cache",
				"POST /system/~api/worker/run-server-step",
				"HEAD /system/~api/worker/job-cache", "POST /system/~api/worker/job-cache"), requests);
	}

	@Test
	void hostWorkspaceUsesTheSuppliedUrlForCacheAndScriptEnvironment() throws Exception {
		var workspace = Files.createDirectories(temp.resolve("work/workspaces/1/1"));
		Files.createDirectories(workspace.resolve("work/.git"));
		Files.createFile(workspace.resolve(WorkspaceHelper.GIT_CLONE_SUCCESSFUL_FILE));
		var git = new GitSettings("Test", "test@example.com", new DefaultCloneInfo(serverUrl + "/project.git", "workspace"),
				"commit", "main", false, false);
		var script = new ScriptConfig("printf '%s' \"$ONEDEV_SERVER_URL\" > server-url", null, ".sh", "sh", new String[0]);
		var data = new ProvisionHostWorkspaceData("workspace", git, List.of(cache()), Map.of(), script, 1L, 1L, serverUrl);
		try {
			service(data);
			assertEquals(serverUrl, Files.readString(workspace.resolve("work/server-url")));
			assertEquals(List.of("GET /system/~api/worker/workspace-cache"), requests);
		} finally {
			var stop = AgentSocket.class.getDeclaredMethod("stopWorkspace", String.class);
			stop.setAccessible(true);
			stop.invoke(socket, "workspace");
		}
	}

	@Test
	void workspaceUserDataTransfersUseTheSuppliedUrl() throws Exception {
		var factory = AgentSocket.class.getDeclaredMethod("newUserDataProvisioner", String.class, String.class, List.class);
		factory.setAccessible(true);
		var provisioner = (UserDataProvisioner) factory.invoke(socket, serverUrl, "workspace",
				List.of(new UserDataFacade("user-data", List.of(new UserDataEntryFacade("data", null)))));
		provisioner.download(temp.toFile(), logger);
		var directory = Files.createDirectories(provisioner.getPathFile(temp.toFile(), "user-data", "data").toPath());
		var data = Files.writeString(directory.resolve("file"), "saved");
		Files.setLastModifiedTime(data, FileTime.fromMillis(System.currentTimeMillis() + 2000));
		provisioner.upload(temp.toFile(), logger);
		assertEquals(List.of("GET /system/~api/worker/workspace-user-data", "POST /system/~api/worker/workspace-user-data",
				"PUT /system/~api/worker/workspace-user-data"), requests);
	}

	@Test
	void housekeepingUsesEachWorkItemsUrlIncludingOpenShells() throws Exception {
		Map<String, AgentSocket.RunningJob> jobs = state("jobThreads");
		Map<String, AgentSocket.WorkspaceTask<?>> provisioning = state("workspaceProvisionTasks");
		Map<String, AgentSocket.WorkspaceTask<?>> serving = state("workspaceServeTasks");
		Map<String, JobShellSession> jobShells = state("jobShellSessions");
		Map<String, WorkspaceShellSession> workspaceShells = state("workspaceShellSessions");
		var jobThread = new Thread();
		var provisionTask = new AgentSocket.WorkspaceTask<>(serverUrl, () -> null);
		var serveTask = new AgentSocket.WorkspaceTask<>(serverUrl, () -> null);
		var jobShell = mock(JobShellSession.class);
		when(jobShell.getJobToken()).thenReturn("job-shell");
		when(jobShell.getServerUrl()).thenReturn(serverUrl);
		var workspaceShell = mock(WorkspaceShellSession.class);
		when(workspaceShell.getWorkspaceToken()).thenReturn("workspace-shell");
		when(workspaceShell.getServerUrl()).thenReturn(serverUrl);
		jobs.put("job", new AgentSocket.RunningJob(jobThread, serverUrl));
		provisioning.put("provision", provisionTask);
		serving.put("serve", serveTask);
		jobShells.put("shell", jobShell);
		workspaceShells.put("shell", workspaceShell);
		try {
			AgentSocket.houseKeeper();
			assertEquals(5, requests.size());
			assertFalse(jobThread.isInterrupted());
			assertFalse(provisionTask.isCancelled());
			assertFalse(serveTask.isCancelled());
			verify(jobShell, never()).exit();
			verify(workspaceShell, never()).exit();
			active = false;
			AgentSocket.houseKeeper();
			assertTrue(jobThread.isInterrupted());
			assertTrue(provisionTask.isCancelled());
			assertTrue(serveTask.isCancelled());
			verify(jobShell).exit();
			verify(workspaceShell).exit();
		} finally {
			jobs.remove("job");
			provisioning.remove("provision");
			serving.remove("serve");
			jobShells.remove("shell");
			workspaceShells.remove("shell");
		}
	}
}
