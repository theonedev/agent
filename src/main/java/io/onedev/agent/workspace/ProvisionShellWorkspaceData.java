package io.onedev.agent.workspace;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.onedev.k8shelper.CacheConfigFacade;
import io.onedev.k8shelper.SetupScriptConfig;

public class ProvisionShellWorkspaceData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final GitSettings gitSettings;

	private final List<CacheConfigFacade> cacheConfigs;

	private final Map<String, String> envVars;

	@Nullable
	private final SetupScriptConfig setupScriptConfig;

	private final String workspaceToken;

	private final Long projectId;

	private final Long workspaceNumber;

	public ProvisionShellWorkspaceData(String workspaceToken, GitSettings gitSettings,
							  List<CacheConfigFacade> cacheConfigs, Map<String, String> envVars,
							  @Nullable SetupScriptConfig setupScriptConfig, Long projectId,
							  Long workspaceNumber) {
		this.gitSettings = gitSettings;
		this.cacheConfigs = cacheConfigs;
		this.envVars = envVars;
		this.setupScriptConfig = setupScriptConfig;
		this.workspaceToken = workspaceToken;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
	}

	public GitSettings getGitSettings() {
		return gitSettings;
	}

	public List<CacheConfigFacade> getCacheConfigs() {
		return cacheConfigs;
	}

	public Map<String, String> getEnvVars() {
		return envVars;
	}

	@Nullable
	public SetupScriptConfig getSetupScriptConfig() {
		return setupScriptConfig;
	}

	public String getWorkspaceToken() {
		return workspaceToken;
	}

	public Long getProjectId() {
		return projectId;
	}

	public Long getWorkspaceNumber() {
		return workspaceNumber;
	}

}
