package io.onedev.agent.job;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.k8shelper.CommandFacade;

@EnabledOnOs({OS.LINUX, OS.MAC})
class CommandSymlinkTest {

	@TempDir
	Path temp;

	@Test
	void nextCommandCannotWriteThroughReplacedCommandDirectory() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		var outside = Files.createDirectory(temp.resolve("outside"));
		var command = new CommandFacade("image", "0:0", List.of(), Map.of(), false, "echo test");
		Files.createSymbolicLink(build.resolve("command"), outside);
		assertThrows(ExplicitException.class,
				() -> JobUtils.getEntrypointArgs(build.toFile(), command, List.of(0)));
		assertArrayEquals(new String[0], outside.toFile().list());
		Files.delete(build.resolve("command"));
		Files.createDirectory(build.resolve("command"));
		var script = build.resolve("command/step-0" + command.getScriptExtension());
		var target = outside.resolve("missing");
		Files.createSymbolicLink(script, target);
		assertThrows(ExplicitException.class,
				() -> JobUtils.getEntrypointArgs(build.toFile(), command, List.of(0)));
		assertFalse(Files.exists(target));
		Files.delete(script);
		JobUtils.getEntrypointArgs(build.toFile(), command, List.of(0));
		assertTrue(Files.readString(script).contains("echo test"));
	}

	@Test
	void containerCommandCannotReadSymlinkedHostPlaceholders() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		var work = Files.createDirectory(build.resolve("work"));
		var outside = Files.writeString(temp.resolve("outside"), "host-only-secret");
		Files.createSymbolicLink(work.resolve("reference"), outside);
		var command = new CommandFacade("1dev/buildx:1.0.0", "0:0", List.of(), Map.of(), false,
				"docker buildx imagetools inspect <&onedev#work/reference#onedev&>");
		assertThrows(ExplicitException.class,
				() -> JobUtils.getEntrypointArgs(build.toFile(), command, List.of(0)));
		assertFalse(Files.exists(build.resolve("command/step-0" + command.getScriptExtension())));
	}

}
