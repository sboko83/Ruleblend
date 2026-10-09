"""Explicit, opt-in UI E2E entry point for Windows and macOS."""
import os
from pathlib import Path
import re
import subprocess
import sys

LOCAL_SUITES = ('smoke', 'projects', 'library', 'conflicts', 'groups', 'overview', 'archives',
                'git-skills', 'git-sync', 'translation', 'settings', 'recovery')
ALIASES = dict(ownership='conflicts', profiles='groups', coverage='overview', exchange='archives',
               sources='git-skills', sync='git-sync')
NATIVE_SUITES = ('desktop', 'translation-live')


def scenario_suite(case):
    if re.fullmatch(r'[PLOGFXSY]\d{2}', case):
        return dict(P='projects', L='library', O='conflicts', G='groups', F='overview',
                    X='archives', S='git-skills', Y='git-sync')[case[0]]
    for pattern, suite in ((r'T0[1-7]', 'translation'), (r'T0[89]', 'translation-live'),
                           (r'N0[1-7]|N11', 'settings'), (r'N0[89]|N10|H08', 'desktop'),
                           (r'R0[1-6]', 'recovery')):
        if re.fullmatch(pattern, case):
            return suite
    raise ValueError(f'Unknown scenario: {case}')


def main(args=None):
    args = list(sys.argv[1:] if args is None else args)
    if not args or args in (['--help'], ['--list']):
        print('Usage: python tools/verify_e2e.py SUITE [SUITE ...] | --all-local | --scenario ID | --list')
        print('Suites: ' + ' '.join(LOCAL_SUITES + NATIVE_SUITES))
        print('Aliases: ' + ' '.join(ALIASES))
        print('Native suites require RULEBLEND_E2E_BUNDLE and macOS.')
        return 0
    try:
        case = None
        if args[0] == '--scenario':
            if len(args) != 2:
                raise ValueError('--scenario requires one ID')
            case = args[1].upper().replace('-', '')
            selected = [scenario_suite(case)]
        elif args == ['--all-local']:
            selected = LOCAL_SUITES
        else:
            selected = [ALIASES.get(arg, arg) for arg in args]
            for suite in selected:
                if suite not in LOCAL_SUITES + NATIVE_SUITES:
                    raise ValueError(f'Unknown suite: {suite}')
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    overall = 0
    for suite in selected:
        command = [sys.executable, '-B', str(Path(__file__).with_name('ui_e2e.py')), suite]
        if case:
            command += ['--tests', case]
        if suite in NATIVE_SUITES:
            bundle = os.environ.get('RULEBLEND_E2E_BUNDLE')
            if not bundle:
                print('Set RULEBLEND_E2E_BUNDLE for native suites.', file=sys.stderr)
                overall = overall or 3
                continue
            command += ['--bundle', bundle]
        print(f'Running {case or suite}', flush=True)
        code = subprocess.run(command, cwd=Path(__file__).resolve().parents[1]).returncode
        if code == 1:
            overall = 1
        elif code and not overall:
            overall = 3
    return overall


if __name__ == '__main__':
    sys.exit(main())
