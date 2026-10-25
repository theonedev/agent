package io.onedev.agent.workspace;

public class SandboxProvisionedShellOpenData extends WorkspaceShellOpenData {
	private static final long serialVersionUID = 1L;

	public SandboxProvisionedShellOpenData(String token, String shellId, Long projectId,
			Long workspaceNumber, String shellExecutable) {
		super(token, shellId, projectId, workspaceNumber, shellExecutable);
	}
}
