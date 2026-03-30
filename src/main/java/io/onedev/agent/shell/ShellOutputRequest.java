package io.onedev.agent.shell;

import java.io.Serializable;

public class ShellOutputRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String sessionId;

	private final String base64Data;

	public ShellOutputRequest(String sessionId, String base64Data) {
		this.sessionId = sessionId;
		this.base64Data = base64Data;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getBase64Data() {
		return base64Data;
	}

}
