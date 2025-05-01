package io.onedev.agent.job;

import java.util.List;

import javax.annotation.Nullable;

import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.k8shelper.Action;
import io.onedev.k8shelper.RegistryLoginFacade;
import io.onedev.k8shelper.ServiceFacade;

public class DockerJobData extends ShellJobData {

	private static final long serialVersionUID = 1L;
	
	private final List<ServiceFacade> services;
	
	private final List<RegistryLoginFacade> registryLogins;

	private final boolean mountDockerSock;
	
	private final String dockerSock;

	private final String dockerBuilder;

	private final String cpuLimit;

	private final String memoryLimit;

	private final String dockerOptions;

	private final String networkOptions;

	private final boolean alwaysPullImage;

	private final SecretMasker secretMasker;

	public DockerJobData(String jobToken, String executorName, String projectPath, Long projectId,
						 String refName, String commitHash, Long buildNumber, Long submitSequence,
						 List<Action> actions, List<ServiceFacade> services, List<RegistryLoginFacade> registryLogins,
						 boolean mountDockerSock, String dockerSock, String dockerBuilder,
						 @Nullable String cpuLimit, @Nullable String memoryLimit, String dockerOptions,
						 @Nullable String networkOptions, boolean alwaysPullImage, SecretMasker secretMasker) {
		super(jobToken, executorName, projectPath, projectId, refName, commitHash,
				buildNumber, submitSequence, actions, secretMasker);
		this.services = services;
		this.registryLogins = registryLogins;
		this.mountDockerSock = mountDockerSock;
		this.dockerSock = dockerSock;
		this.dockerBuilder = dockerBuilder;
		this.cpuLimit = cpuLimit;
		this.memoryLimit = memoryLimit;
		this.dockerOptions = dockerOptions;
		this.networkOptions = networkOptions;
		this.alwaysPullImage = alwaysPullImage;
		this.secretMasker = secretMasker;
	}

	public List<ServiceFacade> getServices() {
		return services;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	public boolean isMountDockerSock() {
		return mountDockerSock;
	}

	public String getDockerSock() {
		return dockerSock;
	}

	public String getDockerBuilder() {
		return dockerBuilder;
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

	public boolean isAlwaysPullImage() {
		return alwaysPullImage;
	}

	public SecretMasker getSecretMasker() {
		return secretMasker;
	}

}
