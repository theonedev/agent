package io.onedev.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.apache.commons.lang3.SerializationUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.onedev.agent.job.JobData;
import io.onedev.agent.job.LogRequest;
import io.onedev.agent.job.TestJobData;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.FileUtils;
import io.onedev.k8shelper.KubernetesHelper;

@WebSocket
public class AgentSocket implements Runnable {

	private static final Logger logger = LoggerFactory.getLogger(AgentSocket.class);
	
	private Session session;
	
	private volatile Thread thread;
	
	private volatile boolean stopped;
	
	private static final ExecutorService executorService = Executors.newCachedThreadPool();
	
	@OnWebSocketConnect
	public void onConnect(Session session) throws IOException {
		logger.info("Connected to server");
		this.session = session;
		thread = new Thread(this);
		thread.start();
	}

	@OnWebSocketMessage
	public void onMessage(byte[] bytes, int offset, int length) {
		Message message = Message.of(bytes, offset, length); 
    	byte[] messageData = message.getData();
		try {
	    	switch (message.getType()) {
	    	case UPDATE:
	    		String versionAtServer = new String(messageData, StandardCharsets.UTF_8);
	    		if (!versionAtServer.equals(Agent.version)) {
	    			logger.info("Updating agent to version " + versionAtServer + "...");
	    			Client client = ClientBuilder.newClient();
	    			try {
	    				WebTarget target = client.target(Agent.serverUrl).path("downloads/agent-lib");
	    				Invocation.Builder builder =  target.request();
	    				builder.header(HttpHeaders.AUTHORIZATION, Agent.BEARER + " " + Agent.token);
	    				
	    				try (Response response = builder.get()){
	    					KubernetesHelper.checkStatus(response);
	    					
	    					File newLibDir = new File(Agent.installDir, "lib/" + versionAtServer);
	    					FileUtils.cleanDir(newLibDir);
	    					try (InputStream is = response.readEntity(InputStream.class)) {
	    						FileUtils.untar(is, newLibDir, false);
	    					} 
	    					
	    					File wrapperConfFile = new File(Agent.installDir, "conf/wrapper.conf");
	    					String wrapperConfContent = FileUtils.readFileToString(wrapperConfFile, StandardCharsets.UTF_8);
	    					FileUtils.writeStringToFile(
	    							wrapperConfFile, 
	    							wrapperConfContent.replace("../lib/" + Agent.version + "/", "../lib/" + versionAtServer + "/"), 
	    							StandardCharsets.UTF_8);
	    				} 
	    			} finally {
	    				client.close();
	    			}
	        		Agent.restart();
	    		} else {
	    			AgentData agentData = new AgentData(Agent.token, Agent.os, Agent.osVersion, Agent.osArch, 
	    					Agent.name, Agent.cpu, Agent.memory, Agent.attributes);
	    			new Message(MessageType.AGENT_DATA, agentData).sendBy(session);
	    		}
	    		break;
	    	case UPDATE_ATTRIBUTES:
	    		Map<String, String> attributes = SerializationUtils.deserialize(messageData);
	    		Agent.attributes = attributes;
	    		Properties props = new Properties();
	    		props.putAll(attributes);
	    		try (OutputStream os = new FileOutputStream(new File(Agent.installDir, "conf/attributes.properties"))) {
		    		props.store(os, null);
	    		}
	    		break;
	    	case RESTART:
	    		Agent.restart();
	    		break;
	    	case STOP:
	    		Agent.stop();
	    		break;
	    	case ERROR:
	    		throw new RuntimeException(new String(messageData, StandardCharsets.UTF_8));
	    	case REQUEST:
	    		executorService.execute(new Runnable() {

					@Override
					public void run() {
						try {
				    		CallData request = SerializationUtils.deserialize(messageData);
				    		CallData response = new CallData(request.getUuid(), service(request.getPayload()));
				    		new Message(MessageType.RESPONSE, response).sendBy(session);
						} catch (Exception e) {
							logger.error("Error handling websocket request", e);
						}
					}
	    			
	    		});
	    		break;
	    	case RESPONSE:
	    		WebsocketUtils.onResponse(SerializationUtils.deserialize(messageData));
	    		break;
	    	case CANCEL_JOB:
	    		String jobToken = new String(messageData, StandardCharsets.UTF_8);
	    		DockerUtils.cancelJob(jobToken);
	    		break;
	    	default:
	    	}
		} catch (Exception e) {
			logger.error("Error processing websocket message", e);
			try {
				session.disconnect();
			} catch (IOException e2) {
			}
		}
	}
	
	@OnWebSocketClose
	public void onClose(int statusCode, String reason) {
		if (reason != null)
			logger.debug("Websocket closed (status code: {}, reason: {})", statusCode, reason);
		else
			logger.debug("Websocket closed (status code: {})", statusCode);
		Agent.reconnect = true;
		while (!stopped) {
			thread.interrupt();
			try {
				Thread.sleep(1000); 
			} catch (InterruptedException e) {
			}
		}
	}
	
	private Serializable service(Serializable request) {
		try {
			if (request instanceof LogRequest) { 
				return (Serializable) LogRequest.readLog(new File(Agent.installDir, "logs/agent.log"));
			} else if (request instanceof JobData) { 
				try {
					DockerUtils.executeJob(session, (JobData) request);
					return null;
				} catch (Exception e) {
					return e;
				}
			} else if (request instanceof TestJobData) {
				try {
					DockerUtils.testRemoteExecutor(session, (TestJobData) request);
					return null;
				} catch (Exception e) {
					return e;
				}
			} else { 
				throw new ExplicitException("Unknown request: " + request.getClass());
			}
		} catch (Exception e) {
			logger.error("Error servicing websocket request", e);
			return e;
		}
	}

    @OnWebSocketError
    public void onError(Throwable t) {
    	logger.error("Websocket error", t);
    	Agent.reconnect = true;
    }

	@Override
	public void run() {
		Message message = new Message(MessageType.HEART_BEAT, new byte[0]);
		while (true) {
			try {
				Thread.sleep(Agent.SOCKET_IDLE_TIMEOUT/3);
			} catch (InterruptedException e) {
				break;
			}
			try {
				message.sendBy(session);
			} catch (Exception e) {
				logger.error("Error pinging server", e);
				try {
					session.disconnect();
				} catch (Exception e2) {
				}
			}
		}
		stopped = true;
	}
	
}