"""Job-only privilege dispatch and ownership policy.

Helpers run the same job entry point with a snapshot of mutable execution state.
The template identity survives sudo, so outputs return to that user. Workspaces
use the shared runtime directly and do not opt into these policies.
"""
from dataclasses import dataclass
from functools import wraps
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Optional

from sandbox import ProvisionedCache
from sandbox_files import walk_directory


@dataclass
class JobState:
    """The complete state exchanged with a privileged job helper."""
    caches: list[dict]
    pulled_images: list[str]
    network: Optional[str]
    owner: list[int]

    @classmethod
    def capture(cls, job):
        return cls([cache.to_state() for cache in job.caches], sorted(job.pulled_images),
                   job.network, list(job.privileges.owner))

    def apply(self, job):
        job.caches = [ProvisionedCache.from_state(cache) for cache in self.caches]
        job.pulled_images = set(self.pulled_images)
        job.network = self.network
        job.privileges.owner = self.owner

    def to_dict(self):
        return dict(caches=self.caches, pulledImages=self.pulled_images,
                    network=self.network, owner=self.owner)

    @classmethod
    def from_dict(cls, state):
        return cls(state["caches"], state["pulledImages"], state["network"], state["owner"])


def root_operation(method):
    """Run a job operation with sudo when the template user is not root."""
    @wraps(method)
    def invoke(job, *args, **kwargs):
        if os.geteuid() == 0:
            return method(job, *args, **kwargs)
        return job.privileges.call_as_root(method.__name__, args, kwargs)
    return invoke


class JobPrivileges:
    """Keep template identity and ownership rules separate from job scheduling."""
    def __init__(self, job, entrypoint):
        self.job, self.entrypoint = job, entrypoint
        self.owner = [os.geteuid(), os.getegid()]

    def call_as_root(self, operation, args, kwargs):
        # Keep credentials off the command line, and preserve the job user's Docker
        # configuration/PATH/HOME rather than inheriting sudo's root environment.
        payload = dict(config=self.job.config, state=JobState.capture(self.job).to_dict(), env=dict(os.environ),
                       operation=operation, args=args, kwargs=kwargs)
        with tempfile.NamedTemporaryFile(prefix="onedev-operation-", dir="/tmp") as result:
            try:
                subprocess.run(["sudo", "-n", "--", sys.executable, "-u", str(self.entrypoint),
                                str(self.job.root), "--operation", result.name],
                               input=json.dumps(payload), text=True, cwd="/",
                               check=True, stderr=subprocess.STDOUT)
            except FileNotFoundError:
                raise RuntimeError("Unable to start privileged sandbox operation '%s': sudo is not installed. "
                                   "Install sudo in the template image and grant its default user passwordless sudo access."
                                   % operation) from None
            except subprocess.CalledProcessError:
                # Operation failures normally arrive in the response. Keep any diagnostic
                # from sudo or an unexpectedly terminated helper above this context.
                raise RuntimeError("Unable to run privileged sandbox operation '%s'. "
                                   "Ensure the template user has passwordless sudo access for this operation; "
                                   "OneDev will not prompt for a password. See the error above for details."
                                   % operation) from None
            response = json.loads(result.read())
        JobState.from_dict(response["state"]).apply(self.job)
        if response.get("error"):
            raise RuntimeError(response["error"])
        return response["result"]

    def requires_root_for_cache(self, path):
        if not Path(path).is_absolute() or os.geteuid() == 0:
            return False
        # Missing parents will be created during restore. Check the nearest
        # existing one to decide whether that creation needs sudo.
        parent = Path(path).parent
        while True:
            try:
                return parent.stat().st_uid != self.owner[0]
            except FileNotFoundError:
                parent = parent.parent
            except PermissionError:
                return True

    def restore_owner(self, paths):
        if self.owner[0] != 0:
            self.change_owner("%s:%s" % tuple(self.owner), paths)

    def change_owner(self, owner, paths):
        paths = list(dict.fromkeys(Path(path).absolute() for path in paths))
        paths = [path for path in paths if not any(parent in paths for parent in path.parents)]
        uid, gid = map(int, owner.split(":"))

        def chown(path):
            info = path.lstat()
            if (info.st_uid, info.st_gid) != (uid, gid):
                os.chown(path, uid, gid, follow_symlinks=False)

        for path in paths:
            if not path.exists() and not path.is_symlink():
                continue
            chown(path)
            if not path.is_symlink() and path.is_dir():
                for parent, directories, files in walk_directory(path):
                    for name in directories + files:
                        chown(Path(parent) / name)


def run_operation(root, result_path, runner_factory):
    """Rehydrate a helper without rewriting attributes or the trust-certificate bundle."""
    payload = json.load(sys.stdin)
    os.environ.clear()
    os.environ.update(payload["env"])
    job = runner_factory(root, payload["config"], initialize=False)
    JobState.from_dict(payload["state"]).apply(job)
    result, error = None, None
    try:
        result = getattr(job, payload["operation"])(*payload["args"], **payload["kwargs"])
    except Exception as failure:
        error = str(failure)
    finally:
        Path(result_path).write_text(json.dumps(dict(result=result, error=error,
                                                    state=JobState.capture(job).to_dict())))
