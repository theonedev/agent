package io.onedev.agent.shell;

import java.io.Serializable;

public class ShellInputRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String sessionId;

	private final String data;

	public ShellInputRequest(String sessionId, String data) {
		this.sessionId = sessionId;
		this.data = data;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getData() {
		return data;
	}

}
