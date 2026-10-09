"""Regression tests for alpha release gates and source provenance."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from release_assets import collect, java_source, validate_tag, verify_assets, verify_vendor_checksum


METADATA = '''IMPLEMENTOR="Eclipse Adoptium"
IMPLEMENTOR_VERSION="Temurin-21.0.7+6"
SEMANTIC_VERSION="21.0.7+6"
JAVA_RUNTIME_VERSION="21.0.7+6-LTS"
JAVA_VERSION="21.0.7"
OS_NAME="Windows"
OS_ARCH="x86_64"
'''


class ReleaseAssetsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'app').mkdir()
        (self.root / 'app/build.gradle.kts').write_text('val appVersion = "0.5.0"\nval appBuild = "261"\n')

    def test_tag_must_match_source_version_and_alpha(self):
        self.assertEqual(('0.5.0', '261'), validate_tag(self.root, 'v0.5.0-alpha.1'))
        for tag in ('v0.6.0-alpha.1', 'v0.5.0', 'v0.5.0-alpha.2', 'v0.5.0-alpha.1\n'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate_tag(self.root, tag)

    def test_java_source_uses_exact_build(self):
        system, arch, name, url = java_source(METADATA)
        self.assertEqual(('windows', 'x64'), (system, arch))
        self.assertEqual('OpenJDK21U-jdk-sources_21.0.7_6.tar.gz', name)
        self.assertIn('/jdk-21.0.7%2B6/', url)

    def test_java_source_accepts_patch_release(self):
        metadata = METADATA.replace('21.0.7+6', '21.0.12.1+1').replace('JAVA_VERSION="21.0.7"', 'JAVA_VERSION="21.0.12.1"')
        _, _, name, url = java_source(metadata)
        self.assertEqual('OpenJDK21U-jdk-sources_21.0.12.1_1.tar.gz', name)
        self.assertIn('/jdk-21.0.12.1%2B1/', url)

    def test_unknown_or_inconsistent_runtime_is_rejected(self):
        for old, new in [('Eclipse Adoptium', 'Other vendor'), ('21.0.7+6-LTS', '21.0.7+7-LTS'),
                         ('Temurin-21.0.7+6', 'Temurin-21.0.7+7'), ('Windows', 'Linux'),
                         ('x86_64', 'aarch64'), ('21.0.7+6', '21.0.7+6-ea')]:
            with self.subTest(new=new), self.assertRaises(ValueError):
                java_source(METADATA.replace(old, new))

    def test_vendor_checksum_rejects_corruption_and_wrong_name(self):
        source = self.root / 'source.tar.gz'
        source.write_bytes(b'source')
        checksum = self.root / 'checksum.txt'
        sha = hashlib.sha256(b'source').hexdigest()
        for text in [sha, sha + '  source.tar.gz', sha.upper() + ' *source.tar.gz']:
            checksum.write_text(text)
            verify_vendor_checksum(source, checksum)
        for text in ['0' * 64, sha + '  wrong.tar.gz', sha + '\n' + sha]:
            checksum.write_text(text)
            with self.assertRaises(ValueError):
                verify_vendor_checksum(source, checksum)

    def package(self, mac=False):
        image = self.root / ('mac' if mac else 'win')
        legal = image / ('Contents/app/resources/legal/java-runtime' if mac else 'app/resources/legal/java-runtime')
        runtime = image / ('Contents/runtime/Contents/Home' if mac else 'runtime')
        legal.mkdir(parents=True)
        runtime.mkdir(parents=True)
        metadata = METADATA.replace('Windows', 'Darwin').replace('x86_64', 'aarch64') if mac else METADATA
        (legal / 'release').write_text(metadata)
        (legal / 'NOTICE').write_text('Vendor notice')
        (runtime / 'release').write_text('JAVA_VERSION="21.0.7"\nMODULES="java.base"\n')
        installer = self.root / ('app.dmg' if mac else 'app.msi')
        installer.write_bytes(b'installer')
        output = self.root / 'assets' / ('mac' if mac else 'win')

        def fetch(url, target):
            target.write_bytes((hashlib.sha256(b'full upstream source').hexdigest() + '  ' +
                                'OpenJDK21U-jdk-sources_21.0.7_6.tar.gz').encode()
                               if url.endswith('.sha256.txt') else b'full upstream source')
        with patch('release_assets.download', side_effect=fetch):
            collect(self.root, 'v0.5.0-alpha.1', image, installer, output)
        return image, installer, output

    def test_collect_preserves_upstream_source_checksum_and_runtime_metadata(self):
        image, _, output = self.package()
        with zipfile.ZipFile(next(output.glob('*.zip'))) as archive:
            self.assertEqual(b'full upstream source', archive.read('OpenJDK21U-jdk-sources_21.0.7_6.tar.gz'))
            self.assertEqual((image / 'app/resources/legal/java-runtime/release').read_bytes(), archive.read('packaging-jdk-release.txt'))
            self.assertIn('bundled-runtime-release.txt', archive.namelist())
            self.assertIn('NOTICE', archive.namelist())

    def test_collect_rejects_different_packaged_runtime(self):
        image, installer, _ = self.package()
        (image / 'runtime/release').write_text('JAVA_VERSION="21.0.8"\n')
        with self.assertRaisesRegex(ValueError, 'does not match'):
            collect(self.root, 'v0.5.0-alpha.1', image, installer, self.root / 'different')

    def test_two_platform_release_and_checksums(self):
        self.package()
        self.package(mac=True)
        directory = self.root / 'assets'
        verify_assets(self.root, 'v0.5.0-alpha.1', directory)
        self.assertEqual(6, len((directory / 'SHA256SUMS').read_text().splitlines()))

    def test_missing_platform_is_rejected(self):
        self.package()
        with self.assertRaisesRegex(ValueError, 'exactly two'):
            verify_assets(self.root, 'v0.5.0-alpha.1', self.root / 'assets')

    def test_tampered_installer_is_rejected(self):
        _, _, output = self.package()
        self.package(mac=True)
        next(output.glob('*.msi')).write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            verify_assets(self.root, 'v0.5.0-alpha.1', self.root / 'assets')

    def test_mixed_builds_and_extra_files_are_rejected(self):
        _, _, output = self.package()
        self.package(mac=True)
        manifest = next(output.glob('*.json'))
        data = json.loads(manifest.read_text())
        data['build'] = '262'
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'does not match'):
            verify_assets(self.root, 'v0.5.0-alpha.1', self.root / 'assets')
        data['build'] = '261'
        manifest.write_text(json.dumps(data))
        (output / 'unexpected.txt').write_text('extra')
        with self.assertRaisesRegex(ValueError, 'Unexpected'):
            verify_assets(self.root, 'v0.5.0-alpha.1', self.root / 'assets')

    def test_unsafe_asset_name_is_rejected(self):
        _, _, output = self.package()
        self.package(mac=True)
        manifest = next(output.glob('*.json'))
        data = json.loads(manifest.read_text())
        name = next(name for name in data['assets'] if name.endswith('.msi'))
        data['assets']['../' + name] = data['assets'].pop(name)
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'Unsafe'):
            verify_assets(self.root, 'v0.5.0-alpha.1', self.root / 'assets')


if __name__ == '__main__':
    unittest.main()
