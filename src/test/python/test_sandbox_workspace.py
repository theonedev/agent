import base64
import importlib.util
import io
import os
from pathlib import Path
import struct
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

AGENT = Path(__file__).resolve().parents[2] / 'main/java/io/onedev/agent'
sys.path.insert(0, str(AGENT))
from sandbox import SandboxRuntime

spec = importlib.util.spec_from_file_location('sandbox_workspace', AGENT / 'workspace/sandbox-workspace.py')
workspace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workspace)
WorkspaceRunner = workspace.WorkspaceRunner


class SandboxWorkspaceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='onedev workspace ')
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name).resolve()
        self.root = self.base / 'workspace'
        self.root.mkdir()
        bin_dir = self.base / 'bin'
        bin_dir.mkdir()
        tmux = bin_dir / 'tmux'
        tmux.write_text('#!/bin/sh\necho tmux-test\n')
        tmux.chmod(0o755)
        environment = patch.dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
        environment.start()
        self.addCleanup(environment.stop)
        self.data = dict(token='token', serverUrl='http://server', env={'CUSTOM': 'a b'},
                         caches=[], userDatas=[], configFiles=[],
                         script=dict(setupCommands=None, teardownCommands=None,
                                     scriptExtension='.sh', scriptExecutable='sh', scriptOptions=[]))

    def repository(self):
        repository = self.base / 'repository'
        repository.mkdir()
        def git(*args):
            return subprocess.check_output(['git', *args], cwd=repository).decode().strip()
        git('init', '-b', 'main')
        git('config', 'user.name', 'Source User')
        git('config', 'user.email', 'source@example.com')
        (repository / 'README').write_text('initial')
        git('add', '.')
        git('commit', '-m', 'Initial')
        encoded_url = base64.b64encode(repository.as_uri().encode()).decode()
        self.data.update(cloneInfo='http-' + encoded_url + '-' + base64.b64encode(b'token').decode(),
                         commitHash=git('rev-parse', 'HEAD'), refName='refs/heads/main',
                         userName='Workspace User', userEmail='user@example.com', lfs=False, submodules=False)

    def start(self, runner):
        failures = []
        def run():
            try:
                runner.run()
            except BaseException as error:
                failures.append(error)
        thread = threading.Thread(target=run)
        thread.start()
        def stop():
            (self.root / '.shutdown').touch()
            thread.join(15)
            self.assertFalse(thread.is_alive())
        self.addCleanup(stop)
        return thread, failures

    def await_file(self, name, failures):
        deadline = time.monotonic() + 10
        while not (self.root / name).exists():
            self.assertFalse(failures, failures)
            self.assertLess(time.monotonic(), deadline)
            time.sleep(0.02)

    def test_direct_setup_teardown_and_restart_preserve_edits(self):
        self.repository()
        self.data['script'].update(setupCommands='printf "%s" "$CUSTOM" > env-value',
                                   teardownCommands='echo done > teardown-ran')
        runner = WorkspaceRunner(self.root, self.data)
        thread, failures = self.start(runner)
        self.await_file('.workspace-ready', failures)
        self.assertEqual('a b', (runner.work / 'env-value').read_text())
        (runner.work / 'README').write_text('unsaved edits')
        subprocess.run(['git', 'checkout', '-b', 'user-branch'], cwd=runner.work, check=True)
        (self.root / '.shutdown').touch()
        thread.join(15)
        self.assertFalse(thread.is_alive())
        self.assertFalse(failures, failures)
        self.assertTrue((runner.work / 'teardown-ran').exists())
        self.assertFalse((self.root / '.workspace-ready').exists())
        self.data['cloneInfo'] = self.data['cloneInfo'].rsplit('-', 1)[0] + '-' + base64.b64encode(b'new-token').decode()
        resumed = WorkspaceRunner(self.root, self.data)
        resumed.setup_repository()
        self.assertEqual('unsaved edits', (resumed.work / 'README').read_text())
        def git(*args):
            return subprocess.check_output(['git', *args], cwd=resumed.work).decode().strip()
        self.assertEqual('user-branch', git('branch', '--show-current'))
        self.assertEqual('Workspace User', git('config', 'user.name'))
        self.assertIn('new-token', git('config', '--get-regexp', 'http.*extraheader'))

    def test_cancelling_setup_runs_teardown(self):
        self.data['script'].update(setupCommands='touch setup-started; sleep 30; touch should-not-exist',
                                   teardownCommands='touch teardown-ran')
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'setup_repository'):
            thread, failures = self.start(runner)
            self.await_file('work/setup-started', failures)
            (self.root / '.shutdown').touch()
            thread.join(12)
        self.assertFalse(thread.is_alive())
        self.assertIsInstance(failures[0], InterruptedError)
        self.assertTrue((runner.work / 'teardown-ran').exists())
        self.assertFalse((runner.work / 'should-not-exist').exists())
        self.assertFalse((self.root / '.workspace-ready').exists())

    def test_teardown_failure_still_uploads_data_and_cache(self):
        self.data['script'].update(setupCommands='exit 7', teardownCommands='exit 8')
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'setup_repository'), patch.object(runner, 'upload_user_data') as users, \
                patch.object(runner, 'upload_caches') as caches:
            with self.assertRaisesRegex(RuntimeError, 'teardown exited with code 8'):
                runner.run()
            users.assert_called_once()
            caches.assert_called_once()
        self.assertFalse((self.root / '.workspace-ready').exists())

    def test_workspace_cache_reuses_shared_protocol(self):
        calls = []
        def request(_runner, endpoint, params=None, body=None, method=None, **kwargs):
            method = method or ('POST' if body is not None else 'GET')
            calls.append((endpoint, method, body.read() if body is not None else None))
            return io.BytesIO(b'\x02' if method == 'GET' else b'')
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(SandboxRuntime, 'request', request):
            runner.setup_cache(dict(key='cache', entries=[dict(path='cache', excludes='')],
                                    strategy='UPLOAD_IF_NOT_EXACT_MATCH'))
            (runner.work / 'cache/link').symlink_to('/sandbox-only-target')
            runner.upload_caches()
        self.assertEqual(3, len(calls))
        self.assertTrue(all(call[0] == 'workspace-cache' for call in calls))
        with tarfile.open(fileobj=io.BytesIO(calls[-1][2])) as archive:
            self.assertEqual('/sandbox-only-target', archive.getmember('link').linkname)

    def test_user_data_file_and_config(self):
        user_file = self.base / 'user-data-file'
        user_file.write_text('template default')
        config_file = self.base / 'config'
        self.data['userDatas'] = [dict(key='personal', entries=[dict(path=str(user_file), excludes=None)])]
        self.data['configFiles'] = [dict(path=str(config_file), content='secret')]
        runner = WorkspaceRunner(self.root, self.data)
        runner.configure_files()
        self.assertEqual('secret', config_file.read_text())
        self.assertEqual(0o600, config_file.stat().st_mode & 0o777)
        payload = io.BytesIO()
        with tarfile.open(fileobj=payload, mode='w', format=tarfile.PAX_FORMAT) as archive:
            info = tarfile.TarInfo('old-name')
            info.size = 3
            info.pax_headers['OneDev.singleFile'] = 'true'
            archive.addfile(info, io.BytesIO(b'old'))
        uploads = []
        def request(endpoint, params=None, body=None, method=None):
            if body is not None:
                uploads.append(body.read())
                return io.BytesIO()
            if method == 'PUT':
                return io.BytesIO()
            return io.BytesIO(struct.pack('>i', 1) + payload.getvalue())
        with patch.object(runner, 'request', request):
            runner.download_user_data()
            self.assertEqual('old', user_file.read_text())
            user_file.write_text('new')
            # Make the change newer than provisioning regardless of filesystem timestamp precision.
            changed_at = runner.provisioned + 2
            os.utime(user_file, (changed_at, changed_at))
            runner.upload_user_data()
        self.assertEqual(1, len(uploads))
        with tarfile.open(fileobj=io.BytesIO(uploads[0])) as archive:
            info = archive.next()
            self.assertEqual('true', info.pax_headers['OneDev.singleFile'])
            self.assertEqual(b'new', archive.extractfile(info).read())

if __name__ == '__main__':
    unittest.main()
