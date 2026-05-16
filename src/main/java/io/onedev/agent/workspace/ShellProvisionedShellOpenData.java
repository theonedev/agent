package io.onedev.agent.workspace;

import org.jspecify.annotations.Nullable;

public class ShellProvisionedShellOpenData extends WorkspaceShellOpenData {

	private static final long serialVersionUID = 1L;

	private final String tmuxExecutable;

	public ShellProvisionedShellOpenData(String token, String sessionId, 
				Long projectId, Long workspaceNumber, String provisionerName, 
				String shellExecutable, @Nullable String tmuxExecutable) {
		super(token, sessionId, projectId, workspaceNumber, shellExecutable);
		this.tmuxExecutable = tmuxExecutable;
	}

	@Nullable
	public String getTmuxExecutable() {
		return tmuxExecutable;
	}

}
