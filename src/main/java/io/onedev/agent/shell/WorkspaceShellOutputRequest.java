package io.onedev.agent.shell;

public class WorkspaceShellOutputRequest extends ShellOutputRequest {

	private static final long serialVersionUID = 1L;

	public WorkspaceShellOutputRequest(String sessionId, String base64Data) {
		super(sessionId, base64Data);
	}

}
