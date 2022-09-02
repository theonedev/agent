package io.onedev.agent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;

import javax.annotation.Nullable;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HttpHeaders;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.OsInfo;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;

public class Agent {

	private static final Logger logger = LoggerFactory.getLogger(Agent.class);
	
	// requires at least 2.11.1 to use allowAnySHA1InWant
	public static final String GIT_MIN_VERSION = "2.11.1";
	
	public static final String LOGBACK_CONFIG_FILE_PROPERTY_NAME = "logback.configurationFile";
	
	public static final String BEARER = "Bearer";
	
	public static final long SOCKET_IDLE_TIMEOUT = 30000;
	
	public static final int MAX_MESSAGE_CHARS = 64*1024*1024;
	
	public static final int MAX_MESSAGE_BYTES = MAX_MESSAGE_CHARS*4+100;
	
	public static final String SERVER_URL_KEY = "serverUrl";
	
	public static final String AGENT_TOKEN_KEY = "agentToken";
	
	public static final String AGENT_TOKEN_FILE_KEY = "agentTokenFile";
	
	public static final String AGENT_NAME_KEY = "agentName";
	
	public static final String AGENT_CPU_KEY = "agentCpu";
	
	public static final String AGENT_MEMORY_KEY = "agentMemory";
	
	public static final String GIT_PATH_KEY = "gitPath";
	
	public static final String DOCKER_PATH_KEY = "dockerPath";
	
	public static final String TEMPORAL_AGENT_KEY = "temporalAgent";
	
	public static boolean sandboxMode;
	
	public static File installDir;
	
	private static volatile boolean stopping;
	
	private static volatile boolean stopped;
	
	private static Thread thread;
	
	public static String serverUrl;
	
	public static String token;
	
	public static int cpu;
	
	public static int memory;
	
	public static String gitPath;
	
	public static String dockerPath;
	
	public static volatile boolean reconnect;
	
	public static String version;
	
	public static String name;
	
	public static String ipAddress;
	
	public static boolean temporal;
	
	public static OsInfo osInfo;
	
	public static Class<?> wrapperManagerClass;
	
	public static volatile Map<String, String> attributes;
	
	public static ObjectMapper objectMapper = new ObjectMapper();
	
	private static Object cacheHomeCreationLock = new Object();
	
	private static volatile WebSocketClient client;
	
	public static void main(String[] args) throws Exception {
		thread = Thread.currentThread();
		
		Runtime.getRuntime().addShutdownHook(new Thread() {
			
			public void run() {
				logger.info("Waiting for running jobs to finish...");
				if (client != null) {
					for (Session session: client.getOpenSessions()) { 
						try {
							WebsocketUtils.call(session, new WaitingForAgentResourceToBeReleased(), 0);
						} catch (InterruptedException | TimeoutException e) {
							logger.error("Error waiting for running jobs", e);
						}
					}
				}
				
				stopping = true;
				while (!stopped) {
					thread.interrupt();
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
					}
				}
			}
			
		});
		
		try {
			wrapperManagerClass = Class.forName("org.tanukisoftware.wrapper.WrapperManager");
		} catch (ClassNotFoundException e) {
		}
		
		try {
			sandboxMode = new File("target/sandbox").exists();
	
			String path = Agent.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
			File loadedFrom = new File(path);
			
			if (loadedFrom.getParentFile() != null 
					&& loadedFrom.getParentFile().getParentFile() != null
					&& loadedFrom.getParentFile().getParentFile().getParentFile() != null
					&& new File(loadedFrom.getParentFile().getParentFile().getParentFile(), "conf/agent.properties").exists()) {
				installDir = loadedFrom.getParentFile().getParentFile().getParentFile();
			} else if (new File("target/sandbox").exists()) {
				installDir = new File("target/sandbox");
			} else {
				throw new RuntimeException("Unable to find agent directory");
			}
	
			installDir = installDir.getCanonicalFile();
			
			configureLogging();
			
			Properties agentProps = new Properties();
			
			try (InputStream is = new FileInputStream(new File(installDir, "conf/agent.properties"))) {
				agentProps.load(is);
			}
			
			name = System.getenv(AGENT_NAME_KEY);
			if (StringUtils.isBlank(name))
				name = System.getProperty(AGENT_NAME_KEY);
			if (StringUtils.isBlank(name))
				name = agentProps.getProperty(AGENT_NAME_KEY);
			if (StringUtils.isBlank(name)) 
				name = InetAddress.getLocalHost().getHostName();
			
	        File tempDir = getTempDir();
			if (tempDir.exists()) {
				logger.info("Cleaning temp directory...");
				FileUtils.cleanDir(tempDir);
			} else if (!tempDir.mkdirs()) {
				throw new RuntimeException("Can not create directory '" + tempDir.getAbsolutePath() + "'");
			}
			
			System.setProperty("java.io.tmpdir", tempDir.getAbsolutePath());
			
			try (InputStream is = Agent.class.getClassLoader().getResourceAsStream("META-INF/onedev-agent.properties")) {
				Properties props = new Properties();
				props.load(is);
				version = props.getProperty("version");
			} 

			File libDir = new File(installDir, "lib");
			if (libDir.exists()) {
				for (File dir: libDir.listFiles()) {
					if (!dir.getName().equals(version))
						FileUtils.deleteDir(dir);
				}
			}
			
			try {
				ipAddress = InetAddress.getLocalHost().getHostAddress();
			} catch (UnknownHostException e) {
				ipAddress = "unknown";
			}
			
			Properties attributeProps = new Properties();
			
			try (InputStream is = new FileInputStream(new File(installDir, "conf/attributes.properties"))) {
				attributeProps.load(is);
				LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
				for (Map.Entry<Object, Object> entry: attributeProps.entrySet()) 
					attributes.put((String)entry.getKey(), (String)entry.getValue());
				Agent.attributes = attributes;
			}

			osInfo = ExecutorUtils.getOsInfo();
			
			serverUrl = System.getenv(SERVER_URL_KEY);
			if (StringUtils.isBlank(serverUrl))
				serverUrl = System.getProperty(SERVER_URL_KEY);
			if (StringUtils.isBlank(serverUrl))
				serverUrl = agentProps.getProperty(SERVER_URL_KEY);
			if (StringUtils.isBlank(serverUrl)) 
				throw new ExplicitException("Property '" + SERVER_URL_KEY + "' not specified");
			
			serverUrl = StringUtils.stripEnd(serverUrl.trim(), "/");
	
			String websocketUrl = serverUrl;
			if (websocketUrl.startsWith("https://")) 
				websocketUrl = websocketUrl.replace("https://", "wss://");
			else if (websocketUrl.startsWith("http://")) 
				websocketUrl = websocketUrl.replace("http://", "ws://");
			else 
				throw new ExplicitException("Property '" + SERVER_URL_KEY + "' should start either with 'http://' or 'https://'");
			
			websocketUrl = websocketUrl + "/server";
			
			token = System.getenv(AGENT_TOKEN_KEY);
			if (StringUtils.isBlank(token))
				token = System.getProperty(AGENT_TOKEN_KEY);
			if (StringUtils.isBlank(token)) {
				// Fix issue https://code.onedev.io/projects/160/issues/601
				String tokenFilePath = System.getenv(AGENT_TOKEN_FILE_KEY);
				if (StringUtils.isBlank(tokenFilePath))
					tokenFilePath = System.getProperty(AGENT_TOKEN_FILE_KEY);
				if (StringUtils.isBlank(tokenFilePath))
					tokenFilePath = agentProps.getProperty(AGENT_TOKEN_FILE_KEY);
				if (StringUtils.isNotBlank(tokenFilePath)) {
					File tokenFile = new File(tokenFilePath);
					if (!tokenFile.isAbsolute())
						tokenFile = new File(installDir, "conf/" + tokenFilePath);
					token = FileUtils.readFileToString(tokenFile, StandardCharsets.UTF_8.name()).trim();
				}
			}
			
			if (StringUtils.isBlank(token))
				token = agentProps.getProperty(AGENT_TOKEN_KEY);
			
			if (StringUtils.isBlank(token)) 
				throw new ExplicitException("Property '" + AGENT_TOKEN_KEY + "' not specified");
			
			HardwareAbstractionLayer hardware = null;
			try {
				hardware = new SystemInfo().getHardware();
			} catch (Exception e) {
				logger.debug("Error calling oshi", e);
			}
			
			String temporalString = System.getenv(TEMPORAL_AGENT_KEY);
			if (StringUtils.isBlank(temporalString))
				temporalString = System.getProperty(TEMPORAL_AGENT_KEY);
			if (StringUtils.isBlank(temporalString))
				temporalString = agentProps.getProperty(TEMPORAL_AGENT_KEY);
			if (StringUtils.isBlank(temporalString)) 
				temporal = false;
			else 
				temporal = Boolean.parseBoolean(temporalString);
			
			String cpuString = System.getenv(AGENT_CPU_KEY);
			if (StringUtils.isBlank(cpuString))
				cpuString = System.getProperty(AGENT_CPU_KEY);
			if (StringUtils.isBlank(cpuString))
				cpuString = agentProps.getProperty(AGENT_CPU_KEY);
			if (StringUtils.isBlank(cpuString)) {
				if (hardware != null) {
					cpu = hardware.getProcessor().getLogicalProcessorCount()*1000;
				} else {
					cpu = 4000;
					logger.warn("Unable to call oshi to get default cpu quota (cpu cores x 1000). Assuming as 4000. "
							+ "Configure it manually via environment variable or system property 'agentCpu' if you "
							+ "do not want to use this value");
				}
			} else {
				try {
					cpu = Integer.parseInt(cpuString);
				} catch (NumberFormatException e) {
					throw new ExplicitException("Property '" + AGENT_CPU_KEY + "' should be a number");
				}
			}

			String memoryString = System.getenv(AGENT_MEMORY_KEY);
			if (StringUtils.isBlank(memoryString))
				memoryString = System.getProperty(AGENT_MEMORY_KEY);
			if (StringUtils.isBlank(memoryString))
				memoryString = agentProps.getProperty(AGENT_MEMORY_KEY);
			if (StringUtils.isBlank(memoryString)) {
				if (hardware != null) {
					memory = (int) (hardware.getMemory().getTotal()/1024/1024); 
				} else {
					memory = 8000;
					logger.warn("Unable to call oshi to get default memory quota (mega bytes of physical memory). "
							+ "Assuming as 8000. Configure it manually via environment variable or system property "
							+ "'agentMemory' if you do not want to use this value");
				}
			} else {
				try {
					memory = Integer.parseInt(memoryString);
				} catch (NumberFormatException e) {
					throw new ExplicitException("Property '" + AGENT_MEMORY_KEY + "' should be a number");
				}
			}
			
			gitPath = System.getenv(GIT_PATH_KEY);
			if (StringUtils.isBlank(gitPath))
				gitPath = System.getProperty(GIT_PATH_KEY);
			if (StringUtils.isBlank(gitPath))
				gitPath = agentProps.getProperty(GIT_PATH_KEY);
			if (StringUtils.isBlank(gitPath)) {
				if (SystemUtils.IS_OS_MAC_OSX && new File("/usr/local/bin/git").exists())
					gitPath = "/usr/local/bin/git";
				else
					gitPath = "git";
			}
			
			String gitError = checkGitError(gitPath, GIT_MIN_VERSION);
			if (gitError != null)
				throw new ExplicitException(gitError);
			
			dockerPath = System.getenv(DOCKER_PATH_KEY);
			if (StringUtils.isBlank(dockerPath))
				dockerPath = System.getProperty(DOCKER_PATH_KEY);
			if (StringUtils.isBlank(dockerPath))
				dockerPath = agentProps.getProperty(DOCKER_PATH_KEY);
			if (StringUtils.isBlank(dockerPath)) {
				if (SystemUtils.IS_OS_MAC_OSX && new File("/usr/local/bin/docker").exists())
					dockerPath = "/usr/local/bin/docker";
				else
					dockerPath = "docker";
			}

			client = new WebSocketClient();
			client.setStopAtShutdown(false);
			client.setMaxIdleTimeout(SOCKET_IDLE_TIMEOUT);
			client.getPolicy().setMaxTextMessageSize(MAX_MESSAGE_BYTES);
			client.getPolicy().setMaxBinaryMessageSize(MAX_MESSAGE_BYTES);
			
			ClientUpgradeRequest request = new ClientUpgradeRequest();
			request.setHeader(HttpHeaders.AUTHORIZATION, BEARER + " " + token);
			
			while (!stopping) {
				logger.info("Connecting to " + serverUrl + "...");
				
				reconnect = false;
				client.start();
				client.connect(new AgentSocket(), new URI(websocketUrl), request);
				
				while (!reconnect && !stopping) {
					try {
						Thread.sleep(5000);
					} catch (Exception e) {
					}
				}

				try {
					client.stop();
				} catch (Exception e) {
				}
			}
		} catch (Exception e) {
			logger.error("Error running agent", e);
		} finally {
			stopped = true;
		}
	}
	
	public static File getTempDir() {
		// Fix issue https://code.onedev.io/projects/160/issues/601
		return new File(getWorkDir(), name + "/temp");
	}

	@Nullable
	public static String checkGitError(String gitExe, String minVersion) {
		try {
			final String[] version = new String[]{null};
			
			new Commandline(gitExe).addArgs("--version").execute(new LineConsumer() {
	
				@Override
				public void consume(String line) {
					if (line.startsWith("git version "))
						version[0] = line.substring("git version ".length());
				}
				
			}, new LineConsumer() {

				@Override
				public void consume(String line) {
					logger.error(line);
				}
				
			}).checkReturnCode();

			if (version[0] == null)
				return "Unable to determine git version of '" + gitExe + "'";
			
			GitVersion gitVersion = new GitVersion(version[0]);
			
			if (gitVersion.isOlderThan(new GitVersion(minVersion)))
				return "Version of git is " + gitVersion + ". Requires at least " + minVersion;
			
			return null;
		} catch (Exception e) {
			String message = ExceptionUtils.getMessage(e);
			if (message.contains("CreateProcess error=2"))
				return "Unable to find git command: " + gitExe;
			else if (message.contains("error launching git"))
				return "Unable to launch git command: " + gitExe;
			else
				return message;
		}
	}
	
	private static void configureLogging() {
		// Set system properties so that they can be used in logback
		// configuration file.
		System.setProperty("logback.logFile", installDir.getAbsolutePath() + "/logs/agent.log");
		System.setProperty("logback.consoleLogPattern", "%d{HH:mm:ss} %-5level %logger{36} - %msg%n");			
		System.setProperty("logback.fileLogPattern", "%date %-5level [%thread] %logger{36} %msg%n");

		File configFile = new File(installDir, "conf/logback.xml");
		System.setProperty(LOGBACK_CONFIG_FILE_PROPERTY_NAME, configFile.getAbsolutePath());

		LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();

		try {
			JoranConfigurator configurator = new JoranConfigurator();
			configurator.setContext(lc);
			lc.reset();
			configurator.doConfigure(configFile);
		} catch (JoranException je) {
			je.printStackTrace();
		}
		StatusPrinter.printInCaseOfErrorsOrWarnings(lc);

		// Redirect JDK logging to slf4j
		java.util.logging.Logger jdkLogger = java.util.logging.Logger.getLogger("");
		for (Handler handler : jdkLogger.getHandlers())
			jdkLogger.removeHandler(handler);
		SLF4JBridgeHandler.install();
	}

	public static void restart() {
		if (wrapperManagerClass != null) {
			try {
				Method method = wrapperManagerClass.getDeclaredMethod("restartAndReturn");
				method.invoke(null, new Object[0]);
			} catch (Exception e) {
				logger.error("Error restarting agent", e);
			}
		} else {
			logger.warn("Restart request ignored as there is no wrapper manager available");
		}
	}
	
	public static boolean isInDocker() {
		return new File(installDir, "IN_DOCKER").exists();
	}
	
	public static void stop() {
		if (wrapperManagerClass != null) {
			try {
				Method method = wrapperManagerClass.getDeclaredMethod("stopAndReturn");
				method.invoke(null, new Object[0]);
			} catch (Exception e) {
				logger.error("Error restarting agent", e);
			}
		} else {
			logger.warn("Stop request ignored as there is no wrapper manager available");
		}
	}
	
	public static File getWorkDir() {
		return new File(installDir, "work");
	}
	
	public static void log(Session session, String jobToken, String message, @Nullable String sessionId) {
		if (sessionId == null)
			sessionId = "";
		new Message(MessageType.JOB_LOG, jobToken + ":" + sessionId + ":" + message).sendBy(session);
	}
	
	public static File getCacheHome(String executorName) {
		// Fix issue https://code.onedev.io/projects/160/issues/601
		File file = new File(getWorkDir(), name + "/cache/" + executorName);
		if (!file.exists()) synchronized (cacheHomeCreationLock) {
			FileUtils.createDir(file);
		}
		return file;
	}
		
}
