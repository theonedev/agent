package io.onedev.agent.shell;

import java.io.Serializable;

import org.jspecify.annotations.Nullable;

public class JobShellOpenData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final boolean runInContainer;

	private final String jobToken;

	private final String sessionId;

	private final long projectId;

	private final long buildNumber;

	private final long submitSequence;

	private final String dockerSock;

	public JobShellOpenData(boolean runInContainer, String jobToken, String sessionId, 
			long projectId, long buildNumber, long submitSequence, @Nullable String dockerSock) {
		this.runInContainer = runInContainer;
		this.jobToken = jobToken;
		this.sessionId = sessionId;
		this.projectId = projectId;
		this.buildNumber = buildNumber;
		this.submitSequence = submitSequence;
		this.dockerSock = dockerSock;
	}

	public boolean isRunInContainer() {
		return runInContainer;
	}

	public String getJobToken() {
		return jobToken;
	}

	public String getSessionId() {
		return sessionId;
	}

	public String getDockerSock() {
		return dockerSock;
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
