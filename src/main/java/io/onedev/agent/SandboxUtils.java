package io.onedev.agent;

import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

/** Host-side sandbox commands and payload packaging shared by jobs and workspaces. */
public class SandboxUtils {

	/** Package the job or workspace manifest, Python runtime, and installation certificates. */
	public static byte[] buildRuntimeArchive(String type, Map<String, Object> manifest,
			@Nullable File trustCertsDir, int fileMode) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var tar = new TarArchiveOutputStream(bytes)) {
			tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
			addTarEntry(tar, type + ".json", new ObjectMapper().writeValueAsBytes(manifest), fileMode);
			var runnerName = "sandbox-" + type + ".py";
			addRuntimeResource(tar, "sandbox.py", fileMode);
			addRuntimeResource(tar, "sandbox_files.py", fileMode);
			if (type.equals("job"))
				addRuntimeResource(tar, "sandbox_privileges.py", fileMode);
			addRuntimeResource(tar, type + "/" + runnerName, fileMode);
			if (trustCertsDir != null && trustCertsDir.exists()) {
				for (var file : FileUtils.listFiles(trustCertsDir, null, true)) {
					var relativePath = trustCertsDir.toPath().relativize(file.toPath()).toString()
							.replace(File.separatorChar, '/');
					addTarEntry(tar, "trust-certs/" + relativePath, Files.readAllBytes(file.toPath()), fileMode);
				}
			}
		}
		return bytes.toByteArray();
	}

	private static void addRuntimeResource(TarArchiveOutputStream tar, String path, int fileMode) throws IOException {
		try (var resource = SandboxUtils.class.getResourceAsStream(path)) {
			if (resource == null)
				throw new ExplicitException("Sandbox resource is missing: " + path);
			addTarEntry(tar, path.substring(path.lastIndexOf('/') + 1), resource.readAllBytes(), fileMode);
		}
	}

	private static void addTarEntry(TarArchiveOutputStream tar, String name, byte[] content, int fileMode)
			throws IOException {
		var entry = new TarArchiveEntry(name);
		entry.setSize(content.length);
		entry.setMode(fileMode);
		tar.putArchiveEntry(entry);
		tar.write(content);
		tar.closeArchiveEntry();
	}

	public static void createSandbox(String executable, String name, SandboxSettings settings, List<Integer> ports,
									 TaskLogger logger) throws IOException {
		var kitDir = FileUtils.createTempDir("onedev-sandbox-kit");
		try {
			// The built-in shell kit refreshes APT in the background on every start.
			// Own the kit so package operations only run when requested by the job/workspace.
			var image = settings.template() != null ? settings.template() : "docker/sandbox-templates:shell-docker";
			var spec = Map.of("schemaVersion", "2", "kind", "sandbox", "name", "onedev",
					"sandbox", Map.of("image", image, "entrypoint", List.of("bash"), "command", List.of("-l")));
			// JSON is valid YAML and quotes custom image references without interpolation.
			var mapper = new ObjectMapper();
			mapper.writeValue(new File(kitDir, "spec.yaml"), spec);
			Commandline command;
			if (ports.isEmpty()) {
				command = new Commandline(executable).addArgs("create", "--name", name, "--pull",
						settings.alwaysPullImage() ? "always" : "missing", "--skills", "off");
				if (settings.cpus() != null)
					command.addArgs("--cpus", settings.cpus().toString());
				if (settings.memory() != null)
					command.addArgs("--memory", settings.memory());
				command.addArgs(kitDir.getAbsolutePath());
			} else {
				// Environment files support automatic host ports on all interfaces; the CLI's
				// --publish shorthand only allocates automatic ports on loopback.
				var options = new HashMap<String, Object>();
				options.put("pullPolicy", settings.alwaysPullImage() ? "always" : "missing");
				options.put("skills", "off");
				if (settings.cpus() != null)
					options.put("cpus", settings.cpus());
				if (settings.memory() != null)
					options.put("memory", settings.memory());
				var environment = Map.of("schemaVersion", "1", "name", name, "agent", "onedev",
						"kits", List.of(kitDir.getAbsolutePath()), "sandboxOptions", options,
						"ports", ports.stream().map(port -> Map.of("sandbox", port, "hostIP", "0.0.0.0", "protocol", "tcp4")).toList());
				var environmentFile = new File(kitDir, "sbxenv.yaml");
				mapper.writeValue(environmentFile, environment);
				command = new Commandline(executable).addArgs("env", "create", "--force", environmentFile.getAbsolutePath());
			}
			var started = Instant.now();
			var result = command.execute(newInfoLogger(logger), newWarningLogger(logger));
			if (result.getReturnCode() != 0)
				logCreationErrors(executable, name, started, Instant.now(), logger);
			result.checkReturnCode();
			allowAllNetworkAccess(executable, name, logger);
		} finally {
			// sbx persists the kit configuration for subsequent starts.
			FileUtils.deletePath(kitDir);
		}
	}

	public static void allowAllNetworkAccess(String executable, String name, TaskLogger logger) {
		// Jobs and workspaces need arbitrary network access, including to their OneDev server.
		new Commandline(executable).addArgs("policy", "allow", "network", "--sandbox", name,
				"--protocol", "tcp,udp", "**").timeout(120)
				.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
	}

	private static void logCreationErrors(String executable, String name, Instant started, Instant finished,
			TaskLogger logger) {
		try {
			// sbx can hide the underlying daemon error even with --debug. Ask for the
			// log location instead of assuming an OS-specific or default state directory.
			var output = new ByteArrayOutputStream();
			new Commandline(executable).addArgs("daemon", "status", "--format", "json").timeout(10)
					.execute(output, null).checkReturnCode();
			var mapper = new ObjectMapper();
			var logPath = mapper.readTree(output.toByteArray()).path("logs").asText("");
			if (logPath.isBlank())
				return;
			String tail;
			try (var channel = Files.newByteChannel(Path.of(logPath))) {
				// Bound diagnostics even when the daemon has been running for a long time.
				var size = channel.size();
				var offset = Math.max(0, size - 1024 * 1024);
				channel.position(offset);
				var buffer = ByteBuffer.allocate((int) (size - offset));
				while (buffer.hasRemaining() && channel.read(buffer) != -1) {
				}
				buffer.flip();
				tail = UTF_8.decode(buffer).toString();
				if (offset != 0)
					tail = tail.substring(tail.indexOf('\n') + 1);
			}
			var errors = new LinkedHashSet<String>();
			for (var line : tail.split("\n")) {
				try {
					var entry = mapper.readTree(line);
					if (entry == null || !name.equals(entry.path("runtime").asText()))
						continue;
					var time = Instant.parse(entry.path("time").asText());
					var error = entry.path("error").asText("");
					if (!time.isBefore(started) && !time.isAfter(finished) && !error.isBlank()
							&& (entry.path("level").asText().equals("ERROR") || entry.path("level").asText().equals("WARN")))
						errors.add(entry.path("msg").asText() + ": " + error);
				} catch (JsonProcessingException | DateTimeParseException ignored) {
					// Ignore incomplete records and records from other log formats.
				}
			}
			for (var error : errors.stream().skip(Math.max(0, errors.size() - 20)).toList())
				logger.error("Sandbox daemon: " + error);
		} catch (IOException | RuntimeException e) {
			if (ExceptionUtils.find(e, InterruptedException.class) != null)
				Thread.currentThread().interrupt();
			// Diagnostics must not replace the original creation failure.
			logger.warning("Unable to read sandbox daemon errors. Run '" + executable
					+ " daemon status' on the executor host to locate its logs.");
		}
	}

	public static boolean isSandboxExists(String executable, String name, TaskLogger logger) {
		var exists = new AtomicBoolean();
		new Commandline(executable).addArgs("ls", "--quiet").timeout(120).execute(new LineConsumer() {
			@Override
			public void consume(String line) {
				if (line.equals(name))
					exists.set(true);
			}
		}, newWarningLogger(logger)).checkReturnCode();
		return exists.get();
	}

	public static void stopSandbox(String executable, String name, TaskLogger logger) {
		logger.log("Stopping sandbox '" + name + "'...");
		new Commandline(executable).addArgs("stop", name).timeout(120)
				.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
	}

	public static void deleteSandbox(String executable, String name, TaskLogger logger) {
		// Creation can fail before or after allocating the sandbox. Only delete resources which exist.
		if (isSandboxExists(executable, name, logger)) {
			logger.log("Deleting sandbox '" + name + "'...");
			new Commandline(executable).addArgs("rm", "--force", name).timeout(120)
					.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
		}
	}
}
