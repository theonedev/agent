package io.onedev.agent;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.CacheHelper;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.SetupCacheFacade;

import javax.annotation.Nullable;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;

public class AgentCacheHelper extends CacheHelper {

    private final String jobToken;

    public AgentCacheHelper(String jobToken, File buildHome, TaskLogger logger) {
        super(buildHome, logger);
        this.jobToken = jobToken;
    }

    @Override
    protected boolean downloadCache(String cacheKey, List<String> cachePaths, List<File> cacheDirs) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                cacheKey, cachePaths, cacheDirs, Agent.sslFactory);
    }

    @Override
    protected boolean downloadCache(List<String> loadKeys, List<String> cachePaths, List<File> cacheDirs) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                loadKeys, cachePaths, cacheDirs, Agent.sslFactory);
    }

    @Override
    protected boolean uploadCache(SetupCacheFacade cacheConfig, List<File> cacheDirs) {
        return KubernetesHelper.uploadCache(Agent.serverUrl, jobToken, cacheConfig,
                cacheDirs, Agent.sslFactory);
    }

}
