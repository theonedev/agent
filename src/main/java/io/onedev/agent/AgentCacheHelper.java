package io.onedev.agent;

import java.io.File;
import java.util.List;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.CacheAvailability;
import io.onedev.k8shelper.CacheHelper;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.SetupCacheFacade;
import org.jspecify.annotations.Nullable;

public class AgentCacheHelper extends CacheHelper {

    private final String jobToken;

    public AgentCacheHelper(String jobToken, File buildDir, TaskLogger logger) {
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
    protected boolean uploadCache(SetupCacheFacade cacheConfig, List<File> cacheDirs) {
        return KubernetesHelper.uploadCache(Agent.serverUrl, jobToken, cacheConfig,
                cacheDirs, Agent.sslFactory);
    }

}
