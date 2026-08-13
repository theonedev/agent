package io.onedev.agent.workspace;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.onedev.k8shelper.CacheConfigFacade;
import io.onedev.k8shelper.ScriptConfig;

public class ProvisionShellWorkspaceData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final GitSettings gitSettings;

	private final List<CacheConfigFacade> cacheConfigs;

	private final Map<String, String> envVars;

	private final ScriptConfig scriptConfig;

	private final String workspaceToken;

	private final Long projectId;

	private final Long workspaceNumber;

	private final String serverUrl;

	public ProvisionShellWorkspaceData(String workspaceToken, GitSettings gitSettings,
							  List<CacheConfigFacade> cacheConfigs, Map<String, String> envVars,
							  ScriptConfig scriptConfig, Long projectId,
							  Long workspaceNumber, String serverUrl) {
		this.gitSettings = gitSettings;
		this.cacheConfigs = cacheConfigs;
		this.envVars = envVars;
		this.scriptConfig = scriptConfig;
		this.workspaceToken = workspaceToken;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
		this.serverUrl = serverUrl;
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

	public ScriptConfig getScriptConfig() {
		return scriptConfig;
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

	public String getServerUrl() {
		return serverUrl;
	}

}
