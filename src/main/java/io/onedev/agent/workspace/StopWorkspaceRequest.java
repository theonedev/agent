package io.onedev.agent.workspace;

import java.io.Serializable;

public class StopWorkspaceRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final long TIMEOUT_MILLIS = 300_000L;

	private final String token;

	private final boolean uploadData;

	public StopWorkspaceRequest(String token) {
		this(token, false);
	}

	public StopWorkspaceRequest(String token, boolean uploadData) {
		this.token = token;
		this.uploadData = uploadData;
	}

	public String getToken() {
		return token;
	}

	public boolean isUploadData() {
		return uploadData;
	}

}
