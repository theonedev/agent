package io.onedev.agent.workspace;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

/**
 * Sent server → agent via {@link io.onedev.agent.WebsocketUtils#call} to read
 * the contents of a file under the workspace work directory. Response is a
 * (possibly null) {@link FileData}.
 */
public class WorkspaceFileDataRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String workspaceToken;

	private final Long projectId;

	private final Long workspaceNumber;

	private final String path;

	private final ProvisionMode provisionMode;

	private final String provisionerName;

	private final String dockerSock;

	public WorkspaceFileDataRequest(ProvisionMode provisionMode, @Nullable String provisionerName, String workspaceToken,
			Long projectId, Long workspaceNumber, @Nullable String dockerSock, String path) {
		this.workspaceToken = workspaceToken;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
		this.path = path;
		this.provisionMode = provisionMode;
		this.provisionerName = provisionerName;
		this.dockerSock = dockerSock;
	}

	public ProvisionMode getProvisionMode() {
		return provisionMode;
	}

	@Nullable
	public String getProvisionerName() {
		return provisionerName;
	}

	@Nullable
	public String getDockerSock() {
		return dockerSock;
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

	public String getPath() {
		return path;
	}

}
