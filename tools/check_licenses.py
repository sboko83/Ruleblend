"""Check resolved runtime license coverage and the bytes shipped in an app image."""
import argparse
import hashlib
import json
from pathlib import Path
import re


DEPENDENCY_ROW = re.compile(
    r"^\| `([^`]+:[^`]+)` \| `([^`]+)` \| \[[^\]]+\]\((licenses/[^)]+)\) \|",
    re.MULTILINE,
)


def verify(root: Path, manifest: Path, image: Path | None = None) -> list[str]:
    errors = []
    entries = {}
    for component, version, license_path in DEPENDENCY_ROW.findall(
        (root / "THIRD-PARTY.md").read_text(encoding="utf-8")
    ):
        coordinate = f"{component}:{version}"
        if coordinate in entries:
            errors.append(f"Duplicate license entry: {coordinate}")
        entries[coordinate] = license_path
    if not entries:
        errors.append("No runtime license entries")

    coordinates = manifest.read_text(encoding="utf-8").splitlines()
    if not coordinates:
        errors.append("Empty runtime dependency manifest")
    for coordinate in coordinates:
        if coordinate not in entries:
            errors.append(f"Unreviewed runtime dependency: {coordinate}")

    sources = json.loads((root / "licenses/SOURCES.json").read_text(encoding="utf-8"))
    if not sources:
        errors.append("No upstream license texts")
    for license_path in sorted(set(entries.values())):
        if license_path not in sources:
            errors.append(f"License has no upstream provenance: {license_path}")
    for name, provenance in sources.items():
        path = root / name
        if not path.is_file():
            errors.append(f"Missing upstream text: {name}")
        elif not path.stat().st_size or hashlib.sha256(path.read_bytes()).hexdigest() != provenance["sha256"]:
            errors.append(f"Changed upstream text: {name}")

    for name in ("LICENSE", "NOTICE", "THIRD-PARTY.md"):
        if not (root / name).is_file() or not (root / name).stat().st_size:
            errors.append(f"Missing project notice: {name}")

    if image is not None:
        mac = (image / "Contents").is_dir()
        legal = image / ("Contents/app/resources/legal" if mac else "app/resources/legal")
        runtime_legal = image / ("Contents/runtime/Contents/Home/legal" if mac else "runtime/legal")
        expected = [root / name for name in ("LICENSE", "NOTICE", "THIRD-PARTY.md")]
        expected.extend(path for path in (root / "licenses").rglob("*") if path.is_file())
        for path in expected:
            relative = path.relative_to(root)
            packaged = legal / relative
            if not packaged.is_file() or packaged.read_bytes() != path.read_bytes():
                errors.append(f"Missing or changed packaged notice: {relative.as_posix()}")
        if not (runtime_legal / "java.base/LICENSE").is_file():
            errors.append("Missing bundled Java license")
        if not (runtime_legal / "java.base/ASSEMBLY_EXCEPTION").is_file():
            errors.append("Missing bundled Java license exception")
        metadata = legal / "java-runtime/release"
        vendor_notice = legal / "java-runtime/NOTICE"
        if not vendor_notice.is_file() or not vendor_notice.stat().st_size:
            errors.append("Missing Java vendor notice")
        runtime_release = image / ("Contents/runtime/Contents/Home/release" if mac else "runtime/release")
        if not metadata.is_file() or not runtime_release.is_file():
            errors.append("Missing Java runtime provenance")
        else:
            def java_version(path):
                match = re.search(r'^JAVA_VERSION="([^"]+)"$', path.read_text(encoding="utf-8"), re.MULTILINE)
                return match.group(1) if match else None
            version = java_version(metadata)
            if version is None or version != java_version(runtime_release):
                errors.append("Java runtime provenance does not match packaged runtime")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--image", type=Path)
    args = parser.parse_args()
    try:
        errors = verify(Path(__file__).resolve().parent.parent, args.manifest, args.image)
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"License audit failed: {error}\n")
    if errors:
        parser.exit(1, "\n".join(errors) + "\n")
    print("License audit: runtime dependencies and upstream texts verified"
          + ("; packaged notices and Java license verified" if args.image else ""))


if __name__ == "__main__":
    main()
