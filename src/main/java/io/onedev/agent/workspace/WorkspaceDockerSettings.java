package io.onedev.agent.workspace;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.DockerSettings;
import io.onedev.k8shelper.RegistryLoginFacade;

public class WorkspaceDockerSettings extends DockerSettings {

	private static final long serialVersionUID = 1L;

	private final String image;

	private final String runAs;

	private final List<Integer> containerPorts;

	private final Map<String, String> envVars;

	public WorkspaceDockerSettings(boolean mountDockerSock, @Nullable String dockerSock,
			@Nullable String cpuLimit, @Nullable String memoryLimit, @Nullable String runOptions,
			List<RegistryLoginFacade> registryLogins, boolean alwaysPullImage,
			String image, String runAs, List<Integer> containerPorts, Map<String, String> envVars) {
		super(mountDockerSock, dockerSock, cpuLimit, memoryLimit, runOptions, registryLogins, alwaysPullImage);
		this.image = image;
		this.runAs = runAs;
		this.containerPorts = containerPorts;
		this.envVars = envVars;
	}

	public String getImage() {
		return image;
	}

	public String getRunAs() {
		return runAs;
	}

	public List<Integer> getContainerPorts() {
		return containerPorts;
	}

	public Map<String, String> getEnvVars() {
		return envVars;
	}

}
