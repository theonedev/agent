package io.onedev.agent;

import javax.annotation.Nullable;
import java.io.Serializable;

public class BuiltInRegistryLogin implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String url;

    private final String jobToken;

    private final String accessToken;

    public BuiltInRegistryLogin(String url, String jobToken, @Nullable String accessToken) {
        this.url = url;
        this.jobToken = jobToken;
        this.accessToken = accessToken;
    }

    public String getUrl() {
        return url;
    }

    public String getCredential() {
        var builder = new StringBuilder();
        builder.append(jobToken);
        if (accessToken != null)
            builder.append(" ").append(accessToken);
        return builder.toString();
    }

}
