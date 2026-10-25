import base64
import contextlib
import csv
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest.mock import patch

from sandbox_test_support import AGENT, JobWorker, archive, command, load_runtime
import sandbox
import sandbox_files
from sandbox_privileges import JobState

runner = load_runtime('sandbox_job', AGENT / 'job/sandbox-job.py')


class SandboxJobTest(unittest.TestCase):
    def setUp(self):
        # Exercise operation bodies here; the Linux permission suite covers sudo dispatch.
        self.root_user = patch.object(runner.os, 'geteuid', return_value=0)
        self.root_user.start()
        self.addCleanup(self.root_user.stop)
        self.previous_directory = os.getcwd()
        self.temp = tempfile.TemporaryDirectory(prefix='onedev sandbox ')
        self.root = Path(self.temp.name)
        self.worker = ThreadingHTTPServer(('127.0.0.1', 0), JobWorker)
        self.worker.requests, self.worker.steps, self.worker.uploads = [], [], []
        self.worker.cache = b'\x02'
        self.thread = threading.Thread(target=self.worker.serve_forever, daemon=True)
        self.thread.start()
        self.job = dict(token='job-secret', serverUrl='http://127.0.0.1:%d' % self.worker.server_port,
                        refName='refs/heads/main', commitHash='', attributes={'os': 'linux'})
        self.runner = runner.JobRunner(self.root, self.job)

    def tearDown(self):
        os.chdir(self.previous_directory)
        self.worker.shutdown()
        self.worker.server_close()
        self.thread.join()
        self.temp.cleanup()

    def run_job(self, actions):
        self.job['actions'] = actions
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            successful = self.runner.run()
        return successful, output.getvalue()

    def cache(self, **extra):
        return dict(type='cache', condition='SUCCESSFUL', optional=False, key='cache-key',
                    entries=[{'path': 'cache', 'excludes': 'ignored/**'}], strategy='UPLOAD_IF_NOT_EXACT_MATCH',
                    **extra)

    def test_job_without_containers_needs_no_privileged_helper_or_ownership_passes(self):
        self.worker.cache = b'\x00' + archive({'file': b'cached'})
        cache = self.cache()
        cache['strategy'] = 'UPLOAD_IF_CHANGED'
        with patch.object(runner.os, 'geteuid', return_value=1000), \
                patch.object(self.runner.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')), \
                patch.object(self.runner.privileges, 'change_owner', side_effect=AssertionError('unexpected ownership pass')):
            success, output = self.run_job([
                cache,
                command('test "$(cat cache/file)" = cached; cp dependency.txt first; cp first cache/file'),
                dict(type='server', condition='SUCCESSFUL', optional=False, path=None,
                     placeholders=[], includes=['first'], excludes=[]),
                command('test "$(cat "$ONEDEV_WORKDIR/../build-version")" = 2.0; cp first second')])
        self.assertTrue(success, output)
        self.assertEqual((self.runner.work / 'second').read_text(), 'dependency')
        self.assertEqual(self.worker.steps[0][2], {'first': b'dependency'})
        self.assertEqual(self.worker.uploads, [{'file': b'dependency'}])

    def test_privileged_container_restores_ownership_once_even_after_failure(self):
        cache = sandbox.ProvisionedCache(dict(entries=[dict(path='cache')]))
        cache.exact.add('cache')
        cache.provisioned = 1234.5
        self.runner.caches = [cache]
        self.runner.pulled_images = {'alpine'}
        self.runner.network = 'job-network'
        for run_as in ('0:0', '1001:1001'):
            for outcome in (True, False, RuntimeError('Docker failed')):
                with self.subTest(run_as=run_as, outcome=outcome):
                    state = dict(JobState.capture(self.runner).to_dict(), owner=[1000, 1000])
                    payload = dict(config=self.job, state=state, env=dict(os.environ), operation='container',
                                   args=[dict(image='alpine', runAs=run_as), '0'], kwargs={})
                    result = self.root / 'result.json'
                    with patch.object(runner.sys, 'stdin', io.StringIO(json.dumps(payload))), \
                            patch.object(runner.JobRunner, 'docker', side_effect=lambda *args: outcome) as docker, \
                            patch.object(runner.JobPrivileges, 'change_owner') as owner:
                        if isinstance(outcome, Exception):
                            docker.side_effect = outcome
                        runner.run_operation(self.root, result, runner.JobRunner)
                    expected = [] if run_as == '0:0' else [(run_as, [self.root])]
                    expected.append(('1000:1000', [self.root]))
                    self.assertEqual([call.args for call in owner.call_args_list], expected)
                    response = json.loads(result.read_text())
                    self.assertEqual(response['error'], str(outcome) if isinstance(outcome, Exception) else None)
                    self.assertEqual(response['state'], state)
                    self.assertFalse((self.root / 'shell.json').exists())

    def test_ownership_changes_do_not_scan_caches(self):
        self.worker.cache = b'\x00' + archive({'file': b'cached'})
        config = self.cache()
        config.update(strategy='UPLOAD_IF_CHANGED', entries=[dict(path='first'), dict(path='second')])
        self.runner.setup_cache(config)
        second = self.runner.work / 'second/file'
        second.write_text('changed')
        changed_at = self.runner.caches[0].provisioned + 2
        os.utime(second, (changed_at, changed_at))
        with patch.object(sandbox, 'cache_paths', wraps=sandbox_files.cache_paths) as scanned, \
                patch.object(runner.os, 'chown'):
            self.runner.privileges.change_owner('1000:1000', [self.runner.work / 'first'])
        scanned.assert_not_called()
        self.runner.upload_caches()
        self.assertEqual(self.worker.uploads, [{'file': b'changed'}])

    def test_denied_cache_upload_is_skipped_without_failing_job(self):
        self.worker.deny_cache_upload = True
        success, output = self.run_job([self.cache(), command('echo cached > cache/file')])
        self.assertTrue(success, output)
        self.assertIn('Not authorized to upload cache: cache-key', output)
        self.assertEqual(self.worker.uploads, [])

    def test_executor_test_connects_to_server_without_running_job(self):
        self.job['test'] = True
        self.job.update(runOptions='--env TEST=value', alwaysPullContainerImage=False)
        for image, code in ((None, 0), ('', 0), ('alpine', 0), ('alpine', 9)):
            with self.subTest(image=image, code=code), patch.object(runner.subprocess, 'call', return_value=code) as call, \
                    patch.object(self.runner.privileges, 'change_owner'):
                self.job['testImage'] = image
                self.worker.requests.clear()
                success, output = self.run_job([])
                self.assertEqual(success, code == 0, output)
                self.assertIn("Connecting to server '%s' from sandbox..." % self.job['serverUrl'], output)
                self.assertEqual(self.worker.requests, [('GET', '/~api/worker/test', {'token': ['job-secret']})])
                if image:
                    call.assert_called_once()
                    args = call.call_args.args[0]
                    self.assertEqual(args[:2], ['docker', 'run'])
                    self.assertEqual(args[-4:], ['--', image, '-c', 'echo hello from container'])
                    self.assertEqual(args[args.index('--entrypoint') + 1], 'sh')
                    self.assertNotIn('--cpus', args)
                    self.assertNotIn('--memory', args)
                    self.assertIn('TEST=value', args)
                    self.assertNotIn('--pull=always', args)
                    self.assertIn(str(self.root) + ':' + str(self.root), args)
                    if code:
                        self.assertIn('Docker command exited with code 9', output)
                else:
                    call.assert_not_called()

    def test_executor_test_fails_when_server_rejects_request(self):
        self.job['test'] = True
        self.worker.test_failure = True
        with self.assertRaisesRegex(RuntimeError, r'Server request test failed \(HTTP 503\)'):
            self.run_job([])

    def test_executor_test_fails_when_server_is_unreachable(self):
        self.job['test'] = True
        with patch.object(sandbox.urllib.request, 'urlopen',
                          side_effect=sandbox.urllib.error.URLError(ConnectionRefusedError(111, 'Connection refused'))):
            with self.assertRaisesRegex(RuntimeError, 'Unable to reach OneDev from sandbox: .*Connection refused'):
                self.run_job([])

    def test_nested_conditions_optional_failures_and_skipped_steps(self):
        success, output = self.run_job([
            command('exit 7', optional=True),
            dict(type='composite', condition='SUCCESSFUL', optional=False, actions=[
                command('exit 3'), command('touch must-not-exist'),
                command('touch nested-always', condition='ALWAYS')]),
            dict(type='composite', condition='SUCCESSFUL', optional=False, actions=[command('exit 99')]),
            command('touch never', condition='NEVER'),
            command('touch final-always', condition='ALWAYS')])
        self.assertFalse(success)
        self.assertTrue((self.runner.work / 'nested-always').exists())
        self.assertTrue((self.runner.work / 'final-always').exists())
        self.assertFalse((self.runner.work / 'must-not-exist').exists())
        for position in ('1-1', '2-0', '3'):
            self.assertIn('##onedev-step:SKIP:step-' + position, output)
        self.assertIn('##onedev-phase:finalization', output)

    def test_dependencies_artifacts_outputs_and_placeholders(self):
        success, output = self.run_job([
            command('test "$(cat dependency.txt)" = dependency\nprintf linux > artifact.txt'),
            dict(type='server', condition='SUCCESSFUL', optional=False, path=None,
                 includes=['**/*.txt'], excludes=['dependency.txt'], placeholders=['attributes/os']),
            command('test "<&onedev#build-version#onedev&>" = 2.0')])
        self.assertTrue(success, output)
        self.assertEqual(self.worker.steps, [([1], {'attributes/os': 'linux'}, {'artifact.txt': b'linux'})])
        self.assertIn('Published artifacts', output)
        self.assertTrue(any(path.endswith('/job-workdir') for _, path, _ in self.worker.requests))

    def test_cache_upload_preserves_links_and_excludes(self):
        success, output = self.run_job([self.cache(), command(
            'printf data > cache/file; ln -s file cache/link; mkdir cache/empty cache/ignored; touch cache/ignored/secret')])
        self.assertTrue(success, output)
        self.assertEqual(self.worker.uploads, [{'empty': None, 'file': b'data', 'link': 'file'}])

    def test_cache_round_trip_preserves_external_dangling_and_cyclic_links(self):
        outside = self.runner.work / 'outside'
        outside.mkdir()
        secret = outside / 'secret'
        secret.write_text('must not be archived')
        source = self.runner.work / 'cache-source'
        source.mkdir()
        (source / 'file').write_text('cached')
        links = {'internal': 'file', 'absolute': str(secret), 'relative': '../outside/secret',
                 'directory': '../outside', 'dangling': '../missing', 'cycle': '.'}
        for name, target in links.items():
            (source / name).symlink_to(target)
        body = io.BytesIO()
        sandbox_files.pack(body, source, ['**'], [], cache=True)
        self.worker.cache = b'\x01' + body.getvalue()

        # Restoring again must replace existing links without following them.
        success, output = self.run_job([self.cache(), self.cache()])
        self.assertTrue(success, output)
        expected = dict(links, file=b'cached')
        self.assertEqual(self.worker.uploads, [expected, expected])
        for name, target in links.items():
            self.assertEqual(str((self.runner.work / 'cache' / name).readlink()), target)
        self.assertEqual(secret.read_text(), 'must not be archived')

    def test_cache_change_detection_checks_links_without_following_targets(self):
        outside = self.root / 'outside'
        outside.write_text('original')
        cache = self.runner.work / 'cache'
        cache.mkdir()
        link = cache / 'link'
        link.symlink_to(outside)
        self.worker.cache = b'\x00' + archive({})
        config = self.cache()
        config['strategy'] = 'UPLOAD_IF_CHANGED'
        self.runner.setup_cache(config)

        # Make changes newer than cache setup without depending on filesystem timestamp precision.
        changed_at = self.runner.caches[0].provisioned + 2
        outside.write_text('changed target')
        os.utime(outside, (changed_at, changed_at))
        self.runner.upload_caches()
        self.assertEqual(self.worker.uploads, [])

        link.unlink()
        link.symlink_to('../missing')
        os.utime(link, (changed_at, changed_at), follow_symlinks=False)
        self.runner.upload_caches()
        self.assertEqual(self.worker.uploads, [{'link': '../missing'}])

    def test_cache_restore_rejects_writes_through_symlinked_directories(self):
        outside = self.root / 'outside'
        outside.mkdir()
        secret = outside / 'secret'
        secret.write_text('keep')
        for existing in (False, True):
            with self.subTest(existing=existing):
                target = self.root / ('restore-' + str(existing))
                target.mkdir()
                if existing:
                    (target / 'link').symlink_to(outside)
                body = io.BytesIO()
                with tarfile.open(fileobj=body, mode='w') as tar:
                    if not existing:
                        info = tarfile.TarInfo('link')
                        info.type, info.linkname = tarfile.SYMTYPE, str(outside)
                        tar.addfile(info)
                    info = tarfile.TarInfo('link/secret')
                    info.size = 7
                    tar.addfile(info, io.BytesIO(b'changed'))
                body.seek(0)
                with self.assertRaisesRegex(ValueError, 'Symbolic link is not allowed'):
                    sandbox_files.unpack(body, target, allow_external_symlinks=True)
                self.assertEqual(secret.read_text(), 'keep')

    def test_cache_restore_replaces_leaf_symlinks_without_writing_to_targets(self):
        outside = self.root / 'outside'
        outside.write_text('keep')
        target = self.root / 'restore'
        target.mkdir()
        (target / 'file').symlink_to(outside)
        sandbox_files.unpack(io.BytesIO(archive({'file': b'cached'})), target, allow_external_symlinks=True)
        self.assertFalse((target / 'file').is_symlink())
        self.assertEqual((target / 'file').read_bytes(), b'cached')
        self.assertEqual(outside.read_text(), 'keep')

    def test_cache_restore_still_rejects_path_and_hardlink_escapes(self):
        outside = self.root / 'outside'
        outside.write_text('keep')
        for name, kind, link in (('../outside', tarfile.REGTYPE, ''),
                                 (str(outside), tarfile.REGTYPE, ''),
                                 ('hardlink', tarfile.LNKTYPE, '../outside'),
                                 ('hardlink', tarfile.LNKTYPE, str(outside))):
            with self.subTest(name=name, link=link):
                body = io.BytesIO()
                with tarfile.open(fileobj=body, mode='w') as tar:
                    info = tarfile.TarInfo(name)
                    info.type, info.linkname = kind, link
                    tar.addfile(info)
                body.seek(0)
                with self.assertRaises(ValueError):
                    sandbox_files.unpack(body, self.root / 'restore', allow_external_symlinks=True)
                self.assertEqual(outside.read_text(), 'keep')

    def test_dependency_restore_keeps_external_symlink_restriction(self):
        body = io.BytesIO()
        with tarfile.open(fileobj=body, mode='w') as tar:
            info = tarfile.TarInfo('link')
            info.type, info.linkname = tarfile.SYMTYPE, '../outside'
            tar.addfile(info)
        body.seek(0)
        with self.assertRaisesRegex(ValueError, 'Archive link escapes target'):
            sandbox_files.unpack(body, self.runner.work)

    def test_exact_cache_hit_does_not_upload(self):
        self.worker.cache = b'\x00' + archive({'file': b'cached'})
        success, output = self.run_job([self.cache(), command('test "$(cat cache/file)" = cached')])
        self.assertTrue(success, output)
        self.assertEqual(self.worker.uploads, [])

    def test_relative_and_absolute_cache_restore_and_upload(self):
        absolute_cache = self.root.resolve() / 'absolute cache'
        cache = self.cache()
        cache['entries'].append({'path': str(absolute_cache), 'excludes': 'ignored/**'})
        self.worker.cache = b'\x01' + archive({'file': b'cached'})
        update = command('set -e\n'
                         'test "$(cat cache/file)" = cached\n'
                         'test "$(cat "$CACHE_DIR/file")" = cached\n'
                         'printf relative > cache/file\n'
                         'printf absolute > "$CACHE_DIR/file"\n'
                         'mkdir "$CACHE_DIR/ignored"\n'
                         'touch "$CACHE_DIR/ignored/secret"')
        update['env'] = {'CACHE_DIR': str(absolute_cache)}
        success, output = self.run_job([cache, update])
        self.assertTrue(success, output)
        self.assertEqual((absolute_cache / 'file').read_bytes(), b'absolute')
        self.assertEqual(self.worker.uploads, [{'file': b'relative'}, {'file': b'absolute'}])
        for method in ('GET', 'POST'):
            paths = [params['path'][0] for verb, endpoint, params in self.worker.requests
                     if verb == method and endpoint.endswith('/job-cache')]
            self.assertEqual(paths, ['cache', str(absolute_cache)])

    def test_failed_job_does_not_upload_cache(self):
        success, _ = self.run_job([self.cache(), command('exit 1')])
        self.assertFalse(success)
        self.assertEqual(self.worker.uploads, [])

    def test_checksum_and_partial_match_upload(self):
        self.worker.cache = b'\x01' + archive({'file': b'cached'})
        success, output = self.run_job([self.cache(checksumIncludes=['dependency.txt'], checksumExcludes=[])])
        self.assertTrue(success, output)
        import hashlib
        requests = [params for method, path, params in self.worker.requests if method == 'GET' and path.endswith('/job-cache')]
        self.assertEqual(requests[0]['checksum'], [hashlib.md5(b'dependency').hexdigest()])
        self.assertEqual(self.worker.uploads, [{'file': b'cached'}])

    def test_checkout_preserves_dependencies_and_checks_out_exact_commit(self):
        repo = self.root / 'repository'
        repo.mkdir()
        subprocess.run(['git', 'init', '--quiet', '-b', 'main', str(repo)], check=True)
        (repo / 'file').write_text('committed')
        subprocess.run(['git', '-C', str(repo), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(repo), '-c', 'user.name=Test', '-c', 'user.email=test@example.com',
                        'commit', '--quiet', '-m', 'initial'], check=True)
        self.job['commitHash'] = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD']).decode().strip()
        encoded = lambda value: base64.b64encode(value.encode()).decode()
        checkout = dict(type='checkout', condition='SUCCESSFUL', optional=False, path=None,
                        cloneInfo='default-' + encoded(repo.as_uri()) + '-' + encoded('token'), depth=1, lfs=False, submodules=False)
        success, output = self.run_job([checkout, command('test "$(cat file)" = committed && test -f dependency.txt')])
        self.assertTrue(success, output)
        self.assertEqual(subprocess.check_output(['git', '-C', str(self.runner.work), 'rev-parse', 'HEAD']).decode().strip(), self.job['commitHash'])

    def test_symlink_and_parent_path_rejection(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.runner.work / 'link').symlink_to(outside)
        for path in ('../outside', '/tmp/outside', 'link/file'):
            with self.assertRaises(ValueError):
                sandbox_files.safe_path(self.runner.work, path)
        with self.assertRaises(ValueError):
            sandbox_files.unpack(io.BytesIO(archive({'../escape': b'x'})), self.runner.work)

    def test_pause_resume(self):
        step = command('sh "$ONEDEV_WORKDIR/../pause.sh"; touch resumed')
        output = io.StringIO()
        result = []
        with contextlib.redirect_stdout(output):
            thread = threading.Thread(target=lambda: result.append(self.runner.command(step, '0')))
            thread.start()
            import time
            for _ in range(100):
                if (self.root / 'pause.sh').exists():
                    # Wait until the pause script has removed a stale continue file.
                    time.sleep(0.2)
                    break
                time.sleep(0.02)
            (self.root / 'continue').touch()
            thread.join(timeout=10)
        self.assertFalse(thread.is_alive())
        self.assertEqual(result, [True])
        self.assertTrue((self.runner.work / 'resumed').exists())

    def test_container_command_preserves_script_credentials_tty_and_environment(self):
        (self.root / 'image').write_text('alpine:latest')
        step = command('printf "<&onedev#attributes/os#onedev&>"', image='<&onedev#image#onedev&>',
                       runAs='123:456', useTTY=True,
                       registryLogins=[dict(registryUrl='registry.example', userName='user', password='secret')])
        step['env'] = {'VALUE': 'a b', 'ONEDEV_WORKDIR': '/wrong'}
        step['executable'] = 'bash'
        configs = []

        def docker(args, env):
            self.assertEqual(args[0], 'run')
            self.assertNotIn('--cpus', args)
            self.assertNotIn('--memory', args)
            self.assertIn('-t', args)
            self.assertEqual(args[args.index('--user') + 1], '123:456')
            self.assertEqual(args[args.index('--entrypoint') + 1], 'sh')
            self.assertEqual(args[args.index('--') + 1], 'alpine:latest')
            self.assertIn('/var/run/docker.sock:/var/run/docker.sock', args)
            self.assertIn('VALUE=a b', args)
            self.assertEqual(args[args.index('--entrypoint') - 2], 'ONEDEV_WORKDIR=' + str(self.runner.work))
            self.assertIn('step-0.sh', args[-1])
            configs.append(Path(env['DOCKER_CONFIG']))
            config = json.loads((configs[-1] / 'config.json').read_text())
            self.assertEqual(base64.b64decode(config['auths']['registry.example']['auth']), b'user:secret')
            with patch.object(runner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'true\n')) as inspect, \
                    patch.object(runner.os, 'execvp') as execute:
                runner.open_shell(self.root)
                self.assertEqual(inspect.call_args.args[0],
                                 ['docker', 'inspect', '--format', '{{.State.Running}}', 'onedev-step-0'])
                execute.assert_called_once_with('docker', ['docker', 'exec', '-it', 'onedev-step-0', 'bash'])
            return True

        with patch.object(self.runner, 'docker', side_effect=docker), patch.object(self.runner.privileges, 'change_owner') as owner:
            self.assertTrue(self.runner.command(step, '0'))
        owner.assert_any_call('123:456', [self.root])
        self.assertFalse(configs[0].exists())
        self.assertEqual((self.root / 'command/step-0.sh').read_text(), 'printf "linux"')
        self.assertTrue((self.root / 'pause.sh').exists())
        self.assertFalse((self.root / 'shell.json').exists())

    def test_container_terminal_handles_unavailable_container_and_returns_to_vm_after_failure(self):
        def docker(args, env):
            for state in (subprocess.CompletedProcess([], 1, ''), subprocess.CompletedProcess([], 0, 'false\n')):
                with patch.object(runner.subprocess, 'run', return_value=state), patch.object(runner.os, 'execvp') as execute:
                    with self.assertRaisesRegex(RuntimeError, 'Container not running'):
                        runner.open_shell(self.root)
                    execute.assert_not_called()
            with patch.object(runner.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'true\n')), \
                    patch.object(runner.os, 'execvp') as execute:
                runner.open_shell(self.root)
                execute.assert_called_once_with('docker', ['docker', 'exec', '-it', 'onedev-step-1-2', 'sh'])
            raise RuntimeError('Docker transport failed')

        with patch.object(self.runner, 'docker', side_effect=docker), patch.object(self.runner.privileges, 'change_owner'):
            with self.assertRaisesRegex(RuntimeError, 'Docker transport failed'):
                self.runner.container(dict(image='tool'), '1-2')
        self.assertFalse((self.root / 'shell.json').exists())
        with patch.object(runner.subprocess, 'run') as inspect, patch.object(runner.os, 'execvp') as execute:
            runner.open_shell(self.root)
            execute.assert_called_once_with('bash', ['bash'])
            inspect.assert_not_called()

    def service(self, **extra):
        return dict(name='database', image='postgres:17', runAs='999:999',
                    arguments='-c "max_connections=50"', envs={'POSTGRES_PASSWORD': 'secret'},
                    readinessCheckCommand='pg_isready', **extra)

    def service_info(self, status='running', **state):
        return subprocess.CompletedProcess([], 0, json.dumps([{
            'State': dict(Status=status, **state),
            'NetworkSettings': {'Networks': {'onedev': {'IPAddress': '172.18.0.2'}}}}]))

    def test_services_wait_for_readiness_and_share_network_with_all_container_steps(self):
        self.job.update(networkOptions='--subnet 172.18.0.0/16 --label "description=job services"',
                        registryLogins=[dict(registryUrl='registry.example', userName='default', password='default')],
                        services=[self.service(registryLogins=[
                            dict(registryUrl='registry.example', userName='service', password='secret')])])
        configurations = []

        def docker(args, env):
            if args[:2] == ['run', '-d']:
                configurations.append(Path(env['DOCKER_CONFIG']))
                config = json.loads((configurations[-1] / 'config.json').read_text())
                self.assertEqual(base64.b64decode(config['auths']['registry.example']['auth']), b'service:secret')
                self.assertEqual(args[args.index('--network-alias') + 1], 'database')
                self.assertEqual(args[args.index('--user') + 1], '999:999')
                self.assertIn('POSTGRES_PASSWORD=secret', args)
                self.assertEqual(args[-3:], ['postgres:17', '-c', 'max_connections=50'])
                self.assertIn('--pull=always', args)
            if args[0] == 'run':
                self.assertEqual(args[args.index('--network') + 1], 'onedev')
                self.assertNotIn('--cpus', args)
                self.assertNotIn('--memory', args)
                self.assertIn('NO_PROXY=localhost,database', args)
                self.assertIn('no_proxy=localhost,database', args)
            if '--rm' in args:
                self.assertTrue((self.runner.work / 'shell-started').exists())
                self.assertNotIn('--pull=always', args)  # Same image already pulled for the service.
            return True

        def publish(name, address):
            self.assertEqual((name, address), ('database', '172.18.0.2'))
            self.assertFalse((self.runner.work / 'shell-started').exists())

        with patch.object(self.runner, 'docker', side_effect=docker) as calls, \
                patch.object(runner.subprocess, 'run', side_effect=[self.service_info(),
                    subprocess.CompletedProcess([], 1, 'still starting\n'), self.service_info(),
                    subprocess.CompletedProcess([], 0, 'accepting connections\n')]) as checks, \
                patch.object(self.runner, 'add_service_host', side_effect=publish) as hosts, \
                patch.object(self.runner.privileges, 'change_owner'), patch.object(runner.time, 'sleep') as sleep, \
                patch.dict(runner.os.environ, NO_PROXY='localhost', no_proxy='localhost'):
            success, output = self.run_job([
                command('test "$NO_PROXY" = localhost,database && test "$no_proxy" = localhost,database && touch shell-started'),
                command('true', image='postgres:17'),
                dict(type='container', condition='SUCCESSFUL', optional=False, image='postgres:17')])
        self.assertTrue(success, output)
        self.assertEqual(calls.call_args_list[0].args[0],
                         ['network', 'create', '--subnet', '172.18.0.0/16', '--label', 'description=job services', 'onedev'])
        self.assertEqual(checks.call_args.args[0], ['docker', 'exec', 'onedev-service-database', 'sh', '-c', 'pg_isready'])
        hosts.assert_called_once()
        sleep.assert_called_once_with(10)
        self.assertIn('Service readiness check: still starting', output)
        self.assertLess(output.index("Service 'database' is ready"), output.index('##onedev-step:START:step-0'))
        self.assertFalse(configurations[0].exists())

    def test_service_exit_reports_logs_and_prevents_steps(self):
        self.job['services'] = [self.service()]
        for status in ('exited', 'dead'):
            with self.subTest(status=status), patch.object(self.runner, 'docker', return_value=True) as docker, \
                    patch.object(runner.subprocess, 'run', return_value=self.service_info(status, OOMKilled=True)), \
                    patch.object(self.runner, 'execute') as execute, contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaisesRegex(RuntimeError, "Service 'database' is stopped unexpectedly"):
                    self.job['actions'] = []
                    self.runner.run()
                docker.assert_called_with(['logs', 'onedev-service-database'], runner.os.environ)
                execute.assert_not_called()
                self.assertIn('Out of memory', output.getvalue())
                self.assertIn('##onedev-phase:finalization', output.getvalue())

    def test_service_network_and_start_failures_prevent_steps(self):
        self.job.update(services=[self.service()], actions=[])
        for results, message in (([False], 'Unable to create job service network'),
                                 ([True, False], "Unable to start service 'database'")):
            with self.subTest(message=message), patch.object(self.runner, 'docker', side_effect=results), \
                    patch.object(self.runner, 'execute') as execute:
                with self.assertRaisesRegex(RuntimeError, message):
                    self.runner.run()
                execute.assert_not_called()

    def test_multiple_services_can_use_cached_images_and_have_distinct_hostnames(self):
        second = self.service()
        second['name'] = 'second-database'
        self.job.update(services=[self.service(), second], alwaysPullContainerImage=False)
        with patch.object(self.runner, 'docker', return_value=True) as docker, \
                patch.object(runner.subprocess, 'run', side_effect=[self.service_info(),
                    subprocess.CompletedProcess([], 0, ''), self.service_info(),
                    subprocess.CompletedProcess([], 0, '')]), patch.object(self.runner, 'add_service_host') as hosts:
            self.runner.start_services()
        self.assertEqual([call.args[0] for call in hosts.call_args_list], ['database', 'second-database'])
        for call in docker.call_args_list:
            self.assertNotIn('--pull=always', call.args[0])

    def test_service_network_cannot_be_overridden_but_shell_only_jobs_need_no_docker(self):
        with patch.object(self.runner, 'docker') as docker:
            self.runner.start_services()
            docker.assert_not_called()
            self.job['services'] = [self.service()]
            for options in ('--network host', '--network=host', '--net none', '--net=none'):
                self.job['runOptions'] = options
                with self.subTest(options=options), self.assertRaisesRegex(ValueError, 'conflicts'):
                    self.runner.start_services()
            self.job['runOptions'] = None
            for options in ('--driver overlay', '--driver=overlay', '-d overlay', '-doverlay', '--'):
                self.job['networkOptions'] = options
                with self.subTest(options=options), self.assertRaisesRegex(ValueError, 'conflicts'):
                    self.runner.start_services()
            docker.assert_not_called()

    def test_run_container_accepts_vm_file_options_and_mounts_absolute_caches(self):
        self.job['runOptions'] = '--env-file /tmp/environment --cidfile /tmp/container-id -v /tmp/shared:/shared --privileged'
        self.runner.caches = [sandbox.ProvisionedCache(dict(entries=[dict(path='/tmp/cache'), dict(path='relative-cache')]))]
        step = dict(image='tool', runAs='0:0', args='--file "a b"', workingDir='/output',
                    volumeMounts={'dependency.txt': '/input/file'}, env={}, useTTY=False)
        with patch.object(self.runner, 'docker', return_value=True) as docker, patch.object(self.runner.privileges, 'change_owner'):
            self.assertTrue(self.runner.container(step, '1-2'))
        args = docker.call_args.args[0]
        self.assertNotIn('--cpus', args)
        self.assertNotIn('--memory', args)
        self.assertEqual(args[args.index('--env-file') + 1], '/tmp/environment')
        self.assertIn('/tmp/shared:/shared', args)
        self.assertIn('/tmp/cache:/tmp/cache', args)
        self.assertIn('/var/run/docker.sock:/var/run/docker.sock', args)
        self.assertIn(str(self.runner.work / 'dependency.txt') + ':/input/file', args)
        self.assertEqual(args[args.index('-w') + 1], '/output')
        self.assertNotIn('--entrypoint', args)
        self.assertEqual(args[-3:], ['tool', '--file', 'a b'])

    def test_conflicting_run_build_and_prune_options_are_rejected(self):
        with patch.object(self.runner, 'docker') as docker:
            for option in ('--detach=true', '-Pdt', '--user=123:456', '-w/tmp', '--entrypoint=sh', '--rm=false', '--'):
                with self.subTest(option=option), self.assertRaisesRegex(ValueError, 'conflicts with step settings'):
                    self.job['runOptions'] = option
                    self.runner.container({'image': 'alpine'}, '0')
            for option in ('--load', '--push=false', '-fOther', '-qotmp', '--tag=x', '--builder=custom', '--platform=linux/amd64', '--'):
                with self.subTest(option=option), self.assertRaisesRegex(ValueError, 'conflicts with step settings'):
                    self.runner.build_image({'moreOptions': option})
            for option in ('--builder=custom', '--builder custom', '--force=false', '-f', '-af', '--'):
                with self.subTest(option=option), self.assertRaisesRegex(ValueError, 'conflicts with step settings'):
                    self.runner.prune_builder_cache({'options': option})
            docker.assert_not_called()

    def test_build_image_outputs_file_options_and_pruning(self):
        self.runner.privileges.owner = [1000, 1000]
        step = dict(buildPath='context', dockerfile='Dockerfile.custom', platforms='linux/amd64,linux/arm64',
                    moreOptions='--secret id=key,src=/tmp/key --metadata-file /tmp/metadata --cache-to type=local,dest=/tmp/cache',
                    tags='registry/image:<&onedev#attributes/os#onedev&> registry/image:latest')
        with patch.object(runner.os, 'geteuid', return_value=1000), \
                patch.object(self.runner.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')), \
                patch.object(self.runner.privileges, 'change_owner', side_effect=AssertionError('unexpected ownership pass')), \
                patch.object(self.runner, 'docker', return_value=True) as docker:
            self.assertTrue(self.runner.build_image(step))
            docker.assert_called_once()
            build = docker.call_args.args[0]
            self.assertEqual(build[:2], ['buildx', 'build'])
            self.assertNotIn('--builder', build)
            self.assertIn('--push', build)
            self.assertIn('registry/image:linux', build)
            self.assertIn('registry/image:latest', build)
            self.assertEqual(build[build.index('--secret') + 1], 'id=key,src=/tmp/key')
            self.assertEqual(build[build.index('-f') + 1], 'Dockerfile.custom')
            self.assertEqual(build[build.index('--platform') + 1], 'linux/amd64,linux/arm64')
            self.assertEqual(build[-2:], ['--', 'context'])
            buildx_config = docker.call_args.args[1]['BUILDX_CONFIG']
            docker.reset_mock()
            self.assertTrue(self.runner.build_image({'destPath': 'oci,layout'}))
            docker.assert_called_once()
            build = docker.call_args.args[0]
            self.assertEqual(build[:2], ['buildx', 'build'])
            self.assertNotIn('--builder', build)
            output = next(csv.reader([build[build.index('--output') + 1]]))
            self.assertEqual(output, ['type=oci', 'tar=false', 'dest=' + str(self.runner.work / 'oci,layout')])
            self.assertTrue((self.runner.work / 'oci,layout').is_dir())
            self.assertNotIn('--push', build)
            for options, expected in ((None, []), ("-a --filter 'until=24h'", ['-a', '--filter', 'until=24h'])):
                with self.subTest(options=options):
                    docker.reset_mock()
                    self.assertTrue(self.runner.prune_builder_cache({'options': options}))
                    docker.assert_called_once()
                    self.assertEqual(docker.call_args.args[0], ['buildx', 'prune', '-f', *expected])
                    self.assertEqual(docker.call_args.args[1]['BUILDX_CONFIG'], buildx_config)

    def test_imagetools_and_prune_resolve_arguments_and_failure_keeps_step_scheduling(self):
        self.runner.privileges.owner = [1000, 1000]
        steps = (
            (dict(type='imagetools', arguments='create -f /tmp/manifest.json -t image:<&onedev#attributes/os#onedev&>'),
             ['imagetools', 'create', '-f', '/tmp/manifest.json', '-t', 'image:linux']),
            (dict(type='prune-builder-cache', options="--filter 'description=<&onedev#attributes/os#onedev&>'"),
             ['prune', '-f', '--filter', 'description=linux']))
        for step, args in steps:
            step.update(condition='SUCCESSFUL', optional=False)
            with self.subTest(step=step['type']), patch.object(runner.os, 'geteuid', return_value=1000), \
                    patch.object(self.runner.privileges, 'call_as_root', side_effect=AssertionError('unexpected sudo')), \
                    patch.object(self.runner.privileges, 'change_owner', side_effect=AssertionError('unexpected ownership pass')), \
                    patch.object(runner.subprocess, 'call', return_value=8) as call:
                success, output = self.run_job([step, command('touch skipped')])
                self.assertFalse(success)
                call.assert_called_once()
                self.assertEqual(call.call_args.args[0], ['docker', 'buildx', *args])
                self.assertEqual(call.call_args.kwargs['cwd'], self.runner.work)
                self.assertIn('Docker command exited with code 8', output)
                self.assertIn('##onedev-step:FAILED:step-0', output)
                self.assertIn('##onedev-step:SKIP:step-1', output)

    def test_ant_patterns(self):
        for name in ('report.xml', 'nested/report.xml', 'a/b/report.xml'):
            self.assertTrue(sandbox_files.matches(name, '**/*.xml'))
        self.assertFalse(sandbox_files.matches('nested/report.xml', '*.xml'))
        self.assertTrue(sandbox_files.matches('cache/sub/file', 'cache/'))


if __name__ == '__main__':
    unittest.main()
