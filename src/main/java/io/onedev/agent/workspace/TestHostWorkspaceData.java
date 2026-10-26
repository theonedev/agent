package io.onedev.agent.workspace;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.TestShellData;

public class TestHostWorkspaceData extends TestShellData {

	private static final long serialVersionUID = 1L;

	@Nullable
	private final String tmuxExecutable;

	public TestHostWorkspaceData(String token, @Nullable String tmuxExecutable) {
		super(token);
		this.tmuxExecutable = tmuxExecutable;
	}

	@Nullable
	public String getTmuxExecutable() {
		return tmuxExecutable;
	}

}
