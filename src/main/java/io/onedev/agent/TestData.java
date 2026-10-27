package io.onedev.agent;

import java.io.Serializable;

public abstract class TestData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;

	// Populated from system settings by the server before dispatching the test.
	private String serverUrl;

	public TestData(String token) {
		this.token = token;
	}

	public String getToken() {
		return token;
	}

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}

}
