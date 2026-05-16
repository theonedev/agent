package io.onedev.agent.workspace;

import java.io.Serializable;

public class WorkspaceDeleteRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final Long projectId;

	private final Long workspaceNumber;

	public WorkspaceDeleteRequest(Long projectId, Long workspaceNumber) {
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
	}

	public Long getProjectId() {
		return projectId;
	}

	public Long getWorkspaceNumber() {
		return workspaceNumber;
	}

}
