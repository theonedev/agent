package io.onedev.agent.workspace;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;

@EnabledOnOs({OS.LINUX, OS.MAC})
class FileDataTest {

	@TempDir
	Path temp;

	@Test
	void dockerPreviewReadsContainerContentWithLiteralFilenames() throws Exception {
		var hostWork = Files.createDirectory(temp.resolve("host-work"));
		var containerWork = Files.createDirectory(temp.resolve("container-work"));
		var secret = Files.writeString(temp.resolve("secret"), "host secret");
		byte[] content = {0, 1, 10, (byte) 255};
		for (var name : List.of("space and 'quote'", "file;touch injected;#", "$(touch injected)", "-option")) {
			Files.createSymbolicLink(hostWork.resolve(name), secret);
			Files.write(containerWork.resolve(name), content);
			var docker = new Commandline("docker") {
				@Override
				public ExecutionResult execute(OutputStream stdout, OutputStream stderr) {
					assertEquals(List.of("exec", "workspace-test", "cat", "--", hostWork + "/" + name), args());
					// Simulate Docker resolving the last argument inside its container.
					return new Commandline("cat").workingDir(containerWork.toFile())
							.args("--", containerWork.resolve(name).toString()).execute(stdout, stderr);
				}
			};
			var data = WorkspaceUtils.readFileData(docker, "workspace-test", hostWork.toString(), name);
			assertNotNull(data);
			assertEquals(name, data.getName());
			assertArrayEquals(content, data.getContent());
			assertEquals(content.length, data.getSize());
			assertFalse(Files.exists(containerWork.resolve("injected")));
		}
	}

	@Test
	void failedDockerReadDoesNotFallBackToHostFile() throws Exception {
		Files.writeString(temp.resolve("file"), "host secret");
		var docker = new Commandline("docker") {
			@Override
			public ExecutionResult execute(OutputStream stdout, OutputStream stderr) {
				var result = new ExecutionResult(this);
				result.setReturnCode(1);
				return result;
			}
		};
		assertNull(WorkspaceUtils.readFileData(docker, "workspace-test", temp.toString(), "file"));
	}

	@Test
	void remoteDockerRequestRetainsContainerSelection() {
		var request = SerializationUtils.clone(new WorkspaceFileDataRequest(
				"docker-provisioner", "token", 1L, 2L, "/docker.sock", "file"));
		assertTrue(request.isDocker());
		assertEquals("docker-provisioner", request.getProvisionerName());
		assertEquals("/docker.sock", request.getDockerSock());
		assertFalse(new WorkspaceFileDataRequest("token", 1L, 2L, "file").isDocker());
	}

	@Test
	void localPreviewRejectsTraversalAndSymlinksOutsideWorkspace() throws Exception {
		var workspace = Files.createDirectory(temp.resolve("workspace"));
		var work = Files.createDirectory(workspace.resolve("work"));
		var outside = Files.createDirectory(temp.resolve("workspace-other"));
		var secret = Files.writeString(outside.resolve("secret"), "host secret");
		Files.createSymbolicLink(work.resolve("file-link"), secret);
		Files.createSymbolicLink(work.resolve("dir-link"), outside);
		Files.createSymbolicLink(work.resolve("missing-link"), outside.resolve("missing"));
		for (var path : List.of("../../workspace-other/secret", "file-link", "dir-link/secret")) {
			assertThrows(ExplicitException.class, () -> WorkspaceUtils.readFileData(workspace.toFile(), path), path);
		}
		assertNull(WorkspaceUtils.readFileData(workspace.toFile(), "missing-link"));
	}

	@Test
	void localPreviewAllowsFilesAndSymlinksWithinTrustedWorkspaceRoot() throws Exception {
		var actual = Files.createDirectory(temp.resolve("actual"));
		var workspace = Files.createSymbolicLink(temp.resolve("workspace"), actual);
		var work = Files.createDirectory(actual.resolve("work"));
		var file = Files.writeString(work.resolve("file"), "content");
		Files.createSymbolicLink(work.resolve("link"), file);
		Files.writeString(actual.resolve("other"), "content");
		for (var path : List.of("file", "link", "../other")) {
			var data = WorkspaceUtils.readFileData(workspace.toFile(), path);
			assertEquals("content", new String(data.getContent(), StandardCharsets.UTF_8));
		}
		assertNull(WorkspaceUtils.readFileData(workspace.toFile(), "missing"));
	}

}
