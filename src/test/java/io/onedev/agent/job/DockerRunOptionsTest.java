package io.onedev.agent.job;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;

@EnabledOnOs({OS.LINUX, OS.MAC})
class DockerRunOptionsTest {

	@TempDir
	Path temp;

	private String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private List<String> parse(String... args) {
		return DockerRunOptions.parse(Arrays.stream(args).map(this::quote).collect(Collectors.joining(" ")),
				temp.resolve("build").toFile());
	}

	private String path(String path) {
		return temp.resolve("build/work").resolve(path).normalize().toString();
	}

	@Test
	void resolvesFileOptionsInBothForms() {
		assertEquals(List.of("--env-file", path("settings/env file"), "--label-file=" + path("labels"),
				"--cidfile", path("new/cid"), "--security-opt=seccomp=" + path("profile.json"),
				"--security-opt", "seccomp=" + path("other.json")),
				parse("--env-file", "settings/env file", "--label-file=labels", "--cidfile", "new/cid",
						"--security-opt=seccomp=profile.json", "--security-opt", "seccomp:other.json"));

	}

	@Test
	void rejectsAbsoluteAndParentPathsForEveryFileOption() {
		for (var invalid : List.of("/etc/secret", "../secret", "dir/../../secret", "name..env",
				"C:/secret", "C:\\secret", "\\\\host\\share", "~/secret", "")) {
			for (var option : List.of("--env-file", "--label-file", "--cidfile")) {
				assertThrows(ExplicitException.class, () -> parse(option + "=" + invalid), option + "=" + invalid);
				assertThrows(ExplicitException.class, () -> parse(option, invalid), option + " " + invalid);
			}
			assertThrows(ExplicitException.class, () -> parse("--security-opt=seccomp=" + invalid));
			assertThrows(ExplicitException.class, () -> parse("--security-opt", "seccomp:" + invalid));
		}
	}

	@Test
	void rechecksFilesReplacedBetweenStepsIncludingDanglingLinks() throws Exception {
		var work = Files.createDirectories(temp.resolve("build/work"));
		var input = Files.writeString(work.resolve("env"), "KEY=value");
		assertEquals(List.of("--env-file", path("env")), parse("--env-file", "env"));
		Files.delete(input);
		var secret = Files.writeString(temp.resolve("secret"), "HOST_SECRET=value");
		Files.createSymbolicLink(input, secret);
		for (var option : List.of("--env-file", "--label-file", "--cidfile"))
			assertThrows(ExplicitException.class, () -> parse(option, "env"));
		assertThrows(ExplicitException.class, () -> parse("--security-opt=seccomp=env"));
		Files.delete(secret);
		assertThrows(ExplicitException.class, () -> parse("--cidfile=env"));
		assertFalse(Files.exists(secret));
	}

	@Test
	void rejectsSymlinksAtEveryLevelIncludingBuildRoot() throws Exception {
		var outside = Files.createDirectory(temp.resolve("outside"));
		var build = Files.createDirectory(temp.resolve("build"));
		Files.createSymbolicLink(build.resolve("work"), outside);
		assertThrows(ExplicitException.class, () -> parse("--env-file=env"));
		Files.delete(build.resolve("work"));
		var work = Files.createDirectory(build.resolve("work"));
		Files.createSymbolicLink(work.resolve("nested"), outside);
		assertThrows(ExplicitException.class, () -> parse("--cidfile=nested/new-file"));
		Files.delete(work.resolve("nested"));
		Files.delete(work);
		Files.delete(build);
		Files.createSymbolicLink(build, outside);
		assertThrows(ExplicitException.class, () -> parse("--env-file=env"));
	}

	@Test
	void rejectsVolumeAndDeviceMountOptionsInBothForms() {
		for (var option : List.of("--volume", "--mount", "--volumes-from", "--volume-driver",
				"--device", "--gpus", "--use-api-socket")) {
			for (var args : List.of(new String[] {option}, new String[] {option, "value"},
					new String[] {option + "=value"})) {
				var error = assertThrows(ExplicitException.class, () -> parse(args));
				assertTrue(error.getMessage().contains("not allowed"), error.getMessage());
			}
		}
	}

	@Test
	void rejectsShortAttachedAndClusteredVolumeOptions() {
		for (var args : List.of(new String[] {"-v"}, new String[] {"-v", "cache:/cache"},
				new String[] {"-v", "/anonymous"}, new String[] {"-v./data:/data"},
				new String[] {"-v=./data:/data"}, new String[] {"-iv./data:/data"},
				new String[] {"-itv", "./data:/data"}, new String[] {"-qPv/data:/data"})) {
			var error = assertThrows(ExplicitException.class, () -> parse(args));
			assertTrue(error.getMessage().contains("not allowed"), error.getMessage());
		}
	}

	@Test
	void preservesNonMountOptionsAndValuesThatResembleOptions() {
		var args = new String[] {"--device-read-bps", "/dev/sda:1mb", "--tmpfs=/tmp",
				"--security-opt", "seccomp=unconfined", "--security-opt=seccomp=builtin", "--security-opt=no-new-privileges",
				"--security-opt=apparmor=profile", "--env", "--env-file", "--label", "--mount", "-e", "--device",
				"-l-v", "--label=--volume", "-il--mount", "--entrypoint=/bin/sh", "--read-only", "--cpus", "2",
				"--env=VALUE=a..b"};
		assertEquals(Arrays.asList(args), parse(args));
		assertEquals(List.of(), DockerRunOptions.parse(null, temp.toFile()));
	}

	@Test
	void rejectsMissingFileValuesAndOptionTerminators() {
		for (var option : List.of("--env-file", "--label-file", "--cidfile", "--security-opt"))
			assertThrows(ExplicitException.class, () -> parse(option));
		assertThrows(ExplicitException.class, () -> parse("--", "--env-file=/etc/secret"));
	}
}
