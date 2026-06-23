package io.onedev.agent.workspace;

import java.io.Serializable;

public abstract class WorkspaceShellOpenData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;

    private final String shellId;

	private final Long projectId;

	private final Long workspaceNumber;

	private final String shellExecutable;

	public WorkspaceShellOpenData(String token, String shellId, 
				Long projectId, Long workspaceNumber, String shellExecutable) {
		this.token = token;
		this.shellId = shellId;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
		this.shellExecutable = shellExecutable;
	}

	public String getToken() {
		return token;
	}

	public String getShellId() {
		return shellId;
	}

	public Long getProjectId() {
		return projectId;
	}

	public Long getWorkspaceNumber() {
		return workspaceNumber;
	}

	public String getShellExecutable() {
		return shellExecutable;
	}

}
