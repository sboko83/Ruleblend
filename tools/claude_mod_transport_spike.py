#!/usr/bin/env python3
"""Offline macOS transport gate. Run --dry-run first; evidence stays in /private/tmp.

Uses the installed Claude CLI, stdlib and sandbox-exec. No prompts are submitted,
no real profile is used, and no plugin test/eval or dependency installer runs.
"""

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time

PLUGIN = 'rb-transport-fixture'
MARKET = 'rb-transport-spike'
PLUGIN_ID = f'{PLUGIN}@{MARKET}'
COMPANION = 'rb-transport-companion'
FAKE_KEY = 'sk-ant-offline-transport-fixture-no-network'


def isolation_probe(root):
    """Run inside the same sandbox before any CLI operation."""
    for path, mode in [(str(Path.home()), 'read'),
                       (str(root.parent / (root.name + '-outside')), 'write')]:
        try:
            if mode == 'read':
                os.listdir(path)
            else:
                with open(path, 'xb'):
                    pass
        except PermissionError:
            pass
        else:
            raise AssertionError(f'{mode} outside fixture was allowed')
    try:
        socket.create_connection(('127.0.0.1', 9), timeout=1)
    except PermissionError:
        pass
    else:
        raise AssertionError('network was allowed')
    try:
        subprocess.run(['/bin/sh', '-c', 'exit 0'], check=True)
    except PermissionError:
        pass
    else:
        raise AssertionError('child command was allowed')
    lib = ctypes.CDLL('/usr/lib/libSystem.B.dylib')
    port = ctypes.c_uint.in_dll(lib, 'bootstrap_port')
    result = ctypes.c_uint()
    assert lib.bootstrap_look_up(port, b'com.apple.securityd', ctypes.byref(result)) != 0
    print('User reads, outside writes, network, child commands and Keychain IPC denied')


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    os.replace(temporary, path)


def fingerprint(root):
    """Length-framed paths/bytes and portable executable intent; never versions."""
    digest = hashlib.sha256()
    for file in sorted(p for p in root.rglob('*') if p.is_file()):
        assert not file.is_symlink()
        path = file.relative_to(root).as_posix().encode()
        content = file.read_bytes()
        digest.update(len(path).to_bytes(8, 'big'))
        digest.update(path)
        digest.update(bytes([bool(file.stat().st_mode & 0o111)]))
        digest.update(len(content).to_bytes(8, 'big'))
        digest.update(content)
    return digest.hexdigest()


class Session:
    """Observe loader evidence without a model request; reap the whole process group."""

    def __init__(self, spike, label):
        self.spike = spike
        self.log = spike.root / f'{label}.log'
        self.output = spike.root / f'{label}.terminal'
        self.master, slave = pty.openpty()
        self.process = subprocess.Popen(
            spike.command('--debug-file', str(self.log)),
            cwd=spike.root, env=spike.env, stdin=slave, stdout=slave, stderr=slave,
            start_new_session=True,
        )
        os.close(slave)

    def read_log(self):
        return self.log.read_text() if self.log.exists() else ''

    def pump(self, duration=0.1):
        if select.select([self.master], [], [], duration)[0]:
            try:
                data = os.read(self.master, 65536)
            except OSError:
                data = b''
            with self.output.open('ab') as stream:
                stream.write(data)

    def wait_for(self, text, after=0):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            self.pump()
            log = self.read_log()[after:]
            if text in log:
                return log
            if self.process.poll() is not None:
                break
        raise AssertionError(f'Missing loader evidence {text!r}; see {self.log}')

    def reload(self):
        offset = len(self.read_log())
        os.write(self.master, b'/reload-plugins\r')
        return offset

    def close(self):
        if self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(self.process.pid, signal.SIGKILL)
                self.process.wait(timeout=2)
        os.close(self.master)


class Spike:
    def __init__(self, cli):
        self.root = Path(tempfile.mkdtemp(prefix='ruleblend-mod-spike-', dir='/private/tmp'))
        self.cli_path = Path(cli).resolve()
        self.profile = self.root / 'profile'
        self.market = self.root / 'runtime'
        self.catalog = self.market / '.claude-plugin/marketplace.json'
        self.library = self.root / 'library'
        self.checks = []
        self.transcript = []
        self.sessions = []
        python = Path(sys.executable).resolve()
        python_app = python.parent.parent / 'Resources/Python.app/Contents/MacOS/Python'
        executable_rules = ' '.join(f'(literal {json.dumps(str(p))})'
                                    for p in [self.cli_path, python, python_app])
        self.sandbox = self.root / 'isolation.sb'
        self.sandbox.write_text(f'''(version 1)
(allow default)
(deny network*)
(deny file-read* (subpath "/Users") (subpath {json.dumps(str(Path.home()))})
 (subpath "/Library/Application Support/ClaudeCode") (subpath "/Library/Keychains"))
(deny file-write*)
(allow file-read* (subpath {json.dumps(str(self.root))}))
(allow file-write* (subpath {json.dumps(str(self.root))})
 (literal "/dev/null") (literal "/dev/tty"))
(deny mach-lookup (global-name "com.apple.securityd")
 (global-name "com.apple.securityd.xpc") (global-name "com.apple.cfnetwork.AuthBrokerAgent"))
(deny process-exec)
(allow process-exec {executable_rules})
''')
        self.env = {
            'PATH': '/usr/bin:/bin', 'TERM': 'xterm-256color',
            'TMPDIR': str(self.root), 'CLAUDE_CONFIG_DIR': str(self.profile),
            'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC': '1', 'DISABLE_AUTOUPDATER': '1',
            'ANTHROPIC_API_KEY': FAKE_KEY, 'CLAUDE_CODE_SKIP_API_KEY_VALIDATION': '1',
            'CLAUDE_CODE_REMOTE_SETTINGS_PATH': str(self.root / 'remote-settings.json'),
        }
        atomic_json(self.root / 'remote-settings.json', {})
        atomic_json(self.profile / '.claude.json', {
            'hasCompletedOnboarding': True, 'theme': 'dark', 'hasAcknowledgedCost': True,
            'customApiKeyResponses': {'approved': [FAKE_KEY[-20:]], 'rejected': []},
            'projects': {str(self.root): {'hasTrustDialogAccepted': True,
                                         'hasCompletedProjectOnboarding': True}},
        })

    def command(self, *args):
        return ['sandbox-exec', '-f', str(self.sandbox), str(self.cli_path),
                '--setting-sources', 'user', *args]

    def cli(self, *args, success=True):
        result = subprocess.run(self.command('plugin', *args), cwd=self.root, env=self.env,
                                capture_output=True, text=True, timeout=25)
        self.transcript.append({'args': args, 'exit': result.returncode,
                                'stdout': result.stdout, 'stderr': result.stderr})
        if success:
            assert result.returncode == 0, self.transcript[-1]
        else:
            assert result.returncode != 0, self.transcript[-1]
        if '--json' in args:
            if args[0] == 'list' or args[:2] == ('marketplace', 'list'):
                return json.loads(result.stdout)
            return json.loads(result.stdout.splitlines()[-1])
        return result.stdout

    def passed(self, name):
        self.checks.append(name)
        print(f'PASS {name}', flush=True)

    def package(self, path, revision, hooks='standard', name=PLUGIN):
        manifest = {'name': name, 'version': '1.0.0', 'description': 'Inert transport fixture',
                    'author': {'name': 'Ruleblend'}}
        if hooks == 'custom':
            manifest['hooks'] = './config/custom.json'
            hooks_file = path / 'config/custom.json'
        elif hooks == 'inline':
            manifest['hooks'] = {'modules': ['./register.js']}
            hooks_file = None
        else:
            hooks_file = path / 'hooks/hooks.json'
        atomic_json(path / '.claude-plugin/plugin.json', manifest)
        if hooks_file:
            atomic_json(hooks_file, {'modules': ['./register.js']})
            module = hooks_file.parent / 'register.js'
        else:
            module = path / 'register.js'
        events = ['session.start'] if revision == 'A' else ['session.start', 'session.end']
        module.write_text('export function register(on) {\n' + ''.join(
            f"  on('{event}', async ($, e, next) => next(e));\n" for event in events) + '}\n')
        (path / 'revision.txt').write_text(revision)

    def publish(self, revision):
        atomic_json(self.catalog, {
            'name': MARKET, 'owner': {'name': 'Ruleblend'},
            'plugins': [{'name': PLUGIN, 'source': f'./snapshots/{revision}',
                         'description': 'Inert transport fixture'},
                        {'name': COMPANION, 'source': './plugins/companion',
                         'description': 'Unchanged nested monorepo fixture'}],
        })

    def installed(self):
        return next(item for item in self.cli('list', '--json') if item['id'] == PLUGIN_ID)

    def check_static_operations(self):
        """A top-level throw exposes any accidental evaluation before a session starts."""
        name = 'rb-static-guard'
        guard = self.market / 'plugins/validation-only'
        self.package(guard, 'A', name=name)
        (guard / 'hooks/register.js').write_text(
            "throw new Error('STATIC_OPERATIONS_MUST_NOT_EXECUTE');\n"
            "export function register(on) {}\n")
        self.cli('validate', str(guard))
        self.publish('A')
        catalog = json.loads(self.catalog.read_text())
        catalog['plugins'].append({'name': name, 'source': './plugins/validation-only'})
        atomic_json(self.catalog, catalog)
        self.cli('marketplace', 'add', str(self.market), '--scope', 'user', '--json')
        self.cli('install', f'{name}@{MARKET}', '--scope', 'user', '--json')
        assert any(item['id'] == f'{name}@{MARKET}' for item in self.cli('list', '--json'))
        self.cli('uninstall', f'{name}@{MARKET}', '--scope', 'user', '--json')
        self.cli('marketplace', 'remove', MARKET, '--json')
        assert self.cli('list', '--json') == []
        self.publish('A')
        self.passed('validate install and list do not evaluate top-level module code')

    def session(self, label, path, revision, plugin_id=PLUGIN_ID):
        session = Session(self, label)
        self.sessions.append(session)
        session.wait_for(str(path))
        events = 'session.start' if revision == 'A' else 'session.start,session.end'
        session.wait_for(f'hooks module {plugin_id} loaded')
        assert f'events: {events}' in session.read_log()
        return session

    def cold(self, label, path, revision, plugin_id=PLUGIN_ID):
        session = self.session(label, path, revision, plugin_id)
        session.close()
        self.sessions.remove(session)

    def run(self):
        probe = self.root / 'probe.py'
        shutil.copyfile(__file__, probe)
        result = subprocess.run(['sandbox-exec', '-f', str(self.sandbox), sys.executable,
                                 str(probe), '--isolation-probe', str(self.root)],
                                capture_output=True, text=True, timeout=10)
        assert result.returncode == 0, result.stderr
        self.passed('isolation before CLI writes')
        version = subprocess.check_output(self.command('--version'), cwd=self.root,
                                         env=self.env, text=True).strip()
        self.version = version
        assert self.cli('list', '--json') == []
        self.passed('empty isolated profile')
        a = self.market / 'snapshots/A'
        self.package(a, 'A')
        companion = self.market / 'plugins/companion'
        self.package(companion, 'A', name=COMPANION)
        companion_hash = fingerprint(companion)
        self.publish('A')
        self.check_static_operations()
        self.cli('validate', str(a))
        self.cli('marketplace', 'add', str(self.market), '--scope', 'user', '--json')
        marketplaces = self.cli('marketplace', 'list', '--json')
        assert marketplaces[0]['source'] == 'directory'
        self.cli('install', PLUGIN_ID, '--scope', 'user', '--json')
        installed_a = self.installed()
        assert installed_a['id'] == PLUGIN_ID and installed_a['scope'] == 'user'
        self.cli('install', f'{COMPANION}@{MARKET}', '--scope', 'user', '--json')
        self.passed('local install without child installers')
        a_hash = fingerprint(a)
        open_a = self.session('open-A', a / 'hooks/hooks.json', 'A')
        # Sync touches Library only. The runtime catalog and A cannot move.
        b_library = self.library / 'mod/files'
        self.package(b_library, 'B')
        catalog_a = self.catalog.read_bytes()
        self.cli('marketplace', 'update', MARKET, '--json')
        assert self.catalog.read_bytes() == catalog_a and fingerprint(a) == a_hash
        self.cold('cold-after-sync', a / 'hooks/hooks.json', 'A')
        self.passed('sync and marketplace refresh retain A with identical upstream version')
        b = self.market / 'snapshots/B'
        shutil.copytree(b_library, b)
        self.cli('validate', str(b))
        b_hash = fingerprint(b)
        assert b_hash != a_hash
        # Only the explicitly accepted Apply operation publishes this pointer.
        self.publish('B')
        update = self.cli('update', PLUGIN_ID, '--scope', 'user', '--json')
        assert update['outcome'] == 'ok'
        installed_b = self.installed()
        self.update_outcome = update.get('updateOutcome')
        self.record_path_stale = installed_b['installPath'] == installed_a['installPath']
        assert fingerprint(a) == a_hash and fingerprint(b) == b_hash
        assert fingerprint(companion) == companion_hash
        entry = next(item for item in json.loads(self.catalog.read_text())['plugins']
                     if item['name'] == COMPANION)
        assert entry['source'] == './plugins/companion'
        assert str(b / 'hooks/hooks.json') not in open_a.read_log()
        self.cold('cold-B', b / 'hooks/hooks.json', 'B')
        self.passed('explicit atomic publish loads B while an open session retains A')
        offset = open_a.reload()
        open_a.wait_for(str(b / 'hooks/hooks.json'), after=offset)
        open_a.wait_for(f'hooks module {PLUGIN_ID} loaded', after=offset)
        assert 'events: session.start,session.end' in open_a.read_log()[offset:]
        open_a.close()
        self.sessions.remove(open_a)
        self.passed('reload switches the open session to B without manifest version bump')
        # A CLI failure does not mean the newly published local path is inactive.
        previous = self.catalog.read_bytes()
        self.publish('missing')
        failure = self.cli('update', PLUGIN_ID, '--scope', 'user', '--json', success=False)
        assert failure['failureCode'] == 'source_missing'
        atomic_json(self.catalog, json.loads(previous))
        self.cold('rollback-B', b / 'hooks/hooks.json', 'B')
        self.passed('failed update restores catalog; fresh session confirms rollback')
        self.cli('uninstall', f'{COMPANION}@{MARKET}', '--scope', 'user', '--json')
        assert fingerprint(companion) == companion_hash
        self.passed('updating one local-path package preserves its nested companion')
        self.cli('disable', PLUGIN_ID, '--scope', 'user', '--json')
        assert not self.installed()['enabled']
        self.cli('enable', PLUGIN_ID, '--scope', 'user', '--json')
        assert self.installed()['enabled']
        # Claude 2.1.287 sanitizes '@' to '-' in the runtime data directory name.
        data = self.profile / f"plugins/data/{PLUGIN_ID.replace('@', '-')}/sentinel"
        data.parent.mkdir(parents=True, exist_ok=True)
        data.write_text('local runtime data')
        options = json.loads((self.profile / 'settings.json').read_text())
        options['pluginConfigs'] = {PLUGIN_ID: {'options': {'fixture': 'local-only'}}}
        atomic_json(self.profile / 'settings.json', options)
        self.cli('uninstall', PLUGIN_ID, '--scope', 'user', '--keep-data', '--json')
        assert data.exists() and self.cli('list', '--json') == []
        settings = json.loads((self.profile / 'settings.json').read_text())
        assert PLUGIN_ID not in settings.get('pluginConfigs', {})
        assert fingerprint(a) == a_hash and fingerprint(b) == b_hash
        self.cli('install', PLUGIN_ID, '--scope', 'user', '--json')
        self.cli('uninstall', PLUGIN_ID, '--scope', 'user', '--json')
        assert not data.exists()
        self.passed('enable disable and uninstall: keep-data preserves data but drops options')
        self.cli('marketplace', 'remove', MARKET, '--json')
        assert self.cli('marketplace', 'list', '--json') == []
        # Alternative origin is auto-discovered and has no marketplace install record.
        skill = self.profile / f'skills/{PLUGIN}'
        self.package(skill, 'A')
        skills = self.cli('list', '--json')
        assert any(item['id'] == f'{PLUGIN}@skills-dir' for item in skills), skills
        self.cold('skills-origin', skill / 'hooks/hooks.json', 'A', f'{PLUGIN}@skills-dir')
        self.cli('uninstall', f'{PLUGIN}@skills-dir', '--scope', 'user', '--json', success=False)
        assert skill.exists()
        self.cli('disable', f'{PLUGIN}@skills-dir', '--scope', 'user', '--json')
        shutil.rmtree(skill)
        self.passed('skills-directory origin auto-loads and cannot be CLI-uninstalled')
        # Exercise custom/inline modules under their real runtime origins, not just validate.
        forms = {}
        for form in ('custom', 'inline'):
            self.package(skill, 'A', hooks=form)
            self.cli('validate', str(skill))
            self.cli('enable', f'{PLUGIN}@skills-dir', '--scope', 'user', '--json')
            session = Session(self, f'{form}-hooks-enabled')
            self.sessions.append(session)
            session.wait_for('Loaded plugins - Enabled:')
            forms[form] = f'hooks module {PLUGIN}@skills-dir loaded' in session.read_log()
            session.close()
            self.sessions.remove(session)
            self.cli('disable', f'{PLUGIN}@skills-dir', '--scope', 'user', '--json')
            shutil.rmtree(skill)
        self.hooks_forms = forms
        assert forms['custom'], forms
        self.passed('custom and inline hooks compatibility measured')
        assert self.cli('list', '--json') == []
        self.passed('teardown: no installs or marketplaces remain in fixture')

    def finish(self):
        for session in self.sessions:
            session.close()
        atomic_json(self.root / 'cli-transcript.json', self.transcript)
        atomic_json(self.root / 'result.json', {
            'version': getattr(self, 'version', None), 'passed': self.checks,
            'updateOutcome': getattr(self, 'update_outcome', None),
            'installRecordPathStale': getattr(self, 'record_path_stale', None),
            'hooksFormsLoaded': getattr(self, 'hooks_forms', None),
        })
        print(f'Evidence: {self.root}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--claude', default=shutil.which('claude'))
    parser.add_argument('--isolation-probe', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not __debug__:
        raise RuntimeError('Run without -O: assertions enforce the isolated gate')
    if args.isolation_probe:
        isolation_probe(args.isolation_probe)
        return
    if args.dry_run:
        print('Create a fresh /private/tmp fixture; prove OS isolation; validate/install A;')
        print('sync equal-version B without activation; explicitly publish B; cold start/reload;')
        print('failure/rollback; enable/disable/uninstall/data; skills-dir/custom/inline origins;')
        print('remove fixture registrations, reap sessions, retain local evidence. No API prompts.')
        return
    assert sys.platform == 'darwin', 'This gate requires macOS sandbox-exec and Keychain isolation'
    assert args.claude, 'Claude CLI is required'
    spike = Spike(args.claude)
    try:
        spike.run()
    finally:
        spike.finish()


if __name__ == '__main__':
    main()
