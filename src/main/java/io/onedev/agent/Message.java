package io.onedev.agent;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jetty.websocket.api.Session;

public class Message implements Serializable {

	private static final long serialVersionUID = 1L;

	private final MessageTypes type;
	
	private final byte[] data;
	
	public Message(MessageTypes type, byte[] data) {
		this.type = type;
		this.data = data;
	}

	public Message(MessageTypes type, String data) {
		this(type, data.getBytes(StandardCharsets.UTF_8));
	}
	
	public Message(MessageTypes type, Serializable data) {
		this(type, SerializationUtils.serialize(data));
	}
	
	public static Message of(byte[] bytes, int offset, int length) {
    	MessageTypes type = MessageTypes.values()[bytes[offset]];
		byte[] data = new byte[length-1];
		System.arraycopy(bytes, offset+1, data, 0, length-1);
		return new Message(type, data);
	}
	
	public MessageTypes getType() {
		return type;
	}

	public byte[] getData() {
		return data;
	}
	
	public void sendBy(Session session) {
		byte[] bytes = new byte[data.length+1];
		bytes[0] = (byte) type.ordinal();
		System.arraycopy(data, 0, bytes, 1, data.length);
		session.getRemote().sendBytesByFuture(ByteBuffer.wrap(bytes));
	}
	
}
