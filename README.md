Agent process to run docker, shell, and sandbox jobs on remote machines.

The agent selects the sandbox executable using `sbxPath`, checked in the
environment, JVM system properties, then `conf/agent.properties`, falling back
to `sbx` on `PATH`. This follows the same precedence as `dockerPath`.

Sandbox jobs run shell commands as the template image's default user. The agent
initializes `/onedev-build` as root via `sbx exec --user root`, assigns it to the
template user, and extracts the runtime as that user without requiring sudo.
Non-root template users need passwordless sudo for containers, services, and
protected absolute caches. Opening a build terminal or resuming a paused job
needs sudo only when the non-root template user does not own `/onedev-build`.
OneDev invokes sudo non-interactively: missing sudo, denied access, or a password
requirement fails the operation with an error instead of prompting for input.
Step containers use their configured `runAs`.
While a step container is running, the build terminal opens inside that container
using its configured user and the command step's interpreter (`sh` for a Run
Container step). Otherwise, the terminal opens the sandbox's POSIX-compatible `sh`.

Sandbox jobs support build-spec services when using
`docker/sandbox-templates:shell-docker` or a custom template based on it.
Services start inside the sandbox and pass their readiness checks before steps
run. Shell and container steps can connect using the service name as the host.
Service registry logins override executor logins. The executor's CPUs and Memory
settings apply to the entire sandbox, including its steps and services. Services
and their network are removed with the sandbox when the job completes, fails, or
is cancelled.

Container steps automatically mount the sandbox's Docker socket at
`/var/run/docker.sock` to support Docker operations inside the container.

Sandbox jobs and new workspaces use a local OneDev sandbox kit with no startup
package refresh and disable shared host skills with `--skills off`. This requires
sbx with v2 kit and `--skills` support and local kits enabled. The kit uses the
configured template, defaulting to
`docker/sandbox-templates:shell-docker`. Package installation scripts should run
`apt-get update` explicitly before `apt-get install`. OneDev does not inspect
package commands or wait for package-manager processes. Existing workspaces keep
their original kit and skills-sharing mode until recreated.
The kit starts `sh` without login-shell flags. OneDev only requires a POSIX-compatible
shell available as `sh`; steps and workspace specs explicitly configured to use
Bash still require it in their image.
The OneDev kit does not inherit the built-in shell kit's credential bindings;
supply credentials through OneDev's job/workspace configuration and user data.

Sandbox workspaces run checkout, setup and teardown commands, and terminals as
the template image's default user. The workspace directory is initialized with
that user's ownership. Use `sudo` in setup commands when installing system
packages if the template permits it.

Sandbox workspace ports are declared when the sandbox is created. Workspaces
with exposed ports require `sbx env create` support: OneDev uses an environment
file to allocate host ports automatically on `0.0.0.0`. Later activations keep
the original exposed ports and read their current host-port mappings. Changes
to a workspace spec's exposed ports apply to new sandboxes only.

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
job and workspace suites against a local mock OneDev API. They require Python 3.9 or newer,
Git, and `sh`; no running OneDev server or Docker Sandbox is needed. Python test
failures fail the Maven build. To use a different Python executable, pass
`-Donedev.python.executable=/path/to/python3`.

Run only the Python suites with `mvn test -Dtest=SandboxPythonTest`. This discovers
all `test_sandbox*.py` files under `src/test/python`.
