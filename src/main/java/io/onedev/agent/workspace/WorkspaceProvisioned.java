package io.onedev.agent.workspace;

import java.io.Serializable;
import java.util.Map;

public class WorkspaceProvisioned implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String workspaceToken;

	private final String portHost;

	private final Map<Integer, Integer> portMappings;

	public WorkspaceProvisioned(String workspaceToken, String portHost, 
				Map<Integer, Integer> portMappings) {
		this.workspaceToken = workspaceToken;
		this.portHost = portHost;
		this.portMappings = portMappings;
	}

	public String getWorkspaceToken() {
		return workspaceToken;
	}

	public String getPortHost() {
		return portHost;
	}

	public Map<Integer, Integer> getPortMappings() {
		return portMappings;
	}

}
