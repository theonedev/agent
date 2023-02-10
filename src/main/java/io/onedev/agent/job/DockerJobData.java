package io.onedev.agent.job;

import io.onedev.k8shelper.Action;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class DockerJobData extends ShellJobData {

	private static final long serialVersionUID = 1L;
	
	private final List<Map<String, Serializable>> services;
	
	private final List<Map<String, String>> registryLogins;
	
	private final boolean mountDockerSock;
	
	private final String dockerSock;

	private final String cpuLimit;

	private final String memoryLimit;

	private final String dockerOptions;
	
	private final int retried;
	
	public DockerJobData(String jobToken, String executorName, String projectPath, Long projectId,
						 String refName, String commitHash, Long buildNumber, List<Action> actions,
						 int retried, List<Map<String, Serializable>> services,
						 List<Map<String, String>> registryLogins, boolean mountDockerSock,
						 String dockerSock, @Nullable String cpuLimit, @Nullable String memoryLimit,
						 String dockerOptions) {
		super(jobToken, executorName, projectPath, projectId, refName, commitHash, buildNumber, actions);
		this.services = services;
		this.registryLogins = registryLogins;
		this.mountDockerSock = mountDockerSock;
		this.dockerSock = dockerSock;
		this.cpuLimit = cpuLimit;
		this.memoryLimit = memoryLimit;
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

	public String getDockerSock() {
		return dockerSock;
	}

	@Nullable
	public String getCpuLimit() {
		return cpuLimit;
	}

	@Nullable
	public String getMemoryLimit() {
		return memoryLimit;
	}

	public String getDockerOptions() {
		return dockerOptions;
	}

	public int getRetried() {
		return retried;
	}

}
