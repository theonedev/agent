package io.onedev.agent.job;

import java.util.List;

import io.onedev.commons.bootstrap.SecretMasker;
import io.onedev.k8shelper.Action;

public class HostJobData extends JobData {

	private static final long serialVersionUID = 1L;

	public HostJobData(String jobToken, String executorName, String projectPath, Long projectId,
			String refName, String commitHash, Long buildNumber, Long submitSequence,
			List<Action> actions, SecretMasker secretMasker) {
		super(jobToken, executorName, projectPath, projectId, refName, commitHash,
				buildNumber, submitSequence, actions, secretMasker);
	}

}
