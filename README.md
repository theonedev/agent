Agent process to run docker, shell, and sandbox jobs on remote machines.

The agent selects the sandbox executable using `sbxPath`, checked in the
environment, JVM system properties, then `conf/agent.properties`, falling back
to `sbx` on `PATH`. This follows the same precedence as `dockerPath`.

Sandbox jobs run their setup, shell commands, cache operations, and interactive
shells as root inside the VM. Step containers still use their configured `runAs`.
While a step container is running, the build terminal opens inside that container
using its configured user and the command step's interpreter (`sh` for a Run
Container step). Otherwise, the terminal opens the sandbox's Bash shell.

Sandbox jobs support build-spec services when using
`docker/sandbox-templates:shell-docker` or a custom template based on it.
Services start inside the sandbox and pass their readiness checks before steps
run. Shell and container steps can connect using the service name as the host.
Service registry logins override executor logins, and executor container CPU
and memory limits apply to services as well. Services and their network are
removed with the sandbox when the job completes, fails, or is cancelled.

Container steps automatically mount the sandbox's Docker socket at
`/var/run/docker.sock` to support Docker operations inside the container.

Run `./dev.sh build` to compile the agent and create `target/sandbox`. Configure
`target/sandbox/conf/agent.properties` with the server URL and agent token, then
use `./dev.sh run` to start the agent with hot loading and automatic rebuilds.
Override the server URL and agent token for a run with:

```bash
./dev.sh run --server-url http://localhost:6610 --agent-token your-agent-token
```

These options take precedence over the `serverUrl` and `agentToken` environment
variables and `agent.properties`, without changing the file. Both `--option value`
and `--option=value` forms are supported. Without an option, the corresponding
environment variable or property is used. Stop the agent with `./dev.sh stop` or
Ctrl+C.

Subsequent builds compile changed Java files with ECJ. Use `./dev.sh rebuild`
for a clean build that preserves the development sandbox. The script also
supports `test`, `package`, `install`, and `clean`, including combinations such
as `./dev.sh clean package`. Structural hot loading requires a JetBrains Runtime
with `AllowEnhancedClassRedefinition`; other JVMs can reload method-body changes.

Run the tests with `mvn test`. On Linux and macOS this also runs the Python sandbox
runner suite against a local mock OneDev API. It requires Python 3.9 or newer,
Git, and `sh`; no running OneDev server or Docker Sandbox is needed. Python test
failures fail the Maven build. To use a different Python executable, pass
`-Donedev.python.executable=/path/to/python3`.

Run only the runner suite with `mvn test -Dtest=SandboxRunnerTest`.
