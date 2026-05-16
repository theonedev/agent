package io.onedev.agent;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;

public class WebsocketUtils {

	private static final Map<String, CallContext> contexts = new ConcurrentHashMap<>();

	private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
	
	public static <T extends Serializable, R extends Serializable> R call(Session session, T request) 
			throws InterruptedException, TimeoutException {
		return call(session, request, DEFAULT_TIMEOUT_MILLIS);
	}

	@SuppressWarnings("unchecked")
	public static <T extends Serializable, R extends Serializable> R call(Session session, T request, long timeoutMillis) 
			throws InterruptedException, TimeoutException {
		String uuid = UUID.randomUUID().toString();
		CallData callData = new CallData(uuid, request);		
		var context = new CallContext(session);
		contexts.put(uuid, context);
		try {
			synchronized (context) {
				new Message(MessageTypes.REQUEST, SerializationUtils.serialize(callData)).sendBy(session);
				long time = System.nanoTime();
				while (true) {
					if (timeoutMillis != 0) {
						long elapsedTime = (System.nanoTime() - time) / 1_000_000;
						if (timeoutMillis > elapsedTime)
							context.wait(timeoutMillis-elapsedTime);
						else
							throw new TimeoutException();
					} else {
						context.wait();
					}

					if (context.response != null) {
						Serializable payload = context.response.orElse(null);
						if (payload instanceof Throwable)
							throw ExceptionUtils.unchecked((Throwable) payload);
						else 
							return (R) payload;	
					} else if (!context.session.isOpen()) {
						throw new ExplicitException("Agent disconnected");
					}
				}
			}
		} finally {
			contexts.remove(uuid);
		}
	}

	public static void onResponse(CallData callData) {
		var context = contexts.get(callData.getUuid());
		if (context != null) {
			synchronized (context) {
				context.response = Optional.ofNullable(callData.getPayload());
				context.notify();
			}
		}
	}

	public static void onClose(Session session) {
		for (var context: contexts.values()) {
			if (context.session == session) {
				synchronized (context) {
					context.notify();
				}
			}
		}
	}
	
	private static class CallContext {

		private final Session session;

		@Nullable
		private Optional<Serializable> response;
		
		private CallContext(Session session) {
			this.session = session;
		}

	}

}
