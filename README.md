# OneDev Agent

Runs OneDev CI/CD jobs and development workspaces on remote machines. The agent
connects to the server over WebSocket and supports log streaming, interactive
terminals, caches, and workspace user data.

## Execution environments

- **Host:** run commands directly on the agent machine using its installed tools.
- **Docker:** run jobs and workspaces in containers, with configurable images,
  users, registry credentials, and resource limits.
- **Sandbox:** run jobs and workspaces in isolated VMs using Docker Sandboxes.
  Docker-enabled templates also support container steps and services inside the VM.

The agent requires Java 17 or newer and Git 2.11.1 or newer. Install Docker for
container workloads, or Docker Sandboxes (`sbx`) for sandbox workloads. Workspace
terminals require `tmux` in their execution environment. Docker workspace images
are checked for `sh`, Git, Git LFS, and `tmux`.

## Configuration

Configure `conf/agent.properties` in the agent installation directory:

```properties
serverUrl=https://onedev.example.com
agentToken=your-agent-token
```

| Setting | Purpose / default |
| --- | --- |
| `serverUrl` | Required HTTP(S) URL for the agent connection |
| `agentToken` | Authentication token issued by OneDev |
| `agentTokenFile` | Read the token from a file; relative paths resolve under `conf/` |
| `agentName` | Agent name; defaults to the host name |
| `gitPath` | Git executable; defaults to `git` on `PATH` |
| `dockerPath` | Docker executable; defaults to `docker` on `PATH` |
| `sbxPath` | Docker Sandboxes executable; defaults to `sbx` on `PATH` |

Settings are checked in the environment, JVM system properties, then
`conf/agent.properties`, using the exact names above. Token lookup has one
exception: when `agentToken` is absent from the environment and JVM properties,
`agentTokenFile` is read before the `agentToken` property in the configuration file.

Set custom agent attributes in `conf/attributes.properties` and place additional
trusted certificates in `conf/trust-certs`. Run the agent as a user that owns and
can write to its installation directory.

The connection URL above can differ from the server URL supplied by OneDev for
jobs and workspaces. Worker API requests use the URL in each workload's
configuration, including cache transfers, terminal traffic, and status checks.
Ensure that URL is reachable from the agent host and the selected container or
sandbox environment. Executor and provisioner tests check this connectivity.

## Sandboxes

### Templates and setup

Use an `sbx` version with v2 kit and `--skills` support, with local kits enabled.
Workspaces with exposed ports also require `sbx env create` support. See the
[Working with Sandbox tutorial](https://docs.onedev.io/tutorials/sandbox/working-with-sandbox)
for host requirements, login, and template setup.

OneDev creates a local kit using the configured template, defaulting to
`docker/sandbox-templates:shell-docker`. Templates need Python 3.9 or newer, Git,
Git LFS, `tar`, and a POSIX-compatible `sh`. Workspace terminals also need `tmux`,
provided by the template or setup commands. Commands configured to use Bash
require Bash in their image.

The kit starts `sh` without login-shell flags or background package refreshes.
Run `apt-get update` explicitly before `apt-get install`. Shared host skills are
disabled with `--skills off`, and the kit does not inherit the built-in shell
kit's credential bindings. Supply credentials through OneDev's job or workspace
configuration and user data. OneDev enables TCP and UDP network access for jobs
and workspaces.

### Jobs

Each job gets a fresh sandbox, removed when the job completes, fails, or is
cancelled. Shell commands run as the template's default user. OneDev initializes
`/onedev-build` as root, assigns it to that user, and extracts the runtime as the
same user without requiring sudo.

Non-root template users need passwordless sudo for container steps, services,
and protected absolute caches. Sudo is invoked non-interactively and fails with
an error if unavailable or denied. Step containers use their configured `runAs`
and mount the sandbox's Docker socket at `/var/run/docker.sock`.

Docker-enabled templates support build-spec services. Services pass readiness
checks before steps run and are reachable by service name from shell and
container steps. Service registry logins override executor logins. CPU and
memory settings apply to the entire sandbox, including its steps and services.

Build terminals open in the active step container using its configured user and
interpreter (`sh` for a Run Container step), or in the sandbox's `sh` otherwise.
Opening a terminal or resuming a paused job needs sudo only when the template
user does not own `/onedev-build`.

### Workspaces

Checkout, setup and teardown commands, and terminals run as the template's
default user, which owns the workspace directory. Use `sudo` in setup commands
when installing system packages if the template permits it.

Exposed ports are declared at creation and mapped to automatic host ports on
`0.0.0.0`. Later activations read the current mappings. Recreate a workspace to
apply changes to its template, kit, skills-sharing mode, or exposed ports.

## Development

Install a JDK 17 or newer and Maven, then build the development installation:

```bash
./dev.sh build
```

Edit `target/sandbox/conf/agent.properties` with your server URL and agent token,
then start the agent with hot loading and automatic rebuilds:

```bash
./dev.sh run
```

Override the connection settings for one run without changing the file:

```bash
./dev.sh run --server-url http://localhost:6610 --agent-token your-agent-token
```

Both `--option value` and `--option=value` forms are supported. These options
override the corresponding environment variables, JVM properties, and file
settings. Stop the development agent with `./dev.sh stop` or Ctrl+C.

Subsequent builds compile changed Java files with ECJ. Use `./dev.sh rebuild`
for a clean build that preserves the development installation. The script also
supports `test`, `package`, `install`, and `clean`, including combinations such
as `./dev.sh clean package`. Structural hot loading requires a JetBrains Runtime
with `AllowEnhancedClassRedefinition`; other JVMs can reload method-body changes.

## Tests

```bash
mvn test
```

On Linux and macOS, this also discovers `test_sandbox*.py` under `src/test/python`
and runs them against a local mock OneDev API. The default Python suites require
Python 3.9 or newer, Git, Git LFS, and `sh`; no running OneDev server or Docker
Sandbox is needed. Python test failures fail the Maven build.

Run only the Python suites, or select a different Python executable:

```bash
mvn test -Dtest=SandboxPythonTest
mvn test -Donedev.python.executable=/path/to/python3
```

Permission integration tests are skipped by default. Run them only in a
disposable sandbox as its non-root template user, with passwordless sudo,
Docker, and `alpine:latest` cached in its private Docker daemon:

```bash
ONEDEV_SANDBOX_PERMISSION_TESTS=1 python3 -B -m unittest discover \
  -s src/test/python -p test_sandbox_permissions.py --verbose
```
