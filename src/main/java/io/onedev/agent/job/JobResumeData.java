package io.onedev.agent.job;

import java.io.Serializable;

public class JobResumeData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final long projectId;

	private final long buildNumber;

	private final long submitSequence;

	public JobResumeData(long projectId, long buildNumber, long submitSequence) {
		this.projectId = projectId;
		this.buildNumber = buildNumber;
		this.submitSequence = submitSequence;
	}

	public long getProjectId() {
		return projectId;
	}

	public long getBuildNumber() {
		return buildNumber;
	}

	public long getSubmitSequence() {
		return submitSequence;
	}

}
