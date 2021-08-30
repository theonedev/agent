package io.onedev.agent;

public enum AgentOs {

	WINDOWS("windows"), MACOSX("macosx"), LINUX("linux"), FREEBSD("freebsd"), OTHERS("computer");
	
	final String icon;
	
	AgentOs(String icon) {
		this.icon = icon;
	}

	public String getIcon() {
		return icon;
	}

}
