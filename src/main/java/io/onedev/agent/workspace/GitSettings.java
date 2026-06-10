package io.onedev.agent.workspace;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

import io.onedev.k8shelper.CloneInfo;

public class GitSettings implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String userName;

	private final String userEmail;

	private final CloneInfo cloneInfo;

	private final String commitHash;

	@Nullable
	private final String branch;

	private final boolean retrieveLfs;

	public GitSettings(String userName, String userEmail, CloneInfo cloneInfo,
			String commitHash, @Nullable String branch, boolean retrieveLfs) {
		this.userName = userName;
		this.userEmail = userEmail;
		this.cloneInfo = cloneInfo;
		this.commitHash = commitHash;
		this.branch = branch;
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

	public String getCommitHash() {
		return commitHash;
	}

	@Nullable
	public String getBranch() {
		return branch;
	}

	public boolean isRetrieveLfs() {
		return retrieveLfs;
	}

}
