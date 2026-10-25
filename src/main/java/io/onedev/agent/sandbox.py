"""Shared sandbox support for Git checkout, HTTP/TLS, archives, and caches.

Uses only the sandbox shell image's Python standard library.
"""
import base64
import fnmatch
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import ssl
import struct
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


PLACEHOLDER = re.compile(r"<&onedev#(.*?)#onedev&>")


def log(message):
    print(message, flush=True)


def log_error(message):
    # Match TaskLogger.error while keeping errors and step events on one stream.
    log("\033[1;31m" + message + "\033[0m")


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


def paths_changed(paths, since):
    """Check entry timestamps without following symbolic links to their targets."""
    return any(max(stat.st_mtime, stat.st_ctime) > since for stat in (path.lstat() for path in paths))


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


class SandboxRuntime:
    """Filesystem, server access, checkout and caches shared by both runners."""

    def __init__(self, root, config, cache_endpoint):
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
            self.cert_file.write_text((Path(bundle).read_text() if bundle else "") + "\n".join(certificates))

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
                if availability == 0:
                    config["exact"].add(path)
        config["provisioned"] = time.time()
        self.caches.append(config)
        return True

    def upload_caches(self):
        for config in self.caches:
            for entry in config["entries"]:
                path = entry["path"]
                directory = self.data_path(path)
                excludes = shlex.split(entry.get("excludes") or "")
                if config["strategy"] == "UPLOAD_IF_NOT_EXACT_MATCH":
                    if path in config["exact"]:
                        continue
                elif not paths_changed(cache_paths(directory, excludes), config["provisioned"]):
                    continue
                params = {"key": config["key"], "checksum": config["checksum"], "path": path,
                          "projectPath": config.get("projectPath")}
                try:
                    with self.request(self.cache_endpoint, {"projectPath": config.get("projectPath")},
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
                    with self.request(self.cache_endpoint, params, body, access_token=config.get("accessToken")):
                        pass
                log("Uploaded cache (key: %s, path: %s)" % (config["key"], path))
