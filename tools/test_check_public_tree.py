"""Publication archive audit contracts; no extraction or real credentials."""
import io
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

from check_public_tree import audit, review_digest


class PublicTreeTest(unittest.TestCase):
    def scan(self, files):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'snapshot.tar'
            with tarfile.open(archive, 'w:') as tar:
                for name, data in files.items():
                    if isinstance(data, str):
                        data = data.encode('utf-8')
                    entry = tarfile.TarInfo(name)
                    entry.size = len(data)
                    tar.addfile(entry, io.BytesIO(data))
            return audit(archive)

    def test_valid_links_and_binary_resources(self):
        count, findings, homes = self.scan({
            'README.md': '[Doc](docs/Guide%20file.md#part) [Web](https://example.com/) [Here](#x)',
            'docs/Guide file.md': '[Root](../README.md)',
            'app/icon.png': b'\x89PNG\x00\xff',
        })
        self.assertEqual((3, [], []), (count, findings, homes))

    def test_private_paths_fail_but_shipped_assets_and_shared_runs_survive(self):
        _, findings, _ = self.scan({name: '' for name in (
            'AGENTS.md', 'CLAUDE.md', 'docs/PLAN.md', 'docs/ru/PLAN.md',
            'docs/art/icon.png', 'tools/run_phases.sh', 'tools/split_kt.py',
            'private/note.md', '.codex/config.toml', '.git/config',
            'tools/helper/.build/data', 'debug.log',
            'app/src/commonMain/composeResources/drawable/app_logo.png',
            '.run/Ruleblend (Windows).run.xml',
        )})
        self.assertEqual(12, len(findings))
        self.assertTrue(all('excluded path' in item for item in findings))

    def test_secrets_are_detected_without_printing_values(self):
        token = 'ghp_' + 'a' * 36
        password = 'https://' + 'account:secret@example.com'
        key = '-----BEGIN ' + 'OPENSSH PRIVATE KEY-----'
        _, findings, _ = self.scan({'config.txt': '\n'.join([token, password, key])})
        self.assertEqual(3, len(findings))
        self.assertNotIn(token, str(findings))
        self.assertNotIn('secret@', str(findings))

    def test_home_paths_require_review_even_in_tests(self):
        _, findings, homes = self.scan({'fixture.kt': '\n'.join([
            '/Users/' + 'alice/project', 'C:\\Users\\' + 'alice\\project',
            '/home/' + 'alice/project', '~/project', r'C:\\Users\\' + 'alice',
        ])})
        self.assertEqual([], findings)
        self.assertEqual(4, len(homes))
        self.assertNotIn('alice', str(homes))

    def test_missing_and_escaping_links_fail(self):
        _, findings, _ = self.scan({'README.md': '[Private](docs/PLAN.md) [Outside](../file.md)'})
        self.assertEqual(2, len(findings))

    def test_unsafe_archive_paths_fail_without_extraction(self):
        _, findings, _ = self.scan({'../outside': '', '/absolute': '', 'a\\b': ''})
        self.assertEqual(3, len(findings))

    def test_symlinks_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'snapshot.tar'
            with tarfile.open(archive, 'w:') as tar:
                entry = tarfile.TarInfo('link')
                entry.type = tarfile.SYMTYPE
                entry.linkname = '../outside'
                tar.addfile(entry)
            self.assertEqual(['link: unsupported archive entry'], audit(archive)[1])

    def test_review_cannot_be_reused_after_archive_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'snapshot.tar'
            archive.write_bytes(b'first archive')
            first = review_digest(archive, ['file:1: service token'], [])
            self.assertNotEqual(first, review_digest(archive, ['file:2: service token'], []))
            archive.write_bytes(b'different secret at the same location')
            self.assertNotEqual(first, review_digest(archive, ['file:1: service token'], []))

    def test_cli_accepts_only_exact_review_and_refuses_structural_failures(self):
        script = Path(__file__).with_name('check_public_tree.py')
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'snapshot.tar'
            for name, content, expected in (
                ('fixture.txt', '/home/' + 'user/project', 0),
                ('README.md', '[Missing](absent.md)', 1),
                ('AGENTS.md', '', 1),
            ):
                with self.subTest(name=name):
                    with tarfile.open(archive, 'w:') as tar:
                        data = content.encode('utf-8')
                        entry = tarfile.TarInfo(name)
                        entry.size = len(data)
                        tar.addfile(entry, io.BytesIO(data))
                    _, findings, homes = audit(archive)
                    digest = review_digest(archive, findings, homes)
                    command = [sys.executable, '-B', str(script), str(archive)]
                    result = subprocess.run(command + ['--reviewed', digest], capture_output=True)
                    self.assertEqual(expected, result.returncode, result.stdout)
                    result = subprocess.run(command + ['--reviewed', 'stale'], capture_output=True)
                    self.assertEqual(1, result.returncode)

    def test_cli_does_not_pass_unreviewed_credentials_or_paths(self):
        script = Path(__file__).with_name('check_public_tree.py')
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp) / 'snapshot.tar'
            for content, expected in (
                ('ghp_' + 'a' * 36, 1), ('/Users/' + 'alice/project', 2), ('clean', 0),
            ):
                with tarfile.open(archive, 'w:') as tar:
                    data = content.encode('utf-8')
                    entry = tarfile.TarInfo('fixture.txt')
                    entry.size = len(data)
                    tar.addfile(entry, io.BytesIO(data))
                result = subprocess.run([sys.executable, '-B', str(script), str(archive)], capture_output=True)
                self.assertEqual(expected, result.returncode, result.stdout)


if __name__ == '__main__':
    unittest.main()
