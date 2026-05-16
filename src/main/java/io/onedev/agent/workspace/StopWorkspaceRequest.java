package io.onedev.agent.workspace;

import java.io.Serializable;

public class StopWorkspaceRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final long TIMEOUT_MILLIS = 300_000L;

	private final String token;

	public StopWorkspaceRequest(String token) {
		this.token = token;
	}

	public String getToken() {
		return token;
	}

}
