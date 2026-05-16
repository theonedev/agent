package io.onedev.agent;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

public class AwaitableFutureTask<V> extends FutureTask<V> {

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final CountDownLatch completed = new CountDownLatch(1);

    public AwaitableFutureTask(Callable<V> callable) {
        super(callable);
    }

    @Override
    public void run() {
        running.set(true);
        try {
            super.run();
        } finally {
            completed.countDown();
        }
    }

    @Override
    protected void done() {
        if (!running.get())
            completed.countDown();
    }

    public void await() {
        try {
            completed.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}