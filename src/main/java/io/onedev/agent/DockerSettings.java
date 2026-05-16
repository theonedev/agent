package io.onedev.agent;

import java.io.Serializable;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.onedev.k8shelper.RegistryLoginFacade;

public class DockerSettings implements Serializable {

	private static final long serialVersionUID = 1L;

	private final boolean mountDockerSock;

	@Nullable
	private final String dockerSock;

	@Nullable
	private final String cpuLimit;

	@Nullable
	private final String memoryLimit;

	@Nullable
	private final String runOptions;

	private final List<RegistryLoginFacade> registryLogins;

	private final boolean alwaysPullImage;

	public DockerSettings(boolean mountDockerSock, @Nullable String dockerSock,
			@Nullable String cpuLimit, @Nullable String memoryLimit, @Nullable String runOptions,
			List<RegistryLoginFacade> registryLogins, boolean alwaysPullImage) {
		this.mountDockerSock = mountDockerSock;
		this.dockerSock = dockerSock;
		this.cpuLimit = cpuLimit;
		this.memoryLimit = memoryLimit;
		this.runOptions = runOptions;
		this.registryLogins = registryLogins;
		this.alwaysPullImage = alwaysPullImage;
	}

	public boolean isMountDockerSock() {
		return mountDockerSock;
	}

	@Nullable
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

	@Nullable
	public String getRunOptions() {
		return runOptions;
	}

	public List<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	public boolean isAlwaysPullImage() {
		return alwaysPullImage;
	}

}
