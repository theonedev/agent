package io.onedev.agent;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.CacheHelper;
import io.onedev.k8shelper.KubernetesHelper;

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
    protected boolean downloadCache(String cacheKey, LinkedHashMap<String, File> cacheDirs) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                cacheKey, cacheDirs, Agent.sslFactory);
    }

    @Override
    protected boolean downloadCache(List<String> cacheLoadKeys, LinkedHashMap<String, File> cacheDirs) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                cacheLoadKeys, cacheDirs, Agent.sslFactory);
    }

    @Override
    protected boolean uploadCache(String cacheKey, LinkedHashMap<String, File> cacheDirs,
                                  @Nullable String accessToken) {
        return KubernetesHelper.uploadCache(Agent.serverUrl, jobToken,
                cacheKey, cacheDirs, accessToken, Agent.sslFactory);
    }

}
