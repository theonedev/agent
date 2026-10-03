package io.onedev.agent.job;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.k8shelper.PruneBuilderCacheFacade;

class PruneBuilderCacheTest {
	@TempDir
	Path buildDir;

	private final List<List<String>> commands = new ArrayList<>();

	private void prune(String options) throws Exception {
		Files.createDirectories(buildDir.resolve("work"));
		var docker = new Commandline("unused") {
			@Override
			public ExecutionResult execute(OutputStream out, OutputStream err) {
				commands.add(List.copyOf(args()));
				return new ExecutionResult(this);
			}
		};
		JobUtils.pruneBuilderCache(docker, "authorized-builder", new PruneBuilderCacheFacade(options),
				buildDir.toFile(), new TaskLogger() {
			@Override
			public void log(String message, String sessionId) { }
		});
	}

	@Test
	void rejectsBuilderAndEndpointOverridesBeforeInvokingDocker() {
		for (var options : List.of("--builder other", "--builder=other", "--builder =other",
				"--all --builder=other", "--context=other", "--host=tcp://other:2375",
				"-Htcp://other:2375", "--config=/other", "--force=false", "-f", "--", "other")) {
			assertThrows(ExplicitException.class, () -> prune(options), options);
			assertTrue(commands.isEmpty(), options);
		}
	}

	@Test
	void validatesOptionsIntroducedByWorkspacePlaceholders() throws Exception {
		Files.createDirectories(buildDir.resolve("work"));
		Files.writeString(buildDir.resolve("work/options"), "--builder=other");
		assertThrows(ExplicitException.class, () -> prune("<&onedev#work/options#onedev&>"));
		assertTrue(commands.isEmpty());
	}

	@Test
	void preservesPruneOptionsAndQuotedFilters() throws Exception {
		prune("--all --verbose=false --filter until=24h --filter=description~=hello "
				+ "--filter \"description~=hello world\" --keep-storage=1GB --max-used-space 2GB "
				+ "--min-free-space=3GB --reserved-space 512MB --timeout=30s");
		assertEquals(List.of(
				List.of("buildx", "create", "--name", "authorized-builder"),
				List.of("buildx", "prune", "--builder", "authorized-builder", "-f", "--all", "--verbose=false",
						"--filter=until=24h", "--filter=description~=hello", "--filter=description~=hello world",
						"--keep-storage=1GB", "--max-used-space=2GB", "--min-free-space=3GB",
						"--reserved-space=512MB", "--timeout=30s")), commands);
	}

	@Test
	void allowsDefaultsAndShortAllOption() throws Exception {
		for (var options : new String[] {null, "", "-a"}) {
			commands.clear();
			prune(options);
			var expected = new ArrayList<>(List.of("buildx", "prune", "--builder", "authorized-builder", "-f"));
			if ("-a".equals(options))
				expected.add("-a");
			assertEquals(expected, commands.get(1));
		}
	}

	@Test
	void rejectsMissingValuesAndOptionsSmuggledAsValues() {
		for (var options : List.of("--filter", "--filter=", "--filter --builder=other",
				"--timeout --builder other", "--reserved-space=")) {
			assertThrows(ExplicitException.class, () -> prune(options), options);
			assertTrue(commands.isEmpty(), options);
		}
	}
}
