package io.onedev.agent.workspace;

import java.io.Serializable;

public class WorkspaceDeleteRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final ProvisionMode provisionMode;

	private final Long projectId;

	private final Long workspaceNumber;

	public WorkspaceDeleteRequest(ProvisionMode provisionMode, Long projectId, Long workspaceNumber) {
		this.provisionMode = provisionMode;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
	}

	public ProvisionMode getProvisionMode() {
		return provisionMode;
	}

	public Long getProjectId() {
		return projectId;
	}

	public Long getWorkspaceNumber() {
		return workspaceNumber;
	}

}
