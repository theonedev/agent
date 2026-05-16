package io.onedev.agent.shell;

import static io.onedev.agent.MessageTypes.JOB_SHELL_EXIT;

import org.eclipse.jetty.websocket.api.Session;

import io.onedev.agent.AgentSocket;
import io.onedev.commons.utils.command.Commandline;

public class JobShellSession extends ShellSession {
	
	public JobShellSession(String sessionId, Session agentSession, Commandline cmdline) {
		super(sessionId, agentSession, JOB_SHELL_EXIT, cmdline);
	}
	
	@Override
	protected void onOutput(String base64Data) {
		AgentSocket.sendOutput(agentSession, new JobShellOutputRequest(sessionId, base64Data));
	}	

}
