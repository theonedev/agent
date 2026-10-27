package io.onedev.agent.job;

import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static org.junit.jupiter.api.Assertions.*;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.onedev.agent.SandboxSettings;
import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.commons.utils.TarUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.k8shelper.Action;
import io.onedev.k8shelper.BuildImageFacade;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.ExecuteCondition;
import io.onedev.k8shelper.PruneBuilderCacheFacade;
import io.onedev.k8shelper.RegistryLoginFacade;
import io.onedev.k8shelper.RunContainerFacade;
import io.onedev.k8shelper.RunImagetoolsFacade;
import io.onedev.k8shelper.ServiceFacade;

@EnabledOnOs({OS.LINUX, OS.MAC})
class SandboxJobControllerTest {

	@TempDir
	Path temp;

	private final TaskLogger logger = new TaskLogger() {
		@Override
		public void log(String message, String sessionId) {
		}
	};

	private SandboxJobController sandbox(String create, String execute) throws Exception {
		return sandbox(create, "cat > payload.tar", execute);
	}

	private SandboxJobController sandbox(String create, String prepare, String execute) throws Exception {
		var executable = temp.resolve("sbx");
		Files.writeString(executable, """
				#!/bin/sh
				cd -- "$(dirname -- "$0")"
				printf '%%s\\n' "$*" >> calls
				case "$1" in
				create)
				  for arg do kit="$arg"; done
				  cat "$kit/spec.yaml" > kit.json || exit 98
				  printf '%%s\\n' "$kit" > kit-path
				  printf '%%s\\n' "$3" > sandbox-name
				  ( %s ) && touch exists ;;
				exec)
				  shift
				  case "$*" in
				    *' id -u') echo 1234; exit 0 ;;
				    *' id -g') echo 5678; exit 0 ;;
				  esac
				  if [ "$1" = --user ]; then
				    [ "$2" = root ] && [ "$4" = sh ] && [ "$5" = -c ] || exit 97
				    [ "$8" = /onedev-build ] && [ "$9" = 1234:5678 ] || exit 97
				    touch initialized; exit 0
				  fi
				  [ -f initialized ] || exit 97
				  if [ "$1" = -i ]; then %s; else
				    case "$*" in *'/continue') touch resumed ;; *) %s ;; esac
				  fi ;;
				stop) touch stopped ;;
				ls)
				  printf '%%s-other\\n' "$(cat sandbox-name)"
				  if [ -f exists ]; then cat sandbox-name; fi ;;
				rm) [ -f exists ] || exit 1; rm exists ;;
				esac
				""".formatted(create, prepare, execute));
		executable.toFile().setExecutable(true);
		return new SandboxJobController(executable.toString(), new SandboxSettings("custom-template", 2, "2g", true, true,
				"http://configured-server", null, "--subnet 172.28.0.0/16", List.of()));
	}

	@Test
	void createsMountlessKitWithoutStartupHooksAndDeletesAfterSuccess() throws Exception {
		var sandbox = sandbox("exit 0", "exit 0");
		var services = List.of(new ServiceFacade("database", "postgres:17", "999:999", "-c 'max_connections=50'",
				Map.of("POSTGRES_PASSWORD", "secret"), "pg_isready",
				List.of(new RegistryLoginFacade("registry.example", "user", "password"))));
		var remoteData = org.apache.commons.lang3.SerializationUtils.clone(new SandboxJobData("token", "sandbox",
				"project", 12L, "refs/heads/main", "commit", 34L, 2L, List.of(), SecretMasker.create(List.of(), "***"),
				services, new SandboxSettings(null, null, null, true, true, "http://server", null, null, List.of())));
		assertTrue(sandbox.runJob(remoteData, remoteData.getServices(), null, Map.of(), logger));
		var calls = Files.readAllLines(temp.resolve("calls"));
		assertEquals(9, calls.size());
		var kitPath = Path.of(Files.readString(temp.resolve("kit-path")).trim());
		assertEquals("create --name build-sandbox-12-34-2 --pull always --skills off --cpus 2 --memory 2g " + kitPath, calls.get(0));
		assertFalse(Files.exists(kitPath));
		var kit = new ObjectMapper().readTree(temp.resolve("kit.json").toFile());
		assertEquals("2", kit.path("schemaVersion").asText());
		assertEquals("sandbox", kit.path("kind").asText());
		assertEquals("custom-template", kit.path("sandbox").path("image").asText());
		assertEquals("sh", kit.path("sandbox").path("entrypoint").get(0).asText());
		assertEquals(new ObjectMapper().createArrayNode(), kit.path("sandbox").path("command"));
		assertFalse(kit.has("extends"));
		assertFalse(kit.has("setup"));
		assertEquals("policy allow network --sandbox build-sandbox-12-34-2 --protocol tcp,udp **", calls.get(1));
		assertEquals("exec build-sandbox-12-34-2 id -u", calls.get(2));
		assertEquals("exec build-sandbox-12-34-2 id -g", calls.get(3));
		assertTrue(calls.get(4).startsWith("exec --user root build-sandbox-12-34-2 sh -c mkdir -p "));
		assertTrue(calls.get(4).contains("chown -R -h -P"));
		assertTrue(calls.get(4).endsWith("sh /onedev-build 1234:5678"));
		assertEquals("exec -i build-sandbox-12-34-2 tar -xf - -C /onedev-build", calls.get(5));
		assertEquals("exec build-sandbox-12-34-2 python3 -u /onedev-build/sandbox-job.py /onedev-build", calls.get(6));
		assertEquals("ls --quiet", calls.get(7));
		assertEquals("rm --force build-sandbox-12-34-2", calls.get(8));
		assertFalse(calls.stream().anyMatch(it -> it.contains("sudo")));
		assertTrue(Files.size(temp.resolve("payload.tar")) > 0);
		var extracted = Files.createTempDirectory(temp, "manifest");
		try (var input = new FileInputStream(temp.resolve("payload.tar").toFile())) {
			TarUtils.untar(input, extracted.toFile(), false);
		}
		// Load from the actual payload, with no source tree or PYTHONPATH available.
		new Commandline(System.getProperty("onedev.python.executable", "python3"))
				.workingDir(extracted.toFile())
				.addArgs("-I", "-B", "-c", "import runpy, sys; sys.path.insert(0, '.'); runpy.run_path('sandbox-job.py')")
				.timeout(10)
				.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
		var manifest = new ObjectMapper().readTree(extracted.resolve("job.json").toFile());
		assertFalse(manifest.has("cpuLimit"));
		assertFalse(manifest.has("memoryLimit"));
		assertEquals("--subnet 172.28.0.0/16", manifest.get("networkOptions").asText());
		var service = manifest.get("services").get(0);
		assertEquals("database", service.get("name").asText());
		assertEquals("postgres:17", service.get("image").asText());
		assertEquals("999:999", service.get("runAs").asText());
		assertEquals("-c 'max_connections=50'", service.get("arguments").asText());
		assertEquals("secret", service.get("envs").get("POSTGRES_PASSWORD").asText());
		assertEquals("pg_isready", service.get("readinessCheckCommand").asText());
		assertEquals("password", service.get("registryLogins").get(0).get("password").asText());
		assertThrows(RuntimeException.class, sandbox::openShell);
	}

	@Test
	void executorTestIncludesOptionalImageContainerSettingsAndServerConnection() throws Exception {
		var sandbox = sandbox("exit 0", "exit 0");
		var trustCerts = Files.createDirectory(temp.resolve("trust-certs"));
		Files.writeString(trustCerts.resolve("server.pem"), "test certificate");
		for (var image : new String[] {null, "alpine"}) {
			sandbox.testSandbox(image, trustCerts.toFile(), logger);
			var extracted = Files.createTempDirectory(temp, "manifest");
			try (var input = new FileInputStream(temp.resolve("payload.tar").toFile())) {
				TarUtils.untar(input, extracted.toFile(), false);
			}
			var manifest = new ObjectMapper().readTree(extracted.resolve("job.json").toFile());
			assertTrue(manifest.get("test").asBoolean());
			assertEquals(image, manifest.get("testImage").isNull() ? null : manifest.get("testImage").asText());
			assertEquals("http://configured-server", manifest.get("serverUrl").asText());
			assertFalse(manifest.get("token").asText().isBlank());
			assertTrue(manifest.get("alwaysPullContainerImage").asBoolean());
			assertFalse(manifest.has("cpuLimit"));
			assertFalse(manifest.has("memoryLimit"));
			assertTrue(manifest.get("registryLogins").isArray());
			assertEquals("test certificate", Files.readString(extracted.resolve("trust-certs/server.pem")));
		}
	}

	@Test
	void skipsDeletionWhenCreationFailsBeforeSandboxExists() throws Exception {
		var messages = new java.util.ArrayList<String>();
		var failure = assertThrows(RuntimeException.class, () -> sandbox("exit 4", "exit 0").testSandbox(null, null, new TaskLogger() {
			@Override
			public void log(String message, String sessionId) {
				messages.add(message);
			}
		}));
		var calls = Files.readAllLines(temp.resolve("calls"));
		assertEquals(3, calls.size());
		assertTrue(calls.get(0).startsWith("create --name sandbox-test-"));
		assertEquals("daemon status --format json", calls.get(1));
		assertEquals("ls --quiet", calls.get(2));
		assertTrue(failure.getMessage().contains("exit code: 4"));
		assertFalse(Files.exists(Path.of(Files.readString(temp.resolve("kit-path")).trim())));
		assertEquals(0, failure.getSuppressed().length);
		assertFalse(messages.stream().anyMatch(it -> it.startsWith("Deleting sandbox") || it.startsWith("Unable to delete sandbox")));
	}

	@Test
	void deletesSandboxLeftByFailedCreation() throws Exception {
		var failure = assertThrows(RuntimeException.class, () -> sandbox("touch exists; exit 4", "exit 0").testSandbox(null, null, logger));
		var calls = Files.readAllLines(temp.resolve("calls"));
		assertEquals(4, calls.size());
		assertEquals("daemon status --format json", calls.get(1));
		assertEquals("ls --quiet", calls.get(2));
		assertTrue(calls.get(3).matches("rm --force sandbox-test-[a-f0-9]{8}"));
		assertTrue(failure.getMessage().contains("exit code: 4"));
		assertFalse(Files.exists(temp.resolve("exists")));
	}

	@Test
	void deletesAfterFailedPreparation() throws Exception {
		assertThrows(RuntimeException.class, () -> sandbox("exit 0", "cat > payload.tar; exit 6", "exit 0").testSandbox(null, null, logger));
		var calls = Files.readAllLines(temp.resolve("calls"));
		assertEquals(8, calls.size());
		assertEquals("ls --quiet", calls.get(6));
		assertTrue(calls.get(7).matches("rm --force sandbox-test-[a-f0-9]{8}"));
		assertFalse(Files.exists(temp.resolve("exists")));
	}

	@Test
	void deletesAfterFailedJob() throws Exception {
		assertThrows(RuntimeException.class, () -> sandbox("exit 0", "exit 5").testSandbox(null, null, logger));
		var calls = Files.readAllLines(temp.resolve("calls"));
		assertTrue(calls.get(calls.size() - 1).matches("rm --force sandbox-test-[a-f0-9]{8}"));
	}

	@Test
	void cancellationDeletesTheSandboxAndTerminalsStayInsideIt() throws Exception {
		var sandbox = sandbox("exit 0", "trap '' TERM; echo '##onedev-step:START:step-0'; "
				+ "touch running; while [ ! -f stopped ]; do sleep 0.1; done");
		var failure = new AtomicReference<Throwable>();
		var messages = new java.util.concurrent.CopyOnWriteArrayList<String>();
		var thread = new Thread(() -> {
			try {
				sandbox.testSandbox(null, null, new TaskLogger() {
					@Override
					public void log(String message, String sessionId) {
						messages.add(message);
					}
				});
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
			var shell = sandbox.openShell();
			assertEquals(temp.resolve("sbx").toString(), shell.executable());
			assertFalse(shell.envs().containsKey("SSH_AUTH_SOCK"));
			assertEquals(List.of("exec", "-it", "-e", "ONEDEV_WORKDIR=/onedev-build/work"),
					shell.args().subList(0, 4));
			assertEquals(List.of("python3", "-c"), shell.args().subList(5, 7));
			assertTrue(shell.args().get(7).contains("'sudo', '-n'"));
			assertEquals(List.of("/onedev-build/sandbox-job.py", "/onedev-build", "--shell"),
					shell.args().subList(8, 11));
			sandbox.resume(logger);
			assertTrue(Files.exists(temp.resolve("resumed")));
		} finally {
			thread.interrupt();
			thread.join(15000);
			if (thread.isAlive()) {
				Files.createFile(temp.resolve("stopped"));
				thread.join(5000);
				fail("Cancellation did not stop the sandbox");
			}
		}
		assertFalse(thread.isAlive());
		assertNotNull(failure.get());
		assertTrue(messages.contains("##onedev-step:CANCELLED:step-0"));
		var calls = Files.readAllLines(temp.resolve("calls"));
		assertEquals("stop " + Files.readString(temp.resolve("sandbox-name")).trim(), calls.get(calls.size() - 3));
		assertTrue(calls.get(calls.size() - 1).matches("rm --force sandbox-test-[a-f0-9]{8}"));
	}

	@Test
	void completedStepsAreNotReportedAsTransportFailures() throws Exception {
		var messages = new java.util.ArrayList<String>();
		sandbox("exit 0", "echo '##onedev-step:START:step-0'; echo '##onedev-step:SUCCESSFUL:step-0'")
				.testSandbox(null, null, new TaskLogger() {
					@Override
					public void log(String message, String sessionId) {
						messages.add(message);
					}
				});
		assertFalse(messages.stream().anyMatch(it -> it.startsWith("##onedev-step:FAILED:")));
	}

	@Test
	void stepCompletionDoesNotAddBlankLogLines() throws Exception {
		// Preserve the command's intentional blank line, but not protocol separators.
		assertEquals(List.of(
				"##onedev-step:START:step-0", "first", "", "##onedev-step:SUCCESSFUL:step-0",
				"##onedev-step:START:step-1", "second", "##onedev-step:SUCCESSFUL:step-1",
				"##onedev-phase:finalization"),
				runSandboxCommands("printf 'first\\n\\n'", "echo second"));
	}

	@Test
	void catWithoutTrailingNewlineKeepsStepOutcomeSeparate() throws Exception {
		assertEquals(List.of(
				"##onedev-step:START:step-0", "{\"name\":\"test\"}", "##onedev-step:SUCCESSFUL:step-0",
				"##onedev-phase:finalization"),
				runSandboxCommands("printf '%s' '{\"name\":\"test\"}' > package.json\ncat package.json"));
	}

	private List<String> runSandboxCommands(String... commands) throws Exception {
		var actions = java.util.Arrays.stream(commands).map(command -> new Action("command",
				new CommandFacade(null, null, List.of(), Map.of(), false, command), ExecuteCondition.SUCCESSFUL, false))
				.toList();
		new ObjectMapper().writeValue(temp.resolve("actions.json").toFile(), SandboxJobController.actions(actions));
		Files.writeString(temp.resolve("run-job.py"), """
				from contextlib import nullcontext
				import importlib.util
				import json
				from pathlib import Path
				import sys
				from unittest.mock import patch

				root = Path('runtime').resolve()
				sys.path.insert(0, str(root))
				spec = importlib.util.spec_from_file_location('sandbox_job', root / 'sandbox-job.py')
				module = importlib.util.module_from_spec(spec)
				spec.loader.exec_module(module)
				config = {'actions': json.loads(Path('actions.json').read_text())}
				runner = module.JobRunner(root, config)
				# Keep server transport out of this test; execute real commands and emit real markers.
				with patch.object(runner, 'prepare_dependencies'), \\
				        patch.object(runner, 'request', return_value=nullcontext()):
				    sys.exit(0 if runner.run() else 1)
				""");
		var python = System.getProperty("onedev.python.executable", "python3");
		var messages = new java.util.ArrayList<String>();
		sandbox("exit 0", "mkdir runtime && tar -xf payload.tar -C runtime && '"
				+ python.replace("'", "'\\''") + "' -I -B run-job.py")
				.testSandbox(null, null, new TaskLogger() {
					@Override
					public void log(String message, String sessionId) {
						messages.add(message);
					}
				});
		var start = messages.indexOf("##onedev-step:START:step-0");
		var end = messages.indexOf("##onedev-phase:finalization");
		assertTrue(start >= 0 && end > start, messages.toString());
		return messages.subList(start, end + 1);
	}

	@Test
	void manifestPreservesCompositeConditionsAndCommandArguments() {
		var command = new CommandFacade(null, null, List.of(), Map.of("VALUE", "a'b\nnext"), false, "echo $VALUE");
		var nested = new Action("command", command, ExecuteCondition.ALWAYS, true);
		var composite = new Action("group", new CompositeFacade(List.of(nested)), ExecuteCondition.NEVER, false);
		var manifest = SandboxJobController.actions(List.of(composite));
		assertEquals("NEVER", manifest.get(0).get("condition"));
		@SuppressWarnings("unchecked")
		var children = (List<Map<String, Object>>) manifest.get(0).get("actions");
		assertEquals("ALWAYS", children.get(0).get("condition"));
		assertEquals(true, children.get(0).get("optional"));
		assertEquals(Map.of("VALUE", "a'b\nnext"), children.get(0).get("env"));
	}

	@Test
	void manifestSerializesDockerStepsAndOutputSettings() throws Exception {
		var logins = List.of(new RegistryLoginFacade("registry.example", "user", "password"));
		var steps = List.of(
				new CommandFacade("alpine", "123:456", logins, Map.of("ENV", "value"), true, "echo test"),
				new RunContainerFacade("tool", "0:0", "--arg 'a b'", Map.of(), "/work", Map.of(".", "/input"), logins, false),
				new BuildImageFacade("context", "Dockerfile.other", new BuildImageFacade.RegistryOutput("image:tag"), logins, "linux/arm64", "--secret id=test,src=/tmp/secret"),
				new BuildImageFacade(null, null, new BuildImageFacade.OCIOutput("image-layout"), List.of(), null, null),
				new RunImagetoolsFacade("create -f /tmp/manifest.json", logins),
				new PruneBuilderCacheFacade("--filter 'until=24h'"));
		var actions = steps.stream().map(step -> new Action("step", step, ExecuteCondition.SUCCESSFUL, false)).toList();
		var mapper = new ObjectMapper();
		var manifest = mapper.readTree(mapper.writeValueAsBytes(SandboxJobController.actions(actions)));
		assertEquals("command", manifest.get(0).get("type").asText());
		assertEquals("123:456", manifest.get(0).get("runAs").asText());
		assertTrue(manifest.get(0).get("useTTY").asBoolean());
		assertEquals("password", manifest.get(0).get("registryLogins").get(0).get("password").asText());
		assertEquals("container", manifest.get(1).get("type").asText());
		assertEquals("/input", manifest.get(1).get("volumeMounts").get(".").asText());
		assertEquals("--arg 'a b'", manifest.get(1).get("args").asText());
		assertEquals("build-image", manifest.get(2).get("type").asText());
		assertEquals("image:tag", manifest.get(2).get("tags").asText());
		assertEquals("linux/arm64", manifest.get(2).get("platforms").asText());
		assertEquals("image-layout", manifest.get(3).get("destPath").asText());
		assertEquals("imagetools", manifest.get(4).get("type").asText());
		assertEquals("create -f /tmp/manifest.json", manifest.get(4).get("arguments").asText());
		assertEquals("prune-builder-cache", manifest.get(5).get("type").asText());
		assertEquals("--filter 'until=24h'", manifest.get(5).get("options").asText());
	}
}
