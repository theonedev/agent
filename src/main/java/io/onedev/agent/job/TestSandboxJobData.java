package io.onedev.agent.job;

import org.jspecify.annotations.Nullable;

import io.onedev.agent.SandboxSettings;

public class TestSandboxJobData extends TestShellJobData {

	private static final long serialVersionUID = 1L;

	private final String image;

	private final SandboxSettings settings;

	public TestSandboxJobData(String token, @Nullable String image, SandboxSettings settings) {
		super(token);
		this.image = image;
		this.settings = settings;
	}

	@Nullable
	public String getImage() {
		return image;
	}

	public SandboxSettings getSettings() {
		return settings;
	}
}
