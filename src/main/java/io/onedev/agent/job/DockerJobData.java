package io.onedev.agent.job;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.onedev.k8shelper.Action;

public class DockerJobData extends ShellJobData {

	private static final long serialVersionUID = 1L;
	
	private final List<Map<String, Serializable>> services;
	
	private final List<Map<String, String>> registryLogins;
	
	private final boolean mountDockerSock;
	
	private final String dockerOptions;
	
	private final int retried;
	
	public DockerJobData(String jobToken, String executorName, String projectPath, Long projectId, 
			String commitHash, Long buildNumber, List<Action> actions, int retried, 
			List<Map<String, Serializable>> services, List<Map<String, String>> registryLogins, 
			boolean mountDockerSock, List<String> trustCertContent, String dockerOptions) {
		super(jobToken, executorName, projectPath, projectId, commitHash, buildNumber, 
				actions, trustCertContent);
		this.services = services;
		this.registryLogins = registryLogins;
		this.mountDockerSock = mountDockerSock;
		this.dockerOptions = dockerOptions;
		this.retried = retried;
	}

	public List<Map<String, Serializable>> getServices() {
		return services;
	}

	public List<Map<String, String>> getRegistryLogins() {
		return registryLogins;
	}

	public boolean isMountDockerSock() {
		return mountDockerSock;
	}

	public String getDockerOptions() {
		return dockerOptions;
	}

	public int getRetried() {
		return retried;
	}

}
