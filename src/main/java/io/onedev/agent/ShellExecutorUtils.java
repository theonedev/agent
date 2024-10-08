package io.onedev.agent;

import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.k8shelper.CommandFacade;
import io.onedev.k8shelper.KubernetesHelper;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ShellExecutorUtils {

	public static void testCommands(Commandline git, String commands, TaskLogger jobLogger) {
		CommandFacade executable = new CommandFacade(null, null, null, commands, new HashMap<>(), true);
		Commandline interpreter = executable.getScriptInterpreter();
		File buildDir = FileUtils.createTempDir("onedev-build");
		try {
			jobLogger.log("Running specified commands...");
			
			File testScriptFile = new File(buildDir, "test" + executable.getScriptExtension());
			FileUtils.writeStringToFile(testScriptFile, executable.normalizeCommands(commands), UTF_8);
			File workspaceDir = new File(buildDir, "workspace");
			FileUtils.createDir(workspaceDir);
			interpreter.workingDir(workspaceDir).addArgs(testScriptFile.getAbsolutePath());
			interpreter.execute(ExecutorUtils.newInfoLogger(jobLogger), ExecutorUtils.newWarningLogger(jobLogger)).checkReturnCode();

			KubernetesHelper.testGitLfsAvailability(git, jobLogger);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			FileUtils.deleteDir(buildDir);
		}
	}
	
}
