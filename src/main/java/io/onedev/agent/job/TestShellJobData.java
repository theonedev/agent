package io.onedev.agent.job;

import java.io.Serializable;
import java.util.List;

public class TestShellJobData implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private final String jobToken;
	
	private final String commands;
	
	public TestShellJobData(String jobToken, String commands) {
		this.jobToken = jobToken;
		this.commands = commands;
	}

	public String getJobToken() {
		return jobToken;
	}

	public String getCommands() {
		return commands;
	}

}
