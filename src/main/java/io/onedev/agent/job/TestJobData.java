package io.onedev.agent.job;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

public class TestJobData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final String jobToken;
	
	private final String dockerImage;
	
	private final List<Map<String, String>> registryLogins;
	
	private final List<String> trustCertContent;
	
	private final String dockerOptions;
	
	public TestJobData(String jobToken, String dockerImage, List<Map<String, String>> registryLogins, 
			List<String> trustCertContent, String dockerOptions) {
		this.jobToken = jobToken;
		this.dockerImage = dockerImage;
		this.registryLogins = registryLogins;
		this.trustCertContent = trustCertContent;
		this.dockerOptions = dockerOptions;
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

	public List<String> getTrustCertContent() {
		return trustCertContent;
	}
	
}
