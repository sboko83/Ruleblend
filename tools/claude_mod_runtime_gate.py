#!/usr/bin/env python3
"""Run the JVM runtime adapter against Claude CLI in the existing offline macOS sandbox."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True
from claude_mod_transport_spike import Spike


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dry-run', action='store_true')
    parser.add_argument('--claude', default=shutil.which('claude'))
    args = parser.parse_args()
    if args.dry_run:
        print('Prove OS isolation in a new /private/tmp fixture; run only the JVM adapter gate;')
        print('check real CLI lifecycle, reinstall, recovery, aliases, user settings isolation and keep-data;')
        print('sync two device libraries through a local bare remote, then verify explicit Apply and orphan controls;')
        print('deny real home, network, Keychain and package child commands; remove the fixture.')
        return
    assert sys.platform == 'darwin' and args.claude, 'macOS and an installed Claude CLI are required'
    fixture = Spike(args.claude)
    try:
        probe = fixture.root / 'probe.py'
        shutil.copyfile(Path(__file__).with_name('claude_mod_transport_spike.py'), probe)
        isolation = subprocess.run(['sandbox-exec', '-f', str(fixture.sandbox), sys.executable,
                                    str(probe), '--isolation-probe', str(fixture.root)],
                                   capture_output=True, text=True, timeout=10)
        assert isolation.returncode == 0, isolation.stderr
        print(isolation.stdout.strip(), flush=True)
        control = fixture.root / 'control.json'
        control.write_text(json.dumps({'command': fixture.command(), 'environment': fixture.env,
                                       'root': str(fixture.root)}) + '\n')
        repo = Path(__file__).resolve().parent.parent
        result = subprocess.run([str(repo / 'gradlew'), ':core:macOsIntegrationTest', '--tests',
                                 '*ClaudeModRuntimeMacOsIntegrationTest', '--rerun-tasks'], cwd=repo,
                                env={**os.environ, 'RULEBLEND_MOD_GATE_CONTROL': str(control)})
        assert result.returncode == 0, 'JVM adapter gate failed'
        print('PASS: real CLI lifecycle, reinstall, recovery, foreign aliases, user settings isolation, drift and keep-data;')
        print('two-device local Git transfer, explicit Apply, whole-package conflict and orphan controls.')
    finally:
        shutil.rmtree(fixture.root)
        assert not fixture.root.exists()
        print('Teardown: synchronous CLI children exited; owned fixture removed; real home stayed OS-isolated.')


if __name__ == '__main__':
    main()
