package io.onedev.agent.workspace;

import io.onedev.agent.SandboxSettings;
import io.onedev.agent.TestData;

public class TestSandboxWorkspaceData extends TestData {

	private static final long serialVersionUID = 1L;

	private final SandboxSettings settings;

	public TestSandboxWorkspaceData(String token, SandboxSettings settings) {
		super(token);
		this.settings = settings;
	}

	public SandboxSettings getSettings() {
		return settings;
	}
}
