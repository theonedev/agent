package io.onedev.agent.job;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.onedev.k8shelper.Action;

public class JobData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final String jobToken;

	private final String executorName;

	private final String projectName;
	
	private final String commitHash;
	
	private final Long buildNumber;
	
	private final List<Action> actions;
	
	private final int retried;
	
	private final List<Map<String, Serializable>> services;
	
	private final List<Map<String, String>> registryLogins;
	
	private final List<String> trustCertContent;
	
	private final String dockerOptions;
	
	public JobData(String jobToken, String executorName, String projectName, String commitHash, 
			Long buildNumber, List<Action> actions, int retried, List<Map<String, Serializable>> services, 
			List<Map<String, String>> registryLogins, List<String> trustCertContent, String dockerOptions) {
		this.jobToken = jobToken;
		this.executorName = executorName;
		this.projectName = projectName;
		this.commitHash = commitHash;
		this.buildNumber = buildNumber;
		this.actions = actions;
		this.retried = retried;
		this.services = services;
		this.registryLogins = registryLogins;
		this.trustCertContent = trustCertContent;
		this.dockerOptions = dockerOptions;
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

	public int getRetried() {
		return retried;
	}

	public List<Map<String, Serializable>> getServices() {
		return services;
	}

	public List<Map<String, String>> getRegistryLogins() {
		return registryLogins;
	}

	public String getDockerOptions() {
		return dockerOptions;
	}

	public List<String> getTrustCertContent() {
		return trustCertContent;
	}
	
}
