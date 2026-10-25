import os
from pathlib import Path
import tempfile
import time
import unittest

import sandbox_test_support
import sandbox
import sandbox_files


class SandboxTimestampTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='onedev timestamps ')
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'file'

    def test_ownership_and_permission_changes_do_not_mark_cache_changed(self):
        self.path.write_text('cached')
        since = time.time()
        time.sleep(0.01)
        uid, gid = (1001, 1001) if os.geteuid() == 0 else (os.getuid(), os.getgid())
        os.chown(self.path, uid, gid, follow_symlinks=False)
        self.path.chmod(0o600)
        self.assertGreater(self.path.lstat().st_ctime, since)
        self.assertFalse(sandbox_files.paths_changed([self.path], since))

    def test_content_modification_is_detected(self):
        self.path.write_text('cached')
        since = time.time()
        modified = since + 2.125
        os.utime(self.path, (modified, modified))
        self.assertTrue(sandbox_files.paths_changed([self.path], since))
        self.assertFalse(sandbox_files.paths_changed([self.path], modified))

    def test_new_file_with_preserved_mtime_is_detected_through_parent(self):
        root = self.path.parent
        entry = dict(path='cache')
        cache = sandbox.ProvisionedCache(dict(entries=[entry], strategy='UPLOAD_IF_CHANGED'))
        cache.provisioned = 100
        for directory in (root, root / 'nested'):
            with self.subTest(directory=directory):
                directory.mkdir(exist_ok=True)
                os.utime(root, (1, 1))
                os.utime(directory, (1, 1))
                self.assertFalse(cache.has_changes(entry, root))
                path = directory / 'new-file'
                path.write_text('new cache entry')
                os.utime(path, (1, 1))
                self.assertGreater(directory.stat().st_mtime, cache.provisioned)
                self.assertTrue(cache.has_changes(entry, root))

    def test_deleting_last_file_is_detected_through_cache_root(self):
        root = self.path.parent
        entry = dict(path='cache')
        cache = sandbox.ProvisionedCache(dict(entries=[entry], strategy='UPLOAD_IF_CHANGED'))
        cache.provisioned = 100
        self.path.write_text('cached')
        os.utime(self.path, (1, 1))
        os.utime(root, (1, 1))
        self.assertFalse(cache.has_changes(entry, root))
        self.path.unlink()
        self.assertTrue(cache.has_changes(entry, root))

    def test_missing_files_are_not_silently_ignored(self):
        with self.assertRaises(FileNotFoundError):
            sandbox_files.paths_changed([self.path], time.time())


if __name__ == '__main__':
    unittest.main()
