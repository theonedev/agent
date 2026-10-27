package io.onedev.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

class AgentSocketTest {

	@Test
	void repeatedCancellationDoesNotInterruptJobCleanup() throws Exception {
		var field = AgentSocket.class.getDeclaredField("jobThreads");
		field.setAccessible(true);
		@SuppressWarnings("unchecked")
		var jobThreads = (Map<String, AgentSocket.RunningJob>) field.get(null);
		var cancelJob = AgentSocket.class.getDeclaredMethod("cancelJob", String.class);
		cancelJob.setAccessible(true);
		var cleanupStarted = new CountDownLatch(1);
		var finishCleanup = new CountDownLatch(1);
		var cleanupInterrupted = new AtomicBoolean();
		var thread = new Thread(() -> {
			try {
				new CountDownLatch(1).await();
			} catch (InterruptedException e) {
				cleanupStarted.countDown();
				try {
					finishCleanup.await();
				} catch (InterruptedException duringCleanup) {
					cleanupInterrupted.set(true);
				}
			}
		});
		var token = UUID.randomUUID().toString();
		jobThreads.put(token, new AgentSocket.RunningJob(thread, "http://configured-server"));
		thread.start();
		try {
			var socket = new AgentSocket();
			cancelJob.invoke(socket, token);
			assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
			cancelJob.invoke(socket, token);
		} finally {
			finishCleanup.countDown();
			thread.join(5000);
			jobThreads.remove(token);
		}
		assertFalse(thread.isAlive());
		assertFalse(cleanupInterrupted.get(), "A repeated cancellation interrupted job cleanup");
	}
}
