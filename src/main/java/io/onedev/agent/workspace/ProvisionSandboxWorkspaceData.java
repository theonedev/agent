package io.onedev.agent.workspace;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.onedev.agent.SandboxSettings;
import io.onedev.k8shelper.CacheConfigFacade;
import io.onedev.k8shelper.ConfigFileFacade;
import io.onedev.k8shelper.ScriptConfig;
import io.onedev.k8shelper.UserDataFacade;

/** All filesystem paths in this request refer to the sandbox, never the host. */
public record ProvisionSandboxWorkspaceData(String token, String sandboxName, SandboxSettings settings,
		GitSettings gitSettings, List<CacheConfigFacade> cacheConfigs, List<UserDataFacade> userDatas,
		List<ConfigFileFacade> configFiles, ScriptConfig scriptConfig, Map<String, String> envVars,
		List<Integer> ports) implements Serializable {
	private static final long serialVersionUID = 1L;
}
