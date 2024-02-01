package io.onedev.agent;

import io.onedev.commons.utils.TaskLogger;
import io.onedev.k8shelper.CacheHelper;
import io.onedev.k8shelper.KubernetesHelper;

import javax.annotation.Nullable;
import java.io.File;
import java.util.List;

public class AgentCacheHelper extends CacheHelper {

    private final String jobToken;

    public AgentCacheHelper(String jobToken, File buildHome, TaskLogger logger) {
        super(buildHome, logger);
        this.jobToken = jobToken;
    }

    @Override
    protected boolean downloadCache(String cacheKey, String cachePath, File cacheDir) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                cacheKey, cachePath, cacheDir, Agent.sslFactory);
    }

    @Override
    protected boolean downloadCache(List<String> cacheLoadKeys, String cachePath, File cacheDir) {
        return KubernetesHelper.downloadCache(Agent.serverUrl, jobToken,
                cacheLoadKeys, cachePath, cacheDir, Agent.sslFactory);
    }

    @Override
    protected boolean uploadCache(String cacheKey, String cachePath,
                                  @Nullable String accessToken, File cacheDir) {
        return KubernetesHelper.uploadCache(Agent.serverUrl, jobToken,
                cacheKey, cachePath, accessToken, cacheDir, Agent.sslFactory);
    }

}
