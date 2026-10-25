"""Opt-in integration tests for a disposable sandbox with sudo and Docker.

Run as the template's non-root user with ONEDEV_SANDBOX_PERMISSION_TESTS=1.
The sandbox must have alpine:latest cached in its private Docker daemon.
"""
import json
import os
from pathlib import Path
import pty
import select
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import patch

from sandbox_test_support import AGENT, JobWorker, archive, command, load_runtime


@unittest.skipUnless(os.environ.get('ONEDEV_SANDBOX_PERMISSION_TESTS') == '1',
                     'requires an isolated sandbox with sudo and Docker')
class SandboxPermissionTest(unittest.TestCase):
    def setUp(self):
        self.assertNotEqual(os.geteuid(), 0, 'run this suite as the template user')
        subprocess.run(['sudo', '-n', 'true'], check=True)
        self.root = Path(tempfile.mkdtemp(prefix='onedev-permissions-', dir='/tmp'))
        self.uid, self.gid = os.geteuid(), os.getegid()
        for name in ('sandbox.py', 'sandbox_files.py', 'sandbox_privileges.py'):
            shutil.copy(AGENT / name, self.root)
        shutil.copy(AGENT / 'job/sandbox-job.py', self.root)
        module = load_runtime('permission_runner', self.root / 'sandbox-job.py')
        self.worker = ThreadingHTTPServer(('127.0.0.1', 0), JobWorker)
        self.worker.requests, self.worker.steps, self.worker.uploads = [], [], []
        self.worker.cache = b'\x00' + archive({'cached': b'original'})
        self.thread = threading.Thread(target=self.worker.serve_forever, daemon=True)
        self.thread.start()
        self.config = dict(token='job-secret', serverUrl='http://127.0.0.1:%d' % self.worker.server_port,
                           alwaysPullContainerImage=False, attributes={'os': 'template'})
        self.job = module.JobRunner(self.root, self.config)
        self.job.work.chmod(0o700)
        self.absolute_cache = '/root/' + self.root.name

    def tearDown(self):
        self.worker.shutdown()
        self.worker.server_close()
        self.thread.join()
        subprocess.run(['sudo', '-n', 'rm', '-rf', '--', str(self.root), self.absolute_cache], check=True)

    def container(self, text, run_as='1234:2345', position='permissions'):
        return self.job.command(command(text, image='alpine:latest', runAs=run_as), position)

    def cache(self, path='cache'):
        return dict(key='permission-cache', entries=[dict(path=path)], strategy='UPLOAD_IF_CHANGED')

    def assert_owner(self, path):
        info = path.stat()
        self.assertEqual((info.st_uid, info.st_gid), (self.uid, self.gid))

    def test_job_without_containers_runs_without_sudo_or_ownership_passes(self):
        self.config['actions'] = [
            dict(self.cache('new/parent/cache'), type='cache', condition='SUCCESSFUL', optional=False),
            command('test "$(cat new/parent/cache/cached)" = original; '
                    'cp dependency.txt first; cp first new/parent/cache/cached'),
            dict(type='server', condition='SUCCESSFUL', optional=False, path=None,
                 placeholders=[], includes=['first'], excludes=[]),
            command('test "$(cat "$ONEDEV_WORKDIR/../build-version")" = 2.0; cp first second')]
        with patch.object(self.job.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')), \
                patch.object(self.job.privileges, 'change_owner', side_effect=AssertionError('unexpected ownership pass')):
            self.assertTrue(self.job.run())
        for name in ('dependency.txt', 'first', 'second', 'new', 'new/parent', 'new/parent/cache', 'new/parent/cache/cached'):
            self.assert_owner(self.job.work / name)
        self.assert_owner(self.root / 'build-version')
        self.assertEqual(self.worker.steps[0][2], {'first': b'dependency'})
        self.assertEqual(self.worker.uploads, [{'cached': b'dependency'}])

    def test_cache_and_server_operations_leave_existing_ownership_unchanged(self):
        unrelated = self.job.work / 'unrelated'
        unrelated.write_text('untouched')
        subprocess.run(['sudo', '-n', 'chown', '1234:2345', str(unrelated)], check=True)
        self.job.setup_cache(self.cache('new/parent/cache'))
        for path in ('new', 'new/parent', 'new/parent/cache', 'new/parent/cache/cached'):
            self.assert_owner(self.job.work / path)
        # New outputs belong to the template user, while readable inputs retain their owners.
        self.assertTrue(self.job.server_step(dict(placeholders=[], path=None, includes=['unrelated'], excludes=[]), '0'))
        self.assert_owner(self.root / 'build-version')
        self.assertEqual(unrelated.stat().st_uid, 1234)
        cached = self.job.work / 'new/parent/cache/cached'
        cached.write_text('modified')
        subprocess.run(['sudo', '-n', 'chown', '1234:2345', str(cached)], check=True)
        self.job.upload_caches()
        self.assertEqual(self.worker.uploads, [{'cached': b'modified'}])
        self.assertEqual(cached.stat().st_uid, 1234)
        self.assertEqual(unrelated.stat().st_uid, 1234)

    def test_partial_cache_restore_failure_leaves_files_owned_by_default_user(self):
        self.worker.cache = b'\x00' + archive({'restored': b'partial', '../invalid': b'invalid'})
        with self.assertRaisesRegex(ValueError, 'Path must be relative'):
            self.job.setup_cache(self.cache('new/parent/cache'))
        for path in ('new', 'new/parent', 'new/parent/cache', 'new/parent/cache/restored'):
            self.assert_owner(self.job.work / path)

    def test_shell_container_failure_and_recovery_with_private_directories(self):
        self.assertTrue(self.job.command(command('id -u > shell-uid'), 'shell'))
        self.assertEqual((self.job.work / 'shell-uid').read_text().strip(), str(self.uid))
        (self.root / 'attributes/os').write_text('updated')
        self.assertTrue(self.container('umask 077; echo root > root-output; chmod 700 "$ONEDEV_WORKDIR"', '0:0'))
        self.assertEqual((self.root / 'attributes/os').read_text(), 'updated')
        self.assert_owner(self.job.work / 'root-output')
        self.assertTrue(self.container('test "$(id -u)" = 1234; cat root-output > user-output'))
        self.assert_owner(self.job.work / 'user-output')
        self.assertFalse(self.container('echo failed > failed-output; exit 7'))
        self.assert_owner(self.root)
        self.assert_owner(self.job.work)
        self.assert_owner(self.job.work / 'failed-output')
        self.assertFalse((self.root / 'shell.json').exists())
        self.assertTrue(self.job.command(command('cat root-output user-output failed-output > result'), 'after'))
        self.assertEqual((self.job.work / 'result').read_text(), 'root\nroot\nfailed\n')

    def test_cache_handoffs_do_not_count_as_content_changes(self):
        self.job.setup_cache(self.cache())
        self.assertTrue(self.container('true'))
        self.job.upload_caches()
        self.assertEqual(self.worker.uploads, [])
        self.assertTrue(self.container('echo updated > cache/cached'))
        self.job.upload_caches()
        self.assertEqual(self.worker.uploads, [{'cached': b'updated\n'}])

    def privileged_stat(self, path):
        return subprocess.check_output(['sudo', '-n', 'stat', '-c', '%u:%g %a', str(path)], text=True).strip()

    def test_absolute_cache_restore_preserves_private_parent_and_returns_cache_ownership(self):
        subprocess.run(['sudo', '-n', 'mkdir', '-p', self.absolute_cache], check=True)
        subprocess.run(['sudo', '-n', 'chmod', '700', self.absolute_cache], check=True)
        parent_stat = self.privileged_stat(self.absolute_cache)
        path = self.absolute_cache + '/missing/cache'
        config = self.cache(path)
        config['entries'].append(dict(path='relative-cache'))
        self.job.setup_cache(config)
        self.assertEqual(self.job.caches[0].exact, {path, 'relative-cache'})
        self.assertEqual(self.privileged_stat(self.absolute_cache), parent_stat)
        self.assertEqual(self.privileged_stat(path).split()[0], f'{self.uid}:{self.gid}')
        self.assertEqual(self.privileged_stat(path + '/cached').split()[0], f'{self.uid}:{self.gid}')
        self.assert_owner(self.job.work / 'relative-cache/cached')
        # Returning cache ownership does not grant traversal of a private parent.
        with self.assertRaises(PermissionError):
            Path(path, 'cached').read_text()
        self.worker.cache = b'\x02'
        self.job.setup_cache(self.cache(self.absolute_cache + '/empty'))
        self.assertEqual(self.privileged_stat(self.absolute_cache + '/empty').split()[0], f'{self.uid}:{self.gid}')

    def test_privileged_partial_restore_returns_files_without_changing_parent(self):
        self.worker.cache = b'\x00' + archive({'restored': b'partial', '../invalid': b'invalid'})
        subprocess.run(['sudo', '-n', 'mkdir', '-p', self.absolute_cache], check=True)
        subprocess.run(['sudo', '-n', 'chmod', '700', self.absolute_cache], check=True)
        parent_stat = self.privileged_stat(self.absolute_cache)
        path = self.absolute_cache + '/cache'
        with self.assertRaisesRegex(RuntimeError, 'Path must be relative'):
            self.job.setup_cache(self.cache(path))
        self.assertEqual(self.privileged_stat(path + '/restored').split()[0], f'{self.uid}:{self.gid}')
        self.assertEqual(self.privileged_stat(self.absolute_cache), parent_stat)
        self.assertEqual(self.job.caches, [])

    def test_private_absolute_cache_supports_container_upload_and_restore(self):
        path = self.absolute_cache + '/cache'
        self.job.setup_cache(self.cache(path))
        self.assertTrue(self.container(f'echo updated > {path}/cached; chmod 600 {path}/cached', '0:0'))
        self.job.upload_caches()
        self.assertEqual(self.worker.uploads, [{'cached': b'updated\n'}])
        self.worker.cache = b'\x00' + archive(self.worker.uploads[0])
        self.job.setup_cache(self.cache(path))
        self.assertEqual(self.privileged_stat(path + '/cached'), f'{self.uid}:{self.gid} 644')
        self.assertEqual(subprocess.check_output(['sudo', '-n', 'cat', path + '/cached']), b'updated\n')

    def test_privileged_restore_rejects_symlink_parent(self):
        subprocess.run(['sudo', '-n', 'mkdir', '-p', self.absolute_cache], check=True)
        subprocess.run(['sudo', '-n', 'chmod', '700', self.absolute_cache], check=True)
        target = self.root / 'unrelated'
        target.mkdir()
        subprocess.run(['sudo', '-n', 'ln', '-s', str(target), self.absolute_cache + '/link'], check=True)
        with self.assertRaisesRegex(RuntimeError, 'Symbolic link is not allowed'):
            self.job.setup_cache(self.cache(self.absolute_cache + '/link/cache'))
        self.assertEqual(list(target.iterdir()), [])

    def test_absolute_cache_under_owned_parent_needs_no_sudo(self):
        path = self.root / 'missing/parent/cache'
        with patch.object(self.job.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')):
            self.job.setup_cache(self.cache(str(path)))
        self.assert_owner(path / 'cached')

    def test_absolute_cache_under_foreign_parent_supports_shell_and_container_handoffs(self):
        parent = Path('/home') / self.root.name
        self.addCleanup(subprocess.run, ['sudo', '-n', 'rm', '-rf', '--', str(parent)], check=True)
        subprocess.run(['sudo', '-n', 'mkdir', str(parent)], check=True)
        subprocess.run(['sudo', '-n', 'chmod', '755', str(parent)], check=True)
        parent_stat = self.privileged_stat(parent)
        cache = parent / 'missing/cache'
        self.job.setup_cache(self.cache(str(cache)))
        self.assertEqual((cache / 'cached').read_text(), 'original')
        for run_as in ('0:0', '1234:2345'):
            self.assertTrue(self.container(f'echo updated > {cache}/cached; chmod 600 {cache}/cached', run_as))
            self.assert_owner(cache / 'cached')
            self.assertEqual((cache / 'cached').read_text(), 'updated\n')
        self.job.upload_caches()
        self.assertEqual(self.worker.uploads, [{'cached': b'updated\n'}])
        self.assertEqual(self.privileged_stat(parent), parent_stat)

    def test_container_outputs_are_read_as_default_user_after_ownership_restore(self):
        cache = self.root / 'absolute-cache'
        self.job.setup_cache(self.cache(str(cache)))
        report = dict(placeholders=[], path=None, includes=['report'], excludes=[])
        for run_as in ('0:0', '1234:2345'):
            with self.subTest(run_as=run_as):
                self.assertTrue(self.container('echo updated > %s/cached; echo report > report; '
                                               'chmod 600 report %s/cached' % (cache, cache), run_as))
                self.assert_owner(cache / 'cached')
                self.assert_owner(self.job.work / 'report')
                with patch.object(self.job.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')), \
                        patch.object(self.job.privileges, 'change_owner', side_effect=AssertionError('unexpected ownership pass')):
                    self.assertTrue(self.job.server_step(report, '0'))
                    self.assertEqual(self.worker.steps[-1][2], {'report': b'report\n'})
                    self.job.upload_caches()
                    self.assertEqual(self.worker.uploads[-1], {'cached': b'updated\n'})
        (self.job.work / 'report').chmod(0)
        (cache / 'cached').chmod(0)
        with patch.object(self.job.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')):
            with self.assertRaises(PermissionError):
                self.job.server_step(report, '0')
            with self.assertRaises(PermissionError):
                self.job.upload_caches()

    def test_buildx_output_is_created_as_default_user(self):
        context = self.job.work / 'image'
        context.mkdir()
        (context / 'Dockerfile').write_text('FROM scratch\nCOPY input /input\n')
        (context / 'input').write_text('payload')
        with patch.object(self.job.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')), \
                patch.object(self.job.privileges, 'change_owner', side_effect=AssertionError('unexpected ownership pass')):
            self.assertTrue(self.job.build_image(dict(buildPath='image', destPath='oci')))
        self.assert_owner(self.job.work / 'oci/index.json')
        self.assertTrue((self.root / 'buildx').exists())
        self.assert_owner(self.root / 'buildx')

    def test_unreadable_directories_fail_unless_explicitly_excluded(self):
        cache = self.cache()
        cache['strategy'] = 'UPLOAD_IF_NOT_EXACT_MATCH'
        self.worker.cache = b'\x01' + archive({'cached': b'original'})
        self.job.setup_cache(cache)
        self.assertTrue(self.container('mkdir private cache/private; echo hidden > private/file; '
                                       'echo hidden > cache/private/file; echo visible > visible; '
                                       'chmod 000 private cache/private', '0:0'))
        report = dict(placeholders=[], path='private', includes=['**'], excludes=[])
        with patch.object(self.job.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')):
            with self.assertRaises(PermissionError):
                self.job.server_step(report, '0')
            with self.assertRaises(PermissionError):
                self.job.upload_caches()
            report.update(path=None, excludes=['private/**', 'cache/**'])
            self.assertTrue(self.job.server_step(report, '0'))
            self.assertEqual(self.worker.steps[-1][2], {'visible': b'visible\n'})
            self.job.caches[0].config['entries'][0]['excludes'] = 'private/**'
            self.job.upload_caches()
            self.assertEqual(self.worker.uploads, [{'cached': b'original'}])

    def test_imagetools_metadata_is_created_as_default_user(self):
        binary = self.root / 'bin'
        binary.mkdir()
        client = binary / 'docker'
        # Exercise client output as the template user without contacting a registry.
        client.write_text('#!/usr/bin/python3\nimport os, sys\nfrom pathlib import Path\n'
                          f'assert (os.geteuid(), os.getegid()) == {(self.uid, self.gid)!r}\n'
                          'os.umask(0o077)\n'
                          'Path(sys.argv[sys.argv.index("--metadata-file") + 1]).write_text("metadata")\n')
        client.chmod(0o755)
        with patch.dict(os.environ, PATH=str(binary) + ':' + os.environ['PATH']), \
                patch.object(self.job.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')), \
                patch.object(self.job.privileges, 'change_owner', side_effect=AssertionError('unexpected ownership pass')):
            self.assertTrue(self.job.imagetools(dict(arguments='create --metadata-file metadata.json example/image')))
        self.assert_owner(self.job.work / 'metadata.json')
        self.assertEqual((self.job.work / 'metadata.json').read_text(), 'metadata')

    def test_helper_preserves_job_docker_environment(self):
        binary = self.root / 'bin'
        binary.mkdir()
        client = binary / 'docker'
        captured = self.job.work / 'docker-env.json'
        client.write_text('#!/usr/bin/python3\nimport os, json, sys\n'
                          'with open(%r, "w") as output:\n'
                          '    json.dump(dict(uid=os.geteuid(), home=os.environ["HOME"], '
                          'config=os.environ["DOCKER_CONFIG"], context=os.environ["DOCKER_CONTEXT"]), output)\n'
                          'os.execv("/usr/bin/docker", ["docker", *sys.argv[1:]])\n' % str(captured))
        client.chmod(0o755)
        config = self.root / 'docker-config'
        config.mkdir()
        (config / 'config.json').write_text('{"auths": {}}')
        with patch.dict(os.environ, PATH=str(binary) + ':' + os.environ['PATH'],
                        DOCKER_CONFIG=str(config), DOCKER_CONTEXT='default'):
            self.assertTrue(self.container('true'))
        self.assertEqual(json.loads(captured.read_text()),
                         dict(uid=0, home=os.environ['HOME'], config=str(config), context='default'))

    def test_root_template_user_needs_no_sudo_dispatch(self):
        self.config['actions'] = [command('test "$(id -u)" = 0; id -u > root-uid')]
        (self.root / 'job.json').write_text(json.dumps(self.config))
        subprocess.run(['sudo', '-n', 'python3', str(self.root / 'sandbox-job.py'), str(self.root)], check=True)
        self.assertEqual((self.job.work / 'root-uid').read_text().strip(), '0')

    def test_pause_resume_and_terminal_use_container_user_during_handoff(self):
        outcome = []
        def run():
            outcome.append(self.container('sh "$ONEDEV_WORKDIR/../pause.sh"; id -u > resumed-uid', position='paused'))
        thread = threading.Thread(target=run)
        thread.start()
        child, terminal = None, None
        try:
            deadline = time.monotonic() + 20
            while subprocess.run(['sudo', '-n', 'test', '-f', str(self.root / 'shell.json')]).returncode:
                self.assertLess(time.monotonic(), deadline, 'container did not start')
                time.sleep(0.1)
            # Wait for the actual container, not just its published terminal target.
            while subprocess.run(['sudo', '-n', 'docker', 'inspect', 'onedev-step-paused'],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
                self.assertLess(time.monotonic(), deadline, 'container did not start')
                time.sleep(0.1)
            terminal, slave = pty.openpty()
            child = subprocess.Popen(['sudo', '-n', 'python3', str(self.root / 'sandbox-job.py'), str(self.root),
                                      '--shell', str(self.uid), str(self.gid), ','.join(map(str, os.getgroups()))],
                                     stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
            os.close(slave)
            os.write(terminal, b'echo TERMINAL-UID-$(id -u); exit\n')
            output = b''
            while b'TERMINAL-UID-1234' not in output and time.monotonic() < deadline:
                if select.select([terminal], [], [], 0.2)[0]:
                    try:
                        output += os.read(terminal, 4096)
                    except OSError:
                        break
            self.assertIn(b'TERMINAL-UID-1234', output)
            child.wait(timeout=5)
        finally:
            if terminal is not None:
                os.close(terminal)
            if child is not None and child.poll() is None:
                child.terminate()
                child.wait(timeout=5)
            subprocess.run(['sudo', '-n', 'touch', str(self.root / 'continue')], check=True)
            thread.join(20)
        self.assertFalse(thread.is_alive())
        self.assertEqual(outcome, [True])
        self.assertEqual((self.job.work / 'resumed-uid').read_text().strip(), '1234')

    def test_service_registration_is_privileged(self):
        self.config['services'] = [dict(name='permission-service', image='alpine:latest', runAs='1234:2345',
                                        arguments='sleep 120', envs={}, readinessCheckCommand='true')]
        try:
            self.job.start_services()
            self.assertEqual(self.job.network, 'onedev')
            self.assertTrue(socket.gethostbyname('permission-service'))
        finally:
            subprocess.run(['sudo', '-n', 'docker', 'rm', '-f', 'onedev-service-permission-service'], check=True)
            subprocess.run(['sudo', '-n', 'docker', 'network', 'rm', 'onedev'], check=True)


if __name__ == '__main__':
    unittest.main()
