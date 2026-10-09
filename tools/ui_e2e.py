#!/usr/bin/env python3
"""Opt-in UI E2E runner. Never called by build/check/verify_ui or CI.

python3 tools/ui_e2e.py ui [--tests '*RootSmokeTest*'] [--timeout 240]
python3 tools/ui_e2e.py projects [--tests P08]
python3 tools/ui_e2e.py library [--tests L08]
python3 tools/ui_e2e.py conflicts [--tests O01]
python3 tools/ui_e2e.py groups [--tests G01]
python3 tools/ui_e2e.py overview [--tests F01]
python3 tools/ui_e2e.py git-sync [--tests Y01]
python3 tools/ui_e2e.py translation [--tests T01]
python3 tools/ui_e2e.py settings [--tests N01]
python3 tools/ui_e2e.py recovery [--tests R01]
python3 tools/ui_e2e.py translation-live --bundle app/build/compose/binaries/main/app/Ruleblend.app
python3 tools/ui_e2e.py smoke
python3 tools/ui_e2e.py desktop --bundle app/build/compose/binaries/main/app/Ruleblend.app
python3 tools/ui_e2e.py cleanup /private/tmp/ruleblend-e2e-...
python3 tools/ui_e2e.py cleanup   # every finished sandbox in the temp directory
"""
import argparse
import hashlib
import json
import os
import plistlib
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[1]
MARKER = '.ruleblend-e2e'


def inside(root, path):
    root = root.resolve(strict=True)
    candidate = Path(path).absolute()
    if not candidate.is_relative_to(root) or not candidate.resolve().is_relative_to(root):
        raise ValueError(f'Outside owned root: {path}')
    return candidate


def owned(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir() or not root.name.startswith('ruleblend-e2e-'):
        raise ValueError('Not an E2E root')
    marker = root / MARKER
    if marker.is_symlink() or marker.read_text(encoding='utf-8').strip() != root.name:
        raise ValueError('Missing or invalid ownership marker')
    return root.resolve()


def cleanup(root):
    def retry_readonly(function, path, error):
        if not isinstance(error, PermissionError):
            raise error
        os.chmod(path, 0o700)
        function(path)
    # rmtree does not follow directory symlinks; Git objects can be read-only on Windows.
    shutil.rmtree(owned(root), onexc=retry_readonly)


def sweep(parent=None):
    """Removes finished sandboxes; a run still in progress has no summary.json yet and is kept."""
    removed, kept = 0, 0
    for root in sorted(Path(parent or tempfile.gettempdir()).glob('ruleblend-e2e-*')):
        try:
            if not (owned(root) / 'summary.json').exists():
                raise ValueError('Run not finished')
        except (ValueError, OSError):
            kept += 1
            continue
        cleanup(root)
        removed += 1
    return removed, kept


def prepare():
    root = Path(tempfile.mkdtemp(prefix='ruleblend-e2e-')).resolve()
    (root / MARKER).write_text(root.name, encoding='utf-8', newline='\n')
    for relative in ('home/.ruleblend', 'home/.claude', 'home/.codex', 'home/.kimi',
                     'home/.pi/agent', 'home/.zcode', 'home/.agents', 'home/.config', 'work', 'tmp',
                     'projects/Проект A', 'projects/B', 'archives', 'upstream', 'remote', 'bin', 'artifacts'):
        (root / relative).mkdir(parents=True, exist_ok=True)
    (root / 'home/.gitconfig').write_text('[credential]\n\thelper =\n[user]\n\tname = E2E Fixture\n\temail = fixture@example.invalid\n', encoding='utf-8', newline='\n')
    (root / 'home/.netrc').write_text('', encoding='utf-8', newline='\n')
    (root / 'neighbor.txt').write_bytes(b'unchanged synthetic neighbor\n')
    # Safe executable fixtures: no user shell rc files and no real assistant process.
    for name in ('claude', 'codex', 'kimi', 'pi', 'zcode', 'rb-translate', 'shell', 'ruleblend-mcp'):
        stub = root / 'bin' / (name + ('.cmd' if os.name == 'nt' else ''))
        stub.write_text('@exit /b 0\n' if os.name == 'nt' else
                        '#!/bin/sh\nprintf "%s\\n" "$0" "$PWD" "$@" >> "' + str(root / 'artifacts/cli.log') + '"\nexit 0\n',
                        encoding='utf-8', newline='\n')
        stub.chmod(0o700)
    return root


def environment(root):
    home = root / 'home'
    suffix = '.cmd' if os.name == 'nt' else ''
    result = {'HOME': str(home), 'PATH': str(root / 'bin'), 'SHELL': str(root / ('bin/shell' + suffix)),
            'TMPDIR': str(root / 'tmp'), 'KIMI_CODE_HOME': str(home / '.kimi'),
            'CLAUDE_CONFIG_DIR': str(home / '.claude'), 'CODEX_HOME': str(home / '.codex'),
            'PI_CODING_AGENT_DIR': str(home / '.pi/agent'),
            'RULEBLEND_LIBRARY': str(home / '.ruleblend/library'),
            'RULEBLEND_TRANSLATE_HELPER': str(root / ('bin/rb-translate' + suffix)),
            'XDG_CONFIG_HOME': str(home / '.config'), 'GIT_CONFIG_GLOBAL': str(home / '.gitconfig'),
            'GIT_CONFIG_NOSYSTEM': '1', 'GIT_TERMINAL_PROMPT': '0', 'LANG': 'en_US.UTF-8',
            'RULEBLEND_E2E_REAL_HOME': str(Path.home()),
            'JAVA_TOOL_OPTIONS': f'-Duser.home="{home}" -Djava.io.tmpdir="{root / "tmp"}"'}
    if os.name == 'nt':
        result.update({key: os.environ[key] for key in ('SystemRoot', 'SystemDrive', 'WINDIR', 'COMSPEC', 'PATHEXT')
                       if key in os.environ})
        result.update(USERPROFILE=str(home), APPDATA=str(home / 'AppData/Roaming'),
                      LOCALAPPDATA=str(home / 'AppData/Local'), TEMP=str(root / 'tmp'), TMP=str(root / 'tmp'))
    return result


def stop(process):
    if process.poll() is None:
        if os.name == 'nt':
            subprocess.run([str(Path(os.environ['SystemRoot']) / 'System32/taskkill.exe'),
                            '/PID', str(process.pid), '/T', '/F'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
            process.wait(timeout=10)
            return
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def execute(command, log, timeout, cwd=REPO, env=None):
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=cwd, env=env, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            return process.wait(timeout=timeout), False
        except subprocess.TimeoutExpired:
            stop(process)
            return process.returncode, True
        finally:
            stop(process)


def verdict(results, exit_code, timed_out=False):
    """Count testcase outcomes, never infer success from stale/aggregate suite attributes."""
    counts = dict(passed=0, failed=0, skipped=0, blocked=0)
    try:
        for file in Path(results).glob('TEST-*.xml'):
            suite = ET.parse(file).getroot()
            cases = list(suite.iter('testcase'))
            if 'tests' in suite.attrib and int(suite.attrib['tests']) != len(cases):
                return dict(status='failed', reason='Incomplete testcase report', **counts)
            for case in cases:
                status = ('failed' if case.find('failure') is not None or case.find('error') is not None
                          else 'skipped' if case.find('skipped') is not None else 'passed')
                counts[status] += 1
    except (OSError, ValueError, ET.ParseError) as error:
        return dict(status='failed', reason=f'Invalid report: {error}', **counts)
    if timed_out:
        return dict(status='failed', reason='Runner timeout; suite incomplete', **counts)
    if exit_code != 0:
        return dict(status='failed', reason=f'Gradle exited {exit_code}', **counts)
    if counts['failed']:
        return dict(status='failed', reason='Scenario assertion failed', **counts)
    if not sum(counts.values()):
        return dict(status='failed', reason='No tests found', **counts)
    if counts['skipped']:
        return dict(status='skipped', reason='Suite has unexecuted scenarios', **counts)
    return dict(status='passed', reason='All selected scenarios completed', **counts)


def status_code(result):
    return 0 if result['status'] == 'passed' else 3 if result['status'] in ('blocked', 'skipped') else 1


def manifest(root):
    files = {}
    for path in sorted(root.rglob('*')):
        if path.is_file() and not path.is_symlink():
            files[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files


def run_ui(root, args, tests=None, stages=('first', 'restart')):
    runs = []
    selected = tests or [args.tests]
    worker_properties = []
    if any('Phase9Test.N11_' in (pattern or '') for pattern in selected):
        if sys.platform != 'darwin':
            return dict(status='blocked', reason='N11 packaged MCP sandbox currently requires macOS')
        code, timeout = execute([str(REPO / 'gradlew'), '--no-daemon', '--console=plain', ':app:createDistributable'],
                                root / 'package.log', args.timeout)
        if code != 0 or timeout:
            return dict(status='failed', reason='N11 packaged app build failed or timed out')
        bundle = REPO / 'app/build/compose/binaries/main/app/Ruleblend.app'
        binary = bundle / 'Contents/MacOS/Ruleblend'
        plist = bundle / 'Contents/Info.plist'
        expected_build = re.search(r'val appBuild = "([^"]+)"', (REPO / 'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
        if not binary.is_file() or not plist.is_file() or plistlib.loads(plist.read_bytes()).get('CFBundleVersion') != expected_build:
            return dict(status='failed', reason='N11 packaged app has the wrong build or is incomplete')
        isolated_bundle = root / 'bundle/Ruleblend.app'
        shutil.copytree(bundle, isolated_bundle, symlinks=True)
        worker_properties = [f'-Pe2eMcpBinary={isolated_bundle / "Contents/MacOS/Ruleblend"}',
                             f'-Pe2eRealHome={Path.home()}']
    for stage in stages:
        code, timeout = execute(
            [str(REPO / ('gradlew.bat' if os.name == 'nt' else 'gradlew')), '--no-daemon', '--console=plain', ':app:uiE2e',
             f'-Pe2eRoot={root}', f'-Pe2eStage={stage}', *worker_properties] +
            [item for pattern in selected for item in ('--tests', pattern)],
            root / f'gradle-{stage}.log', args.timeout)
        result = verdict(root / 'results' / stage, code, timeout)
        runs.append(dict(stage=stage, **result))
        if result['status'] != 'passed':
            return dict(status=result['status'], runs=runs)
        if stage == 'first' and any(f'Phase6Test.{case}_' in pattern for pattern in selected
                                    for case in ('X01', 'X02')):
            # Round trips import into a fresh config and library after the exporting JVM exits.
            shutil.move(root / 'home/.ruleblend', root / 'archives/exported-home')
            (root / 'home/.ruleblend').mkdir()
        if stage == 'first':
            for case in ('S03', 'S04', 'S05'):
                if any(f'Phase6Test.{case}_' in pattern for pattern in selected):
                    advance_phase6_git_fixture(root, case)
    # A cold restart is a separate process and independently decoded disk state.
    pids = [root / f'artifacts/{stage}/pid.txt' for stage in stages]
    if len(stages) > 1 and any(p.exists() for p in pids):
        assert all(p.exists() for p in pids), 'Incomplete cold restart evidence'
        assert len({p.read_text(encoding='utf-8') for p in pids}) == len(pids), 'Restart reused the JVM'
        if '*RootSmokeTest*' in selected:
            config = json.loads((root / 'home/.ruleblend/config.json').read_text(encoding='utf-8'))
            assert str(root / 'projects/Проект A') in config['projects']
    # Every scenario JVM, single-stage ones included, must be gone and release the process lock.
    for p in pids:
        if not p.exists():
            continue
        if process_alive(int(p.read_text(encoding='utf-8'))):
            raise AssertionError('Scenario JVM survived shutdown')
    lock_file = root / 'home/.ruleblend/ruleblend.lock'
    if lock_file.exists():
        with lock_file.open('a+b') as lock:
            if os.name == 'nt':
                import msvcrt
                lock.seek(0)
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    return dict(status='passed', runs=runs)


def process_alive(pid):
    if os.name == 'nt':
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
        kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            if ctypes.get_last_error() == 87:  # ERROR_INVALID_PARAMETER: PID no longer exists.
                return False
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            code = wintypes.DWORD()
            if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
                raise ctypes.WinError(ctypes.get_last_error())
            return code.value == 259  # STILL_ACTIVE
        finally:
            kernel.CloseHandle(handle)
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def real_fingerprints():
    home = Path.home()
    # Assistant runtime logs/sessions change while this task runs. Fingerprint the configuration
    # and installation surfaces Ruleblend can touch, not unrelated live conversation databases.
    paths = [home / name for name in ('.ruleblend', '.kitbash', '.claude/CLAUDE.md', '.claude/settings.json',
             '.claude/skills', '.claude/agents', '.claude.json', '.codex/AGENTS.md', '.codex/config.toml',
             '.codex/skills', '.codex/agents', '.kimi', '.pi/agent/AGENTS.md', '.pi/agent/skills',
             '.zcode', '.agents/skills')]
    override = os.environ.get('KIMI_CODE_HOME')
    if override:
        paths.append(Path(override))
    # Hash in memory; reports contain only digests, not real filenames or file contents.
    digests = []
    for path in paths:
        digest = hashlib.sha256()
        if path.is_dir():
            for p in sorted(path.rglob('*')):
                digest.update(str(p.relative_to(path)).encode())
                if p.is_symlink():
                    digest.update(os.readlink(p).encode())
                elif p.is_file():
                    digest.update(p.read_bytes())
        elif path.name == '.claude.json' and path.is_file():
            # Claude Code rewrites this file on its own all the time; Ruleblend only owns MCP entries.
            try:
                data = json.loads(path.read_text(encoding='utf-8'))
                owned = dict(root=data.get('mcpServers'), projects={
                    key: value.get('mcpServers') for key, value in (data.get('projects') or {}).items()
                    if isinstance(value, dict)})
                digest.update(json.dumps(owned, sort_keys=True).encode())
            except ValueError:
                digest.update(path.read_bytes())
        elif path.is_file():
            digest.update(path.read_bytes())
        else:
            digest.update(b'missing')
        digests.append(digest.hexdigest())
    return paths, digests


def run_desktop(root, args):
    if sys.platform != 'darwin':
        return dict(status='blocked', reason='macOS required')
    bundle = Path(args.bundle).resolve()
    binary = bundle / 'Contents/MacOS/Ruleblend'
    if not binary.is_file():
        return dict(status='blocked', reason='Packaged Ruleblend.app missing; build :app:createDistributable first')
    if not Path('/usr/bin/sandbox-exec').exists():
        return dict(status='blocked', reason='No enforceable native filesystem sandbox')
    ffmpeg = shutil.which('ffmpeg')
    if not ffmpeg:
        return dict(status='blocked', reason='ffmpeg required for native window capture')
    paths, before = real_fingerprints()
    # Native enforcement also applies to the packaged JVM and all descendants.
    profile = root / 'desktop.sb'
    profile.write_text('(version 1)\n(allow default)\n(deny network*)\n(deny file-write*)\n'
                       f'(allow file-write* (subpath {json.dumps(str(root))}) (literal "/dev/null"))\n'
                       + ''.join(f'(deny file-read* (subpath {json.dumps(str(p))}))\n' for p in paths), encoding='utf-8', newline='\n')
    # Prove that a denied write is really blocked before letting the application start.
    denied = root.parent / (root.name + '-escape')
    code, _ = execute(['/usr/bin/sandbox-exec', '-f', str(profile), '/usr/bin/touch', str(denied)],
                      root / 'sandbox-probe.log', 10, root / 'work', environment(root))
    if code == 0 or denied.exists():
        return dict(status='blocked', reason='Native isolation probe did not reject outside write')
    with (root / 'desktop.log').open('w') as log:
        process = subprocess.Popen(['/usr/bin/sandbox-exec', '-f', str(profile), str(binary)],
                                   cwd=root / 'work', env=environment(root), stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
        try:
            code, timeout = execute(['/usr/bin/osascript', '-l', 'JavaScript', str(REPO / 'tools/ui_e2e_desktop.js'),
                                     str(process.pid), str(root / 'projects/Проект A'), str(root / 'artifacts'), ffmpeg],
                                    root / 'accessibility.log', min(args.timeout, 180), root / 'work')
            result = desktop_verdict(root, code, timeout)
            if result['status'] == 'passed':
                assert process.wait(timeout=5) == 0, 'Packaged application did not exit cleanly'
        finally:
            stop(process)
            _, after = real_fingerprints()
            (root / 'real-home-digests.json').write_text(json.dumps(dict(before=before, after=after)), encoding='utf-8', newline='\n')
            if after != before:
                raise AssertionError('Real home fingerprint changed during desktop probe; isolation not accepted')
    return result


def phase9_mcp_call(root, profile, binary):
    """One real stdio MCP client while the packaged UI process stays open."""
    with (root / 'mcp-native.log').open('w') as log:
        process = subprocess.Popen(['/usr/bin/sandbox-exec', '-f', str(profile), str(binary), '--mcp'],
                                   cwd=root / 'work', env=environment(root), stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=log, text=True, start_new_session=True)
        try:
            def call(request):
                process.stdin.write(json.dumps(request) + '\n')
                process.stdin.flush()
                if not select.select([process.stdout], [], [], 20)[0]:
                    raise TimeoutError('Native MCP process did not reply')
                result = json.loads(process.stdout.readline())
                if result.get('error'):
                    raise RuntimeError(f'Native MCP error: {result["error"]}')
                return result

            call(dict(jsonrpc='2.0', id=1, method='initialize', params=dict(
                protocolVersion='2024-11-05', capabilities={}, clientInfo=dict(name='e2e', version='1'))))
            process.stdin.write(json.dumps(dict(jsonrpc='2.0', method='notifications/initialized')) + '\n')
            process.stdin.flush()
            call(dict(jsonrpc='2.0', id=2, method='tools/call', params=dict(
                name='create_rule', arguments=dict(name='Native MCP rule', content='First native body'))))
            call(dict(jsonrpc='2.0', id=3, method='tools/call', params=dict(
                name='update_rule', arguments=dict(id='native-mcp-rule', content='Second native body'))))
            file = root / 'home/.ruleblend/library/blocks/native-mcp-rule.md'
            assert 'Second native body' in file.read_text(encoding='utf-8')
        finally:
            stop(process)


def run_phase9_desktop(root, args):
    case = args.tests
    if sys.platform != 'darwin':
        return dict(status='blocked', reason='macOS required')
    bundle = Path(args.bundle).resolve()
    binary = bundle / 'Contents/MacOS/Ruleblend'
    if not binary.is_file():
        return dict(status='blocked', reason='Packaged Ruleblend.app missing; build :app:createDistributable first')
    if not Path('/usr/bin/sandbox-exec').exists():
        return dict(status='blocked', reason='No enforceable native filesystem sandbox')
    ffmpeg = shutil.which('ffmpeg')
    if not ffmpeg:
        return dict(status='blocked', reason='ffmpeg required for native window capture')
    if case == 'N09':
        long_project = root / 'projects/Очень длинное название проекта для узкого окна'
        long_project.mkdir()
        foreign_skill = long_project / '.agents/skills/foreign-skill'
        foreign_skill.mkdir(parents=True)
        (foreign_skill / 'SKILL.md').write_text('---\nname: foreign-skill\ndescription: Foreign\n---\nForeign skill body\n', encoding='utf-8', newline='\n')
        (root / 'home/.ruleblend/config.json').write_text(json.dumps(dict(
            window=dict(width=620, height=470, x=60, y=80),
            columnWidths=dict(libraryFacets=330, libraryInspector=410,
                              integrationSidebar=260, integrationFoundPaneHeight=160, placePalette=410))), encoding='utf-8', newline='\n')
        setup = run_ui(root, args, ['dev.ruleblend.e2e.Phase9Test.N09_setup*'], ('setup',))
        if setup['status'] != 'passed':
            return dict(status=setup['status'], reason='N09 Compose fixture failed', setup=setup)
    expected_version = re.search(r'val appVersion = "([^"]+)"', (REPO / 'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
    paths, before = real_fingerprints()
    profile = root / 'desktop.sb'
    profile.write_text('(version 1)\n(allow default)\n(deny network*)\n(deny file-write*)\n'
                       f'(allow file-write* (subpath {json.dumps(str(root))}) (literal "/dev/null"))\n'
                       + ''.join(f'(deny file-read* (subpath {json.dumps(str(p))}))\n' for p in paths), encoding='utf-8', newline='\n')
    denied = root.parent / (root.name + '-escape')
    code, _ = execute(['/usr/bin/sandbox-exec', '-f', str(profile), '/usr/bin/touch', str(denied)],
                      root / 'sandbox-probe.log', 10, root / 'work', environment(root))
    if code == 0 or denied.exists():
        return dict(status='blocked', reason='Native isolation probe did not reject outside write')
    stages = ('first', 'restart') if case in ('N08', 'N10') else ('first',)
    runs, pids = [], []
    try:
        for stage in stages:
            artifacts = root / 'artifacts' / stage
            artifacts.mkdir()
            with (root / f'desktop-{stage}.log').open('w') as log:
                process = subprocess.Popen(['/usr/bin/sandbox-exec', '-f', str(profile), str(binary)],
                                           cwd=root / 'work', env=environment(root), stdout=log,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                pids.append(process.pid)
                try:
                    def drive(mode):
                        result_path = artifacts / f'{mode}-result.json'
                        code, timeout = execute(
                            ['/usr/bin/osascript', '-l', 'JavaScript', str(REPO / 'tools/ui_e2e_phase9_desktop.js'),
                             str(process.pid), mode, stage, str(root / 'projects/Проект A'),
                             str(artifacts), ffmpeg, expected_version],
                            root / f'ax-{stage}-{mode}.log', args.timeout, root / 'work')
                        result = json.loads(result_path.read_text(encoding='utf-8')) if result_path.exists() else dict(status='failed', reason='Native driver did not finish')
                        if timeout or code != 0:
                            result = dict(status='failed', reason='Native driver timed out or exited with error')
                        return result

                    if case == 'N11':
                        result = drive('N11-prepare')
                        if result['status'] == 'passed':
                            phase9_mcp_call(root, profile, binary)
                            result = drive('N11-verify')
                    else:
                        result = drive(case)
                    if result['status'] == 'passed' and process.wait(timeout=10) != 0:
                        result = dict(status='failed', reason='Packaged app did not close cleanly')
                    runs.append(dict(stage=stage, **result))
                finally:
                    stop(process)
            if runs[-1]['status'] != 'passed':
                break
    finally:
        _, after = real_fingerprints()
        (root / 'real-home-digests.json').write_text(json.dumps(dict(before=before, after=after)), encoding='utf-8', newline='\n')
        if after != before:
            raise AssertionError('Real home fingerprint changed during native phase 9')
    if any(run['status'] != 'passed' for run in runs):
        return dict(status=runs[-1]['status'], runs=runs)
    if len(set(pids)) != len(stages):
        return dict(status='failed', reason='Cold restart reused process id', runs=runs)
    if case == 'N08':
        config = json.loads((root / 'home/.ruleblend/config.json').read_text(encoding='utf-8'))
        assert config['projects'] == [str(root / 'projects/Проект A')]
        exported = Path((root / 'artifacts/first/exported-zip.txt').read_text(encoding='utf-8'))
        assert inside(root, exported).is_file() and exported.suffix == '.zip'
    if case == 'N09':
        config = json.loads((root / 'home/.ruleblend/config.json').read_text(encoding='utf-8'))
        assert config['columnWidths']['libraryFacets'] == 330
        assert config['columnWidths']['placePalette'] == 410
    if case == 'N10':
        plist = plistlib.loads((bundle / 'Contents/Info.plist').read_bytes())
        assert plist['CFBundleShortVersionString'] == expected_version
        assert (bundle / 'Contents/Resources' / plist['CFBundleIconFile']).is_file()
        first = json.loads((root / 'artifacts/first/window-geometry.json').read_text(encoding='utf-8'))
        restored = json.loads((root / 'artifacts/restart/window-geometry.json').read_text(encoding='utf-8'))
        assert all(abs(a - b) <= 5 for a, b in zip(first, restored)), 'Window frame was not restored'
    return dict(status='passed', runs=runs)


def run_translation_live(root, args):
    """AX drives the packaged app; only synthetic library data is prepared outside its UI."""
    if sys.platform != 'darwin':
        return dict(status='blocked', reason='macOS required')
    bundle = Path(args.bundle).resolve()
    binary = bundle / 'Contents/MacOS/Ruleblend'
    helper = bundle / 'Contents/app/resources/rb-translate'
    if not binary.is_file() or not helper.is_file() or not os.access(helper, os.X_OK):
        return dict(status='blocked', reason='Packaged app or executable helper missing; build :app:createDistributable')
    if not Path('/usr/bin/sandbox-exec').exists():
        return dict(status='blocked', reason='No enforceable native filesystem sandbox')
    paths, before = real_fingerprints()
    profile = root / 'desktop.sb'
    profile.write_text('(version 1)\n(allow default)\n(deny network*)\n(deny file-write*)\n'
                       f'(allow file-write* (subpath {json.dumps(str(root))}) (literal "/dev/null"))\n'
                       + ''.join(f'(deny file-read* (subpath {json.dumps(str(p))}))\n' for p in paths), encoding='utf-8', newline='\n')
    denied = root.parent / (root.name + '-escape')
    code, _ = execute(['/usr/bin/sandbox-exec', '-f', str(profile), '/usr/bin/touch', str(denied)],
                      root / 'sandbox-probe.log', 10, root / 'work', environment(root))
    if code == 0 or denied.exists():
        return dict(status='blocked', reason='Native isolation probe did not reject outside write')
    env = environment(root)
    env.pop('RULEBLEND_TRANSLATE_HELPER')  # Verify the bundle's own lookup, never the stub fixture.
    pairs = []
    for index, (source, target) in enumerate((('en', 'ru'), ('ru', 'en')), 1):
        request = json.dumps(dict(id=index, op='status', **{'from': source, 'to': target}, quality='low')) + '\n'
        try:
            check = subprocess.run(['/usr/bin/sandbox-exec', '-f', str(profile), str(helper)],
                                   input=request, text=True, capture_output=True, timeout=30,
                                   cwd=root / 'work', env=env)
            response = json.loads(check.stdout.strip())
            if check.returncode != 0 or not response.get('ok'):
                raise ValueError(check.stderr[:300] or response)
            pairs.append(dict(pair=f'{source}->{target}', status=response.get('status')))
        except (OSError, ValueError, subprocess.TimeoutExpired) as error:
            return dict(status='blocked', reason=f'Helper preflight failed: {error}')
    (root / 'artifacts/live-pairs.json').write_text(json.dumps(pairs, indent=2), encoding='utf-8', newline='\n')
    if pairs[0]['status'] != 'installed':
        return dict(status='blocked', reason='EN->RU language pack not installed', pairs=pairs)

    blocks = root / 'home/.ruleblend/library/blocks'
    blocks.mkdir(parents=True, exist_ok=True)
    (blocks / 'live-translation-rule.md').write_text(
        '---\nname: "Live Translation Rule"\ndescription: ""\nversion: 1\ntype: "rule"\n'
        'favorite: false\nheading: ""\nheadingLevel: 2\n---\n\nPlease save this rule.\n\n'
        'Пожалуйста, проверьте этот файл.\n\nUse `--safe` here.\n', encoding='utf-8', newline='\n')
    # English chrome: every Cyrillic string on screen is then library text or its translation.
    (root / 'home/.ruleblend/config.json').write_text('{"language": "en"}\n', encoding='utf-8', newline='\n')
    stages = ('first', 'restart') if args.tests == 'T09' else ('first',)
    runs = []
    pids = []
    try:
        for stage in stages:
            stage_artifacts = root / f'artifacts/{stage}'
            stage_artifacts.mkdir()
            with (root / f'live-{stage}.log').open('w') as log:
                process = subprocess.Popen(['/usr/bin/sandbox-exec', '-f', str(profile), str(binary)],
                                           cwd=root / 'work', env=env, stdout=log,
                                           stderr=subprocess.STDOUT, start_new_session=True)
                pids.append(process.pid)
                try:
                    code, timeout = execute(
                        ['/usr/bin/osascript', '-l', 'JavaScript', str(REPO / 'tools/ui_e2e_translation_live.js'),
                         str(process.pid), str(stage_artifacts), args.tests,
                         'true' if pairs[1]['status'] == 'installed' else 'false'],
                        root / f'live-accessibility-{stage}.log', min(args.timeout, 300), root / 'work')
                    outcome = stage_artifacts / 'translation-live-result.json'
                    result = json.loads(outcome.read_text(encoding='utf-8')) if outcome.exists() else dict(status='failed', reason='Native driver did not finish')
                    if timeout or code != 0:
                        result = dict(status='failed', reason='Native translation driver timeout or exit failure')
                    if result['status'] == 'passed' and process.wait(timeout=10) != 0:
                        result = dict(status='failed', reason='Packaged app did not close cleanly')
                    runs.append(dict(stage=stage, **result))
                finally:
                    stop(process)
            if result['status'] != 'passed':
                break
    finally:
        _, after = real_fingerprints()
        (root / 'real-home-digests.json').write_text(json.dumps(dict(before=before, after=after)), encoding='utf-8', newline='\n')
        if after != before:
            raise AssertionError('Real home fingerprint changed during live translation')
    if any(run['status'] != 'passed' for run in runs):
        return dict(status=runs[-1]['status'], runs=runs, pairs=pairs)
    if len(set(pids)) != len(stages):
        return dict(status='failed', reason='Cold restart reused application PID', runs=runs, pairs=pairs)
    if args.tests == 'T08' and pairs[1]['status'] != 'installed':
        return dict(status='blocked', reason='RU->EN language pack not installed; EN->RU native flow passed',
                    runs=runs, pairs=pairs)
    return dict(status='passed', runs=runs, pairs=pairs)


def desktop_verdict(root, exit_code, timed_out):
    if timed_out:
        return dict(status='failed', reason='Native driver timeout; suite incomplete')
    if exit_code != 0:
        return dict(status='failed', reason=f'Native driver exited {exit_code}')
    result = json.loads((root / 'artifacts/desktop-result.json').read_text(encoding='utf-8'))
    if result['status'] == 'passed':
        required = ['launch', 'folder-dialog', 'choose-folder', 'keyboard-input', 'screenshot', 'close']
        assert result['completed'] == required, 'Incomplete native sequence'
        config = json.loads((root / 'home/.ruleblend/config.json').read_text(encoding='utf-8'))
        assert config['projects'] == [str(root / 'projects/Проект A')], 'Native picker selected an unexpected path'
        assert (root / 'artifacts/desktop.png').stat().st_size > 0, 'Missing native screenshot'
    return result


def prepare_git_skill_fixture(root):
    upstream = root / 'upstream'
    skill = upstream / 'skills' / 'imported-skill'
    skill.mkdir(parents=True)
    (skill / 'SKILL.md').write_text('---\nname: imported-skill\ndescription: Synthetic upstream\n---\nUpstream body\n', encoding='utf-8', newline='\n')
    (skill / 'tool.txt').write_text('upstream resource\n', encoding='utf-8', newline='\n')
    git_env = environment(root)
    git_binary = shutil.which('git')
    if not git_binary:
        raise RuntimeError('Git is required for the local L10 fixture')
    for command in (['init', '-q'], ['add', '.'], ['commit', '-q', '-m', 'Synthetic upstream']):
        subprocess.run([git_binary, '-C', str(upstream), *command], check=True, env=git_env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def prepare_phase6_git_fixture(root):
    upstream = root / 'upstream'
    for name in ('first-skill', 'second-skill'):
        skill = upstream / 'skills' / name
        skill.mkdir(parents=True)
        (skill / 'SKILL.md').write_text(f'---\nname: {name}\ndescription: Synthetic upstream\n---\n{name} v1\n', encoding='utf-8', newline='\n')
        (skill / 'references').mkdir()
        (skill / 'references' / 'guide.txt').write_text(f'{name} reference v1\n', encoding='utf-8', newline='\n')
        script = skill / 'scripts' / 'run.sh'
        script.parent.mkdir()
        script.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8', newline='\n')
        script.chmod(0o755)
    git_binary = shutil.which('git')
    if not git_binary:
        raise RuntimeError('Git is required for the local phase-6 fixture')
    for command in (['init', '-q'], ['config', 'core.autocrlf', 'false'], ['add', '.'],
                    ['update-index', '--chmod=+x', 'skills/first-skill/scripts/run.sh', 'skills/second-skill/scripts/run.sh'],
                    ['commit', '-q', '-m', 'Initial skills']):
        subprocess.run([git_binary, '-C', str(upstream), *command], check=True, env=environment(root),
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def advance_phase6_git_fixture(root, case):
    upstream = root / 'upstream'
    if case in ('S03', 'S05'):
        for name in ('first-skill', 'second-skill'):
            skill = upstream / 'skills' / name
            (skill / 'SKILL.md').write_text(f'---\nname: {name}\ndescription: Synthetic upstream\n---\n{name} v2\n', encoding='utf-8', newline='\n')
            (skill / 'references' / 'guide.txt').write_text(f'{name} reference v2\n', encoding='utf-8', newline='\n')
    elif case == 'S04':
        shutil.rmtree(upstream / 'skills' / 'first-skill')
    else:
        return
    git_binary = shutil.which('git')
    for command in (['add', '-A'], ['commit', '-q', '-m', 'Advance source']):
        subprocess.run([git_binary, '-C', str(upstream), *command], check=True, env=environment(root),
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def run_one(args, tests=None, stages=('first', 'restart')):
    root = prepare()
    print(f'E2E sandbox: {root}', flush=True)
    try:
        if tests and any('Phase10Test.R06_' in pattern for pattern in tests):
            # A real legacy-only first launch must not see a precreated current home.
            shutil.rmtree(root / 'home/.ruleblend')
        if tests and any('Phase2Test.L10_' in pattern for pattern in tests):
            prepare_git_skill_fixture(root)
        if tests and any(f'Phase6Test.{case}_' in pattern for pattern in tests
                         for case in ('X01', 'X02', 'S01', 'S02', 'S03', 'S04', 'S05')):
            prepare_phase6_git_fixture(root)
        if args.suite == 'desktop':
            result = run_desktop(root, args) if args.tests == 'H08' else run_phase9_desktop(root, args)
        elif args.suite == 'translation-live':
            result = run_translation_live(root, args)
        else:
            result = run_ui(root, args, tests, stages)
        assert (root / 'neighbor.txt').read_bytes() == b'unchanged synthetic neighbor\n'
    except Exception as error:
        result = dict(status='failed', reason=str(error))
    (root / 'manifest.json').write_text(json.dumps(manifest(root), indent=2), encoding='utf-8', newline='\n')
    (root / 'summary.json').write_text(json.dumps(result, indent=2), encoding='utf-8', newline='\n')
    reports = REPO / 'build/ui-e2e' / root.name
    reports.mkdir(parents=True)
    for name in ('summary.json', 'manifest.json', 'real-home-digests.json', 'desktop.log', 'accessibility.log'):
        if (root / name).exists():
            shutil.copy2(root / name, reports / name)
    if (root / 'artifacts').exists():
        shutil.copytree(root / 'artifacts', reports / 'artifacts')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print(f'Report: {reports}')
    if result['status'] == 'passed':
        cleanup(root)
        return 0
    print(f'Sandbox retained: {root}')
    return status_code(result)


def run_report_contract():
    """R05: an earlier failed report survives a successful isolated retry by scenario id."""
    failed = prepare()
    retried = prepare()
    successful = False
    try:
        first_results = failed / 'results/first'
        first_results.mkdir(parents=True)
        (first_results / 'TEST-fixture.xml').write_text(
            '<testsuite tests="1"><testcase name="R05"><failure/></testcase></testsuite>', encoding='utf-8', newline='\n')
        first = verdict(first_results, 1)
        (failed / 'summary.json').write_text(json.dumps(first), encoding='utf-8', newline='\n')
        retry_results = retried / 'results/first'
        retry_results.mkdir(parents=True)
        (retry_results / 'TEST-fixture.xml').write_text(
            '<testsuite tests="1"><testcase name="R05"/></testsuite>', encoding='utf-8', newline='\n')
        retry = verdict(retry_results, 0)
        (retried / 'summary.json').write_text(json.dumps(retry), encoding='utf-8', newline='\n')
        assert first['status'] == 'failed' and retry['status'] == 'passed'
        assert failed != retried and json.loads((failed / 'summary.json').read_text(encoding='utf-8')) == first
        assert verdict(retry_results, 0, True)['status'] == 'failed'
        (retry_results / 'TEST-fixture.xml').write_text('<testsuite tests="1"><testcase><skipped/></testcase></testsuite>', encoding='utf-8', newline='\n')
        assert verdict(retry_results, 0)['status'] == 'skipped'
        (retry_results / 'TEST-fixture.xml').unlink()
        assert verdict(retry_results, 0)['status'] == 'failed'
        assert status_code(dict(status='blocked')) == 3
        reports = REPO / 'build/ui-e2e' / failed.name
        reports.mkdir(parents=True)
        shutil.copy2(failed / 'summary.json', reports / 'synthetic-first-failure.json')
        (reports / 'r05-contract.json').write_text(json.dumps(dict(first=first, retry=retry,
            skipped='skipped', blocked='blocked', timed_out='failed', empty='failed',
            retained=str(failed)), indent=2), encoding='utf-8', newline='\n')
        print(f'R05 passed: distinct report outcomes; synthetic failed sandbox retained: {failed}')
        successful = True
        return 0
    finally:
        if not successful:
            cleanup(failed)
        cleanup(retried)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('suite', choices=['ui', 'smoke', 'projects', 'library', 'conflicts', 'groups', 'overview', 'archives', 'git-skills', 'git-sync', 'translation', 'settings', 'recovery', 'translation-live', 'desktop', 'cleanup'])
    parser.add_argument('root', nargs='?')
    parser.add_argument('--tests')
    parser.add_argument('--timeout', type=int, default=240)
    parser.add_argument('--bundle')
    args = parser.parse_args()
    if args.suite == 'cleanup':
        if args.root:
            cleanup(args.root)
        else:
            removed, kept = sweep()
            print(f'Removed {removed} finished sandboxes; kept {kept} unfinished or foreign')
        return 0
    if args.suite in ('desktop', 'translation-live') and not args.bundle:
        parser.error('--bundle is required')
    if args.suite == 'translation-live':
        scenarios = ['T08', 'T09']
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown translation-live scenario')
        results = []
        for case in scenarios:
            args.tests = case
            results.append(run_one(args))
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'desktop':
        scenarios = ['H08', 'N08', 'N09', 'N10', 'N11']
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown desktop scenario')
        results = []
        for case in scenarios:
            args.tests = case
            results.append(run_one(args))
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'projects':
        scenarios = [f'P{number:02d}' for number in range(1, 13)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown projects scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase1Test.{case}_*'],
                           ('first', 'restart') if case in ('P03', 'P07', 'P10') else ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'conflicts':
        scenarios = [f'O{number:02d}' for number in range(1, 13)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown conflicts scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase3Test.{case}_*'], ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'library':
        scenarios = [f'L{number:02d}' for number in range(1, 13)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown library scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase2Test.{case}_*'], ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'groups':
        scenarios = [f'G{number:02d}' for number in range(1, 10)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown groups scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase4Test.{case}_*'],
                           ('first', 'restart') if case == 'G07' else ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'overview':
        scenarios = [f'F{number:02d}' for number in range(1, 10)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown overview scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase5Test.{case}_*'],
                           ('first', 'restart') if case == 'F09' else ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite in ('archives', 'git-skills'):
        prefix = 'X' if args.suite == 'archives' else 'S'
        scenarios = [f'{prefix}{number:02d}' for number in range(1, 6)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error(f'Unknown {args.suite} scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase6Test.{case}_*'],
                           ('first', 'manual-restart', 'auto-restart') if case == 'S05'
                           else ('first', 'restart') if case in ('X01', 'X02', 'S03', 'S04') else ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'git-sync':
        scenarios = [f'Y{number:02d}' for number in range(1, 11)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown git-sync scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase7Test.{case}_*'],
                           ('first', 'restart') if case in ('Y09', 'Y10') else ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'translation':
        scenarios = [f'T{number:02d}' for number in range(1, 8)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown translation scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase8Test.{case}_*'], ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'settings':
        scenarios = [f'N{number:02d}' for number in (1, 2, 3, 4, 5, 6, 7, 11)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown settings scenario')
        results = [run_one(args, [f'dev.ruleblend.e2e.Phase9Test.{case}_*'],
                           ('first', 'restart', 'settled') if case == 'N05' else
                           ('first', 'restart') if case in ('N01', 'N02', 'N04') else ('first',))
                   for case in scenarios]
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'recovery':
        scenarios = [f'R{number:02d}' for number in range(1, 7)]
        if args.tests:
            scenarios = [case for case in scenarios if case == args.tests.upper()]
            if not scenarios:
                parser.error('Unknown recovery scenario')
        results = []
        for case in scenarios:
            if case == 'R05':
                results.append(run_report_contract())
                continue
            if case == 'R03':
                # The full smoke twice, each on its own clean root with a cold restart: nothing may
                # carry over between runs or reach the real home.
                _, before = real_fingerprints()
                for _ in range(2):
                    results.append(run_one(args, ['dev.ruleblend.e2e.Phase1Test.P07_*']))
                _, after = real_fingerprints()
                if after != before:
                    print('R03 failed: real home fingerprint changed during the repeated smoke')
                    results.append(1)
                continue
            results.append(run_one(args, [f'dev.ruleblend.e2e.Phase10Test.{case}_*'],
                                   ('first', 'restart') if case in ('R01', 'R06') else ('first',)))
        return 1 if 1 in results else 3 if 3 in results else 0
    if args.suite == 'smoke':
        return run_one(args, ['dev.ruleblend.e2e.Phase1Test.P07_*'])
    if args.suite == 'ui':
        tests = [args.tests] if args.tests else ['*RootSmokeTest*', '*SelectorContractTest*', '*SandboxContractTest*']
        return run_one(args, tests)
    return run_one(args)


if __name__ == '__main__':
    sys.exit(main())
