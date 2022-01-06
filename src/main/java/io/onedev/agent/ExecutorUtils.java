package io.onedev.agent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.StringUtils;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.OsInfo;

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
		AtomicReference<String> osVersion = new AtomicReference<>(null);
		if (SystemUtils.IS_OS_WINDOWS) {
			osName = "Windows";
			
			logger.info("Checking Windows OS version...");
			
			Commandline systemInfo = new Commandline("systemInfo");
			
			systemInfo.execute(new LineConsumer() {

				@Override
				public void consume(String line) {
					if (line.startsWith("OS Version:")) 
						osVersion.set(StringUtils.substringBefore(StringUtils.substringAfter(line, ":").trim(), " "));
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.error(line);
				}
				
			}).checkReturnCode();

			if (osVersion.get() == null)
				throw new ExplicitException("Unable to find Windows OS version");
		} else {
			osName = System.getProperty("os.name");
			osVersion.set(System.getProperty("os.version"));
		}

		return new OsInfo(osName, osVersion.get(), System.getProperty("os.arch"));
	}

}
