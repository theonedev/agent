"""Shared sandbox support for Git checkout, HTTP/TLS, archives, and caches.

Uses only the sandbox shell image's Python standard library.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import ssl
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


from sandbox_files import cache_paths, pack, paths_changed, safe_path, selected_files, unpack


PLACEHOLDER = re.compile(r"<&onedev#(.*?)#onedev&>")


def log(message):
    print(message, flush=True)


def log_error(message):
    # Match TaskLogger.error while keeping errors and step events on one stream.
    log("\033[1;31m" + message + "\033[0m")


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


class ServerRequestError(RuntimeError):
    def __init__(self, endpoint, status):
        super().__init__("Server request %s failed (HTTP %s)" % (endpoint, status))
        self.status = status


class ProvisionedCache:
    """Cache configuration and the local state carried between Docker helpers."""
    def __init__(self, config):
        self.config = config
        self.exact = set()
        self.provisioned = 0
        self.provisioned_paths = {}

    def has_changes(self, entry, directory):
        # The root's mtime also detects entries added, removed or renamed directly inside it.
        restored_at = self.provisioned_paths.get(entry["path"], self.provisioned)
        return paths_changed(cache_paths(directory, shlex.split(entry.get("excludes") or ""), include_root=True),
                             restored_at)

    def to_state(self):
        return dict(config=self.config, exact=sorted(self.exact), provisioned=self.provisioned,
                    provisionedPaths=self.provisioned_paths)

    @classmethod
    def from_state(cls, state):
        cache = cls(state["config"])
        cache.exact = set(state["exact"])
        cache.provisioned = state["provisioned"]
        cache.provisioned_paths = state.get("provisionedPaths", {})
        return cache


class SandboxRuntime:
    """Filesystem, server access, checkout and caches shared by both runners."""

    def __init__(self, root, config, cache_endpoint, initialize=True):
        self.root, self.config = root, config
        self.cache_endpoint = cache_endpoint
        self.work = safe_path(root, "work")
        self.work.mkdir(parents=True, exist_ok=True)
        self.caches = []
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
            if initialize:
                self.cert_file.write_text((Path(bundle).read_text() if bundle else "") + "\n".join(certificates))

    def test_runtime(self, workspace=False):
        subprocess.run(["git", "--version"], check=True)
        if workspace:
            subprocess.run(["tmux", "-V"], check=True)
        log("Sandbox Python runtime is ready")
        log("Connecting to server '%s' from sandbox..." % self.config["serverUrl"])
        with self.request("test"):
            pass
        subprocess.run(["sh", "-c", "echo hello from shell"], check=True)

    def request(self, endpoint, params=None, data=None, method=None, access_token=None):
        params = {k: v for k, v in (params or {}).items() if v is not None}
        params["token"] = self.config["token"]
        url = self.config["serverUrl"].rstrip("/") + "/~api/worker/" + endpoint
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
            # Do not print URLs containing the session token.
            code = error.code
            error.close()
            raise ServerRequestError(endpoint, code) from None
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

    def git_config(self, clone_info):
        fields = clone_info.split("-")
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
        return url, config

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
        url, config = self.git_config(step["cloneInfo"])
        for key, value in config.items():
            git("config", key, value)
            git_options.extend(["-c", key + "=" + value])
        depth = ["--depth=" + str(step["depth"])] if step["depth"] else []
        commit = self.config["commitHash"]
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
        ref = self.config["refName"]
        if ref.startswith("refs/heads/"):
            branch = ref[len("refs/heads/"):]
            git("checkout", "-B", branch, commit)
            git("update-ref", "refs/remotes/origin/" + branch, commit)
            git("branch", "--set-upstream-to=origin/" + branch, branch)
        return True

    def data_path(self, path):
        """Resolve cache, user-data and configuration paths inside the sandbox."""
        directory = Path(path)
        if directory.is_absolute():
            return safe_path(Path(directory.anchor), path[len(directory.anchor):])
        return safe_path(self.work, path)

    def cache_checksum(self, config):
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
            return digest.hexdigest()
        return None

    def setup_cache(self, step, persistent=False):
        config = dict(step, key=self.replace(step["key"]), checksum=self.cache_checksum(step))
        cache = ProvisionedCache(config)
        for entry in config["entries"]:
            path = entry["path"]
            directory = self.data_path(path)
            state_file = None
            if persistent:
                name = hashlib.sha256(path.encode()).hexdigest()
                state_file = safe_path(self.root, "cache/" + name + "/state")
                state = json.loads(state_file.read_text()) if state_file.exists() else None
                if state and directory.is_dir() and state["key"] == config["key"]:
                    if state["exact"] and state["checksum"] == config["checksum"]:
                        cache.exact.add(path)
                    cache.provisioned_paths[path] = state["provisioned"]
                    continue
                # Invalidate old metadata before a replacement that might fail partway through.
                state_file.unlink(missing_ok=True)
            availability = self.restore_cache(config, path)
            if availability == 0:
                cache.exact.add(path)
            if state_file is not None:
                restored_at = time.time()
                state = dict(key=config["key"], checksum=config["checksum"],
                             exact=availability == 0, provisioned=restored_at)
                self.write_cache_state(state_file, state)
                cache.provisioned_paths[path] = restored_at
        cache.provisioned = time.time()
        self.caches.append(cache)
        return True

    def write_cache_state(self, path, state):
        path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(mode="w", dir=path.parent, prefix=".restore-", delete=False) as stream:
            temporary = Path(stream.name)
            try:
                json.dump(state, stream)
                stream.close()
                os.replace(temporary, path)
            finally:
                temporary.unlink(missing_ok=True)

    def restore_cache(self, config, path):
        directory = self.data_path(path)
        directory.mkdir(parents=True, exist_ok=True)
        params = {"key": config["key"], "checksum": config["checksum"], "path": path}
        with self.request(self.cache_endpoint, params) as response:
            availability = read_exact(response, 1)[0]
            if availability in (0, 1):
                # The server stores the archive as bytes; only this VM restores its links.
                unpack(response, directory, allow_external_symlinks=True)
                log(("Exact" if availability == 0 else "Partial") + " matched cache (key: %s, path: %s)" % (config["key"], path))
            elif availability != 2:
                raise ValueError("Invalid cache availability")
            return availability

    def absolute_cache_paths(self):
        return list(dict.fromkeys(Path(entry["path"]) for cache in self.caches
                                  for entry in cache.config["entries"] if Path(entry["path"]).is_absolute()))

    def upload_caches(self):
        for cache in self.caches:
            for entry in cache.config["entries"]:
                self.upload_cache(cache, entry)

    def upload_cache(self, cache, entry):
        config = cache.config
        path = entry["path"]
        directory = self.data_path(path)
        excludes = shlex.split(entry.get("excludes") or "")
        if config["strategy"] == "UPLOAD_IF_NOT_EXACT_MATCH":
            if path in cache.exact:
                return
        elif not cache.has_changes(entry, directory):
            return
        params = {"key": config["key"], "checksum": config["checksum"], "path": path,
                  "projectPath": config.get("projectPath")}
        try:
            with self.request(self.cache_endpoint, {"projectPath": config.get("projectPath")},
                              method="HEAD", access_token=config.get("accessToken")):
                pass
        except ServerRequestError as error:
            if error.status == 401:
                log("Not authorized to upload cache: " + config["key"])
                return
            raise
        with tempfile.TemporaryFile() as body:
            pack(body, directory, ["**"], excludes, cache=True)
            body.seek(0)
            with self.request(self.cache_endpoint, params, body, access_token=config.get("accessToken")):
                pass
        log("Uploaded cache (key: %s, path: %s)" % (config["key"], path))
