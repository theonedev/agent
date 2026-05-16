package io.onedev.agent.shell;

public class JobShellOutputRequest extends ShellOutputRequest {

	private static final long serialVersionUID = 1L;

	public JobShellOutputRequest(String sessionId, String base64Data) {
		super(sessionId, base64Data);
	}

}
