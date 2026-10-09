"""Prepare and verify alpha release assets using only the Python standard library."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tempfile
import urllib.parse
import urllib.request
import zipfile


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def validate_tag(root, tag):
    text = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
    version = re.search(r'^val appVersion = "([0-9]+\.[0-9]+\.[0-9]+)"$', text, re.M)
    build = re.search(r'^val appBuild = "([0-9]+)"$', text, re.M)
    if not version or not build or tag != f'v{version[1]}-alpha.1':
        raise ValueError('Release tag must equal v<appVersion>-alpha.1')
    return version[1], build[1]


def java_source(metadata):
    values = dict(re.findall(r'^([A-Z_]+)="([^"\r\n]*)"$', metadata, re.M))
    version = values.get('SEMANTIC_VERSION', '')
    if (values.get('IMPLEMENTOR') != 'Eclipse Adoptium'
            or not re.fullmatch(r'21\.0\.[0-9]+\+[0-9]+', version)
            or values.get('JAVA_RUNTIME_VERSION') not in (version, version + '-LTS')
            or values.get('IMPLEMENTOR_VERSION') != 'Temurin-' + version):
        raise ValueError('Only an exact stable Temurin 21 runtime can be released')
    systems = {'Darwin': 'macos', 'Windows': 'windows'}
    architectures = {'aarch64': 'aarch64', 'x86_64': 'x64', 'amd64': 'x64'}
    system = systems.get(values.get('OS_NAME'))
    arch = architectures.get(values.get('OS_ARCH'))
    if system is None or arch is None or (system == 'windows' and arch != 'x64'):
        raise ValueError('Unsupported release runtime platform')
    name = 'OpenJDK21U-jdk-sources_' + version.replace('+', '_') + '.tar.gz'
    url = ('https://github.com/adoptium/temurin21-binaries/releases/download/'
           + urllib.parse.quote('jdk-' + version, safe='') + '/' + name)
    return system, arch, name, url


def download(url, target):
    request = urllib.request.Request(url, headers={'User-Agent': 'Ruleblend-release'})
    with urllib.request.urlopen(request, timeout=60) as response, target.open('wb') as stream:
        shutil.copyfileobj(response, stream)


def verify_vendor_checksum(source, checksum):
    text = checksum.read_text(encoding='utf-8').strip()
    match = re.fullmatch(r'([a-fA-F0-9]{64})(?:\s+\*?' + re.escape(source.name) + r')?', text)
    if not match or match[1].lower() != digest(source):
        raise ValueError('Java source vendor checksum mismatch')


def collect(root, tag, image, installer, output):
    version, build = validate_tag(root, tag)
    mac = (image / 'Contents').is_dir()
    legal = image / ('Contents/app/resources/legal/java-runtime' if mac else 'app/resources/legal/java-runtime')
    runtime = image / ('Contents/runtime/Contents/Home/release' if mac else 'runtime/release')
    metadata = (legal / 'release').read_text(encoding='utf-8')
    system, arch, name, url = java_source(metadata)
    actual = dict(re.findall(r'^([A-Z_]+)="([^"\r\n]*)"$', runtime.read_text(encoding='utf-8'), re.M))
    expected = dict(re.findall(r'^([A-Z_]+)="([^"\r\n]*)"$', metadata, re.M))
    if any(actual.get(key) != expected.get(key) for key in ('JAVA_VERSION',)):
        raise ValueError('Packaged runtime does not match Java provenance')
    suffix = '.dmg' if system == 'macos' else '.msi'
    if installer.suffix != suffix or mac != (system == 'macos'):
        raise ValueError('Installer and runtime platforms differ')
    prefix = f'ruleblend-{tag}-build{build}-{system}-{arch}'
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError('Release output directory must be empty')
    with tempfile.TemporaryDirectory() as temporary:
        source = Path(temporary) / name
        checksum = Path(temporary) / (name + '.sha256.txt')
        download(url + '.sha256.txt', checksum)
        download(url, source)
        verify_vendor_checksum(source, checksum)
        source_zip = output / (prefix + '-java-sources.zip')
        with zipfile.ZipFile(source_zip, 'w', compression=zipfile.ZIP_STORED) as archive:
            for path, arcname in ((source, name), (checksum, checksum.name),
                                  (legal / 'release', 'packaging-jdk-release.txt'),
                                  (runtime, 'bundled-runtime-release.txt'), (legal / 'NOTICE', 'NOTICE')):
                archive.write(path, arcname)
        vendor_hash = digest(source)
    packaged = output / (prefix + suffix)
    shutil.copyfile(installer, packaged)
    manifest = {'tag': tag, 'version': version, 'build': build, 'platform': system,
                'architecture': arch, 'java_source_url': url, 'java_source_sha256': vendor_hash,
                'assets': {path.name: digest(path) for path in (packaged, source_zip)}}
    (output / (prefix + '-provenance.json')).write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')


def verify_assets(root, tag, directory):
    version, build = validate_tag(root, tag)
    manifests = list(directory.rglob('*-provenance.json'))
    if len(manifests) != 2:
        raise ValueError('Release requires exactly two platform manifests')
    platforms = set()
    names = set()
    files = []
    for manifest in manifests:
        data = json.loads(manifest.read_text(encoding='utf-8'))
        if (data['tag'], data['version'], data['build']) != (tag, version, build):
            raise ValueError('Asset provenance does not match the release')
        platform = data['platform']
        if platform in platforms or platform not in ('macos', 'windows'):
            raise ValueError('Duplicate or unsupported release platform')
        platforms.add(platform)
        suffix = '.dmg' if platform == 'macos' else '.msi'
        assets = data['assets']
        if len(assets) != 2 or sorted(Path(name).suffix for name in assets) != sorted([suffix, '.zip']):
            raise ValueError('Each platform requires an installer and Java sources')
        for name, sha256 in assets.items():
            if Path(name).name != name or name in names:
                raise ValueError('Unsafe or duplicate asset name')
            path = manifest.parent / name
            if digest(path) != sha256:
                raise ValueError('Release asset checksum mismatch')
            names.add(name)
            files.append(path)
        if manifest.name in names:
            raise ValueError('Duplicate provenance name')
        names.add(manifest.name)
        files.append(manifest)
    if set(directory.rglob('*')) - {p for p in directory.rglob('*') if p.is_dir()} != set(files):
        raise ValueError('Unexpected release assets')
    (directory / 'SHA256SUMS').write_text(''.join(f'{digest(path)}  {path.name}\n' for path in sorted(files)), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['validate-tag', 'collect', 'verify'])
    parser.add_argument('--tag', required=True)
    parser.add_argument('--image', type=Path)
    parser.add_argument('--installer', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/release-assets'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    if args.command == 'validate-tag':
        validate_tag(root, args.tag)
    elif args.command == 'collect':
        if args.image is None or args.installer is None:
            parser.error('collect requires --image and --installer')
        collect(root, args.tag, args.image, args.installer, args.output)
    else:
        verify_assets(root, args.tag, args.output)
    print('Release ' + args.command + ': verified')


if __name__ == '__main__':
    main()
