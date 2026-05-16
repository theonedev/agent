package io.onedev.agent.workspace;

import java.io.Serializable;

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

	public WorkspaceFileDataRequest(String workspaceToken, Long projectId, Long workspaceNumber, String path) {
		this.workspaceToken = workspaceToken;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
		this.path = path;
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
