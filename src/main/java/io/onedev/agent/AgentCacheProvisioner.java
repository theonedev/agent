package io.onedev.agent;

import java.io.File;
import java.util.List;

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
                                    String cachePathsString, List<File> cacheDirs) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                key, checksum, cachePathsString, cacheDirs, Agent.sslFactory);
    }

    @Override
    protected boolean uploadCache(CacheConfigFacade cacheConfig, List<File> cacheDirs) {
        return KubernetesHelper.uploadCache(Agent.serverUrl, jobToken, cacheConfig,
                cacheDirs, Agent.sslFactory);
    }

}
