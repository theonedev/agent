package io.onedev.agent;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.onedev.k8shelper.RegistryLoginFacade;
import io.onedev.k8shelper.VolumeMountFacade;

public abstract class TestDockerData extends TestData {

	private static final long serialVersionUID = 1L;

	private final String dockerImage;

	private final String dockerSock;

	private final Collection<RegistryLoginFacade> registryLogins;

	private final String dockerOptions;

	private final String cpuLimit;

	private final String memoryLimit;

	private final List<VolumeMountFacade> volumeMounts;

	public TestDockerData(String token, String dockerImage, @Nullable String dockerSock,
						  Collection<RegistryLoginFacade> registryLogins, String dockerOptions,
						  @Nullable String cpuLimit, @Nullable String memoryLimit, List<VolumeMountFacade> volumeMounts) {
		super(token);
		this.dockerImage = dockerImage;
		this.dockerSock = dockerSock;
		this.registryLogins = registryLogins;
		this.dockerOptions = dockerOptions;
		this.cpuLimit = cpuLimit;
		this.memoryLimit = memoryLimit;
		this.volumeMounts = volumeMounts;
	}

	public String getDockerImage() {
		return dockerImage;
	}

	@Nullable
	public String getDockerSock() {
		return dockerSock;
	}

	public Collection<RegistryLoginFacade> getRegistryLogins() {
		return registryLogins;
	}

	public String getDockerOptions() {
		return dockerOptions;
	}

	@Nullable
	public String getCpuLimit() {
		return cpuLimit;
	}

	@Nullable
	public String getMemoryLimit() {
		return memoryLimit;
	}

	public List<VolumeMountFacade> getVolumeMounts() {
		return volumeMounts;
	}

}
