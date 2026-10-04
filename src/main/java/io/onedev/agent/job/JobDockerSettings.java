package io.onedev.agent.job;

import java.util.List;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.DockerSettings;
import io.onedev.k8shelper.RegistryLoginFacade;

public class JobDockerSettings extends DockerSettings {

	private static final long serialVersionUID = 1L;

	private final String dockerBuilder;

	private final boolean imageBuildEnabled;

	private final boolean builderCachePruneEnabled;

	private final boolean imagetoolsEnabled;

	@Nullable
	private final String networkOptions;

	public JobDockerSettings(boolean mountDockerSock, @Nullable String dockerSock,
			@Nullable String cpuLimit, @Nullable String memoryLimit, @Nullable String runOptions,
			List<RegistryLoginFacade> registryLogins, boolean alwaysPullImage,
			String dockerBuilder, @Nullable String networkOptions,
			boolean imageBuildEnabled, boolean builderCachePruneEnabled, boolean imagetoolsEnabled) {
		super(mountDockerSock, dockerSock, cpuLimit, memoryLimit, runOptions, registryLogins, alwaysPullImage);
		this.dockerBuilder = dockerBuilder;
		this.networkOptions = networkOptions;
		this.imageBuildEnabled = imageBuildEnabled;
		this.builderCachePruneEnabled = builderCachePruneEnabled;
		this.imagetoolsEnabled = imagetoolsEnabled;
	}

	public String getDockerBuilder() {
		return dockerBuilder;
	}

	public boolean isImageBuildEnabled() {
		return imageBuildEnabled;
	}

	public boolean isBuilderCachePruneEnabled() {
		return builderCachePruneEnabled;
	}

	public boolean isImagetoolsEnabled() {
		return imagetoolsEnabled;
	}

	@Nullable
	public String getNetworkOptions() {
		return networkOptions;
	}

}
