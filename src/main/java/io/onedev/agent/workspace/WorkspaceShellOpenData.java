package io.onedev.agent.workspace;

import java.io.Serializable;

public abstract class WorkspaceShellOpenData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;

    private final String sessionId;

	private final Long projectId;

	private final Long workspaceNumber;

	private final String shellExecutable;

	public WorkspaceShellOpenData(String token, String sessionId, 
				Long projectId, Long workspaceNumber, String shellExecutable) {
		this.token = token;
		this.sessionId = sessionId;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
		this.shellExecutable = shellExecutable;
	}

	public String getToken() {
		return token;
	}

	public String getSessionId() {
		return sessionId;
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
