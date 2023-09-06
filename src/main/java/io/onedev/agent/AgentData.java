package io.onedev.agent;

import io.onedev.k8shelper.OsInfo;

import java.io.Serializable;
import java.util.Map;

public class AgentData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;
	
	private final OsInfo osInfo;
	
	private final String name;
	
	private final String ipAddress;
	
	private final int cpus;

	private final Map<String, String> attributes;
	
	public AgentData(String token, OsInfo osInfo, String name, String ipAddress,
					 int cpus, Map<String, String> attributes) {
		this.token = token;
		this.osInfo = osInfo;
		this.name = name;
		this.ipAddress = ipAddress;
		this.cpus = cpus;
		this.attributes = attributes;
	}

	public String getToken() {
		return token;
	}

	public OsInfo getOsInfo() {
		return osInfo;
	}

	public String getName() {
		return name;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public int getCpus() {
		return cpus;
	}

	public Map<String, String> getAttributes() {
		return attributes;
	}
	
}
