"""OneDev job orchestration, using only the sandbox shell image's Python standard library.

The host copies this file and a JSON manifest into a fresh, mountless sandbox.
No build command, checkout, cache extraction, or step scheduling runs on the host.
"""
import base64
from contextlib import contextmanager
import csv
import fnmatch
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import ssl
import struct
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


PLACEHOLDER = re.compile(r"<&onedev#(.*?)#onedev&>")


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


def log(message):
    print(message, flush=True)


def log_error(message):
    # Match TaskLogger.error while keeping errors and step events on one stream.
    log("\033[1;31m" + message + "\033[0m")


def wait_for_package_initialization(proc=Path("/proc")):
    # The shell agent starts apt-get update in the background, and sbx create
    # returns before it finishes. Wait for the processes (including the period
    # before they acquire locks), not for lock files, which persist after use.
    # There is no separate deadline: job timeout/cancellation stops the sandbox.
    waiting = False
    while True:
        for stat in proc.glob("[0-9]*/stat"):
            try:
                command, _, status = stat.read_text().rpartition(") ")
            except (FileNotFoundError, ProcessLookupError):
                continue
            if command.partition("(")[2] in ("apt", "apt-get", "dpkg") and status.split()[0] not in ("Z", "X"):
                break
        else:
            if waiting:
                log("Sandbox package initialization finished")
            return
        if not waiting:
            log("Waiting for sandbox package initialization to finish...")
            waiting = True
        time.sleep(1)


def safe_path(root, relative, allow_leaf_symlink=False):
    if not isinstance(relative, str) or ".." in relative or Path(relative).is_absolute() or "\\" in relative:
        raise ValueError("Path must be relative and must not contain '..': " + str(relative))
    path = root
    if path.is_symlink():
        raise ValueError("Symbolic link is not allowed: " + str(path))
    parts = Path(relative).parts
    for index, part in enumerate(parts):
        path = path / part
        if path.is_symlink() and not (allow_leaf_symlink and index == len(parts) - 1):
            raise ValueError("Symbolic link is not allowed: " + str(path))
    return path


def open_shell(root):
    try:
        target = json.loads(safe_path(root, "shell.json").read_text())
    except FileNotFoundError:
        os.execvp("bash", ["bash"])
        return
    state = subprocess.run(["docker", "inspect", "--format", "{{.State.Running}}", target["container"]],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if state.returncode or state.stdout.strip() != "true":
        raise RuntimeError("Container not running")
    os.execvp("docker", ["docker", "exec", "-it", target["container"], target["executable"]])


def matches(path, pattern):
    """Ant-style path matching: ** spans directories, * and ? do not.

    Accepted difference from Ant: fnmatchcase treats bracket expressions as
    character classes, so file[1].txt matches file1.txt instead of file[1].txt.
    Exact Ant compatibility for bracket expressions is intentionally not required.
    """
    parts, patterns = path.split("/"), pattern.rstrip("/").split("/")
    if pattern.endswith("/"):
        patterns.append("**")

    def match(i, j):
        if j == len(patterns):
            return i == len(parts)
        if patterns[j] == "**":
            return match(i, j + 1) or (i < len(parts) and match(i + 1, j))
        return i < len(parts) and fnmatch.fnmatchcase(parts[i], patterns[j]) and match(i + 1, j + 1)

    return match(0, 0)


def selected_files(root, includes, excludes, follow_links=False):
    if root.is_symlink():
        raise ValueError("Source directory must not be a symbolic link: " + str(root))
    if not root.exists():
        return []
    files = []
    seen = set()
    for parent, dirs, names in os.walk(root, followlinks=follow_links):
        resolved = Path(parent).resolve()
        if not resolved.is_relative_to(root.resolve()):
            raise ValueError("Source directory resolves outside work directory: " + str(parent))
        if resolved in seen:
            dirs[:] = []
            continue
        seen.add(resolved)
        dirs[:] = sorted(d for d in dirs if follow_links or not (Path(parent) / d).is_symlink())
        for name in names:
            path = Path(parent) / name
            relative = path.relative_to(root).as_posix()
            if (path.is_file() and (follow_links or not path.is_symlink())
                    and any(matches(relative, p) for p in includes)
                    and not any(matches(relative, p) for p in excludes)):
                if not path.resolve().is_relative_to(root.resolve()):
                    raise ValueError("Source file resolves outside work directory: " + str(path))
                files.append(path)
    return sorted(files)


def unpack(stream, target, allow_external_symlinks=False):
    """Keep extraction writes inside target, optionally preserving VM-local cache links."""
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=stream, mode="r|*") as archive:
        for member in archive:
            name = member.name.removeprefix("./").rstrip("/")
            if name in ("", "."):
                continue
            dest = safe_path(target, name, allow_leaf_symlink=allow_external_symlinks)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if allow_external_symlinks and dest.is_symlink():
                dest.unlink()
            if member.isdir():
                dest.mkdir(exist_ok=True)
            elif member.isfile():
                with archive.extractfile(member) as source, dest.open("wb") as output:
                    shutil.copyfileobj(source, output)
                dest.chmod(member.mode & 0o777)
                os.utime(dest, (member.mtime, member.mtime))
            elif member.issym():
                if not allow_external_symlinks:
                    link = (dest.parent / member.linkname).resolve()
                    if not link.is_relative_to(target.resolve()):
                        raise ValueError("Archive link escapes target: " + member.name)
                if dest.exists():
                    dest.unlink()
                dest.symlink_to(member.linkname)
            elif member.islnk():
                source = safe_path(target, member.linkname)
                shutil.copyfile(source, dest)
                dest.chmod(member.mode & 0o777)
            else:
                raise ValueError("Unsupported archive entry: " + member.name)


def cache_paths(root, excludes):
    """Include link entries without traversing their targets."""
    for parent, dirs, names in os.walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if not any(
            matches((Path(parent) / d).relative_to(root).as_posix(), p) for p in excludes))
        for name in dirs + sorted(names):
            path = Path(parent) / name
            if not any(matches(path.relative_to(root).as_posix(), p) for p in excludes):
                yield path


def pack(stream, root, includes, excludes, cache=False):
    with tarfile.open(fileobj=stream, mode="w|", dereference=False) as archive:
        paths = cache_paths(root, excludes) if cache else selected_files(root, includes, excludes)
        for path in paths:
            if not cache and path.is_symlink() and not path.resolve().is_relative_to(root.resolve()):
                raise ValueError("Source link escapes source directory: " + str(path))
            archive.add(path, arcname=path.relative_to(root).as_posix(), recursive=False)


def read_exact(stream, length):
    if length < 0 or length > 256 * 1024 * 1024:
        raise ValueError("Invalid server response length")
    result = bytearray()
    while len(result) < length:
        chunk = stream.read(length - len(result))
        if not chunk:
            raise ValueError("Incomplete server response")
        result.extend(chunk)
    return bytes(result)


def read_int(stream):
    return struct.unpack(">i", read_exact(stream, 4))[0]


def write_string(stream, value):
    value = value.encode("utf-8")
    stream.write(struct.pack(">i", len(value)))
    stream.write(value)


class Runner:
    def __init__(self, root, job):
        self.root, self.job = root, job
        self.work = safe_path(root, "work")
        self.work.mkdir(parents=True, exist_ok=True)
        self.caches = []
        self.pulled_images = set()
        self.network = None
        self.ssl = ssl.create_default_context()
        self.cert_file = None
        certificates = []
        for cert in sorted((root / "trust-certs").glob("*")):
            if cert.is_file() and not cert.name.startswith("."):
                self.ssl.load_verify_locations(cafile=str(cert))
                certificates.append(cert.read_text())
        if certificates:
            # Keep the system roots as well as the installation's additional roots.
            bundle = ssl.get_default_verify_paths().cafile
            self.cert_file = safe_path(root, "trust-certs.pem")
            self.cert_file.write_text((Path(bundle).read_text() if bundle else "") + "\n".join(certificates))
        for name, value in job.get("attributes", {}).items():
            path = safe_path(root, "attributes/" + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(value)

    def request(self, endpoint, params=None, data=None, method=None, access_token=None):
        params = {k: v for k, v in (params or {}).items() if v is not None}
        params["token"] = self.job["token"]
        url = self.job["serverUrl"].rstrip("/") + "/~api/worker/" + endpoint
        headers = {"Content-Type": "application/octet-stream"}
        if access_token:
            headers["OneDevAuthorization"] = "Bearer " + access_token
        if data is not None:
            headers["Content-Length"] = str(os.fstat(data.fileno()).st_size)
        req = urllib.request.Request(url + "?" + urllib.parse.urlencode(params), data=data,
                                     headers=headers, method=method)
        try:
            return urllib.request.urlopen(req, context=self.ssl)
        except urllib.error.HTTPError as error:
            # Do not print URLs containing the job token.
            code = error.code
            error.close()
            raise RuntimeError("Server request %s failed (HTTP %s)" % (endpoint, code)) from None
        except urllib.error.URLError as error:
            raise RuntimeError("Unable to reach OneDev from sandbox: " + str(error.reason)) from None

    def placeholder_values(self, names):
        values = {}
        for name in names:
            path = safe_path(self.root, name)
            if path.is_file():
                values[name] = path.read_text().strip()
        return values

    def replace(self, value):
        values = self.placeholder_values(PLACEHOLDER.findall(value))

        def substitute(match):
            name = match.group(1)
            if name not in values:
                raise ValueError("Unable to resolve placeholder: " + name)
            return values[name]

        return PLACEHOLDER.sub(substitute, value)

    def command(self, step, position):
        wait_for_package_initialization()
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
        logins = self.job.get("registryLogins", []) + step.get("registryLogins", [])
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

    def change_owner(self, owner, paths):
        command = ["chown", "-R", owner, *map(str, paths)]
        subprocess.run(command, check=True, stderr=subprocess.STDOUT)

    def container_options(self, image):
        options = []
        if self.job.get("alwaysPullContainerImage", True) and image not in self.pulled_images:
            options.append("--pull=always")
            self.pulled_images.add(image)
        if self.job.get("cpuLimit"):
            options.extend(["--cpus", self.job["cpuLimit"]])
        if self.job.get("memoryLimit"):
            options.extend(["--memory", self.job["memoryLimit"]])
        return options

    def service_env(self, env):
        env = dict(env)
        names = [service["name"] for service in self.job.get("services", [])]
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

    def start_services(self):
        services = self.job.get("services", [])
        if not services:
            return
        # A fresh Docker daemon belongs to this job's sandbox. Deleting the VM
        # also removes this network and all services, including on cancellation.
        docker_options(self.job.get("runOptions"), {"--network", "--net"}, "", "Pq")
        options = docker_options(self.job.get("networkOptions"), {"--driver"}, "d", "")
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

    def container(self, step, position, arguments=None, entrypoint=None):
        if self.network:
            docker_options(self.job.get("runOptions"), {"--network", "--net"}, "", "Pq")
        options = docker_options(self.job.get("runOptions"), {
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
        paths = [self.root]
        for cache in self.caches:
            for entry in cache["entries"]:
                path = Path(entry["path"])
                if path.is_absolute():
                    args.extend(["-v", str(path) + ":" + str(path)])
                    paths.append(path)
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
        owner = "%s:%s" % (os.geteuid(), os.getegid())
        shell = safe_path(self.root, "shell.json")
        try:
            if run_as != "0:0":
                self.change_owner(run_as, paths)
            with self.docker_env(step) as env:
                # Publish atomically so terminals never read a partially written target.
                target = safe_path(self.root, "shell.json.tmp")
                target.write_text(json.dumps({"container": container_name, "executable": step.get("executable", "sh")}))
                target.replace(shell)
                return self.docker(args, env)
        finally:
            shell.unlink(missing_ok=True)
            # Container-created files must remain usable by later shell and server steps.
            if owner != "0:0":
                self.change_owner(owner, paths)

    def build_image(self, step):
        options = docker_options(self.replace(step.get("moreOptions") or ""), {
            "--builder", "--platform", "--push", "--load", "--file", "--tag", "--output"}, "fto", "q")
        with self.docker_env(step) as env:
            args = ["buildx", "build"]
            if self.job.get("alwaysPullContainerImage", True):
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

    def checkout(self, step):
        log("Checking out code...")
        directory = safe_path(self.work, step.get("path") or "")
        directory.mkdir(parents=True, exist_ok=True)
        for name in (".git", ".gitmodules", ".lfsconfig"):
            path = directory / name
            if path.is_symlink() or path.is_file():
                path.unlink()
            elif path.exists():
                shutil.rmtree(path)
        env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_LFS_SKIP_SMUDGE="0" if step["lfs"] else "1")
        git_options = ["-c", "safe.directory=*"]

        def git(*args, capture=False):
            result = subprocess.run(["git", *git_options, *args], cwd=directory, env=env,
                                    stdout=subprocess.PIPE if capture else None, stderr=subprocess.STDOUT)
            if result.returncode:
                raise RuntimeError("Git command failed (exit code %s)" % result.returncode)
            return result.stdout

        git("init", "--template=", "-b", "main", ".")
        fields = step["cloneInfo"].split("-")
        kind = fields.pop(0)
        fields = [base64.b64decode(value).decode() for value in fields]
        url = fields[0]
        config = {"user.name": "OneDev", "user.email": "onedev@onedev.io"}
        if self.cert_file:
            config["http.sslCAInfo"] = str(self.cert_file)
        if kind in ("http", "default"):
            origin = urllib.parse.urlsplit(url)
            config["http.%s://%s/.extraHeader" % (origin.scheme, origin.netloc)] = "OneDevAuthorization: Bearer " + fields[1]
        elif kind == "ssh":
            key = safe_path(self.root, ".ssh/id_rsa")
            key.parent.mkdir(exist_ok=True)
            if key.exists():
                key.chmod(0o600)
            key.write_text(fields[1])
            key.chmod(0o400)
            hosts = safe_path(self.root, ".ssh/known_hosts")
            hosts.write_text(fields[2])
            config["core.sshCommand"] = "ssh -i %s -o UserKnownHostsFile=%s -F /dev/null" % (shlex.quote(str(key)), shlex.quote(str(hosts)))
        else:
            raise ValueError("Unknown clone authentication type")
        for key, value in config.items():
            git("config", key, value)
            git_options.extend(["-c", key + "=" + value])
        depth = ["--depth=" + str(step["depth"])] if step["depth"] else []
        commit = self.job["commitHash"]
        git("fetch", url, "--force", "--progress", *depth, commit)
        git("remote", "add", "origin", url)
        if step["lfs"]:
            git("lfs", "install", "--local", "--force")
        for entry in git("ls-tree", "--full-tree", "-r", "-z", commit, capture=True).split(b"\0"):
            if entry.startswith(b"160000 commit "):
                path = safe_path(directory, entry.split(b"\t", 1)[1].decode())
                if path.is_dir():
                    shutil.rmtree(path)
                elif path.exists():
                    path.unlink()
        git("-c", "submodule.recurse=false", "checkout", "--force", "--quiet", commit)
        if step["submodules"] and (directory / ".gitmodules").exists():
            git("submodule", "update", "--init", "--recursive", "--force", *depth)
            git("submodule", "foreach", "--quiet", "--recursive",
                'branch=$(git symbolic-ref --short refs/remotes/origin/HEAD 2>/dev/null); '
                'if [ -n "$branch" ]; then branch=${branch#origin/}; '
                'git fetch origin "$branch:refs/remotes/origin/$branch" --quiet && '
                'git checkout -B "$branch" "$sha1" --quiet && '
                'git branch --set-upstream-to="origin/$branch" "$branch" >/dev/null || true; fi')
            git("submodule", "foreach", "--quiet", "--recursive",
                'for key in http.sslCAInfo core.sshCommand user.name user.email pull.rebase; do '
                'value=$(git -C "$toplevel" config --get "$key"); '
                'if [ -n "$value" ]; then git config "$key" "$value"; fi; done; '
                'git -C "$toplevel" config --name-only --get-regexp "^http\\..*\\.extraheader$" | '
                'while IFS= read -r key; do value=$(git -C "$toplevel" config --get "$key"); git config "$key" "$value"; done')
        ref = self.job["refName"]
        if ref.startswith("refs/heads/"):
            branch = ref[len("refs/heads/"):]
            git("checkout", "-B", branch, commit)
            git("update-ref", "refs/remotes/origin/" + branch, commit)
            git("branch", "--set-upstream-to=origin/" + branch, branch)
        return True

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

    def cache_dir(self, path):
        # Absolute cache paths are rooted in the sandbox's own filesystem.
        directory = Path(path)
        if directory.is_absolute():
            return safe_path(Path(directory.anchor), path[len(directory.anchor):])
        return safe_path(self.work, path)

    def setup_cache(self, step):
        config = dict(step, key=self.replace(step["key"]), checksum=None, exact=set())
        if "checksumIncludes" in config:
            patterns = config["checksumIncludes"] + config["checksumExcludes"]
            if any(".." in pattern for pattern in patterns):
                raise ValueError("Checksum patterns must not contain '..'")
            files = selected_files(self.work, config["checksumIncludes"], config["checksumExcludes"], follow_links=True)
            if not files:
                raise ValueError("No checksum files found")
            digest = hashlib.md5()
            for path in files:
                with path.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
            config["checksum"] = digest.hexdigest()
        for entry in config["entries"]:
            path = entry["path"]
            directory = self.cache_dir(path)
            directory.mkdir(parents=True, exist_ok=True)
            params = {"key": config["key"], "checksum": config["checksum"], "path": path}
            with self.request("job-cache", params) as response:
                availability = read_exact(response, 1)[0]
                if availability in (0, 1):
                    # The server stores the archive as bytes; only this VM restores its links.
                    unpack(response, directory, allow_external_symlinks=True)
                    log(("Exact" if availability == 0 else "Partial") + " matched cache (key: %s, path: %s)" % (config["key"], path))
                elif availability != 2:
                    raise ValueError("Invalid cache availability")
                if availability == 0:
                    config["exact"].add(path)
        config["provisioned"] = time.time()
        self.caches.append(config)
        return True

    def upload_caches(self):
        for config in self.caches:
            for entry in config["entries"]:
                path = entry["path"]
                directory = self.cache_dir(path)
                excludes = shlex.split(entry.get("excludes") or "")
                if config["strategy"] == "UPLOAD_IF_NOT_EXACT_MATCH":
                    if path in config["exact"]:
                        continue
                elif not any(max(stat.st_mtime, stat.st_ctime) > config["provisioned"]
                             for stat in (p.lstat() for p in cache_paths(directory, excludes))):
                    continue
                params = {"key": config["key"], "checksum": config["checksum"], "path": path,
                          "projectPath": config.get("projectPath")}
                try:
                    with self.request("job-cache", {"projectPath": config.get("projectPath")},
                                      method="HEAD", access_token=config.get("accessToken")):
                        pass
                except RuntimeError as error:
                    if "(HTTP 401)" in str(error):
                        log("Not authorized to upload cache: " + config["key"])
                        continue
                    raise
                with tempfile.TemporaryFile() as body:
                    pack(body, directory, ["**"], excludes, cache=True)
                    body.seek(0)
                    with self.request("job-cache", params, body, access_token=config.get("accessToken")):
                        pass
                log("Uploaded cache (key: %s, path: %s)" % (config["key"], path))

    def skip(self, actions, prefix):
        for index, step in enumerate(actions):
            position = prefix + [str(index)]
            if step["type"] == "composite":
                self.skip(step["actions"], position)
            else:
                log("##onedev-step:SKIP:step-" + "-".join(position))

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
            self.skip(step["actions"], position)
        else:
            log("##onedev-step:SKIP:step-" + "-".join(position))

    def execute_leaf(self, step, position):
        log("##onedev-step:START:step-" + position)
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
        log("##onedev-step:%s:step-%s" % ("SUCCESSFUL" if result else "FAILED", position))
        return result

    def run(self):
        if self.job.get("test"):
            subprocess.run(["git", "--version"], check=True)
            log("Sandbox Python runtime is ready")
            log("Connecting to server '%s' from sandbox..." % self.job["serverUrl"])
            with self.request("test"):
                pass
            subprocess.run(["sh", "-c", "echo hello from shell"], check=True)
            image = self.job.get("testImage")
            if image:
                log("Testing command execution in container '%s'..." % image)
                return self.container({"image": image}, "test", ["-c", "echo hello from container"], entrypoint="sh")
            return True
        log("Downloading job dependencies...")
        with self.request("dependencies") as response:
            unpack(response, self.work)
        with self.request("job-workdir", {"workDir": str(self.work)}, method="PUT"):
            pass
        try:
            self.start_services()
            successful = self.execute(self.job["actions"])
        finally:
            log("##onedev-phase:finalization")
        if successful:
            self.upload_caches()
        return successful


if __name__ == "__main__":
    shell = len(sys.argv) > 2 and sys.argv[2] == "--shell"
    try:
        root = Path(sys.argv[1])
        if shell:
            open_shell(root)
        else:
            job = json.loads((root / "job.json").read_text())
            sys.exit(0 if Runner(root, job).run() else 1)
    except Exception as error:
        log_error(("Unable to open build terminal: " if shell else "Sandbox job failed: ") + str(error))
        sys.exit(1)
