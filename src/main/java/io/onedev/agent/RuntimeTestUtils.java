package io.onedev.agent;

import static io.onedev.agent.AgentUtils.callWithRegistryLogins;
import static io.onedev.agent.AgentUtils.newInfoLogger;
import static io.onedev.agent.AgentUtils.newWarningLogger;
import static io.onedev.k8shelper.KubernetesHelper.buildRestClient;
import static io.onedev.k8shelper.KubernetesHelper.checkStatus;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.function.Function;

import jakarta.ws.rs.core.UriBuilder;

import org.apache.commons.lang3.SystemUtils;
import org.jspecify.annotations.Nullable;

import io.onedev.agent.workspace.TestDockerWorkspaceData;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.PosixFacility;
import io.onedev.k8shelper.RegistryLoginFacade;
import io.onedev.k8shelper.ShellFacility;
import io.onedev.k8shelper.WindowsBatchFacility;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.util.CertificateUtils;

/** Checks shared by executor and workspace provisioner configuration tests. */
public final class RuntimeTestUtils {

	private RuntimeTestUtils() {
	}

	public static void testServerConnection(String serverUrl, String token,
			@Nullable SSLFactory sslFactory, TaskLogger logger) {
		logger.log(String.format("Connecting to server '%s' from agent host...", serverUrl));
		try (var client = buildRestClient(sslFactory)) {
			var target = client.target(serverUrl)
					.path("~api/worker/test")
					.queryParam("token", token);
			try (var response = target.request().get()) {
				checkStatus(response);
			}
		}
	}

	/** Host executors check commands; host workspaces also check their tmux executable. */
	public static void testHost(@Nullable Commandline tmux, TaskLogger logger) {
		testCommands(logger);
		if (tmux != null)
			testTmuxAvailability(tmux, logger);
	}

	/** Shared by Docker and Kubernetes tests of the selected image. */
	public static String getContainerTestCommands(boolean workspace) {
		return workspace
				? "git --version && git lfs version && tmux -V && echo hello from container"
				: "echo hello from container";
	}

	public static void testDocker(Commandline docker, Collection<RegistryLoginFacade> registryLogins,
								  String dockerImage, @Nullable String cpuLimit,
								  @Nullable String memoryLimit, @Nullable String dockerOptions,
								  boolean testWorkspace,
								  Function<String, String> hostPathResolver,
								  TaskLogger taskLogger) {
		callWithRegistryLogins(docker, registryLogins, () -> {
			File testDir = FileUtils.createTempDir("docker-test");
			try {
				taskLogger.log("Testing specified docker image...");
				docker.args("run", "--rm");
				if (cpuLimit != null)
					docker.addArgs("--cpus", cpuLimit);
				if (memoryLimit != null)
					docker.addArgs("--memory", memoryLimit);
				if (dockerOptions != null)
					docker.addArgs(StringUtils.parseQuoteTokens(dockerOptions));
				docker.addArgs("-v", hostPathResolver.apply(testDir.getAbsolutePath()) + ":" + "/onedev-docker-test");
				docker.addArgs("-w", "/onedev-docker-test");
				var commands = getContainerTestCommands(testWorkspace);
				docker.addArgs(dockerImage, "sh", "-c", commands);
				docker.execute(newInfoLogger(taskLogger), newWarningLogger(taskLogger)).checkReturnCode();

				taskLogger.log("Checking busybox availability...");
				docker.args("run", "--rm", "busybox", "sh", "-c", "echo hello from busybox");
				docker.execute(newInfoLogger(taskLogger), newWarningLogger(taskLogger)).checkReturnCode();
			} finally {
				FileUtils.deletePath(testDir);
			}
			return null;
		});
	}

	public static void testDocker(Commandline docker, TestDockerData testData,
								  Function<String, String> hostPathResolver,
								  TaskLogger taskLogger) {
		testDocker(docker, testData.getRegistryLogins(), testData.getDockerImage(), testData.getCpuLimit(),
				testData.getMemoryLimit(), testData.getDockerOptions(), testData instanceof TestDockerWorkspaceData, hostPathResolver,
				taskLogger);
	}

	public static void testDockerServerConnection(Commandline docker, Collection<RegistryLoginFacade> registryLogins,
			String serverUrl, String token, @Nullable String dockerOptions,
			@Nullable SSLFactory sslFactory, TaskLogger logger) {
		logger.log(String.format("Connecting to server '%s' from Docker container...", serverUrl));
		callWithRegistryLogins(docker, registryLogins, () -> {
			docker.args("run", "--rm");
			if (dockerOptions != null)
				docker.addArgs(StringUtils.parseQuoteTokens(dockerOptions));
			ByteArrayInputStream trustCerts = null;
			if (sslFactory != null) {
				// Include the same default and custom roots used by host connectivity tests.
				var pem = String.join("\n", CertificateUtils.convertToPem(sslFactory.getTrustedCertificates()));
				trustCerts = new ByteArrayInputStream(pem.getBytes(UTF_8));
				docker.addArgs("-i");
			}
			docker.addArgs("--entrypoint", "curl", "curlimages/curl:8.23.0",
					"--fail", "--silent", "--show-error", "--location",
					"--connect-timeout", "30", "--max-time", "60",
					"--output", "/dev/null", "--write-out", "%{http_code}");
			if (trustCerts != null)
				docker.addArgs("--cacert", "/dev/stdin");
			var url = UriBuilder.fromUri(serverUrl).path("~api/worker/test")
					.queryParam("token", token).build();
			docker.addArgs(url.toString());
			var output = new ByteArrayOutputStream();
			docker.execute(output, newWarningLogger(logger), trustCerts).checkReturnCode();
			var status = output.toString(UTF_8).trim();
			if (!status.equals("200") && !status.equals("204"))
				throw new ExplicitException("Http request failed with status " + status);
			logger.log("Successfully connected to server from Docker container");
			return null;
		});
	}

	private static void testCommands(TaskLogger taskLogger) {
		var testDir = FileUtils.createTempDir("commands-test");
		try {
			taskLogger.log("Running test commands...");

			ShellFacility shellFacility;
			if (SystemUtils.IS_OS_WINDOWS)
				shellFacility = new WindowsBatchFacility();
			else
				shellFacility = new PosixFacility();
			var cmdline = new Commandline(shellFacility.getExecutable());
			cmdline.addArgs(shellFacility.getScriptOptions());

			var testScriptFile = new File(testDir, "test" + shellFacility.getScriptExtension());
			FileUtils.writeStringToFile(testScriptFile, "echo 'hello from shell'", UTF_8);
			cmdline.workingDir(testDir).addArgs(testScriptFile.getAbsolutePath());
			cmdline.execute(newInfoLogger(taskLogger), newWarningLogger(taskLogger)).checkReturnCode();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			FileUtils.deletePath(testDir);
		}
	}

	private static void testTmuxAvailability(Commandline tmux, TaskLogger logger) {
		logger.log("Checking if tmux exists...");

		tmux.args("-V");

		var result = tmux.execute(new LineConsumer() {

			@Override
			public void consume(String line) {
			}

		}, new LineConsumer() {

			@Override
			public void consume(String line) {
				logger.error(line);
			}

		});
		if (result.getReturnCode() == 0) {
			logger.log("tmux found");
		} else {
			throw new ExplicitException("tmux not found");
		}
	}

}
