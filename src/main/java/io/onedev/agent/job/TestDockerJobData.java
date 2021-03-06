package io.onedev.agent.job;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class TestDockerJobData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final String executorName;
	
	private final String jobToken;
	
	private final String dockerImage;
	
	private final List<Map<String, String>> registryLogins;

	private final String dockerOptions;
	
	public TestDockerJobData(String executorName, String jobToken, String dockerImage, 
			List<Map<String, String>> registryLogins, String dockerOptions) {
		this.executorName = executorName;
		this.jobToken = jobToken;
		this.dockerImage = dockerImage;
		this.registryLogins = registryLogins;
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

	public List<Map<String, String>> getRegistryLogins() {
		return registryLogins;
	}

	public String getDockerOptions() {
		return dockerOptions;
	}

}
