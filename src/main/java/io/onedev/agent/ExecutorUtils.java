package io.onedev.agent;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.CompositeFacade;
import io.onedev.k8shelper.OsInfo;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static io.onedev.agent.DockerExecutorUtils.getErrorMessage;
import static io.onedev.k8shelper.KubernetesHelper.formatDuration;

public class ExecutorUtils {

	private static final Logger logger = LoggerFactory.getLogger(ExecutorUtils.class);

	public static LineConsumer newInfoLogger(TaskLogger jobLogger) {
		return new LineConsumer(StandardCharsets.UTF_8.name()) {
	
			private String sessionId = UUID.randomUUID().toString();
			
			@Override
			public void consume(String line) {
				jobLogger.log(line, sessionId);
			}
			
		};
	}

	public static LineConsumer newWarningLogger(TaskLogger jobLogger) {
		return new LineConsumer(StandardCharsets.UTF_8.name()) {
	
			@Override
			public void consume(String line) {
				jobLogger.warning(line);
			}
			
		};
	}

	public static LineConsumer newErrorLogger(TaskLogger jobLogger) {
		return new LineConsumer(StandardCharsets.UTF_8.name()) {
	
			@Override
			public void consume(String line) {
				jobLogger.error(line);
			}
			
		};
	}
	
	public static OsInfo getOsInfo() {
		String osName;
		String osVersion;
		if (SystemUtils.IS_OS_WINDOWS) {
			osName = "Windows";
			
			logger.info("Checking Windows OS version...");
			
			Commandline systemInfo = new Commandline("cmd").addArgs("/c", "ver");
			
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			systemInfo.execute(baos, new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.error(line);
				}
				
			}).checkReturnCode();
			
			String output = baos.toString();
			osVersion = StringUtils.substringBeforeLast(output, ".");
			osVersion = StringUtils.substringAfterLast(osVersion, " ");
			logger.info("Windows OS version: " + osVersion);
		} else {
			osName = System.getProperty("os.name");
			osVersion = System.getProperty("os.version");
		}

		return new OsInfo(osName, osVersion, System.getProperty("os.arch"));
	}

	public static boolean runStep(CompositeFacade entry, List<Integer> position,
								  TaskLogger logger, Callable<Boolean> task) {
		String stepPath = entry.getPathAsString(position);
		logger.notice("Running step \"" + stepPath + "\"...");
		try {
			long time = System.currentTimeMillis();
			var successful = task.call();
			var duration = formatDuration(System.currentTimeMillis() - time);
			if (successful)
				logger.success("Step \"" + stepPath + "\" is successful (" + duration + ")");
			else
				logger.error("Step \"" + stepPath + "\" is failed (" + duration + ")");
			return successful;
		} catch (Exception e) {
			if (ExceptionUtils.find(e, InterruptedException.class) == null) {
				logger.error(getErrorMessage(e));
				return false;
			} else {
				throw ExceptionUtils.unchecked(e);
			}
		}
	}
}
