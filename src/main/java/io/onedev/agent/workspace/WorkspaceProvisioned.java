package io.onedev.agent.workspace;

import java.io.Serializable;
import java.util.Map;

import org.jspecify.annotations.Nullable;

public class WorkspaceProvisioned implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String workspaceToken;

	private final String portHost;

	@Nullable
	private final String tailscaleIp;

	private final Map<Integer, Integer> portMappings;

	public WorkspaceProvisioned(String workspaceToken, String portHost, @Nullable String tailscaleIp,
				Map<Integer, Integer> portMappings) {
		this.workspaceToken = workspaceToken;
		this.portHost = portHost;
		this.tailscaleIp = tailscaleIp;
		this.portMappings = portMappings;
	}

	public String getWorkspaceToken() {
		return workspaceToken;
	}

	public String getPortHost() {
		return portHost;
	}

	@Nullable
	public String getTailscaleIp() {
		return tailscaleIp;
	}

	public Map<Integer, Integer> getPortMappings() {
		return portMappings;
	}

}
