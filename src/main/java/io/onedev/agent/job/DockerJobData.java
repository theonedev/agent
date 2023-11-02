package io.onedev.agent.job;

import io.onedev.k8shelper.Action;
import io.onedev.k8shelper.ServiceFacade;

import javax.annotation.Nullable;
import java.util.List;

public class DockerJobData extends ShellJobData {

	private static final long serialVersionUID = 1L;
	
	private final List<ServiceFacade> services;
	
	private final List<RegistryLoginFacade> registryLogins;

	private final String builtInRegistryUrl;

	private final List<ImageMappingFacade> imageMappings;

	private final boolean mountDockerSock;
	
	private final String dockerSock;

	private final String cpuLimit;

	private final String memoryLimit;

	private final String dockerOptions;

	private final String networkOptions;
	
	private final int retried;
	
	public DockerJobData(String jobToken, String executorName, String projectPath, Long projectId,
						 String refName, String commitHash, Long buildNumber, List<Action> actions,
						 int retried, List<ServiceFacade> services, List<RegistryLoginFacade> registryLogins,
						 String builtInRegistryUrl, List<ImageMappingFacade> imageMappings,
						 boolean mountDockerSock, String dockerSock, @Nullable String cpuLimit,
						 @Nullable String memoryLimit, String dockerOptions, @Nullable String networkOptions) {
		super(jobToken, executorName, projectPath, projectId, refName, commitHash, buildNumber, actions);
		this.services = services;
		this.registryLogins = registryLogins;
		this.builtInRegistryUrl = builtInRegistryUrl;
		this.imageMappings = imageMappings;
		this.mountDockerSock = mountDockerSock;
		this.dockerSock = dockerSock;
		this.cpuLimit = cpuLimit;
		this.memoryLimit = memoryLimit;
		this.dockerOptions = dockerOptions;
		this.networkOptions = networkOptions;
		this.retried = retried;
	}

	public List<ServiceFacade> getServices() {
		return services;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	public String getBuiltInRegistryUrl() {
		return builtInRegistryUrl;
	}

	public List<ImageMappingFacade> getImageMappings() {
		return imageMappings;
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

	@Nullable
	public String getNetworkOptions() {
		return networkOptions;
	}

	public int getRetried() {
		return retried;
	}

}
