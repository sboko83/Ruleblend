"""Audit a git archive tar before publication; findings never include matched values."""
import argparse
import hashlib
from pathlib import PurePosixPath
import posixpath
import re
import sys
import tarfile
from urllib.parse import unquote, urlsplit


PRIVATE_FILES = {
    'AGENTS.md', 'CLAUDE.md', 'docs/PLAN.md', 'docs/ru/PLAN.md',
    'tools/run_phases.sh', 'tools/split_kt.py', 'local.properties',
}
PRIVATE_ROOTS = {
    'private', '.git', '.claude', '.codex', '.agents', '.vscode', '.idea',
    '.superpowers', '.zcode', '.gradle', '.kotlin',
}
SECRET_PATTERNS = {
    'private key': re.compile(r'-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    'service token': re.compile(
        r'\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,}'
        r'|glpat-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{20,}'
        r'|sk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}|AKIA[0-9A-Z]{16})\b'),
    'URL password': re.compile(r'https?://[^\s/:@]+:[^\s/@]+@'),
}
HOME_PATH = re.compile(r'(?:[A-Za-z]:[/\\]+Users[/\\]+|/Users/|/home/)[A-Za-z0-9_.-]+(?=[/\\\s"\'`]|$)')
LINK = re.compile(r'!?\[[^\]\n]*\]\((?:<([^>\n]+)>|([^\s]+?))(?:\s+"[^"\n]*")?\)')


def private_path(name):
    parts = PurePosixPath(name).parts
    return (name in PRIVATE_FILES or parts[0] in PRIVATE_ROOTS
            or name == 'docs/art' or name.startswith('docs/art/')
            or any(part in {'.swiftpm', '.build', 'build'} for part in parts)
            or name.endswith('.log'))


def audit(archive):
    """Return file count, blocking findings, and home paths requiring human review."""
    with tarfile.open(archive, 'r:') as tar:
        members = tar.getmembers()
        names = {member.name.rstrip('/') for member in members}
        findings, homes = [], []
        count = 0
        for member in members:
            name = member.name.rstrip('/')
            parts = PurePosixPath(name).parts
            if not parts or name.startswith('/') or '..' in parts or '\\' in name:
                findings.append(f'{name}: unsafe archive path')
                continue
            if private_path(name):
                findings.append(f'{name}: excluded path')
            if member.isdir():
                continue
            count += 1
            if not member.isfile():
                findings.append(f'{name}: unsupported archive entry')
                continue
            data = tar.extractfile(member).read()
            try:
                content = data.decode('utf-8')
            except UnicodeDecodeError:
                continue
            if '\x00' in content:
                continue
            for number, line in enumerate(content.splitlines(), 1):
                for label, pattern in SECRET_PATTERNS.items():
                    if pattern.search(line):
                        findings.append(f'{name}:{number}: {label}')
                if HOME_PATH.search(line):
                    homes.append(f'{name}:{number}: absolute home path')
            if not name.endswith('.md'):
                continue
            for match in LINK.finditer(content):
                target = match.group(1) or match.group(2)
                url = urlsplit(target)
                if url.scheme or url.netloc or not url.path:
                    continue
                path = unquote(url.path)
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), path))
                if path.startswith('/') or resolved == '..' or resolved.startswith('../'):
                    findings.append(f'{name}: link outside archive')
                elif resolved not in names:
                    number = content.count('\n', 0, match.start()) + 1
                    findings.append(f'{name}:{number}: missing local link destination')
        return count, sorted(set(findings)), sorted(set(homes))


def review_digest(archive, findings, homes):
    """Bind manual approval to findings and exact archive bytes, including binary assets."""
    digest = hashlib.sha256()
    with open(archive, 'rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    digest.update('\n'.join(findings + homes).encode('utf-8'))
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', help='Uncompressed tar created by git archive')
    parser.add_argument('--reviewed', metavar='SHA256',
                        help='Accept findings only after manual review of this exact archive')
    args = parser.parse_args()
    try:
        count, findings, homes = audit(args.archive)
        digest = review_digest(args.archive, findings, homes)
    except (OSError, tarfile.TarError, ValueError) as error:
        print(f'Cannot audit archive: {type(error).__name__}', file=sys.stderr)
        return 1
    if args.reviewed and args.reviewed != digest:
        print('FAIL review digest does not match this archive')
        return 1
    if args.reviewed:
        if any(not any(item.endswith(': ' + label) for label in SECRET_PATTERNS)
               for item in findings):
            print('FAIL excluded paths, archive structure and broken links cannot be accepted by review')
            return 1
        print(f'{count} files; {len(findings) + len(homes)} findings accepted by explicit manual review')
        return 0
    for finding in findings:
        print(f'FAIL {finding}')
    for home in homes:
        print(f'REVIEW {home}')
    print(f'{count} files; {len(findings)} blocking findings; {len(homes)} home-path locations to review')
    if findings or homes:
        print(f'Review digest: {digest}')
    return 1 if findings else 2 if homes else 0


if __name__ == '__main__':
    sys.exit(main())
