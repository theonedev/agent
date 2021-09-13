package io.onedev.agent.job;

import java.io.Serializable;
import java.util.List;

import io.onedev.k8shelper.Action;

public class ShellJobData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final String jobToken;

	private final String executorName;

	private final String projectName;
	
	private final String commitHash;
	
	private final Long buildNumber;
	
	private final List<Action> actions;
	
	private final List<String> trustCertContent;
	
	public ShellJobData(String jobToken, String executorName, String projectName, String commitHash, 
			Long buildNumber, List<Action> actions, List<String> trustCertContent) {
		this.jobToken = jobToken;
		this.executorName = executorName;
		this.projectName = projectName;
		this.commitHash = commitHash;
		this.buildNumber = buildNumber;
		this.actions = actions;
		this.trustCertContent = trustCertContent;
	}

	public String getJobToken() {
		return jobToken;
	}

	public String getExecutorName() {
		return executorName;
	}

	public String getProjectName() {
		return projectName;
	}

	public String getCommitHash() {
		return commitHash;
	}

	public Long getBuildNumber() {
		return buildNumber;
	}

	public List<Action> getActions() {
		return actions;
	}

	public List<String> getTrustCertContent() {
		return trustCertContent;
	}
	
}
