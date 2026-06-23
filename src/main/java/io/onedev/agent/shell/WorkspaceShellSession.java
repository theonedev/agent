package io.onedev.agent.shell;

import static io.onedev.agent.MessageTypes.WORKSPACE_SHELL_EXIT;

import org.eclipse.jetty.websocket.api.Session;
import org.jspecify.annotations.Nullable;

import io.onedev.agent.AgentSocket;
import io.onedev.commons.utils.command.Commandline;

public class WorkspaceShellSession extends ShellSession {
	
	public WorkspaceShellSession(String sessionId, Session agentSession, Commandline cmdline) {
		this(sessionId, agentSession, cmdline, null);
	}

	public WorkspaceShellSession(String sessionId, Session agentSession, Commandline cmdline,
			@Nullable Runnable onTerminate) {
		super(sessionId, agentSession, WORKSPACE_SHELL_EXIT, cmdline, onTerminate);
	}
	
	@Override
	protected void onOutput(String base64Data) {
		AgentSocket.sendOutput(agentSession, new WorkspaceShellOutputRequest(sessionId, base64Data));
	}	

}
