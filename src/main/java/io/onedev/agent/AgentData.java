package io.onedev.agent;

import java.io.Serializable;
import java.util.Map;

import io.onedev.k8shelper.OsInfo;

public class AgentData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;
	
	private final OsInfo osInfo;
	
	private final String name;
	
	private final String ipAddress;
	
	private final int cpu;
	
	private final int memory;
	
	private final boolean temporal;
	
	private final Map<String, String> attributes;
	
	public AgentData(String token, OsInfo osInfo, String name, String ipAddress, 
			int cpu, int memory, boolean temporal, Map<String, String> attributes) {
		this.token = token;
		this.osInfo = osInfo;
		this.name = name;
		this.ipAddress = ipAddress;
		this.cpu = cpu;
		this.memory = memory;
		this.temporal = temporal;
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

	public int getCpu() {
		return cpu;
	}

	public int getMemory() {
		return memory;
	}

	public boolean isTemporal() {
		return temporal;
	}

	public Map<String, String> getAttributes() {
		return attributes;
	}
	
}
