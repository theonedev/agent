package io.onedev.agent.job;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import io.onedev.commons.utils.FileUtils;

public class LogRequest implements Serializable {

	private static final long serialVersionUID = 1L;

	private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

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
	
	public static List<String> toZoneId(List<String> logLines, ZoneId zoneId) {
		var zonedLogLines = new ArrayList<String>();
		for (var logLine: logLines) {
			if (zoneId.equals(ZoneId.systemDefault())) {
				zonedLogLines.add(logLine);
			} else {
				int secondSpaceIndex = -1;
				if (logLine.length() > 0) {
					int firstSpaceIndex = logLine.indexOf(' ');
					if (firstSpaceIndex != -1 && firstSpaceIndex + 1 < logLine.length()) 
						secondSpaceIndex = logLine.indexOf(' ', firstSpaceIndex + 1);
				}
				if (secondSpaceIndex != -1) {						
					try {
						var dateString = logLine.substring(0, secondSpaceIndex);				
						var localDate = LocalDateTime.parse(dateString, dateFormatter);
						var zonedDate = localDate.atZone(ZoneId.systemDefault());
						dateString = zonedDate.withZoneSameInstant(zoneId).format(dateFormatter);
						zonedLogLines.add(dateString + logLine.substring(secondSpaceIndex));
					} catch (DateTimeParseException ignored) {
						zonedLogLines.add(logLine);
					}
				} else {
					zonedLogLines.add(logLine);
				}
			}
		}
		return zonedLogLines;
	}

}
