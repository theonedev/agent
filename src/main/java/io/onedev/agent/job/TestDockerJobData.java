package io.onedev.agent.job;

import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;

public class TestDockerJobData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final String executorName;
	
	private final String jobToken;
	
	private final String dockerImage;

	private final String dockerSock;

	private final Collection<RegistryLoginFacade> registryLogins;

	private final String builtInRegistryUrl;

	private final String dockerOptions;
	
	public TestDockerJobData(String executorName, String jobToken, String dockerImage,
							 @Nullable String dockerSock, Collection<RegistryLoginFacade> registryLogins,
							 String builtInRegistryUrl, String dockerOptions) {
		this.executorName = executorName;
		this.jobToken = jobToken;
		this.dockerImage = dockerImage;
		this.dockerSock = dockerSock;
		this.registryLogins = registryLogins;
		this.builtInRegistryUrl = builtInRegistryUrl;
		this.dockerOptions = dockerOptions;
	}

	public String getExecutorName() {
		return executorName;
	}

	public String getJobToken() {
		return jobToken;
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

	public String getBuiltInRegistryUrl() {
		return builtInRegistryUrl;
	}

	public String getDockerOptions() {
		return dockerOptions;
	}

}
