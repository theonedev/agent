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
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.k8shelper.BuildImageFacade;

class BuildImageTest {
	@TempDir
	Path buildDir;

	private List<String> build(String options, String buildPath, String dockerfile) throws Exception {
		Files.createDirectories(buildDir.resolve("work"));
		var result = new ArrayList<String>();
		var command = new Commandline("unused") {
			@Override
			public ExecutionResult execute(OutputStream out, OutputStream err) {
				assertEquals(List.of("buildx", "create", "--name", "test"), args());
				return new ExecutionResult(this);
			}
		};
		var step = new BuildImageFacade(buildPath, dockerfile,
				(docker, dir, out, err) -> result.addAll(docker.args()), List.of(), "linux/amd64");
		JobUtils.buildImage(command, "test", options, step, buildDir.toFile(), true, new TaskLogger() {
			@Override
			public void log(String message, String sessionId) { }
		});
		return result;
	}

	@Test
	void passesAdministratorOptionsWithoutRestrictionsOrJobInterpolation() throws Exception {
		Files.createDirectories(buildDir.resolve("work"));
		Files.writeString(buildDir.resolve("work/options"), "--secret id=untrusted,src=/host/file");
		var result = build("--allow security.insecure --secret id=x,src=/host/file "
				+ "--build-arg \"MESSAGE=hello world\" --label <&onedev#work/options#onedev&>", null, null);
		assertEquals(List.of("buildx", "build", "--builder", "test", "--pull", "--platform", "linux/amd64",
				"--allow", "security.insecure", "--secret", "id=x,src=/host/file", "--build-arg", "MESSAGE=hello world",
				"--label", "<&onedev#work/options#onedev&>", buildDir.resolve("work").toString()), result);
		assertFalse(build(null, null, null).contains("--secret"));
	}

	@Test
	void preventsContextOptionInjectionAndRejectsEscapingPaths() throws Exception {
		var result = build(null, "--help", null);
		assertTrue(result.contains(buildDir.resolve("work/--help").toString()));
		assertFalse(result.contains("--help"));
		for (var path : List.of("../outside", "/outside", "oci-layout:///outside", "C:\\outside")) {
			assertThrows(ExplicitException.class, () -> build(null, path, null));
			assertThrows(ExplicitException.class, () -> build(null, null, path));
		}
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void rejectsSymlinkedContextDockerfilesAndAutomaticInputs() throws Exception {
		var work = Files.createDirectory(buildDir.resolve("work"));
		var outside = Files.createDirectory(buildDir.resolve("outside"));
		Files.createSymbolicLink(work.resolve("linked"), outside);
		assertThrows(ExplicitException.class, () -> build(null, "linked", null));
		assertThrows(ExplicitException.class, () -> build(null, null, "linked/Dockerfile"));
		for (var name : List.of("Dockerfile", "dockerfile", ".dockerignore", "Dockerfile.dockerignore", "Dockerfile.rego")) {
			var link = Files.createSymbolicLink(work.resolve(name), outside.resolve("missing"));
			assertThrows(ExplicitException.class, () -> build(null, null, null), name);
			Files.delete(link);
		}
	}

	@Test
	void transportsOptionsSeparatelyFromProjectStep() {
		var settings = new JobDockerSettings(false, null, null, null, null, List.of(), true, "test", null, "--no-cache");
		var restored = org.apache.commons.lang3.SerializationUtils.clone(settings);
		assertEquals("--no-cache", restored.getBuildOptions());
		assertFalse(java.util.Arrays.stream(BuildImageFacade.class.getDeclaredFields())
				.anyMatch(it -> it.getName().equals("moreOptions")));
	}
}
