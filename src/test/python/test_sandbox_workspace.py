import base64
import hashlib
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import tarfile
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from sandbox_test_support import AGENT, load_runtime
from sandbox import SandboxRuntime

workspace = load_runtime('sandbox_workspace', AGENT / 'workspace/sandbox-workspace.py')
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

    def test_setup_failure_does_not_upload_data_or_cache(self):
        self.data['script'].update(setupCommands='exit 7', teardownCommands='exit 8')
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'setup_repository'), patch.object(runner, 'upload_user_data') as users, \
                patch.object(runner, 'upload_caches') as caches:
            with self.assertRaisesRegex(RuntimeError, 'teardown exited with code 8'):
                runner.run()
            users.assert_not_called()
            caches.assert_not_called()
        self.assertFalse((self.root / '.workspace-ready').exists())

    def test_active_deletion_uploads_even_if_teardown_fails(self):
        self.data['script']['teardownCommands'] = 'exit 8'
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'setup_repository'), patch.object(runner, 'upload_user_data') as users, \
                patch.object(runner, 'upload_caches') as caches:
            thread, failures = self.start(runner)
            self.await_file('.workspace-ready', failures)
            (self.root / '.upload-data').touch()
            (self.root / '.shutdown').touch()
            thread.join(15)
            self.assertFalse(thread.is_alive())
            self.assertEqual(1, len(failures))
            self.assertIn('teardown exited with code 8', str(failures[0]))
            users.assert_called_once()
            caches.assert_called_once()

    def test_stop_and_reprovision_preserve_data_until_active_deletion(self):
        self.repository()
        user_file = self.base / 'user-data-file'
        user_file.write_text('template default')
        self.data['userDatas'] = [dict(key='personal', entries=[dict(path=str(user_file))])]
        self.data['caches'] = [dict(key='cache', entries=[dict(path='cache')], strategy='UPLOAD_IF_CHANGED')]
        self.data['script']['teardownCommands'] = 'echo teardown >> cache/value'
        calls = []
        uploads = {}

        def request(_runner, endpoint, params=None, body=None, method=None, **kwargs):
            method = method or ('POST' if body is not None else 'GET')
            calls.append((endpoint, method))
            if body is not None:
                uploads[endpoint] = body.read()
            if method == 'GET':
                return io.BytesIO(b'\x02' if endpoint == 'workspace-cache' else struct.pack('>i', 0))
            return io.BytesIO()

        with patch.object(SandboxRuntime, 'request', request):
            runner = WorkspaceRunner(self.root, self.data)
            thread, failures = self.start(runner)
            self.await_file('.workspace-ready', failures)
            cache_file = runner.work / 'cache/value'
            cache_file.write_text('local cache\n')
            user_file.write_text('local user data')
            changed_at = runner.user_data_provision_dates[('personal', str(user_file))] + 0.002
            os.utime(user_file, (changed_at, changed_at))
            while time.time() <= changed_at:
                time.sleep(0.001)
            (self.root / '.shutdown').touch()
            thread.join(15)
            self.assertFalse(thread.is_alive())
            self.assertFalse(failures, failures)
            self.assertEqual([('workspace-cache', 'GET'), ('workspace-user-data', 'GET')], calls)

            (self.root / '.shutdown').unlink()
            resumed = WorkspaceRunner(self.root, self.data)
            thread, failures = self.start(resumed)
            self.await_file('.workspace-ready', failures)
            self.assertEqual(2, len(calls), 'Reprovisioning must not restore existing data')
            self.assertEqual('local cache\nteardown\n', cache_file.read_text())
            self.assertEqual('local user data', user_file.read_text())
            (self.root / '.upload-data').touch()
            (self.root / '.shutdown').touch()
            thread.join(15)
            self.assertFalse(thread.is_alive())
            self.assertFalse(failures, failures)

        with tarfile.open(fileobj=io.BytesIO(uploads['workspace-cache'])) as archive:
            self.assertEqual(b'local cache\nteardown\nteardown\n', archive.extractfile('value').read())
        with tarfile.open(fileobj=io.BytesIO(uploads['workspace-user-data'])) as archive:
            self.assertEqual(b'local user data', archive.extractfile(archive.next()).read())

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

    def cache_config(self, path='cache', key='key', strategy='UPLOAD_IF_NOT_EXACT_MATCH', **kwargs):
        return dict(key=key, entries=[dict(path=path)], strategy=strategy, **kwargs)

    def test_cache_state_is_keyed_by_path_and_survives_reordering(self):
        paths = ['relative', str(self.base / 'absolute')]
        runner = WorkspaceRunner(self.root, self.data)
        for path in paths:
            directory = runner.data_path(path)
            directory.mkdir()
            (directory / 'value').write_text(path)
        config = dict(key='key', entries=[dict(path=path) for path in paths],
                      strategy='UPLOAD_IF_NOT_EXACT_MATCH')
        with patch.object(runner, 'restore_cache', return_value=0) as restore:
            runner.setup_cache(config, persistent=True)
            self.assertEqual(2, restore.call_count)
        for path in paths:
            name = hashlib.sha256(path.encode()).hexdigest()
            state = json.loads((self.root / 'cache' / name / 'state').read_text())
            self.assertEqual({'key', 'checksum', 'exact', 'provisioned'}, set(state))
        resumed = WorkspaceRunner(self.root, self.data)
        reordered = dict(config, entries=list(reversed(config['entries'])))
        with patch.object(resumed, 'restore_cache') as restore:
            resumed.setup_cache(reordered, persistent=True)
            restore.assert_not_called()
        self.assertEqual(set(paths), resumed.caches[0].exact)
        for path in paths:
            self.assertEqual(path, (resumed.data_path(path) / 'value').read_text())

    def test_cache_state_rejects_symlinked_parent(self):
        (self.root / 'cache').symlink_to(self.base, target_is_directory=True)
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'restore_cache') as restore:
            with self.assertRaisesRegex(ValueError, 'Symbolic link is not allowed'):
                runner.setup_cache(self.cache_config(), persistent=True)
            restore.assert_not_called()

    def test_incomplete_cache_restore_is_retried(self):
        config = self.cache_config()
        runner = WorkspaceRunner(self.root, self.data)
        def interrupted_restore(config, path):
            directory = runner.data_path(path)
            directory.mkdir()
            (directory / 'partial').write_text('incomplete')
            raise IOError('Interrupted restore')
        with patch.object(runner, 'restore_cache', side_effect=interrupted_restore):
            with self.assertRaisesRegex(IOError, 'Interrupted restore'):
                runner.setup_cache(config, persistent=True)
        resumed = WorkspaceRunner(self.root, self.data)
        with patch.object(resumed, 'restore_cache', return_value=0) as restore:
            resumed.setup_cache(config, persistent=True)
            restore.assert_called_once()

    def test_reprovision_preserves_exact_match_and_original_change_baseline(self):
        for strategy in ('UPLOAD_IF_NOT_EXACT_MATCH', 'UPLOAD_IF_CHANGED'):
            with self.subTest(strategy=strategy):
                config = self.cache_config(path=strategy, strategy=strategy)
                runner = WorkspaceRunner(self.root, self.data)
                directory = runner.data_path(strategy)
                directory.mkdir()
                data = directory / 'value'
                data.write_text('stored')
                os.utime(data, (90, 90))
                os.utime(directory, (90, 90))
                with patch.object(runner, 'restore_cache', return_value=0) as restore, \
                        patch('sandbox.time.time', return_value=100):
                    runner.setup_cache(config, persistent=True)
                    restore.assert_called_once()  # Existing template/repository paths still restore once.
                resumed = WorkspaceRunner(self.root, self.data)
                with patch.object(resumed, 'restore_cache') as restore, patch('sandbox.time.time', return_value=300):
                    resumed.setup_cache(config, persistent=True)
                    restore.assert_not_called()
                with patch.object(resumed, 'request', side_effect=AssertionError('Unexpected upload')):
                    resumed.upload_caches()
                if strategy == 'UPLOAD_IF_CHANGED':
                    data.write_text('edited in an earlier activation')
                    os.utime(data, (200, 200))
                    self.assertTrue(resumed.caches[0].has_changes(config['entries'][0], directory))

    def test_changed_key_restores_and_failed_replacement_invalidates_old_state(self):
        directory = self.root / 'work/cache'
        directory.mkdir(parents=True)
        first = WorkspaceRunner(self.root, self.data)
        with patch.object(first, 'restore_cache', return_value=0):
            first.setup_cache(self.cache_config(), persistent=True)
        changed = WorkspaceRunner(self.root, self.data)
        with patch.object(changed, 'restore_cache', side_effect=IOError('Interrupted replacement')) as restore:
            with self.assertRaisesRegex(IOError, 'Interrupted replacement'):
                changed.setup_cache(self.cache_config(key='changed-key'), persistent=True)
            restore.assert_called_once()
        reverted = WorkspaceRunner(self.root, self.data)
        with patch.object(reverted, 'restore_cache', return_value=0) as restore:
            reverted.setup_cache(self.cache_config(), persistent=True)
            restore.assert_called_once()

    def test_changed_checksum_keeps_local_cache_without_claiming_exact_match(self):
        runner = WorkspaceRunner(self.root, self.data)
        directory = runner.work / 'cache'
        directory.mkdir()
        data = directory / 'value'
        data.write_text('local edits')
        lock = runner.work / 'lock'
        lock.write_text('initial')
        config = self.cache_config(checksumIncludes=['lock'], checksumExcludes=[])
        with patch.object(runner, 'restore_cache', return_value=0):
            runner.setup_cache(config, persistent=True)
        lock.write_text('changed')
        resumed = WorkspaceRunner(self.root, self.data)
        with patch.object(resumed, 'restore_cache') as restore:
            resumed.setup_cache(config, persistent=True)
            restore.assert_not_called()
        self.assertEqual('local edits', data.read_text())
        self.assertFalse(resumed.caches[0].exact)
        self.assertNotEqual(runner.caches[0].config['checksum'], resumed.caches[0].config['checksum'])

    def test_missing_cache_is_restored_even_with_saved_state(self):
        runner = WorkspaceRunner(self.root, self.data)
        directory = runner.work / 'cache'
        directory.mkdir()
        with patch.object(runner, 'restore_cache', return_value=0):
            runner.setup_cache(self.cache_config(), persistent=True)
        directory.rmdir()
        resumed = WorkspaceRunner(self.root, self.data)
        with patch.object(resumed, 'restore_cache', return_value=0) as restore:
            resumed.setup_cache(self.cache_config(), persistent=True)
            restore.assert_called_once()

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
            changed_at = runner.user_data_provision_dates[('personal', str(user_file))] + 2
            os.utime(user_file, (changed_at, changed_at))
            runner.upload_user_data()
        self.assertEqual(1, len(uploads))
        with tarfile.open(fileobj=io.BytesIO(uploads[0])) as archive:
            info = archive.next()
            self.assertEqual('true', info.pax_headers['OneDev.singleFile'])
            self.assertEqual(b'new', archive.extractfile(info).read())

    def test_unchanged_user_data_is_not_uploaded_after_reprovisioning(self):
        user_file = self.base / 'user-file'
        user_file.write_text('stored file')
        user_dir = self.base / 'user-directory'
        user_dir.mkdir()
        (user_dir / 'file').write_text('stored directory')
        self.data['userDatas'] = [dict(key='personal', entries=[dict(path=str(path)) for path in (user_file, user_dir)])]
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'request', side_effect=lambda *args: io.BytesIO(struct.pack('>i', 0))):
            runner.download_user_data()
        baselines = runner.user_data_provision_dates.copy()
        resumed = WorkspaceRunner(self.root, self.data)
        with patch.object(resumed, 'request') as request:
            resumed.download_user_data()
            resumed.upload_user_data()
            request.assert_not_called()
        self.assertEqual(baselines, resumed.user_data_provision_dates)

    def test_only_changed_user_data_uploads_with_reused_and_fresh_entries(self):
        changed = self.base / 'changed'
        unchanged = self.base / 'unchanged'
        fresh = self.base / 'fresh'
        for path in (changed, unchanged, fresh):
            path.write_text('stored')
        self.data['userDatas'] = [dict(key='personal', entries=[dict(path=str(path)) for path in (changed, unchanged)])]
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'request', side_effect=lambda *args: io.BytesIO(struct.pack('>i', 0))):
            runner.download_user_data()
        changed.write_text('earlier edits')
        changed_at = runner.user_data_provision_dates[('personal', str(changed))] + 0.002
        os.utime(changed, (changed_at, changed_at))
        while time.time() <= changed_at:
            time.sleep(0.001)
        self.data['userDatas'][0]['entries'] = [dict(path=str(path)) for path in (fresh, unchanged, changed)]
        resumed = WorkspaceRunner(self.root, self.data)
        restores = []
        uploads = []

        def request(endpoint, params=None, body=None, method=None):
            if body is not None:
                uploads.append(params['path'])
                return io.BytesIO()
            if method == 'PUT':
                return io.BytesIO()
            restores.append(params['path'])
            return io.BytesIO(struct.pack('>i', 0))

        with patch.object(resumed, 'request', request):
            resumed.download_user_data()
            resumed.upload_user_data()
        self.assertEqual([str(fresh)], restores)
        self.assertEqual([str(changed)], uploads)
        self.assertEqual(runner.user_data_provision_dates[('personal', str(changed))],
                         resumed.user_data_provision_dates[('personal', str(changed))])

    def test_restoring_missing_user_data_resets_its_baseline(self):
        path = self.base / 'user-file'
        path.write_text('stored')
        self.data['userDatas'] = [dict(key='personal', entries=[dict(path=str(path))])]
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'request', side_effect=lambda *args: io.BytesIO(struct.pack('>i', 0))):
            runner.download_user_data()
        marker = self.root / 'user-data' / hashlib.sha256(('personal\0' + str(path)).encode()).hexdigest() / 'state'
        os.utime(marker, (1, 1))
        path.unlink()
        resumed = WorkspaceRunner(self.root, self.data)
        with patch.object(resumed, 'request', side_effect=lambda *args: io.BytesIO(struct.pack('>i', 0))) as request:
            resumed.download_user_data()
            resumed.upload_user_data()
            self.assertEqual(1, request.call_count)
        self.assertGreater(resumed.user_data_provision_dates[('personal', str(path))], 1)

    def test_user_data_baseline_symlink_is_rejected(self):
        path = self.base / 'user-file'
        path.write_text('stored')
        self.data['userDatas'] = [dict(key='personal', entries=[dict(path=str(path))])]
        marker = self.root / 'user-data' / hashlib.sha256(('personal\0' + str(path)).encode()).hexdigest() / 'state'
        marker.parent.mkdir(parents=True)
        marker.symlink_to(path)
        runner = WorkspaceRunner(self.root, self.data)
        with patch.object(runner, 'request') as request:
            with self.assertRaisesRegex(ValueError, 'Symbolic link'):
                runner.download_user_data()
            request.assert_not_called()
        self.assertFalse(runner.user_data_ready)

if __name__ == '__main__':
    unittest.main()
