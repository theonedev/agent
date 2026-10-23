package io.onedev.agent;

import java.io.Serializable;
import java.util.List;

import io.onedev.k8shelper.RegistryLoginFacade;

/** Settings shared by the server and remote sandbox launchers, with the server URL resolved by OneDev. */
public record SandboxSettings(String template, Integer cpus, String memory,
							  boolean alwaysPullImage, boolean alwaysPullContainerImage,
							  String serverUrl, String cpuLimit, String memoryLimit,
							  String runOptions, String networkOptions,
							  List<RegistryLoginFacade> registryLogins) implements Serializable {

	private static final long serialVersionUID = 1L;
}
