package io.onedev.agent;

import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.ImmediateFuture;
import io.onedev.commons.utils.command.*;
import io.onedev.commons.utils.command.PtyMode.ResizeSupport;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.apache.commons.io.IOUtils.closeQuietly;

public class ShellSession {

	private static final Logger logger = LoggerFactory.getLogger(ShellSession.class);

	private final String sessionId;
	
	private final Session agentSession;
	
	private final PtyMode ptyMode;
	
	private volatile OutputStream shellStdin;
	
	private final Future<?> execution;
	
	public ShellSession(String sessionId, Session agentSession, Commandline cmdline) {
		this.sessionId = sessionId;
		this.agentSession = agentSession;
		
        ptyMode = new PtyMode();
        cmdline.ptyMode(ptyMode);


        execution = Bootstrap.executorService.submit(new Runnable() {

			@Override
			public void run() {
                try {
					var stdoutHolder = new AtomicReference<InputStream>(null);
                    Function<InputStream, Future<?>> stdoutHandler = is -> {
						stdoutHolder.set(is);
						return StreamPumper.pump(is, new OutputStream() {

							@Override
							public void write(byte[] b, int off, int len) {
								sendOutput(new String(b, off, len, StandardCharsets.UTF_8));
							}

							@Override
							public void write(int b) {
								throw new UnsupportedOperationException();
							}

						});
					};

					var stderrHolder = new AtomicReference<InputStream>(null);
					Function<InputStream, Future<?>> stderrHandler = is -> {
						stderrHolder.set(is);
						return StreamPumper.pump(is, new OutputStream() {

							@Override
							public void write(byte[] b, int off, int len) {
								sendError(new String(b, off, len, StandardCharsets.UTF_8));
							}

							@Override
							public void write(int b) {
								throw new UnsupportedOperationException();
							}

						});
					};
                    cmdline.processKiller(new ProcessTreeKiller() {

                        @Override
                        public void kill(Process process, String executionId) {
							closeQuietly(stdoutHolder.get());
							closeQuietly(stderrHolder.get());
	                        super.kill(process, executionId);
                        }

                    });

                    ExecutionResult result = cmdline.execute(stdoutHandler, stderrHandler, os -> {
						shellStdin = os;
						return new ImmediateFuture<Void>(null);
					});
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
	            } finally {
					closeQuietly(shellStdin);
					shellStdin = null;
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
		var shellStdinCopy = shellStdin;
		if (shellStdinCopy != null) {
			try {
				shellStdinCopy.write(input.getBytes(StandardCharsets.UTF_8));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
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
