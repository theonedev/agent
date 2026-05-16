package io.onedev.agent.shell;

import static io.onedev.agent.MessageTypes.WORKSPACE_SHELL_EXIT;

import org.eclipse.jetty.websocket.api.Session;

import io.onedev.agent.AgentSocket;
import io.onedev.commons.utils.command.Commandline;

public class WorkspaceShellSession extends ShellSession {
	
	public WorkspaceShellSession(String sessionId, Session agentSession, Commandline cmdline) {
		super(sessionId, agentSession, WORKSPACE_SHELL_EXIT, cmdline);
	}
	
	@Override
	protected void onOutput(String base64Data) {
		AgentSocket.sendOutput(agentSession, new WorkspaceShellOutputRequest(sessionId, base64Data));
	}	

}
