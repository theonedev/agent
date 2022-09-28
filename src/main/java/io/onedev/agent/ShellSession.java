package io.onedev.agent;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;

import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.ExposeOutputStream;
import io.onedev.commons.utils.command.ProcessKiller;
import io.onedev.commons.utils.command.ProcessTreeKiller;
import io.onedev.commons.utils.command.PtyMode;
import io.onedev.commons.utils.command.PtyMode.ResizeSupport;
import io.onedev.commons.utils.command.PumpInputToOutput;

public class ShellSession {

	private static final Logger logger = LoggerFactory.getLogger(ShellSession.class);

	private final String sessionId;
	
	private final Session agentSession;
	
	private final PtyMode ptyMode;
	
	private final ExposeOutputStream shellInput;
	
	private final Future<?> execution;
	
	public ShellSession(String sessionId, Session agentSession, Commandline cmdline) {
		this.sessionId = sessionId;
		this.agentSession = agentSession;
		
        ptyMode = new PtyMode();
        cmdline.ptyMode(ptyMode);

        shellInput = new ExposeOutputStream();
        
        execution = AgentSocket.executorService.submit(new Runnable() {

			@Override
			public void run() {
                try {
                    PumpInputToOutput outputHandler = new PumpInputToOutput(new OutputStream() {

            	        @Override
            	        public void write(byte[] b, int off, int len) throws IOException {
                            sendOutput(new String(b, off, len, StandardCharsets.UTF_8));
            	        }
            	
            	        @Override
            	        public void write(int b) throws IOException {
            	        	throw new UnsupportedOperationException();
            	        }

                    });
                    PumpInputToOutput errorHandler = new PumpInputToOutput(new OutputStream() {

                        @Override
                        public void write(byte[] b, int off, int len) throws IOException {
                        	sendError(new String(b, off, len, StandardCharsets.UTF_8));
                        }

                        @Override
                        public void write(int b) throws IOException {
                        	throw new UnsupportedOperationException();
                        }

                    });
                    ProcessKiller processKiller = new ProcessTreeKiller() {

                        @Override
                        public void kill(Process process, String executionId) {
	                        outputHandler.close();
	                        errorHandler.close();
	                        super.kill(process, executionId);
                        }

                    };
                    ExecutionResult result = cmdline.execute(outputHandler, errorHandler, shellInput, processKiller);
                    if (result.getReturnCode() != 0)
                    	sendError("Shell exited");
                    else
                    	new Message(MessageTypes.SHELL_CLOSED, sessionId).sendBy(agentSession);
	            } catch (ExplicitException e) {
	            	sendError(e.getMessage());
	            } catch (Throwable e) {
	            	ExplicitException explicitException = ExceptionUtils.find(e, ExplicitException.class);
	            	if (explicitException != null) {
		            	sendError(explicitException.getMessage());
	            	} else if (ExceptionUtils.find(e, InterruptedException.class) != null) {
	                    sendError("Shell exited");
	                } else {
	                	logger.error("Error running shell", e);
	                    sendError("Error running shell, check agent log for details");
	                }
	            }
			}
        	
        });
        
	}
	
	private void sendOutput(String output) {
		AgentSocket.sendOutput(sessionId, agentSession, output);
	}

	private void sendError(String error) {
		AgentSocket.sendError(sessionId, agentSession, error);
	}

	public void sendInput(String input) {
		try {
			shellInput.write(input);
		} catch (IOException e) {
		}
	}

	public void resize(int rows, int cols) {
		ResizeSupport resizeSupport = ptyMode.getResizeSupport();
		if (resizeSupport != null)
			resizeSupport.resize(rows, cols);
	}

	public void exit() {
		sendInput("exit\n");
		execution.cancel(true);
	}

}
