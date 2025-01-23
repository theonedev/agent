package io.onedev.agent.job;

import java.io.Serializable;
import java.util.List;

import io.onedev.k8shelper.Action;

public class ShellJobData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final String jobToken;

	private final String executorName;

	private final String projectPath;
	
	private final Long projectId;
	
	private final String refName;
	
	private final String commitHash;
	
	private final Long buildNumber;

	private final Long submitSequence;
	
	private final List<Action> actions;
	
	public ShellJobData(String jobToken, String executorName, String projectPath, Long projectId,
						String refName, String commitHash, Long buildNumber, Long submitSequence, List<Action> actions) {
		this.jobToken = jobToken;
		this.executorName = executorName;
		this.projectPath = projectPath;
		this.projectId = projectId;
		this.refName = refName;
		this.commitHash = commitHash;
		this.buildNumber = buildNumber;
		this.submitSequence = submitSequence;
		this.actions = actions;
	}

	public String getJobToken() {
		return jobToken;
	}

	public String getExecutorName() {
		return executorName;
	}

	public String getProjectPath() {
		return projectPath;
	}

	public Long getProjectId() {
		return projectId;
	}

	public String getRefName() {
		return refName;
	}

	public String getCommitHash() {
		return commitHash;
	}

	public Long getBuildNumber() {
		return buildNumber;
	}

	public Long getSubmitSequence() {
		return submitSequence;
	}

	public List<Action> getActions() {
		return actions;
	}

}
