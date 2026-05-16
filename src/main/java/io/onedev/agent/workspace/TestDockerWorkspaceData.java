package io.onedev.agent.workspace;

import java.util.Collection;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.TestDockerData;
import io.onedev.k8shelper.RegistryLoginFacade;

public class TestDockerWorkspaceData extends TestDockerData {

	private static final long serialVersionUID = 1L;

	private final String provisionerName;

	public TestDockerWorkspaceData(String provisionerName, String token, String dockerImage,
								   @Nullable String dockerSock, Collection<RegistryLoginFacade> registryLogins,
								   String dockerOptions, @Nullable String cpuLimit, @Nullable String memoryLimit) {
		super(token, dockerImage, dockerSock, registryLogins, dockerOptions, cpuLimit, memoryLimit);
		this.provisionerName = provisionerName;
	}

	public String getProvisionerName() {
		return provisionerName;
	}

}
