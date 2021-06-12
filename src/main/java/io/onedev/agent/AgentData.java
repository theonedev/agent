package io.onedev.agent;

import java.io.Serializable;
import java.util.Map;

public class AgentData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String token;
	
	private final AgentOs os;
	
	private final String osVersion;
	
	private final String osArch;
	
	private final String name;
	
	private final int cpu;
	
	private final int memory;
	
	private final Map<String, String> attributes;
	
	public AgentData(String token, AgentOs os, String osVersion, String osArch, 
			String name, int cpu, int memory, Map<String, String> attributes) {
		this.token = token;
		this.os = os;
		this.osVersion = osVersion;
		this.osArch = osArch;
		this.name = name;
		this.cpu = cpu;
		this.memory = memory;
		this.attributes = attributes;
	}

	public String getToken() {
		return token;
	}

	public AgentOs getOs() {
		return os;
	}

	public String getOsVersion() {
		return osVersion;
	}

	public String getOsArch() {
		return osArch;
	}

	public String getName() {
		return name;
	}

	public int getCpu() {
		return cpu;
	}

	public int getMemory() {
		return memory;
	}

	public Map<String, String> getAttributes() {
		return attributes;
	}
	
}
