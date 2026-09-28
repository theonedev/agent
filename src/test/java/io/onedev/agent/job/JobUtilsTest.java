package io.onedev.agent.job;

import static io.onedev.k8shelper.JobHelper.FINALIZATION;
import static io.onedev.k8shelper.JobHelper.INITIALIZATION;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.onedev.k8shelper.JobHelper;

class JobUtilsTest {
    @Test
    void phasesAndStepEventsHaveSeparateProtocols() {
        for (var phase : java.util.List.of(INITIALIZATION, FINALIZATION)) {
            var marker = JobUtils.buildPhaseMessage(phase);
            assertEquals(phase, JobUtils.parsePhaseMessage(marker));
            assertNull(JobHelper.parseStepEventMessage(marker));
        }
        assertNull(JobUtils.parsePhaseMessage(JobHelper.buildStepStartMessage(java.util.List.of(0))));
        assertNull(JobUtils.parsePhaseMessage(JobHelper.PHASE_PREFIX + "START:initialization"));
    }

    @Test
    void stepReportsFailureCancellationAndErrors() {
        var position = java.util.List.of(0);
        for (var failure : java.util.List.of(new IllegalStateException("failure"),
                new InterruptedException(), new java.util.concurrent.CancellationException(),
                new AssertionError("fatal"), new RuntimeException(new InterruptedException()))) {
            var messages = new java.util.ArrayList<String>();
            var logger = logger(messages);
            java.util.concurrent.Callable<Boolean> step = () -> {
                if (failure instanceof Error error) throw error;
                throw (Exception) failure;
            };
            var outcome = JobUtils.getStepOutcome(failure);
            if (failure.getClass() == IllegalStateException.class)
                assertFalse(JobUtils.runStep(position, logger, step));
            else
                assertThrows(Throwable.class, () -> JobUtils.runStep(position, logger, step));
            assertEquals(JobHelper.buildStepStartMessage(position), messages.get(0));
            assertEquals(JobHelper.buildStepEndMessage(position, outcome), messages.get(messages.size() - 1));
        }
    }

    @Test
    void reportsFailureIfForwardingTheStartMarkerThrows() {
        var messages = new java.util.ArrayList<String>();
        var position = java.util.List.of(0);
        var logger = new io.onedev.commons.utils.TaskLogger() {
            public void log(String message, String sessionId) {
                messages.add(message);
                if (message.equals(JobHelper.buildStepStartMessage(position)))
                    throw new IllegalStateException("Failed after accepting the start marker");
            }
        };
        assertFalse(JobUtils.runStep(position, logger, () -> {
            fail("The step must not run when its start marker failed");
            return true;
        }));
        assertEquals(JobHelper.buildStepEndMessage(position, JobHelper.StepEventKind.FAILED), messages.get(messages.size() - 1));
    }

    @Test
    void blockedOutcomeDoesNotBlockOrdinaryOutput() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var messages = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var logger = new io.onedev.commons.utils.TaskLogger() {
            public void log(String message, String sessionId) {
                if (message.equals(JobHelper.buildStepEndMessage(java.util.List.of(0), JobHelper.StepEventKind.SUCCESSFUL))) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                }
                messages.add(message);
            }
        };
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var step = workers.submit(() -> JobUtils.runStep(java.util.List.of(0), logger, () -> true));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            workers.submit(() -> logger.log("concurrent output")).get(5, java.util.concurrent.TimeUnit.SECONDS);
            release.countDown();
            assertTrue(step.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(messages.contains("concurrent output"));
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    private io.onedev.commons.utils.TaskLogger logger(java.util.List<String> messages) {
        return new io.onedev.commons.utils.TaskLogger() {
            public void log(String message, String sessionId) {
                messages.add(message);
            }
        };
    }
}
