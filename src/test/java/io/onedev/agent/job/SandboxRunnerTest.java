package io.onedev.agent.job;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;

@EnabledOnOs({OS.LINUX, OS.MAC})
class SandboxRunnerTest {

	@Test
	void pythonRunnerSuite() {
		// Keep runner events printed by tests from being interpreted by the enclosing build.
		new Commandline(System.getProperty("onedev.python.executable", "python3"))
				.workingDir(new File(System.getProperty("basedir", ".")))
				.addArgs("-B", "src/test/python/test_sandbox_runner.py", "--verbose")
				.timeout(60)
				.execute(new LineConsumer("UTF-8") {
					@Override
					public void consume(String line) {
						System.out.println("[SandboxRunnerTest] " + line);
					}
				}, new LineConsumer("UTF-8") {
					@Override
					public void consume(String line) {
						System.err.println("[SandboxRunnerTest] " + line);
					}
				}).checkReturnCode();
	}
}
