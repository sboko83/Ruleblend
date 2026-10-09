"""Regression checks for the release license gate."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from check_licenses import verify


class LicenseAuditTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.manifest = self.root / "runtime.txt"
        self.manifest.write_text("example:library:1.0\n", encoding="utf-8")
        self.write("THIRD-PARTY.md", "| `example:library` | `1.0` | [MIT](licenses/MIT.txt) |\n")
        self.write("LICENSE", "project terms")
        self.write("NOTICE", "project notice")
        self.write("licenses/MIT.txt", "upstream terms")
        self.write("licenses/SOURCES.json", json.dumps({
            "licenses/MIT.txt": {
                "source": "https://example.org/LICENSE",
                "sha256": hashlib.sha256(b"upstream terms").hexdigest(),
            },
        }))

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def test_reviewed_dependencies_pass(self):
        self.assertEqual([], verify(self.root, self.manifest))

    def test_new_dependency_or_version_fails(self):
        self.manifest.write_text("example:library:2.0\nexample:new:1.0\n", encoding="utf-8")
        self.assertEqual(2, len(verify(self.root, self.manifest)))

    def test_empty_manifest_and_missing_table_fail(self):
        self.manifest.write_text("", encoding="utf-8")
        self.write("THIRD-PARTY.md", "no table")
        self.assertEqual(2, len(verify(self.root, self.manifest)))

    def test_changed_or_missing_upstream_text_fails(self):
        self.write("licenses/MIT.txt", "altered terms")
        self.assertIn("Changed upstream text: licenses/MIT.txt", verify(self.root, self.manifest))
        (self.root / "licenses/MIT.txt").unlink()
        self.assertIn("Missing upstream text: licenses/MIT.txt", verify(self.root, self.manifest))

    def test_missing_provenance_fails(self):
        self.write("licenses/SOURCES.json", "{}")
        self.assertIn("License has no upstream provenance: licenses/MIT.txt", verify(self.root, self.manifest))

    def test_duplicate_coordinate_fails(self):
        content = (self.root / "THIRD-PARTY.md").read_text(encoding="utf-8")
        self.write("THIRD-PARTY.md", content * 2)
        self.assertIn("Duplicate license entry: example:library:1.0", verify(self.root, self.manifest))

    def test_missing_packaged_notices_and_java_terms_fail(self):
        image = self.root / "image"
        image.mkdir()
        errors = verify(self.root, self.manifest, image)
        self.assertIn("Missing bundled Java license", errors)
        self.assertIn("Missing bundled Java license exception", errors)
        self.assertTrue(any("packaged notice" in error for error in errors))

    def test_both_platform_layouts_pass(self):
        for mac in (False, True):
            with self.subTest(mac=mac):
                image = self.root / ("mac" if mac else "windows")
                legal = image / ("Contents/app/resources/legal" if mac else "app/resources/legal")
                for name in ("LICENSE", "NOTICE", "THIRD-PARTY.md", "licenses/MIT.txt", "licenses/SOURCES.json"):
                    destination = legal / name
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes((self.root / name).read_bytes())
                java = image / ("Contents/runtime/Contents/Home/legal/java.base" if mac else "runtime/legal/java.base")
                java.mkdir(parents=True)
                (java / "LICENSE").write_text("GPL", encoding="utf-8")
                (java / "ASSEMBLY_EXCEPTION").write_text("exception", encoding="utf-8")
                vendor = legal / "java-runtime"
                vendor.mkdir()
                (vendor / "NOTICE").write_text("vendor", encoding="utf-8")
                (vendor / "release").write_text('JAVA_VERSION="21.0.7"\n', encoding="utf-8")
                (java.parent.parent / "release").write_text('JAVA_VERSION="21.0.7"\n', encoding="utf-8")
                self.assertEqual([], verify(self.root, self.manifest, image))
                (vendor / "release").write_text('JAVA_VERSION="21.0.8"\n', encoding="utf-8")
                self.assertIn("Java runtime provenance does not match packaged runtime",
                              verify(self.root, self.manifest, image))


if __name__ == "__main__":
    unittest.main()
