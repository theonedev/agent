package io.onedev.agent.workspace;

import java.io.Serializable;

public class WorkspaceAwaitRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;

	public WorkspaceAwaitRequest(String token) {
		this.token = token;
	}

	public String getToken() {
		return token;
	}

}
