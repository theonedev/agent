"""OneDev job orchestration inside a sandbox.

The host copies this entry point, shared Python modules, and a JSON manifest into a fresh,
mountless sandbox. Build commands, checkout, caches, and step scheduling run there.
Filesystem and Buildx operations use the template user; container operations and
absolute cache operations may elevate and return their outputs to that user.
"""
import base64
from contextlib import contextmanager
import csv
import io
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import tempfile
import time

from sandbox import ProvisionedCache, SandboxRuntime, log, log_error, read_exact, read_int
from sandbox_files import pack, safe_path, unpack
from sandbox_privileges import JobPrivileges, root_operation, run_operation


def log_marker(message):
    # Command output may not end with a newline; protocol markers must start on their own line.
    # The controller consumes the extra separator when output already ends with a newline.
    log("\n" + message)


def docker_options(value, reserved, short_reserved, short_flags):
    """Reject options owned by step settings, without restricting VM file access."""
    args = shlex.split(value or "")
    for arg in args:
        option = arg.split("=", 1)[0]
        if option == "--" or option in reserved:
            raise ValueError("Docker option '%s' conflicts with step settings" % option)
        if arg.startswith("-") and not arg.startswith("--"):
            for char in arg[1:]:
                if char in short_reserved:
                    raise ValueError("Docker option '-%s' conflicts with step settings" % char)
                if char not in short_flags:
                    break  # The remainder is an attached option value.
    return args


def open_shell(root, identity=None):
    try:
        target = json.loads(safe_path(root, "shell.json").read_text())
    except FileNotFoundError:
        os.chdir(root / "work")
        if identity is not None and os.geteuid() == 0:
            uid, gid, groups = identity
            os.setgroups(groups)
            os.setgid(gid)
            os.setuid(uid)
        os.execvp("sh", ["sh"])
        return
    state = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", target["container"]],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if state.returncode or state.stdout.strip() != "true":
        raise RuntimeError("Container not running")
    os.execvp("docker", ["docker", "exec", "-it", target["container"], target["executable"]])


def write_string(stream, value):
    value = value.encode("utf-8")
    stream.write(struct.pack(">i", len(value)))
    stream.write(value)


class JobRunner(SandboxRuntime):
    def __init__(self, root, job, initialize=True):
        super().__init__(root, job, cache_endpoint="job-cache", initialize=initialize)
        self.pulled_images = set()
        self.network = None
        self.privileges = JobPrivileges(self, Path(__file__).resolve())
        if initialize:
            for name, value in job.get("attributes", {}).items():
                path = safe_path(root, "attributes/" + name)
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(value)

    def owned_paths(self):
        return [self.root, *self.absolute_cache_paths()]

    def restore_cache(self, config, path):
        if self.privileges.requires_root_for_cache(path):
            return self.restore_cache_as_root(config, path)
        return super().restore_cache(config, path)

    @root_operation
    def restore_cache_as_root(self, config, path):
        directory = self.data_path(path)
        try:
            return super().restore_cache(config, path)
        finally:
            # Also return partially restored files to the template user on failure.
            # Parent directories keep their original ownership and permissions.
            self.privileges.restore_owner([directory])

    def upload_cache(self, cache, entry):
        path = entry["path"]
        if self.privileges.requires_root_for_cache(path):
            self.upload_cache_as_root(cache.to_state(), entry)
        else:
            super().upload_cache(cache, entry)

    @root_operation
    def upload_cache_as_root(self, state, entry):
        super().upload_cache(ProvisionedCache.from_state(state), entry)

    def prepare_dependencies(self):
        log("Downloading job dependencies...")
        with self.request("dependencies") as response:
            unpack(response, self.work)

    def command(self, step, position):
        safe_path(self.root, "pause.sh").write_text(
            'rm -f "$ONEDEV_WORKDIR/../continue"\n'
            "echo '##onedev[PauseExecution]'\n"
            'while [ ! -f "$ONEDEV_WORKDIR/../continue" ]; do sleep 1; done\n')
        safe_path(self.root, "pause").write_text(step["pauseCommand"])
        script = safe_path(self.root, "command/step-" + position + step["extension"])
        script.parent.mkdir(exist_ok=True)
        script.write_text(self.replace(step["commands"]))
        if step.get("image") is not None:
            # Trust checkouts created by another UID, as with Docker executors.
            invoke = shlex.join([step["executable"], *step["options"], str(script)])
            return self.container(step, position, ["-c",
                "if command -v git >/dev/null; then git config --global --add safe.directory '*'; fi; " + invoke],
                entrypoint="sh")
        env = dict(os.environ, ONEDEV_WORKDIR=str(self.work))
        env.update(step["env"])
        env = self.service_env(env)
        # Keep command output and step boundaries on one stream so log stages cannot race.
        code = subprocess.call([step["executable"], *step["options"], str(script)], cwd=self.work, env=env,
                               stderr=subprocess.STDOUT)
        if code:
            log_error("Command exited with code " + str(code))
        return code == 0

    @contextmanager
    def docker_env(self, step):
        env = dict(os.environ)
        # Buildx state must survive step-scoped registry configurations.
        env["BUILDX_CONFIG"] = str(self.root / "buildx")
        # Apply executor defaults first so step credentials win for the same registry.
        logins = self.config.get("registryLogins", []) + step.get("registryLogins", [])
        if not logins:
            yield env
            return
        with tempfile.TemporaryDirectory(prefix="onedev-docker-") as directory:
            source = Path(env.get("DOCKER_CONFIG", Path.home() / ".docker"))
            for name in ("contexts", "cli-plugins"):
                if (source / name).exists():
                    (Path(directory) / name).symlink_to(source / name, target_is_directory=True)
            config = {}
            if (source / "config.json").is_file():
                config = json.loads((source / "config.json").read_text())
            for login in logins:
                registry = login["registryUrl"]
                credentials = (login["userName"] + ":" + login["password"]).encode()
                config.setdefault("auths", {})[registry] = {"auth": base64.b64encode(credentials).decode()}
                config.get("credHelpers", {}).pop(registry, None)
            config.pop("credsStore", None)
            path = Path(directory) / "config.json"
            path.write_text(json.dumps(config))
            path.chmod(0o600)
            env["DOCKER_CONFIG"] = directory
            yield env

    def docker(self, args, env):
        code = subprocess.call(["docker", *args], cwd=self.work, env=env, stderr=subprocess.STDOUT)
        if code:
            log_error("Docker command exited with code " + str(code))
        return code == 0

    def container_options(self, image):
        options = []
        if self.config.get("alwaysPullContainerImage", True) and image not in self.pulled_images:
            options.append("--pull=always")
            self.pulled_images.add(image)
        return options

    def service_env(self, env):
        env = dict(env)
        names = [service["name"] for service in self.config.get("services", [])]
        if names:
            # Sandbox templates proxy outbound HTTP by default. Internal
            # service requests must stay inside the VM's Docker network.
            for key, fallback in (("NO_PROXY", "no_proxy"), ("no_proxy", "NO_PROXY")):
                existing = env.get(key, env.get(fallback, os.environ.get(key, os.environ.get(fallback, ""))))
                env[key] = ",".join(dict.fromkeys(filter(None, [*existing.split(","), *names])))
        return env

    def add_service_host(self, name, address):
        # Shell steps run on the VM, outside Docker's embedded DNS. Its bridge
        # addresses are reachable directly; only the VM's hosts file is changed.
        command = ["tee", "-a", "/etc/hosts"]
        subprocess.run(command, input="\n%s\t%s\n" % (address, name), text=True,
                       stdout=subprocess.DEVNULL, check=True)

    @root_operation
    def start_services(self):
        services = self.config.get("services", [])
        if not services:
            return
        # A fresh Docker daemon belongs to this job's sandbox. Deleting the VM
        # also removes this network and all services, including on cancellation.
        docker_options(self.config.get("runOptions"), {"--network", "--net"}, "", "Pq")
        options = docker_options(self.config.get("networkOptions"), {"--driver"}, "d", "")
        if not self.docker(["network", "create", *options, "onedev"], os.environ):
            raise RuntimeError("Unable to create job service network")
        self.network = "onedev"
        for service in services:
            name, image = service["name"], service["image"]
            container = "onedev-service-" + name
            log("Starting service (name: %s, image: %s)..." % (name, image))
            args = ["run", "-d", "--stop-timeout=30", "--name", container,
                    "--network", self.network, "--network-alias", name,
                    "--user", service["runAs"], *self.container_options(image)]
            for key, value in self.service_env(service["envs"]).items():
                args.extend(["-e", key + "=" + value])
            args.extend(["--", image, *shlex.split(service.get("arguments") or "")])
            with self.docker_env(service) as env:
                if not self.docker(args, env):
                    raise RuntimeError("Unable to start service '%s'" % name)
            self.wait_for_service(service, container)

    def wait_for_service(self, service, container):
        name = service["name"]
        log("Waiting for service '%s' to be ready..." % name)
        while True:
            result = subprocess.run(["docker", "inspect", container], check=True,
                                    stdout=subprocess.PIPE, text=True)
            info = json.loads(result.stdout)[0]
            state = info["State"]
            if state["Status"] == "running":
                result = subprocess.run(["docker", "exec", container, "sh", "-c",
                                         service["readinessCheckCommand"]],
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
                for line in result.stdout.splitlines():
                    log("Service readiness check: " + line)
                if result.returncode == 0:
                    address = info["NetworkSettings"]["Networks"][self.network]["IPAddress"]
                    if not address:
                        raise RuntimeError("Service '%s' has no address on the job network" % name)
                    self.add_service_host(name, address)
                    log("Service '%s' is ready" % name)
                    break
            elif state["Status"] in ("exited", "dead"):
                if state.get("OOMKilled"):
                    log_error("Out of memory")
                elif state.get("Error"):
                    log_error(state["Error"])
                self.docker(["logs", container], os.environ)
                raise RuntimeError("Service '%s' is stopped unexpectedly" % name)
            time.sleep(10)

    @root_operation
    def container(self, step, position, arguments=None, entrypoint=None):
        if self.network:
            docker_options(self.config.get("runOptions"), {"--network", "--net"}, "", "Pq")
        options = docker_options(self.config.get("runOptions"), {
            "--workdir", "--detach", "--attach", "--tty", "--interactive", "--rm", "--restart",
            "--name", "--user", "--entrypoint", "--stop-timeout"}, "wdatiu", "Pq")
        run_as = self.replace(step.get("runAs") or "0:0")
        image = self.replace(step["image"])
        container_name = "onedev-step-" + position
        args = ["run", "--rm", "--name", container_name, "--stop-timeout=30"]
        args.extend(self.container_options(image))
        if self.network:
            args.extend(["--network", self.network])
        args.extend([*options, "--user", run_as, "-v", str(self.root) + ":" + str(self.root)])
        for source, target in step.get("volumeMounts", {}).items():
            args.extend(["-v", str(self.work / self.replace(source)) + ":" + self.replace(target)])
        paths = self.owned_paths()
        for path in self.absolute_cache_paths():
            args.extend(["-v", str(path) + ":" + str(path)])
        working_dir = str(self.work) if entrypoint else step.get("workingDir")
        if working_dir:
            args.extend(["-w", self.replace(working_dir)])
        args.extend(["-v", "/var/run/docker.sock:/var/run/docker.sock"])
        for name, value in self.service_env(step.get("env", {})).items():
            args.extend(["-e", name + "=" + value])
        args.extend(["-e", "ONEDEV_WORKDIR=" + str(self.work)])
        if step.get("useTTY"):
            args.append("-t")
        if entrypoint:
            args.extend(["--entrypoint", entrypoint])
        args.extend(["--", image])
        args.extend(arguments if arguments is not None else shlex.split(self.replace(step.get("args") or "")))
        shell = safe_path(self.root, "shell.json")
        try:
            if run_as != "0:0":
                self.privileges.change_owner(run_as, paths)
            with self.docker_env(step) as env:
                # Publish atomically so terminals never read a partially written target.
                target = safe_path(self.root, "shell.json.tmp")
                target.write_text(json.dumps({"container": container_name, "executable": step.get("executable", "sh")}))
                target.replace(shell)
                return self.docker(args, env)
        finally:
            # Restore before cleanup; either operation may encounter paths owned by
            # another UID or made inaccessible by the container.
            try:
                self.privileges.restore_owner(paths)
            finally:
                shell.unlink(missing_ok=True)

    def build_image(self, step):
        options = docker_options(self.replace(step.get("moreOptions") or ""), {
            "--builder", "--platform", "--push", "--load", "--file", "--tag", "--output"}, "fto", "q")
        with self.docker_env(step) as env:
            args = ["buildx", "build"]
            if self.config.get("alwaysPullContainerImage", True):
                args.append("--pull")
            args.extend(options)
            if step.get("platforms"):
                args.extend(["--platform", self.replace(step["platforms"])])
            if step.get("dockerfile"):
                args.extend(["-f", self.replace(step["dockerfile"])])
            if "tags" in step:
                args.append("--push")
                for tag in shlex.split(self.replace(step["tags"])):
                    args.extend(["-t", tag])
            else:
                directory = self.work / self.replace(step["destPath"])
                directory.mkdir(parents=True, exist_ok=True)
                output = io.StringIO()
                csv.writer(output, lineterminator="").writerow(["type=oci", "tar=false", "dest=" + str(directory)])
                args.extend(["--output", output.getvalue()])
            args.extend(["--", self.replace(step.get("buildPath") or ".")])
            return self.docker(args, env)

    def imagetools(self, step):
        with self.docker_env(step) as env:
            return self.docker(["buildx", "imagetools", *shlex.split(self.replace(step["arguments"]))], env)

    def prune_builder_cache(self, step):
        options = docker_options(self.replace(step.get("options") or ""), {"--builder", "--force"}, "f", "a")
        with self.docker_env(step) as env:
            return self.docker(["buildx", "prune", "-f", *options], env)

    def server_step(self, step, position):
        values = self.placeholder_values(step["placeholders"])
        directory = safe_path(self.work, self.replace(step.get("path") or ""))
        with tempfile.TemporaryFile() as body:
            indices = [int(index) for index in position.split("-")]
            body.write(struct.pack(">i", len(indices)))
            for index in indices:
                body.write(struct.pack(">i", index))
            body.write(struct.pack(">i", len(values)))
            for key, value in values.items():
                write_string(body, key)
                write_string(body, value)
            pack(body, directory, [self.replace(p) for p in step["includes"]],
                 [self.replace(p) for p in step["excludes"]])
            body.seek(0)
            with self.request("run-server-step", {"resultFormat": "json"}, body) as response:
                return self.read_server_result(response)

    def read_server_result(self, response):
        while True:
            kind = read_int(response)
            payload = read_exact(response, read_int(response))
            if kind == 1:
                log(payload.decode())
            elif kind == 2:
                result = json.loads(payload)
                for name, value in result["outputFiles"].items():
                    path = safe_path(self.root, name)
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(base64.b64decode(value))
                return result["successful"]
            else:
                raise ValueError("Unexpected server response frame")

    def execute(self, actions, prefix=None):
        successful = True
        for index, step in enumerate(actions):
            position = (prefix or []) + [str(index)]
            if step["condition"] != "ALWAYS" and not (step["condition"] == "SUCCESSFUL" and successful):
                self.skip_step(step, position)
                continue
            if step["type"] == "composite":
                result = self.execute(step["actions"], position)
            else:
                result = self.execute_leaf(step, "-".join(position))
            if not step["optional"]:
                successful = successful and result
        return successful

    def skip_step(self, step, position):
        if step["type"] == "composite":
            for index, child in enumerate(step["actions"]):
                self.skip_step(child, position + [str(index)])
        else:
            log_marker("##onedev-step:SKIP:step-" + "-".join(position))

    def execute_leaf(self, step, position):
        log_marker("##onedev-step:START:step-" + position)
        try:
            kind = step["type"]
            if kind == "command":
                result = self.command(step, position)
            elif kind == "container":
                result = self.container(step, position)
            elif kind == "build-image":
                result = self.build_image(step)
            elif kind == "imagetools":
                result = self.imagetools(step)
            elif kind == "prune-builder-cache":
                result = self.prune_builder_cache(step)
            elif kind == "checkout":
                result = self.checkout(step)
            elif kind == "server":
                result = self.server_step(step, position)
            elif kind == "cache":
                result = self.setup_cache(step)
            else:
                raise ValueError("Step requires a Docker executor: " + step.get("description", kind))
        except Exception as error:
            log_error("Step failed: " + str(error))
            result = False
        log_marker("##onedev-step:%s:step-%s" % ("SUCCESSFUL" if result else "FAILED", position))
        return result

    def run(self):
        if self.config.get("test"):
            return self.test()
        self.prepare_dependencies()
        with self.request("job-workdir", {"workDir": str(self.work)}, method="PUT"):
            pass
        try:
            if self.config.get("services"):
                self.start_services()
            successful = self.execute(self.config["actions"])
        finally:
            log_marker("##onedev-phase:finalization")
        if successful and self.caches:
            self.upload_caches()
        return successful

    def test(self):
        self.test_runtime()
        image = self.config.get("testImage")
        if image:
            log("Testing command execution in container '%s'..." % image)
            return self.container({"image": image}, "test", ["-c", "echo hello from container"], entrypoint="sh")
        return True


def main(args):
    mode = args[1] if len(args) > 1 else None
    try:
        root = Path(args[0])
        if mode == "--shell":
            identity = (int(args[2]), int(args[3]), [int(g) for g in args[4].split(",") if g])
            open_shell(root, identity)
        elif mode == "--operation":
            run_operation(root, args[2], JobRunner)
        else:
            job = json.loads((root / "job.json").read_text())
            return 0 if JobRunner(root, job).run() else 1
        return 0
    except Exception as error:
        log_error(("Unable to open build terminal: " if mode == "--shell" else "Sandbox job failed: ") + str(error))
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
