package io.onedev.agent.job;

import java.io.Serializable;

import javax.annotation.Nullable;

public class JobResult implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String jobToken;
	
	private final String errorMessage;
	
	public JobResult(String jobToken, @Nullable String errorMessage) {
		this.jobToken = jobToken;
		this.errorMessage = errorMessage;
	}

	public String getJobToken() {
		return jobToken;
	}

	public String getErrorMessage() {
		return errorMessage;
	}
	
}
