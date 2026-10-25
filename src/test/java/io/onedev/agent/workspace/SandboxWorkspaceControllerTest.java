package io.onedev.agent.workspace;

import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import io.onedev.agent.Agent;
import io.onedev.agent.AgentSocket;
import io.onedev.agent.SandboxSettings;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.k8shelper.ScriptConfig;

@EnabledOnOs({OS.LINUX, OS.MAC})
class SandboxWorkspaceControllerTest {

	@TempDir
	Path temp;

	private final TaskLogger logger = new TaskLogger() {
		@Override
		public void log(String message, String sessionId) {
		}
	};

	private SandboxWorkspaceController controller(String init, List<Integer> ports) throws Exception {
		var executable = temp.resolve("sbx");
		Files.writeString(executable, """
				#!/bin/sh
				cd -- "$(dirname -- "$0")"
				printf '%%s\\n' "$*" >> calls
				case "$1" in
				ls) [ ! -f exists ] || echo workspace-1-2 ;;
				create)
				  for arg do kit="$arg"; done
				  cat "$kit/spec.yaml" > kit.json || exit 98
				  printf '%%s\\n' "$kit" > kit-path
				  touch exists ;;
				env)
				  [ "$2" = create ] || exit 97
				  for arg do environment="$arg"; done
				  kit="$(dirname -- "$environment")"
				  cat "$environment" > environment.json || exit 98
				  cat "$kit/spec.yaml" > kit.json || exit 98
				  printf '%%s\\n' "$kit" > kit-path
				  echo 32000 > host-port
				  touch exists ;;
				stop) touch stopped ;;
				rm) rm exists ;;
				ports)
				  if [ -f environment.json ]; then
				    printf '[{"host_ip":"0.0.0.0","sandbox_port":8080,"host_port":%%s,"protocol":"tcp4"}]\\n' "$(cat host-port)"
				  else
				    echo '[]'
				  fi ;;
				exec)
				  case "$*" in
				    *' id -u') echo 1234 ;;
				    *' id -g') echo 5678 ;;
				    *' tar -xf '*) cat > configuration.tar ;;
				    *'mkdir -p '*) rm -f running shutdown ;;
				    *'test -f '*) [ ! -f running ] || echo ready ;;
				    *'touch /onedev-workspace/.shutdown'*) touch shutdown ;;
				    *'python3 -u '*) %s; touch running; while [ ! -f shutdown ]; do sleep 0.05; done; touch uploaded ;;
				    *' git '*) printf 'git-result' ;;
				    *' cat -- '*) printf 'file-result' ;;
				  esac ;;
				esac
				exit 0
				""".formatted(init));
		assertTrue(executable.toFile().setExecutable(true));
		var settings = new SandboxSettings("template", 2, "2g", true, false,
				"http://server", null, null, List.of());
		var data = new ProvisionSandboxWorkspaceData("token", SandboxWorkspaceController.getSandboxName(1L, 2L), settings, null,
				List.of(), List.of(), List.of(),
				new ScriptConfig("echo setup", "echo teardown", ".sh", "sh", new String[0]),
				Map.of("CUSTOM", "a b"), ports);
		return new SandboxWorkspaceController(executable.toString(), data, logger);
	}

	@Test
	void provisionsMountlessSandboxAndKeepsItForRestart() throws Exception {
		var controller = controller("true", List.of(8080));
		try {
			var provisioned = controller.provisionWorkspace(null, "agent-host");
			assertEquals(Map.of(8080, 32000), provisioned.getPortMappings());
			assertEquals("agent-host", provisioned.getPortHost());
			assertEquals("git-result", new String(controller.executeGit(new String[] {"status"}).getStdout(), UTF_8));
			assertNotNull(controller.readFile("file with spaces;$(touch injected)"));
			var shell = controller.openShell("shell", "sh");
			assertEquals(temp.resolve("sbx").toString(), shell.executable());
			assertTrue(shell.args().contains("--shell"));
			assertFalse(shell.args().contains("--user"));
			controller.closeShell("shell");
		} finally {
			controller.stopSandbox();
		}
		var calls = Files.readString(temp.resolve("calls"));
		controller.closeShell("shell");
		assertEquals(calls, Files.readString(temp.resolve("calls")));
		assertThrows(RuntimeException.class, () -> controller.readFile("README"));
		assertTrue(Files.exists(temp.resolve("uploaded")));
		assertTrue(Files.exists(temp.resolve("stopped")));
		assertTrue(Files.exists(temp.resolve("exists")));
		assertFalse(Files.exists(temp.resolve("injected")));
		var kitPath = Path.of(Files.readString(temp.resolve("kit-path")).trim());
		assertTrue(calls.contains("env create --force " + kitPath.resolve("sbxenv.yaml")));
		assertFalse(Files.exists(kitPath));
		var environment = new ObjectMapper().readTree(temp.resolve("environment.json").toFile());
		assertEquals("workspace-1-2", environment.path("name").asText());
		assertEquals("onedev", environment.path("agent").asText());
		assertEquals(kitPath.toString(), environment.path("kits").get(0).asText());
		assertFalse(environment.has("workspace"));
		var options = environment.path("sandboxOptions");
		assertEquals("always", options.path("pullPolicy").asText());
		assertEquals("off", options.path("skills").asText());
		assertEquals(2, options.path("cpus").asInt());
		assertEquals("2g", options.path("memory").asText());
		var port = environment.path("ports").get(0);
		assertEquals(8080, port.path("sandbox").asInt());
		assertEquals("0.0.0.0", port.path("hostIP").asText());
		assertEquals("tcp4", port.path("protocol").asText());
		assertFalse(port.has("host"));
		var kit = new ObjectMapper().readTree(temp.resolve("kit.json").toFile());
		assertEquals("template", kit.path("sandbox").path("image").asText());
		assertFalse(kit.has("extends"));
		assertFalse(kit.has("setup"));
		assertFalse(calls.contains("docker"));
		assertTrue(calls.contains("python3 -u /onedev-workspace/sandbox-workspace.py"));
		var privilegedCalls = calls.lines().filter(it -> it.startsWith("exec --user root ")).toList();
		assertEquals(1, privilegedCalls.size());
		assertTrue(privilegedCalls.get(0).contains("chown -R -h -P"));
		assertTrue(privilegedCalls.get(0).endsWith("sh /onedev-workspace 1234:5678"));
		assertFalse(calls.contains(temp.toString()));
		assertTrue(calls.indexOf("touch /onedev-workspace/.shutdown") < calls.indexOf("\nstop workspace-1-2"));
		var extracted = Files.createTempDirectory(temp, "configuration");
		try (var input = Files.newInputStream(temp.resolve("configuration.tar"))) {
			TarUtils.untar(input, extracted.toFile(), false);
		}
		// The workspace payload must load independently of the job script and source tree.
		new Commandline(System.getProperty("onedev.python.executable", "python3"))
				.workingDir(extracted.toFile())
				.addArgs("-I", "-B", "-c", "import runpy, sys; sys.path.insert(0, '.'); runpy.run_path('sandbox-workspace.py')")
				.timeout(10)
				.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
		Files.writeString(temp.resolve("host-port"), "32001");
		var resumed = controller("true", List.of(9090));
		try {
			assertEquals(Map.of(8080, 32001), resumed.provisionWorkspace(null, "agent-host").getPortMappings());
		} finally {
			resumed.stopSandbox();
		}
		calls = Files.readString(temp.resolve("calls"));
		assertEquals(1, calls.lines().filter(it -> it.startsWith("env create ")).count());
		assertEquals(2, calls.lines().filter(it -> it.equals(
				"policy allow network --sandbox workspace-1-2 --protocol tcp,udp **")).count());
		assertFalse(calls.contains("--publish"));
		assertFalse(calls.contains("--unpublish"));
		SandboxWorkspaceController.deleteSandbox(temp.resolve("sbx").toString(), "workspace-1-2", logger);
		assertFalse(Files.exists(temp.resolve("exists")));
	}

	@Test
	void serializedRequestsUseSandboxAndDeleteItAfterSessionEnds() throws Exception {
		var field = AgentSocket.class.getDeclaredField("sandboxWorkspaces");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		var sandboxes = (Map<String, SandboxWorkspaceController>) field.get(null);
		var service = AgentSocket.class.getDeclaredMethod("service", Serializable.class);
		service.setAccessible(true);
		var socket = new AgentSocket();
		var previousSbxPath = Agent.sbxPath;
		var previousInstallDir = Agent.installDir;
		var controller = controller("true", List.of());
		try {
			Agent.sbxPath = temp.resolve("sbx").toString();
			Agent.installDir = temp.toFile();
			var hostFile = temp.resolve("work/workspaces/1/2/work/file");
			Files.createDirectories(hostFile.getParent());
			Files.writeString(hostFile, "host content");
			controller.provisionWorkspace(null, "agent-host");
			sandboxes.put("token", controller);
			var gitRequest = new WorkspaceGitCommandRequest(ProvisionMode.SANDBOX, null, "token", 1L, 2L,
					null, new String[] {"status"});
			var git = (GitExecutionResult) service.invoke(socket, SerializationUtils.clone(gitRequest));
			assertEquals(0, git.getReturnCode());
			assertEquals("git-result", new String(git.getStdout(), UTF_8));
			var fileRequest = new WorkspaceFileDataRequest(ProvisionMode.SANDBOX, null, "token", 1L, 2L, null, "file");
			var file = (FileData) service.invoke(socket, SerializationUtils.clone(fileRequest));
			assertEquals("file-result", new String(file.getContent(), UTF_8));
			controller.stopSandbox();
			sandboxes.remove("token");
			assertStoppedWorkspaceRequests(socket, service, gitRequest, fileRequest);
			assertTrue(Files.exists(temp.resolve("exists")));
			var deleteRequest = new WorkspaceDeleteRequest(ProvisionMode.SANDBOX, 1L, 2L);
			assertNull(service.invoke(socket, SerializationUtils.clone(deleteRequest)));
			assertFalse(Files.exists(temp.resolve("exists")));
			assertEquals("host content", Files.readString(hostFile));
			assertNull(service.invoke(socket, SerializationUtils.clone(deleteRequest)));
		} finally {
			sandboxes.remove("token");
			try {
				controller.stopSandbox();
			} finally {
				Agent.sbxPath = previousSbxPath;
				Agent.installDir = previousInstallDir;
			}
		}
	}

	private void assertStoppedWorkspaceRequests(AgentSocket socket, Method service, Serializable... requests) throws Exception {
		var testThread = Thread.currentThread();
		var context = (LoggerContext) LoggerFactory.getILoggerFactory();
		var filter = new TurboFilter() {
			@Override
			public FilterReply decide(Marker marker, Logger logger, Level level, String message,
					Object[] arguments, Throwable throwable) {
				// These requests deliberately access a stopped workspace. Keep all other errors visible.
				if (Thread.currentThread() == testThread && logger.getName().equals(AgentSocket.class.getName())
						&& level == Level.ERROR && "Error handling websocket request".equals(message)
						&& throwable instanceof ExplicitException
						&& "Sandbox workspace not running".equals(throwable.getMessage()))
					return FilterReply.DENY;
				return FilterReply.NEUTRAL;
			}
		};
		filter.start();
		context.addTurboFilter(filter);
		try {
			for (var request : requests) {
				var failure = assertInstanceOf(ExplicitException.class, service.invoke(socket, SerializationUtils.clone(request)));
				assertEquals("Sandbox workspace not running", failure.getMessage());
			}
		} finally {
			context.getTurboFilterList().remove(filter);
			filter.stop();
		}
	}

	@Test
	void failedInitializationStopsSandboxWithoutDiscardingFiles() throws Exception {
		var workspace = controller("exit 7", List.of());
		assertThrows(RuntimeException.class, () -> workspace.provisionWorkspace(null, "agent-host"));
		assertTrue(Files.exists(temp.resolve("stopped")));
		assertTrue(Files.exists(temp.resolve("exists")));
		assertFalse(Files.exists(temp.resolve("uploaded")));
	}

	@Test
	void cancellingWorkspaceRequestsTeardownBeforeStoppingSandbox() throws Exception {
		var controller = controller("true", List.of());
		controller.provisionWorkspace(null, "agent-host");
		var failure = new AtomicReference<Throwable>();
		var thread = new Thread(() -> {
			try {
				controller.awaitRunner();
			} catch (Throwable e) {
				failure.set(e);
			}
		});
		thread.start();
		try {
			assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
				while (!Files.exists(temp.resolve("running")))
					Thread.sleep(20);
			});
		} finally {
			thread.interrupt();
			thread.join(15000);
		}
		assertFalse(thread.isAlive());
		assertNotNull(failure.get());
		assertTrue(Files.exists(temp.resolve("uploaded")));
		assertTrue(Files.exists(temp.resolve("stopped")));
	}
}
