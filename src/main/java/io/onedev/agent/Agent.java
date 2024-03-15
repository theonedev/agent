package io.onedev.agent;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HttpHeaders;
import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.LineConsumer;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.k8shelper.OsInfo;
import nl.altindag.ssl.SSLFactory;
import nl.altindag.ssl.util.JettySslUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;
import oshi.SystemInfo;

import javax.annotation.Nullable;
import java.io.*;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;

import static io.onedev.commons.bootstrap.Bootstrap.setupProxies;

public class Agent {

	private static final Logger logger = LoggerFactory.getLogger(Agent.class);
	
	// requires at least 2.11.1 to use allowAnySHA1InWant
	public static final String GIT_MIN_VERSION = "2.11.1";
	
	public static final String LOGBACK_CONFIG_FILE_PROPERTY_NAME = "logback.configurationFile";
	
	public static final long SOCKET_IDLE_TIMEOUT = 30000;
	
	public static final int MAX_MESSAGE_CHARS = 64*1024*1024;
	
	public static final int MAX_MESSAGE_BYTES = MAX_MESSAGE_CHARS*4+100;
	
	public static final String SERVER_URL_KEY = "serverUrl";
	
	public static final String AGENT_TOKEN_KEY = "agentToken";
	
	public static final String AGENT_TOKEN_FILE_KEY = "agentTokenFile";
	
	public static final String AGENT_NAME_KEY = "agentName";
	
	public static final String GIT_PATH_KEY = "gitPath";
	
	public static final String DOCKER_PATH_KEY = "dockerPath";

	public static boolean sandboxMode;
	
	public static File installDir;
	
	private static volatile boolean stopping;
	
	private static volatile boolean stopped;
	
	private static Thread thread;
	
	public static String serverUrl;
	
	public static String token;
	
	public static int cpus;
	
	public static String gitPath;
	
	public static String dockerPath;
	
	public static volatile boolean reconnect;
	
	public static String version;
	
	public static String name;
	
	public static String ipAddress;

	public static OsInfo osInfo;
	
	private static Class<?> wrapperManagerClass;
	
	public static volatile Map<String, String> attributes;
	
	public static ObjectMapper objectMapper = new ObjectMapper();

	public static SSLFactory sslFactory;

	private static volatile WebSocketClient client;
	
	public static void main(String[] args) throws Exception {
		thread = Thread.currentThread();
		
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			if (client != null) {
				for (Session session: client.getOpenSessions()) {
					logger.info("Waiting for running jobs to finish...");
					try {
						WebsocketUtils.call(session, new WantToDisconnectAgent(), 0);
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
		}));
		
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

			File testFile = new File(installDir, "test");
			try (OutputStream os = new FileOutputStream(testFile)){
				os.write(UUID.randomUUID().toString().getBytes());
			} catch (Exception e) {
				System.err.println(String.format("Unable to write test file. Make sure current user owns everything under '%s'",
						installDir.getAbsolutePath()));
				System.exit(1);
			}

			setupProxies();
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
				for (var child: tempDir.listFiles()) {
					try {
						if (child.isFile())
							FileUtils.deleteFile(child);
						else
							FileUtils.deleteDir(child);
					} catch (Exception e) {
						String errorMessage = "Unable to delete '" + child.getAbsolutePath() + "'";
						if (child.getName().startsWith("onedev-build"))
							logger.warn(errorMessage);
						else
							throw new RuntimeException(errorMessage);
					}
				}
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
			
			URI serverUri = new URI(serverUrl);
			int serverPort = serverUri.getPort();
			if (serverPort == -1) {
				if (serverUri.getScheme().equals("http"))
					serverPort = 80;
				else
					serverPort = 443;
			}

			while (true) {
				if (stopping) {
					stopped = true;
					System.exit(0);
				} else {
					try (Socket socket = new Socket()) {
						logger.info("Connecting to " + serverUrl + "...");
						socket.connect(new InetSocketAddress(serverUri.getHost(), serverPort));
						ipAddress = socket.getLocalAddress().getHostAddress();
						break;
					} catch (Exception e) {
				    	if (!logExpectedError(e, logger))
				    		logger.error("Error connecting to server", e);
						try {
							Thread.sleep(5000);
						} catch (Exception e2) {
						}
					}
				}
			}
			
			String websocketUrl = serverUrl;
			if (websocketUrl.startsWith("https://")) 
				websocketUrl = websocketUrl.replace("https://", "wss://");
			else if (websocketUrl.startsWith("http://")) 
				websocketUrl = websocketUrl.replace("http://", "ws://");
			else 
				throw new ExplicitException("Property '" + SERVER_URL_KEY + "' should start either with 'http://' or 'https://'");
			
			websocketUrl = websocketUrl + "/~server";
			
			token = System.getenv(AGENT_TOKEN_KEY);
			if (StringUtils.isBlank(token))
				token = System.getProperty(AGENT_TOKEN_KEY);
			if (StringUtils.isBlank(token)) {
				// Fix issue https://code.onedev.io/onedev/server/~issues/601
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
			
			try {
				cpus = new SystemInfo().getHardware().getProcessor().getLogicalProcessorCount();
			} catch (Exception e) {
				logger.debug("Error calling oshi", e);
				cpus = 2;
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

			sslFactory = KubernetesHelper.buildSSLFactory(getTrustCertsDir());
			SslContextFactory.Client sslContextFactory = JettySslUtils.forClient(sslFactory);

			HttpClient httpClient = new HttpClient(sslContextFactory);
			client = new WebSocketClient(httpClient);

			client.setStopAtShutdown(false);
			client.setMaxIdleTimeout(SOCKET_IDLE_TIMEOUT);
			client.getPolicy().setMaxTextMessageSize(MAX_MESSAGE_BYTES);
			client.getPolicy().setMaxBinaryMessageSize(MAX_MESSAGE_BYTES);
			
			ClientUpgradeRequest request = new ClientUpgradeRequest();
			request.setHeader(HttpHeaders.AUTHORIZATION, KubernetesHelper.BEARER + " " + token);
			
			while (!stopping) {
				try {
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
				} catch (Exception e) {
			    	if (!logExpectedError(e, logger))
			    		logger.error("Error connecting to server", e);
					try {
						Thread.sleep(5000);
					} catch (Exception ignored) {
					}
				}
			}
		} catch (Exception e) {
	    	logger.error("Error running agent", e);
		} finally {
			stopped = true;
		}
	}

	static File getTrustCertsDir() {
		return new File(installDir, "conf/trust-certs");
	}
	
	static boolean logExpectedError(Throwable t, Logger logger) {
		if (t.getMessage() != null) {
	    	if (t.getMessage().contains("Connection refused")) {
	    		logger.error("Connection refused. Is server up?");
	    		return true;
	    	} else if (t.getMessage().contains("500 Server Error")) {
	    		logger.error("Server internal error, please check server log");
	    		return true;
	    	} else if (t.getMessage().contains("503 Service Unavailable")) {
	    		logger.error("Service unavailable");
	    		return true;
	    	} else if (t.getMessage().contains("403 Forbidden")) {
				logger.error("Agent token rejected by server");
				return true;
			} else if (t.getMessage().contains("Token already used by another agent")
					|| t.getMessage().contains("Server not ready")
					|| t.getMessage().contains("Internal server error, check log for details")) {
				logger.error(t.getMessage());
				return true;
	    	} else {
	    		return false;
	    	}
		} else {
			return false;
		}
	}
	
	public static File getTempDir() {
		return new File(getWorkDir(), "temp");
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
				Method method = wrapperManagerClass.getDeclaredMethod("stopAndReturn", int.class);
				method.invoke(null, new Object[] {0});
			} catch (Exception e) {
				logger.error("Error stopping agent", e);
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
		new Message(MessageTypes.JOB_LOG, jobToken + ":" + sessionId + ":" + message).sendBy(session);
	}
	
}
