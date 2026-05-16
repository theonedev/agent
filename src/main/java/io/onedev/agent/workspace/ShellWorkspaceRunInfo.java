package io.onedev.agent.workspace;

import java.util.List;
import java.util.concurrent.Future;

import io.onedev.k8shelper.CacheProvisioner;

public class ShellWorkspaceRunInfo extends WorkspaceRunInfo {

    public ShellWorkspaceRunInfo(Future<?> future, List<CacheProvisioner> cacheProvisioners) {
        super(future, cacheProvisioners);
    }

}
