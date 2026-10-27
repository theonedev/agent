"""Persistent workspace lifecycle, running directly in a sandbox shell.

Checkout, TLS, archive handling and caches use the shared sandbox module.
Docker, when provided by the template, is available to user commands as-is.
"""
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time

from sandbox import SandboxRuntime, log, log_error, read_int
from sandbox_files import cache_paths, pack, paths_changed, safe_path, unpack


def unpack_user_data(stream, target):
    """Restore either a single-file archive or a directory with sandbox-local links."""
    with tempfile.TemporaryFile() as archive:
        shutil.copyfileobj(stream, archive)
        archive.seek(0)
        with tarfile.open(fileobj=archive, mode="r:") as tar:
            first = tar.next()
            if first and first.pax_headers.get("OneDev.singleFile") == "true":
                if not first.isfile():
                    raise ValueError("Expected a regular user data file")
                target.parent.mkdir(parents=True, exist_ok=True)
                with tar.extractfile(first) as source, target.open("wb") as dest:
                    shutil.copyfileobj(source, dest)
                target.chmod(first.mode & 0o777)
                return
        archive.seek(0)
        unpack(archive, target, allow_external_symlinks=True)


def pack_user_data(stream, path, excludes):
    """Mark single-file uploads so they can be restored to the configured path."""
    if path.is_file():
        with tarfile.open(fileobj=stream, mode="w", format=tarfile.PAX_FORMAT) as tar:
            info = tar.gettarinfo(str(path), arcname=path.name)
            info.pax_headers["OneDev.singleFile"] = "true"
            with path.open("rb") as source:
                tar.addfile(info, source)
    else:
        pack(stream, path, ["**"], excludes, cache=True)


class WorkspaceRunner(SandboxRuntime):
    def __init__(self, root, workspace):
        super().__init__(root, workspace, cache_endpoint="workspace-cache")
        self.env = dict(os.environ, **workspace.get("env", {}))
        self.env.update(ONEDEV_WORKDIR=str(self.work), ONEDEV_ACCESS_TOKEN=workspace["token"],
                        ONEDEV_SERVER_URL=workspace["serverUrl"], TERM="xterm-256color", LANG="C.UTF-8")
        if self.cert_file:
            self.env["ONEDEV_TRUST_CERTS_FILE"] = str(self.cert_file)
        else:
            self.env.pop("ONEDEV_TRUST_CERTS_FILE", None)
        self.user_data_ready = False
        self.user_data_provision_dates = {}

    def setup_repository(self):
        marker = safe_path(self.root, ".git-clone-successful")
        if not (marker.is_file() and (self.work / ".git").exists()):
            marker.unlink(missing_ok=True)
            self.checkout(dict(self.config, path=None, depth=0))
            marker.touch()
        else:
            log("Repository was cloned successfully, preserving workspace changes")
        # Refresh session credentials without fetching or changing the user's branch and edits.
        _, config = self.git_config(self.config["cloneInfo"])
        config.update({"user.name": self.config["userName"], "user.email": self.config["userEmail"], "pull.rebase": "false"})
        for key, value in config.items():
            subprocess.run(["git", "-c", "safe.directory=*", "config", key, value],
                           cwd=self.work, check=True, stderr=subprocess.STDOUT)

    def configure_files(self):
        for config in self.config.get("configFiles", []):
            target = self.data_path(config["path"])
            log("Provisioning config file '%s'..." % config["path"])
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(config["content"])
            target.chmod(0o600)

    def download_user_data(self):
        self.user_data_ready = False
        self.user_data_provision_dates.clear()
        for data in self.config.get("userDatas", []):
            download_logged = False
            for entry in data["entries"]:
                path = self.data_path(entry["path"])
                key = hashlib.sha256((data["key"] + "\0" + entry["path"]).encode()).hexdigest()
                marker = safe_path(self.root, "user-data/" + key + "/state")
                # Existing template files still need restoring once; later sessions keep local edits.
                if marker.exists() and path.exists():
                    self.user_data_provision_dates[(data["key"], entry["path"])] = marker.stat().st_mtime
                    continue
                if not download_logged:
                    log("Downloading user data '%s'..." % data["key"])
                    download_logged = True
                params = {"key": data["key"], "path": entry["path"]}
                with self.request("workspace-user-data", params) as response:
                    if read_int(response) == 0:
                        if not path.exists():
                            path.mkdir(parents=True, exist_ok=True)
                    else:
                        unpack_user_data(response, path)
                marker.parent.mkdir(parents=True, exist_ok=True)
                marker.touch()
                self.user_data_provision_dates[(data["key"], entry["path"])] = marker.stat().st_mtime
        self.user_data_ready = True

    def upload_user_data(self):
        if not self.user_data_ready:
            return
        for data in self.config.get("userDatas", []):
            uploaded = False
            for entry in data["entries"]:
                path = self.data_path(entry["path"])
                if not path.exists():
                    continue
                excludes = shlex.split(entry.get("excludes") or "")
                paths = [path] if path.is_file() else cache_paths(path, excludes, include_root=True)
                provisioned = self.user_data_provision_dates.get((data["key"], entry["path"]))
                if provisioned is not None and not paths_changed(paths, provisioned):
                    continue
                with tempfile.TemporaryFile() as archive:
                    pack_user_data(archive, path, excludes)
                    archive.seek(0)
                    with self.request("workspace-user-data", {"key": data["key"], "path": entry["path"]}, archive):
                        pass
                uploaded = True
            if uploaded:
                with self.request("workspace-user-data", {"key": data["key"]}, method="PUT"):
                    pass
                log("Uploaded user data '%s'" % data["key"])

    def run_script(self, name, cancellable=False):
        script = self.config["script"]
        commands = script.get(name + "Commands")
        if commands is None:
            return
        log("Running %s commands..." % name)
        path = safe_path(self.root, name + script["scriptExtension"])
        path.write_text(commands)
        process = subprocess.Popen([script["scriptExecutable"], *script["scriptOptions"], str(path)],
                                   cwd=self.work, env=self.env, stderr=subprocess.STDOUT, start_new_session=True)
        while True:
            try:
                code = process.wait(timeout=0.5)
                if code:
                    raise RuntimeError("Workspace %s exited with code %s" % (name, code))
                return
            except subprocess.TimeoutExpired:
                if cancellable and (self.root / ".shutdown").exists():
                    os.killpg(process.pid, signal.SIGTERM)
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                        process.wait()
                    raise InterruptedError("Workspace setup cancelled")

    def run(self):
        setup_started = False
        active = False
        try:
            self.setup_repository()
            self.configure_files()
            for cache in self.config.get("caches", []):
                self.setup_cache(cache, persistent=True)
            self.download_user_data()
            if (self.root / ".shutdown").exists():
                return
            setup_started = True
            self.run_script("setup", cancellable=True)
            # The template or setup commands must provide tmux for workspace terminals.
            subprocess.run(["tmux", "-V"], check=True, stdout=subprocess.DEVNULL)
            safe_path(self.root, ".workspace-ready").touch()
            active = True
            while not (self.root / ".shutdown").exists():
                time.sleep(0.5)
        finally:
            safe_path(self.root, ".workspace-ready").unlink(missing_ok=True)
            try:
                if setup_started:
                    self.run_script("teardown")
            finally:
                if active and (self.root / ".upload-data").exists():
                    try:
                        self.upload_user_data()
                    finally:
                        self.upload_caches()

    def test(self):
        self.test_runtime(workspace=True)
        log("Sandbox workspace runtime is ready")

    def open_shell(self, shell_id, executable):
        os.chdir(self.work)
        os.execvpe("tmux", ["tmux", "-L", "onedev-" + shell_id, "new-session", executable], self.env)


def main(args):
    try:
        root = Path(args[0])
        workspace = WorkspaceRunner(root, json.loads((root / "workspace.json").read_text()))
        if len(args) > 1 and args[1] == "--shell":
            workspace.open_shell(args[2], args[3])
        elif len(args) > 1 and args[1] == "--test":
            workspace.test()
        else:
            workspace.run()
        return 0
    except Exception as error:
        log_error("Sandbox workspace failed: " + str(error))
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
