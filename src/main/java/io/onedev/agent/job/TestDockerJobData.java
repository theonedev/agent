package io.onedev.agent.job;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.TestDockerData;
import io.onedev.k8shelper.RegistryLoginFacade;
import io.onedev.k8shelper.VolumeMountFacade;

public class TestDockerJobData extends TestDockerData {

	private static final long serialVersionUID = 1L;
	
	private final String executorName;
	
	public TestDockerJobData(String executorName, String token, String dockerImage,
							 @Nullable String dockerSock, Collection<RegistryLoginFacade> registryLogins,
							 String dockerOptions, @Nullable String cpuLimit, @Nullable String memoryLimit,
							 List<VolumeMountFacade> volumeMounts) {
		super(token, dockerImage, dockerSock, registryLogins, dockerOptions, cpuLimit, memoryLimit, volumeMounts);
		this.executorName = executorName;
	}

	public String getExecutorName() {
		return executorName;
	}

}
