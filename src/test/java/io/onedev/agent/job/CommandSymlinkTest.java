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
import io.onedev.k8shelper.RunImagetoolsFacade;
import io.onedev.commons.utils.command.Commandline;

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
	void imagetoolsCannotReadSymlinkedDescriptors() throws Exception {
		var build = Files.createDirectory(temp.resolve("build"));
		var work = Files.createDirectory(build.resolve("work"));
		var outside = Files.createDirectory(temp.resolve("outside"));
		Files.createSymbolicLink(work.resolve("link"), outside);
		Files.createSymbolicLink(work.resolve("dangling"), outside.resolve("missing"));
		for (var path: List.of("link/descriptor.json", "dangling")) {
			for (var option: List.of("--file ", "--file=", "-f ", "-f", "-f=")) {
				var step = new RunImagetoolsFacade("create " + option + path, List.of());
				assertThrows(ExplicitException.class, () -> JobUtils.runImagetools(
						new Commandline("unused"), step, build.toFile(), null));
			}
		}
	}

}
