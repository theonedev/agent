package io.onedev.agent.workspace;

import org.jspecify.annotations.Nullable;

public class DockerProvisionedShellOpenData extends WorkspaceShellOpenData {

	private static final long serialVersionUID = 1L;

	private final String provisionerName;

	private final String dockerSock;	

	public DockerProvisionedShellOpenData(String token, String sessionId, 
				Long projectId, Long workspaceNumber, String provisionerName, 
				String shellExecutable, @Nullable String dockerSock) {
		super(token, sessionId, projectId, workspaceNumber, shellExecutable);
		this.provisionerName = provisionerName;
		this.dockerSock = dockerSock;
	}

	public String getProvisionerName() {
		return provisionerName;
	}

	@Nullable
	public String getDockerSock() {
		return dockerSock;
	}

}
