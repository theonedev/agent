package io.onedev.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.onedev.commons.utils.TaskLogger;

@EnabledOnOs({OS.LINUX, OS.MAC})
class SandboxUtilsTest {

	@TempDir
	Path temp;

	private final List<String> messages = new ArrayList<>();

	private final TaskLogger logger = new TaskLogger() {
		@Override
		public void log(String message, String sessionId) {
			messages.add(message);
		}
	};

	private String executable(int exitCode, String diagnostics, List<?> entries) throws Exception {
		new ObjectMapper().writeValue(temp.resolve("entries.json").toFile(), entries);
		var executable = temp.resolve("sbx");
		Files.writeString(executable, """
				#!/usr/bin/env python3
				import datetime, json, pathlib, sys
				root = pathlib.Path(__file__).parent
				args = sys.argv[1:]
				with (root / 'calls').open('a') as calls:
				    calls.write(' '.join(args) + '\\n')
				if args[0] in ('create', 'env'):
				    kit = pathlib.Path(args[-1])
				    if args[0] == 'env':
				        kit = kit.parent
				    (root / 'kit-path').write_text(str(kit))
				    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
				    with (root / 'daemon.log').open('w') as log:
				        for entry in json.loads((root / 'entries.json').read_text()):
				            if isinstance(entry, dict):
				                if entry.get('time') == 'current':
				                    entry['time'] = now
				                entry = json.dumps(entry, ensure_ascii=False)
				            log.write(entry + '\\n')
				    sys.exit(%d)
				if args == ['daemon', 'status', '--format', 'json']:
				    mode = '%s'
				    if mode == 'unsupported':
				        sys.exit(2)
				    if mode == 'malformed':
				        print('not JSON')
				    else:
				        print(json.dumps({'logs': str(root / ('missing.log' if mode == 'missing' else 'daemon.log'))}))
				""".formatted(exitCode, diagnostics));
		assertTrue(executable.toFile().setExecutable(true));
		return executable.toString();
	}

	private Map<String, String> error(String name, String time, String error) {
		return Map.of("runtime", name, "time", time, "level", "ERROR", "msg", "create sandbox failed", "error", error,
				"unrelatedMetadata", "not for the build log");
	}

	private void create(String executable, List<Integer> ports) throws Exception {
		SandboxUtils.createSandbox(executable, "build-test", new SandboxSettings(null, null, null, true, true,
				"http://server", null, null, List.of()), ports, logger);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void reportsOnlyErrorsFromThisSandboxAndCreationAttempt(boolean withPorts) throws Exception {
		var cause = "no match for platform in manifest: 测试";
		var entry = error("build-test", "current", cause);
		var executable = executable(7, "available", List.of(
				error("build-test", "2000-01-01T00:00:00Z", "previous attempt"),
				error("another-build", "current", "another project's error"),
				error("build-test", "2999-01-01T00:00:00Z", "later attempt"),
				error("build-test", "invalid timestamp", "invalid record"),
				"not JSON", entry, entry, "{\"incomplete\":"));
		var failure = assertThrows(RuntimeException.class, () -> create(executable, withPorts ? List.of(8080) : List.of()));
		assertTrue(failure.getMessage().contains("exit code: 7"));
		assertEquals(List.of(TaskLogger.wrapWithAnsiError("Sandbox daemon: create sandbox failed: " + cause)), messages);
		assertFalse(Files.exists(Path.of(Files.readString(temp.resolve("kit-path")))));
	}

	@ParameterizedTest
	@ValueSource(strings = {"unsupported", "malformed", "missing"})
	void preservesCreationFailureWhenDiagnosticsAreUnavailable(String diagnostics) throws Exception {
		var executable = executable(7, diagnostics, List.of());
		var failure = assertThrows(RuntimeException.class, () -> create(executable, List.of()));
		assertTrue(failure.getMessage().contains("exit code: 7"));
		assertEquals(1, messages.size());
		assertTrue(messages.get(0).contains("Unable to read sandbox daemon errors"));
		assertFalse(Files.exists(Path.of(Files.readString(temp.resolve("kit-path")))));
	}

	@Test
	void boundsLogReadingAndNumberOfReportedErrors() throws Exception {
		var entries = new ArrayList<Object>();
		entries.add("x".repeat(1024 * 1024 + 100));
		for (var i = 0; i < 25; i++)
			entries.add(error("build-test", "current", "cause " + i));
		var executable = executable(7, "available", entries);
		assertThrows(RuntimeException.class, () -> create(executable, List.of()));
		assertEquals(20, messages.size());
		assertEquals(TaskLogger.wrapWithAnsiError("Sandbox daemon: create sandbox failed: cause 5"), messages.get(0));
		assertEquals(TaskLogger.wrapWithAnsiError("Sandbox daemon: create sandbox failed: cause 24"), messages.get(19));
	}

	@Test
	void successfulCreationDoesNotReadDaemonLogs() throws Exception {
		create(executable(0, "available", List.of()), List.of());
		assertEquals(1, Files.readAllLines(temp.resolve("calls")).size());
		assertTrue(messages.isEmpty());
	}
}
