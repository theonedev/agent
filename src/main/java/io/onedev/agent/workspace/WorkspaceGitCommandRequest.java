package io.onedev.agent.workspace;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

/**
 * Sent server → agent via {@link io.onedev.agent.WebsocketUtils#call} to
 * execute a git command in the workspace. Response is a
 * {@link GitExecutionResult}.
 */
public class WorkspaceGitCommandRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final ProvisionMode provisionMode;

	private final String provisionerName;

	private final String workspaceToken;

	private final Long projectId;

	private final Long workspaceNumber;

	private final String dockerSock;

	private final String[] gitArgs;

	public WorkspaceGitCommandRequest(ProvisionMode provisionMode, @Nullable String provisionerName, String workspaceToken,
			Long projectId, Long workspaceNumber, @Nullable String dockerSock, String[] gitArgs) {
		this.provisionMode = provisionMode;
		this.provisionerName = provisionerName;
		this.workspaceToken = workspaceToken;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
		this.dockerSock = dockerSock;
		this.gitArgs = gitArgs;
	}

	public ProvisionMode getProvisionMode() {
		return provisionMode;
	}

	@Nullable
	public String getProvisionerName() {
		return provisionerName;
	}

	public String getWorkspaceToken() {
		return workspaceToken;
	}

	public Long getProjectId() {
		return projectId;
	}

	public Long getWorkspaceNumber() {
		return workspaceNumber;
	}

	@Nullable
	public String getDockerSock() {
		return dockerSock;
	}

	public String[] getGitArgs() {
		return gitArgs;
	}

}
