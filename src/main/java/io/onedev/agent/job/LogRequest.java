package io.onedev.agent.job;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import io.onedev.commons.utils.FileUtils;

public class LogRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	public static List<String> readLog(File logFile) {
    	List<String> lines = new ArrayList<>();
    	int index = logFile.getParentFile().list().length;
    	try {
			File logDir = logFile.getParentFile();
			for (int i=index; i>=1; i--) {
				File rollFile = new File(logDir, logFile.getName() + "." + i);
				if (rollFile.exists())
					lines.addAll((FileUtils.readLines(rollFile, StandardCharsets.UTF_8)));
			}
			lines.addAll((FileUtils.readLines(logFile, StandardCharsets.UTF_8)));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
    	return lines;
	}
	
}
