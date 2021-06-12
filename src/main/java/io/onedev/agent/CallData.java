package io.onedev.agent;

import java.io.Serializable;

public class CallData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final String uuid;
	
	private final Serializable payload;
	
	public CallData(String uuid, Serializable payload) {
		this.uuid = uuid;
		this.payload = payload;
	}

	public String getUuid() {
		return uuid;
	}

	public Serializable getPayload() {
		return payload;
	}
	
}
