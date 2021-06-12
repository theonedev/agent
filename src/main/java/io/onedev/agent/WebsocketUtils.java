package io.onedev.agent;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jetty.websocket.api.Session;

import io.onedev.commons.utils.ExceptionUtils;

public class WebsocketUtils {

	private static final Map<String, Optional<Serializable>> responses = new HashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T extends Serializable, R extends Serializable> R call(Session session, T request, long timeout) 
			throws InterruptedException, TimeoutException {
		String uuid = UUID.randomUUID().toString();
		CallData callData = new CallData(uuid, request);
		new Message(MessageType.REQUEST, SerializationUtils.serialize(callData)).sendBy(session);
		
		synchronized (responses) {
			responses.put(uuid, null);
			try {
				long time = System.currentTimeMillis();
				while (true) {
					if (timeout != 0) {
						long ellapsedTime = System.currentTimeMillis() - time;
						if (timeout > ellapsedTime)
							responses.wait(timeout-ellapsedTime);
						else
							throw new TimeoutException();
					} else {
						responses.wait();
					}
					Optional<Serializable> response = responses.get(uuid);
					if (response != null) {
						Serializable payload = response.orElse(null);
						if (payload instanceof Exception)
							throw ExceptionUtils.unchecked((Exception) payload);
						else 
							return (R) payload;
					}
				}
			} finally {
				responses.remove(uuid);
			}
		}
	}

	public static void onResponse(CallData callData) {
		synchronized (responses) {
			if (responses.containsKey(callData.getUuid())) {
				responses.put(callData.getUuid(), Optional.ofNullable(callData.getPayload()));
				responses.notifyAll();
			}
		}
	}
	
}
