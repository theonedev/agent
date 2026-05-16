package io.onedev.agent.workspace;

import java.util.List;
import java.util.concurrent.Future;

import io.onedev.k8shelper.CacheProvisioner;
import io.onedev.k8shelper.UserDataProvisioner;

public class DockerWorkspaceRunInfo extends WorkspaceRunInfo {

    private final UserDataProvisioner userDataProvisioner;

    public DockerWorkspaceRunInfo(Future<?> future, 
                List<CacheProvisioner> cacheProvisioners, 
                UserDataProvisioner userDataProvisioner) {
        super(future, cacheProvisioners);
        this.userDataProvisioner = userDataProvisioner;
    }

    public UserDataProvisioner getUserDataProvisioner() {
        return userDataProvisioner;
    }

}
