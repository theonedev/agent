package io.onedev.agent;

import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

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
			try (var shared = SandboxUtils.class.getResourceAsStream("sandbox.py");
					var runner = SandboxUtils.class.getResourceAsStream(type + "/" + runnerName)) {
				if (shared == null || runner == null)
					throw new ExplicitException("Sandbox " + type + " resource is missing");
				addTarEntry(tar, "sandbox.py", shared.readAllBytes(), fileMode);
				addTarEntry(tar, runnerName, runner.readAllBytes(), fileMode);
			}
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
			command.execute(newInfoLogger(logger), newWarningLogger(logger)).checkReturnCode();
		} finally {
			// sbx persists the kit configuration for subsequent starts.
			FileUtils.deletePath(kitDir);
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
