package io.onedev.agent.job;

import java.util.List;

import io.onedev.agent.SandboxSettings;
import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.k8shelper.Action;
import io.onedev.k8shelper.ServiceFacade;

public class SandboxJobData extends JobData {

	private static final long serialVersionUID = 1L;

	private final List<ServiceFacade> services;

	private final SandboxSettings settings;

	public SandboxJobData(String jobToken, String executorName, String projectPath, Long projectId,
			String refName, String commitHash, Long buildNumber, Long submitSequence,
			List<Action> actions, SecretMasker secretMasker, List<ServiceFacade> services, SandboxSettings settings) {
		super(jobToken, executorName, projectPath, projectId, refName, commitHash, buildNumber, submitSequence,
				actions, secretMasker);
		this.services = services;
		this.settings = settings;
	}

	public SandboxSettings getSettings() {
		return settings;
	}

	public List<ServiceFacade> getServices() {
		return services;
	}
}
