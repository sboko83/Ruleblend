#!/usr/bin/env python3
"""macOS-only CLI input experiment; isolated profiles, no external network or real credentials.

Codex paste waits for its composer, bracketed-paste mode and MCP initialization.
This isolated experiment does not establish readiness for arbitrary user profiles.
Run --dry-run first. Evidence stays in a private temporary directory for inspection.
"""

from pathlib import Path
import argparse
import fcntl
import json
import os
import pty
import re
import select
import signal
import shutil
import struct
import subprocess
import tempfile
import termios
import time
import sys

parser = argparse.ArgumentParser()
parser.add_argument('assistant', choices=['claude', 'codex'])
parser.add_argument('--dry-run', action='store_true')
parser.add_argument('--cli', type=Path)
args = parser.parse_args()
assert sys.platform == 'darwin', 'This experiment requires macOS sandbox-exec'
resolved = args.cli or shutil.which(args.assistant)
assert resolved, f'{args.assistant} CLI was not found'
cli = Path(resolved).resolve()
if args.assistant == 'codex' and cli.suffix == '.js':
    # The npm shim needs a package manifest; use its bundled native CLI in embedded-server mode.
    candidates = list((cli.parent.parent / 'node_modules/@openai').glob('codex-darwin-*/vendor/*/bin/codex'))
    assert len(candidates) == 1, 'Pass --cli with the exact native Codex executable'
    cli = candidates[0]
if args.dry_run:
    print(f'CLI: {cli}')
    print('Temporary profile/workspace; external network, user-home reads, outside writes and Keychain IPC denied')
    print('Claude: native --prefill; Codex: composer-gated bracketed paste; no Enter/model prompt')
    sys.exit(0)
root = Path(tempfile.mkdtemp(prefix='rb-prefill-', dir='/private/tmp'))
workspace = root / 'workspace'; workspace.mkdir()
profile = root / 'profile'; profile.mkdir()
fake = 'sk-ant-offline-ruleblend-fixture-no-network'
draft = 'RULEBLEND_PREFILL_MARKER Обсудим правило Commit.\nНичего не отправляй автоматически.'
policy = root / 'isolation.sb'
policy.write_text(f'''(version 1)
(allow default)
(deny network*)
(deny file-read* (subpath "/Users") (subpath "/Library/Keychains") (subpath "/Library/Application Support/ClaudeCode"))
(allow file-read* (literal {json.dumps(str(cli))}) (subpath {json.dumps(str(root))}))
(deny file-write*)
(allow file-write* (subpath {json.dumps(str(root))}) (literal "/dev/null") (regex #"^/dev/tty"))
(deny mach-lookup (global-name "com.apple.securityd") (global-name "com.apple.securityd.xpc") (global-name "com.apple.cfnetwork.AuthBrokerAgent"))
''')
env = {'PATH':'/usr/bin:/bin','TERM':'xterm-256color','LANG':'en_US.UTF-8',
       'TMPDIR':str(root),'HOME':str(root),'CLAUDE_CONFIG_DIR':str(profile),'CODEX_HOME':str(profile),
       'CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC':'1','DISABLE_AUTOUPDATER':'1',
       'ANTHROPIC_API_KEY':fake,'CLAUDE_CODE_SKIP_API_KEY_VALIDATION':'1',
       'ANTHROPIC_BASE_URL':'http://127.0.0.1:9','OPENAI_API_KEY':'offline-ruleblend-fixture',
       'OPENAI_BASE_URL':'http://127.0.0.1:9/v1',
       'CLAUDE_CODE_REMOTE_SETTINGS_PATH':str(root/'remote-settings.json')}
(root/'remote-settings.json').write_text('{}')
mcp_events = root / 'mcp-events.jsonl'
mcp_fixture = root / 'mcp-fixture.py'
mcp_fixture.write_text('''import json, os, sys
from pathlib import Path
events = Path(sys.argv[1])
def record(value):
    with events.open('a') as stream:
        stream.write(json.dumps(value) + '\\n')
record({'event': 'start', 'pid': os.getpid()})
try:
    for line in sys.stdin:
        request = json.loads(line)
        method = request.get('method')
        record({'method': method})
        if 'id' not in request:
            continue
        result = {'initialize': {
            'protocolVersion': request.get('params', {}).get('protocolVersion'),
            'capabilities': {'tools': {}},
            'serverInfo': {'name': 'ruleblend-offline-fixture', 'version': '1.0'},
        }, 'tools/list': {'tools': []}, 'resources/list': {'resources': []},
            'resources/templates/list': {'resourceTemplates': []},
            'prompts/list': {'prompts': []}, 'ping': {}}.get(method)
        response = {'jsonrpc': '2.0', 'id': request['id']}
        if result is None:
            response['error'] = {'code': -32601, 'message': 'Unknown fixture method'}
        else:
            response['result'] = result
        print(json.dumps(response), flush=True)
finally:
    record({'event': 'exit'})
''')
mcp = {'mcpServers': {'ruleblend-spike': {
    'command': '/usr/bin/python3', 'args': [str(mcp_fixture), str(mcp_events)],
}}}
if args.assistant == 'claude':
    (profile/'.claude.json').write_text(json.dumps({
        'hasCompletedOnboarding':True,'theme':'dark','hasAcknowledgedCost':True,
        'customApiKeyResponses':{'approved':[fake[-20:]],'rejected':[]},
        'projects':{str(workspace):{'hasTrustDialogAccepted':True,'hasCompletedProjectOnboarding':True}},
    }))
    command=[str(cli),'--setting-sources','user','--debug-file',str(root/'debug.log'),
             '--strict-mcp-config','--mcp-config',json.dumps(mcp),'--prefill',draft]
else:
    (profile/'config.toml').write_text(f'''model = "probe-model"
model_provider = "probe"
check_for_update_on_startup = false
cli_auth_credentials_store = "file"
approval_policy = "on-request"
sandbox_mode = "read-only"
[model_providers.probe]
name = "Offline fixture"
base_url = "http://127.0.0.1:9/v1"
wire_api = "responses"
requires_openai_auth = false
[projects.{json.dumps(str(workspace))}]
trust_level = "trusted"
[mcp_servers.ruleblend-spike]
command = "/usr/bin/python3"
args = {json.dumps([str(mcp_fixture), str(mcp_events)])}
''')
    command=[str(cli),'--no-daemon','--no-alt-screen','-C',str(workspace)]
master, slave = pty.openpty()
fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack('HHHH',35,110,0,0))
process=subprocess.Popen(['/usr/bin/sandbox-exec','-f',str(policy),*command],cwd=workspace,env=env,
                         stdin=slave,stdout=slave,stderr=slave,start_new_session=True)
os.close(slave)
output=bytearray()
def clean_output():
    clean=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',output.decode(errors='replace'))
    return re.sub(r'\x1b\][^\x07]*(?:\x07|\x1b\\)','',clean)

def mcp_ready():
    if not mcp_events.exists():
        return False
    methods = [json.loads(line).get('method') for line in mcp_events.read_text().splitlines()]
    return 'initialize' in methods and 'tools/list' in methods

def composer_ready():
    return (b'\x1b[?2004h' in output and
            'Ask Codex to do anything' in clean_output() and mcp_ready())

def pump(duration, until=lambda: False):
    end=time.monotonic()+duration
    while time.monotonic()<end and process.poll() is None:
        if select.select([master],[],[],min(.1,max(0,end-time.monotonic())))[0]:
            try:
                data=os.read(master,65536)
            except OSError:
                break
            if not data:
                break
            output.extend(data)
            if b'\x1b[6n' in data: os.write(master,b'\x1b[1;1R')
            if b'\x1b[c' in data: os.write(master,b'\x1b[?1;2c')
        if until():
            return True
    return until()
try:
    if args.assistant == 'codex':
        assert pump(20, composer_ready), 'Codex composer did not become ready; draft was not pasted'
    else:
        assert pump(20, lambda: 'review before pressing Enter' in clean_output() and mcp_ready()), 'Claude prefill/MCP did not become ready'
    (root/'startup.terminal').write_bytes(output)
    if args.assistant == 'codex' and process.poll() is None:
        os.write(master,b'\x1b[200~'+draft.encode()+b'\x1b[201~')
        assert pump(10, lambda: 'RULEBLEND_PREFILL_MARKER' in clean_output()), 'Pasted draft did not appear'
        (root/'prefilled.terminal').write_bytes(output)
    clean=clean_output()
    print('Artifacts:',root)
    print('Process alive:',process.poll() is None)
    assert process.poll() is None, clean[-3000:]
    assert 'RULEBLEND_PREFILL_MARKER' in clean, clean[-3000:]
    if args.assistant == 'claude':
        assert 'review before pressing Enter' in clean
    records = list(profile.rglob('*.jsonl'))
    for path in records:
        for line in path.read_text().splitlines():
            record = json.loads(line)
            assert record.get('type') != 'user', f'Unexpected user submission: {path}'
            payload = record.get('payload') or {}
            assert payload.get('type') not in ('user_message', 'task_started'), f'Unexpected turn: {path}'
    print('PASS: draft is visible; no user submission in session records; external network denied')
    methods = [json.loads(line).get('method') for line in mcp_events.read_text().splitlines()]
    assert 'initialize' in methods and 'tools/list' in methods, methods
    print('PASS: session-local MCP initialized and tools discovered without a model request')
    # Exit is a CLI-local command. Clear the draft first; never submit the prepared message.
    os.write(master, b'\x03')
    pump(.5)
    os.write(master, b'\x04')
    pump(2)
finally:
    if process.poll() is None:
        try:
            os.killpg(process.pid,signal.SIGTERM)
        except (ProcessLookupError, PermissionError):
            # Darwin can reject a group signal while the leader is becoming a zombie.
            # Reap that exact child; never treat a still-running child as cleaned up.
            process.wait(timeout=3)
        try: process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid,signal.SIGKILL);process.wait(timeout=3)
    os.close(master)
    (root/'capture.terminal').write_bytes(output)
    print('Process reaped:',process.returncode)
