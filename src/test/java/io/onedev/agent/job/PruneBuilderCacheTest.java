package io.onedev.agent.job;

import static org.junit.jupiter.api.Assertions.*;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
	void passesOptionsIntroducedByWorkspacePlaceholders() throws Exception {
		Files.createDirectories(buildDir.resolve("work"));
		Files.writeString(buildDir.resolve("work/options"), "--new-option=enabled");
		prune("<&onedev#work/options#onedev&>");
		assertEquals(List.of("buildx", "prune", "--builder", "authorized-builder", "-f", "--new-option=enabled"),
				commands.get(1));
	}

	@Test
	void preservesPruneOptionsAndQuotedFilters() throws Exception {
		prune("--all --verbose=false --filter until=24h --filter=description~=hello "
				+ "--filter \"description~=hello world\" --keep-storage=1GB --max-used-space 2GB "
				+ "--min-free-space=3GB --reserved-space 512MB --timeout=30s");
		assertEquals(List.of(
				List.of("buildx", "create", "--name", "authorized-builder"),
				List.of("buildx", "prune", "--builder", "authorized-builder", "-f", "--all", "--verbose=false",
						"--filter", "until=24h", "--filter=description~=hello", "--filter", "description~=hello world",
						"--keep-storage=1GB", "--max-used-space", "2GB", "--min-free-space=3GB",
						"--reserved-space", "512MB", "--timeout=30s")), commands);
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

}
