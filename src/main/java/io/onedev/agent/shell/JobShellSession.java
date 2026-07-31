package io.onedev.agent.shell;

import static io.onedev.agent.MessageTypes.JOB_SHELL_EXIT;

import org.eclipse.jetty.websocket.api.Session;

import io.onedev.agent.AgentSocket;
import io.onedev.commons.utils.command.Commandline;

public class JobShellSession extends ShellSession {

	private final String jobToken;
	
	public JobShellSession(String jobToken, String sessionId, Session agentSession, Commandline cmdline) {
		super(sessionId, agentSession, JOB_SHELL_EXIT, cmdline);
		this.jobToken = jobToken;
	}
	
	@Override
	protected void onOutput(String base64Data) {
		AgentSocket.sendOutput(agentSession, new JobShellOutputRequest(sessionId, base64Data));
	}	

	public String getJobToken() {
		return jobToken;
	}
	
}
