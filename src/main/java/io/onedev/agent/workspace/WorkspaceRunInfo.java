package io.onedev.agent.workspace;

import java.util.List;
import java.util.concurrent.Future;

import io.onedev.k8shelper.CacheProvisioner;

public abstract class WorkspaceRunInfo {

    private final Future<?> future;

    private final List<CacheProvisioner> cacheProvisioners;

    public WorkspaceRunInfo(Future<?> future, List<CacheProvisioner> cacheProvisioners) {
        this.future = future;
        this.cacheProvisioners = cacheProvisioners;
    }

    public Future<?> getFuture() {
        return future;
    }

    public List<CacheProvisioner> getCacheProvisioners() {
        return cacheProvisioners;
    }

}
