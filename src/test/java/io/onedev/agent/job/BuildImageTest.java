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
				(docker, dir, out, err) -> result.addAll(docker.args()), List.of(), "linux/amd64", options);
		JobUtils.buildImage(command, "test", step, buildDir.toFile(), true, true, "test-executor", new TaskLogger() {
			@Override
			public void log(String message, String sessionId) { }
		});
		return result;
	}

	@Test
	void passesStepOptionsWithJobInterpolation() throws Exception {
		Files.createDirectories(buildDir.resolve("work"));
		Files.writeString(buildDir.resolve("work/revision"), "revision=abc123");
		var result = build("--no-cache --secret id=x,src=/host/file --ssh default "
				+ "--build-arg \"MESSAGE=hello world\" --label <&onedev#work/revision#onedev&>", null, null);
		assertEquals(List.of("buildx", "build", "--builder", "test", "--pull", "--platform", "linux/amd64",
				"--no-cache", "--secret", "id=x,src=/host/file", "--ssh", "default", "--build-arg", "MESSAGE=hello world",
				"--label", "revision=abc123", buildDir.resolve("work").toString()), result);
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
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void rejectsSymlinkedExplicitDockerfileFallbacks() throws Exception {
		Files.createDirectories(buildDir.resolve("work/context"));
		var recipes = Files.createDirectory(buildDir.resolve("work/recipes"));
		var outside = Files.writeString(buildDir.resolve("outside"), "outside workspace");
		for (var name : List.of("dockerfile", "dockerfile.dockerignore", "dockerfile.rego")) {
			if (!name.equals("dockerfile"))
				Files.writeString(recipes.resolve("dockerfile"), "FROM scratch\n");
			var link = Files.createSymbolicLink(recipes.resolve(name), outside);
			assertThrows(ExplicitException.class,
					() -> build(null, "context", "recipes/./Dockerfile"), name);
			Files.delete(link);
			Files.deleteIfExists(recipes.resolve("dockerfile"));
		}
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void allowsSafeFallbackAndDoesNotCheckUnrelatedLowercaseFiles() throws Exception {
		var recipes = Files.createDirectories(buildDir.resolve("work/recipes"));
		Files.writeString(recipes.resolve("dockerfile"), "FROM scratch\n");
		assertDoesNotThrow(() -> build(null, null, "recipes/Dockerfile"));
		Files.writeString(recipes.resolve("custom"), "FROM scratch\n");
		Files.createSymbolicLink(recipes.resolve("dockerfile.rego"), buildDir.resolve("outside"));
		assertDoesNotThrow(() -> build(null, null, "recipes/custom"));
	}

	@Test
	@EnabledOnOs({OS.LINUX, OS.MAC})
	void disablesGitInspectionInBuildProcess() throws Exception {
		Files.createDirectories(buildDir.resolve("work"));
		var executable = Files.writeString(buildDir.resolve("docker"), "#!/bin/sh\n"
				+ "if [ \"$1\" = buildx ] && [ \"$2\" = build ]; then\n"
				+ "  printf '%s\\n' \"$BUILDX_GIT_INFO\" \"$BUILDX_GIT_LABELS\" \"$BUILDX_GIT_CHECK_DIRTY\" > \"$0.env\"\n"
				+ "fi\n");
		assertTrue(executable.toFile().setExecutable(true));
		for (var gitSetting : new String[] {null, "true", "1", "full"}) {
			var docker = new Commandline(executable.toString());
			if (gitSetting != null) {
				docker.envs().put("BUILDX_GIT_INFO", gitSetting);
				docker.envs().put("BUILDX_GIT_LABELS", gitSetting);
				docker.envs().put("BUILDX_GIT_CHECK_DIRTY", gitSetting);
			}
			var step = new BuildImageFacade(null, null,
					new BuildImageFacade.RegistryOutput("test:latest"), List.of(), null, null);
			JobUtils.buildImage(docker, "test", step, buildDir.toFile(), false, true, "test-executor", new TaskLogger() {
				@Override
				public void log(String message, String sessionId) { }
			});
			assertEquals(List.of("false", "false", "false"), Files.readAllLines(buildDir.resolve("docker.env")));
		}
	}

	@Test
	void transportsOptionsWithProjectStep() {
		var step = new BuildImageFacade(null, null,
				new BuildImageFacade.RegistryOutput("test:latest"), List.of(), null, "--no-cache");
		var restored = org.apache.commons.lang3.SerializationUtils.clone(step);
		assertEquals("--no-cache", restored.getMoreOptions());
	}
}
