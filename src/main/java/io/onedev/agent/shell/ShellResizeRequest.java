package io.onedev.agent.shell;

import java.io.Serializable;

public abstract class ShellResizeRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String sessionId;

	private final int rows;

	private final int cols;

	public ShellResizeRequest(String sessionId, int rows, int cols) {
		this.sessionId = sessionId;
		this.rows = rows;
		this.cols = cols;
	}

	public String getSessionId() {
		return sessionId;
	}

	public int getRows() {
		return rows;
	}
	
	public int getCols() {
		return cols;
	}

}
