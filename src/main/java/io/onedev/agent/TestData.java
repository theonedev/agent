package io.onedev.agent;

import java.io.Serializable;

public abstract class TestData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;

	public TestData(String token) {
		this.token = token;
	}

	public String getToken() {
		return token;
	}

}
