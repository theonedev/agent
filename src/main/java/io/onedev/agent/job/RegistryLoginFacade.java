package io.onedev.agent.job;

import java.io.Serializable;

public class RegistryLoginFacade implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String registryUrl;
	
	private final String userName;
	
	private final String password;
	
	public RegistryLoginFacade(String registryUrl, String userName, String password) {
		this.registryUrl = registryUrl;
		this.userName = userName;
		this.password = password;
	}

	public String getRegistryUrl() {
		return registryUrl;
	}

	public String getUserName() {
		return userName;
	}

	public String getPassword() {
		return password;
	}
	
}
