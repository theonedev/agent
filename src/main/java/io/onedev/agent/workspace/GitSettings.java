package io.onedev.agent.workspace;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

import io.onedev.k8shelper.CloneInfo;

public class GitSettings implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String userName;

	private final String userEmail;

	private final CloneInfo cloneInfo;

	@Nullable
	private final String refName;

	private final boolean retrieveLfs;

	public GitSettings(String userName, String userEmail, CloneInfo cloneInfo,
			@Nullable String refName, boolean retrieveLfs) {
		this.userName = userName;
		this.userEmail = userEmail;
		this.cloneInfo = cloneInfo;
		this.refName = refName;
		this.retrieveLfs = retrieveLfs;
	}

	public String getUserName() {
		return userName;
	}

	public String getUserEmail() {
		return userEmail;
	}

	public CloneInfo getCloneInfo() {
		return cloneInfo;
	}

	@Nullable
	public String getRefName() {
		return refName;
	}

	public boolean isRetrieveLfs() {
		return retrieveLfs;
	}

}
