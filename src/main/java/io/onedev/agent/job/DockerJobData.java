package io.onedev.agent.job;

import java.util.List;

import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.k8shelper.Action;
import io.onedev.k8shelper.ServiceFacade;

public class DockerJobData extends ShellJobData {

	private static final long serialVersionUID = 1L;
	
	private final List<ServiceFacade> services;

	private final JobDockerSettings dockerSettings;

	public DockerJobData(String jobToken, String executorName, String projectPath, Long projectId,
						 String refName, String commitHash, Long buildNumber, Long submitSequence,
						 List<Action> actions, SecretMasker secretMasker, List<ServiceFacade> services, 
						 JobDockerSettings dockerSettings) {
		super(jobToken, executorName, projectPath, projectId, refName, commitHash,
				buildNumber, submitSequence, actions, secretMasker);
		this.services = services;
		this.dockerSettings = dockerSettings;
	}

	public List<ServiceFacade> getServices() {
		return services;
	}

	public JobDockerSettings getDockerSettings() {
		return dockerSettings;
	}

}
