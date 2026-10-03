package io.onedev.agent.job;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.k8shelper.RunImagetoolsFacade;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ImagetoolsTest {

	@TempDir
	Path buildDir;

	private List<String> run(String arguments) {
		var actual = new ArrayList<String>();
		var docker = new Commandline("unused") {
			@Override
			public ExecutionResult execute(OutputStream out, OutputStream err) {
				actual.addAll(args());
				return new ExecutionResult(this);
			}
		};
		JobUtils.runImagetools(docker, new RunImagetoolsFacade(arguments, List.of()), buildDir.toFile(), null);
		return actual;
	}

	@Test
	void rejectsCombinedFlagsBeforeDockerCanReadDescriptors() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		var outside = Files.writeString(buildDir.resolve("outside"), "keep");
		Files.createSymbolicLink(work.resolve("descriptor"), outside);
		for (var option: List.of("-Dfdescriptor", "-Df descriptor", "-DDfdescriptor", "-Df=descriptor")) {
			assertThrows(ExplicitException.class, () -> run("create --dry-run " + option), option);
		}
		assertEquals("keep", Files.readString(outside));
	}

	@Test
	void rejectsMetadataEscapesAndSymlinkedParentsOrFiles() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		var outside = Files.createDirectory(buildDir.resolve("outside"));
		var sentinel = Files.writeString(outside.resolve("result.json"), "keep");
		Files.createSymbolicLink(work.resolve("output"), outside);
		Files.createSymbolicLink(work.resolve("result.json"), sentinel);
		Files.createSymbolicLink(work.resolve("dangling"), outside.resolve("missing"));
		for (var path: List.of("output/result.json", "output/new.json", "result.json", "dangling",
				"../outside/result.json", sentinel.toString(), "C:\\outside\\result.json")) {
			for (var option: List.of("--metadata-file ", "--metadata-file=")) {
				assertThrows(ExplicitException.class, () -> run("create " + option + path), option + path);
			}
		}
		assertEquals("keep", Files.readString(sentinel));
		assertArrayEquals(new String[] {"result.json"}, outside.toFile().list());
	}

	@Test
	void preservesValidDescriptorMetadataAndOtherArguments() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		Files.writeString(work.resolve("descriptor"), "{}");
		Files.createDirectory(work.resolve("output"));
		assertEquals(List.of("buildx", "imagetools", "create", "-D", "-f", "descriptor",
				"--metadata-file", "output/result.json", "-texample/image:tag", "-plinux/amd64"),
				run("create -D -f descriptor --metadata-file=output/result.json -texample/image:tag -plinux/amd64"));
		assertEquals(List.of("buildx", "imagetools", "create", "-fdescriptor", "--dry-run"),
				run("create -fdescriptor --dry-run"));
	}
}
