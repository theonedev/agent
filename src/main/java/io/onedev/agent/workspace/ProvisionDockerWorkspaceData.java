package io.onedev.agent.workspace;

import java.io.Serializable;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.onedev.k8shelper.CacheConfigFacade;
import io.onedev.k8shelper.ConfigFileFacade;
import io.onedev.k8shelper.SetupScriptConfig;
import io.onedev.k8shelper.UserDataFacade;

public class ProvisionDockerWorkspaceData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final GitSettings gitSettings;

	private final List<CacheConfigFacade> cacheConfigs;

	private final List<UserDataFacade> userDatas;

	private final String userDataInitEntrypointArgs;

	private final List<ConfigFileFacade> configFiles;

	@Nullable
	private final SetupScriptConfig setupScriptConfig;

	private final String workspaceToken;

	private final String provisionerName;

	private final Long projectId;

	private final Long workspaceNumber;

	private final WorkspaceDockerSettings dockerSettings;

	private final String serverUrl;

	public ProvisionDockerWorkspaceData(String workspaceToken, String provisionerName, GitSettings gitSettings,
							   List<CacheConfigFacade> cacheConfigs, List<UserDataFacade> userDatas,
							   @Nullable String userDataInitEntrypointArgs, List<ConfigFileFacade> configFiles, 
							   @Nullable SetupScriptConfig setupScriptConfig, Long projectId, Long workspaceNumber, 
							   WorkspaceDockerSettings dockerSettings, String serverUrl) {
		this.gitSettings = gitSettings;
		this.cacheConfigs = cacheConfigs;
		this.userDatas = userDatas;
		this.userDataInitEntrypointArgs = userDataInitEntrypointArgs;
		this.configFiles = configFiles;
		this.setupScriptConfig = setupScriptConfig;
		this.workspaceToken = workspaceToken;
		this.provisionerName = provisionerName;
		this.projectId = projectId;
		this.workspaceNumber = workspaceNumber;
		this.dockerSettings = dockerSettings;
		this.serverUrl = serverUrl;
	}

	public GitSettings getGitSettings() {
		return gitSettings;
	}

	public List<CacheConfigFacade> getCacheConfigs() {
		return cacheConfigs;
	}

	public List<UserDataFacade> getUserDatas() {
		return userDatas;
	}

	@Nullable
	public String getUserDataInitEntrypointArgs() {
		return userDataInitEntrypointArgs;
	}

	public List<ConfigFileFacade> getConfigFiles() {
		return configFiles;
	}

	@Nullable
	public SetupScriptConfig getSetupScriptConfig() {
		return setupScriptConfig;
	}

	public String getWorkspaceToken() {
		return workspaceToken;
	}

	public String getProvisionerName() {
		return provisionerName;
	}

	public Long getProjectId() {
		return projectId;
	}

	public Long getWorkspaceNumber() {
		return workspaceNumber;
	}

	public WorkspaceDockerSettings getDockerSettings() {
		return dockerSettings;
	}

	public String getServerUrl() {
		return serverUrl;
	}

}
