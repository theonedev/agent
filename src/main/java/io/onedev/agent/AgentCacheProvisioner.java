package io.onedev.agent;

import java.io.File;

import org.jspecify.annotations.Nullable;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.CacheAvailability;
import io.onedev.k8shelper.CacheConfigFacade;
import io.onedev.k8shelper.CacheProvisioner;
import io.onedev.k8shelper.KubernetesHelper;

public class AgentCacheProvisioner extends CacheProvisioner {

    private final String jobToken;

    public AgentCacheProvisioner(String jobToken, File buildDir, TaskLogger logger) {
        super(buildDir, logger);
        this.jobToken = jobToken;
    }

    @Override
    protected CacheAvailability downloadCache(String key, @Nullable String checksum,
                                    String path, File cacheDir) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                key, checksum, path, cacheDir, Agent.sslFactory);
    }

    @Override
    protected boolean uploadCache(CacheConfigFacade cacheConfig, String path, File cacheDir) {
        return KubernetesHelper.uploadCache(Agent.serverUrl, jobToken, cacheConfig,
                path, cacheDir, Agent.sslFactory);
    }

}
