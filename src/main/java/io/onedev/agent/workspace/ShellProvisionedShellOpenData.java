package io.onedev.agent.workspace;

import java.util.Map;

import org.jspecify.annotations.Nullable;

public class ShellProvisionedShellOpenData extends WorkspaceShellOpenData {

	private static final long serialVersionUID = 1L;

	private final String tmuxExecutable;

	private final Map<String, String> envVars;

	private final String serverUrl;

	public ShellProvisionedShellOpenData(String token, String shellId, 
				Long projectId, Long workspaceNumber, String provisionerName, 
				String shellExecutable, @Nullable String tmuxExecutable,
				Map<String, String> envVars, String serverUrl) {
		super(token, shellId, projectId, workspaceNumber, shellExecutable);
		this.tmuxExecutable = tmuxExecutable;
		this.envVars = envVars;
		this.serverUrl = serverUrl;
	}

	@Nullable
	public String getTmuxExecutable() {
		return tmuxExecutable;
	}

	public Map<String, String> getEnvVars() {
		return envVars;
	}

	public String getServerUrl() {
		return serverUrl;
	}

}
