package io.onedev.agent.shell;

public class JobShellResizeRequest extends ShellResizeRequest {

	private static final long serialVersionUID = 1L;

	public JobShellResizeRequest(String sessionId, int rows, int cols) {
		super(sessionId, rows, cols);
	}

}
