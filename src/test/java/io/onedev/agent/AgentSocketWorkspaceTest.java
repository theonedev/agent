package io.onedev.agent;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.io.Serializable;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.lang3.SerializationUtils;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.onedev.agent.workspace.FileData;
import io.onedev.agent.workspace.GitExecutionResult;
import io.onedev.agent.workspace.ProvisionMode;
import io.onedev.agent.workspace.WorkspaceDeleteRequest;
import io.onedev.agent.workspace.WorkspaceFileDataRequest;
import io.onedev.agent.workspace.WorkspaceGitCommandRequest;
import io.onedev.commons.utils.command.Commandline;

@EnabledOnOs({OS.LINUX, OS.MAC})
class AgentSocketWorkspaceTest {

	@TempDir
	Path temp;

	@ParameterizedTest
	@EnumSource(value = ProvisionMode.class, names = {"HOST", "DOCKER"})
	void serializedRequestsUseSelectedBackendAndDeleteStorage(ProvisionMode provisionMode) throws Exception {
		var previousInstallDir = Agent.installDir;
		var previousGitPath = Agent.gitPath;
		var previousDockerPath = Agent.dockerPath;
		try {
			Agent.installDir = temp.toFile();
			Agent.gitPath = "git";
			var workspace = Files.createDirectories(temp.resolve("work/workspaces/1/2"));
			var work = Files.createDirectory(workspace.resolve("work"));
			var filename = "file with spaces;$(touch injected)";
			Files.writeString(work.resolve(filename), "host content");
			new Commandline("git").workingDir(work.toFile()).addArgs("init", "--quiet")
					.execute(OutputStream.nullOutputStream(), OutputStream.nullOutputStream()).checkReturnCode();
			var docker = temp.resolve("docker");
			Files.writeString(docker, """
					#!/bin/sh
					[ "$DOCKER_HOST" = unix:///custom/docker.sock ] || exit 90
					[ "$1" = exec ] || exit 91
					if [ "$2" = -w ]; then
					  [ "$4" = workspace-test-1-2 ] || exit 92
					  printf docker-git
					else
					  [ "$2" = workspace-test-1-2 ] || exit 93
					  [ "$5" = '/onedev-workspace/work/file with spaces;$(touch injected)' ] || exit 94
					  printf 'docker content'
					fi
					""");
			assertTrue(docker.toFile().setExecutable(true));
			Agent.dockerPath = docker.toString();
			var service = AgentSocket.class.getDeclaredMethod("service", Serializable.class);
			service.setAccessible(true);
			var socket = new AgentSocket();
			var git = (GitExecutionResult) service.invoke(socket, SerializationUtils.clone(
					new WorkspaceGitCommandRequest(provisionMode, "test", "token", 1L, 2L, "/custom/docker.sock",
							new String[] {"rev-parse", "--is-inside-work-tree"})));
			assertEquals(0, git.getReturnCode());
			assertEquals(provisionMode == ProvisionMode.HOST ? "true" : "docker-git", new String(git.getStdout(), UTF_8).trim());
			var file = (FileData) service.invoke(socket, SerializationUtils.clone(
					new WorkspaceFileDataRequest(provisionMode, "test", "token", 1L, 2L, "/custom/docker.sock", filename)));
			assertEquals(provisionMode == ProvisionMode.HOST ? "host content" : "docker content", new String(file.getContent(), UTF_8));
			assertFalse(Files.exists(work.resolve("injected")));
			assertNull(service.invoke(socket, SerializationUtils.clone(new WorkspaceDeleteRequest(provisionMode, 1L, 2L))));
			assertFalse(Files.exists(workspace));
			assertNull(service.invoke(socket, SerializationUtils.clone(new WorkspaceDeleteRequest(provisionMode, 1L, 2L))));
		} finally {
			Agent.installDir = previousInstallDir;
			Agent.gitPath = previousGitPath;
			Agent.dockerPath = previousDockerPath;
		}
	}
}
