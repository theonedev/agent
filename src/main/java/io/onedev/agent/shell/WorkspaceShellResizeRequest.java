package io.onedev.agent.shell;

public class WorkspaceShellResizeRequest extends ShellResizeRequest {

	private static final long serialVersionUID = 1L;

	public WorkspaceShellResizeRequest(String sessionId, int rows, int cols) {
		super(sessionId, rows, cols);
	}

}
