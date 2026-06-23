package io.onedev.agent.shell;

import static org.apache.commons.io.IOUtils.closeQuietly;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.eclipse.jetty.websocket.api.Session;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.onedev.agent.AgentUtils;
import io.onedev.agent.Message;
import io.onedev.agent.MessageTypes;
import io.onedev.commons.bootstrap.Bootstrap;
import io.onedev.commons.utils.ExceptionUtils;
import io.onedev.commons.utils.ExplicitException;
import io.onedev.commons.utils.command.Commandline;
import io.onedev.commons.utils.command.ExecutionResult;
import io.onedev.commons.utils.command.ProcessTreeKiller;
import io.onedev.commons.utils.command.PtyMode;
import io.onedev.commons.utils.command.PtyMode.ResizeSupport;
import io.onedev.commons.utils.command.StreamPumper;

public abstract class ShellSession {

	private static final Logger logger = LoggerFactory.getLogger(ShellSession.class);

	protected final String sessionId;
	
	protected final Session agentSession;
		
	private final PtyMode ptyMode;
	
	private volatile OutputStream shellStdin;
	
	private final Future<?> execution;

	@Nullable
	private final Runnable onTerminate;
	
	public ShellSession(String sessionId, Session agentSession, MessageTypes exitMessageType, Commandline cmdline) {
		this(sessionId, agentSession, exitMessageType, cmdline, null);
	}

	public ShellSession(String sessionId, Session agentSession, MessageTypes exitMessageType, Commandline cmdline,
			@Nullable Runnable onTerminate) {
		this.sessionId = sessionId;
		this.agentSession = agentSession;
		this.onTerminate = onTerminate;
		
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
								onOutput(Base64.getEncoder().encodeToString(Arrays.copyOfRange(b, off, off + len)));
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
								onOutput(Base64.getEncoder().encodeToString(Arrays.copyOfRange(b, off, off + len)));
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
						return CompletableFuture.completedFuture(null);
					});
                    if (result.getReturnCode() != 0)
                    	onOutput(AgentUtils.encodeBase64Error("Shell exited with return code: " + result.getReturnCode()));
                    else
                    	new Message(exitMessageType, sessionId).sendBy(agentSession);
	            } catch (Throwable e) {
	            	ExplicitException explicitException = ExceptionUtils.find(e, ExplicitException.class);
	            	if (explicitException != null) {
		            	onOutput(AgentUtils.encodeBase64Error("Shell exited with error: " + explicitException.getMessage()));
	            	} else if (ExceptionUtils.find(e, InterruptedException.class) == null) {
	                	logger.error("Error running shell", e);
	                    onOutput(AgentUtils.encodeBase64Error("Error running shell, check agent log for details"));
	                }
	            } finally {
					closeQuietly(shellStdin);
					shellStdin = null;
				}
			}
        	
        });
        
	}
	
	protected abstract void onOutput(String base64Data);

	public void writeToStdin(String data) {
		var shellStdinCopy = shellStdin;
		if (shellStdinCopy != null) {
			try {
				shellStdinCopy.write(data.getBytes(StandardCharsets.UTF_8));
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
		execution.cancel(true);
		if (onTerminate != null)
			onTerminate.run();
	}

}
