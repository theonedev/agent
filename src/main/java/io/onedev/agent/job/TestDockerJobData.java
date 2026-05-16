package io.onedev.agent.job;

import java.util.Collection;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.TestDockerData;
import io.onedev.k8shelper.RegistryLoginFacade;

public class TestDockerJobData extends TestDockerData {

	private static final long serialVersionUID = 1L;
	
	private final String executorName;
	
	public TestDockerJobData(String executorName, String token, String dockerImage,
							 @Nullable String dockerSock, Collection<RegistryLoginFacade> registryLogins,
							 String dockerOptions, @Nullable String cpuLimit, @Nullable String memoryLimit) {
		super(token, dockerImage, dockerSock, registryLogins, dockerOptions, cpuLimit, memoryLimit);
		this.executorName = executorName;
	}

	public String getExecutorName() {
		return executorName;
	}

}
