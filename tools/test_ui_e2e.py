"""Runner contract tests. Explicit command: python3 -m unittest discover -s tools -p test_ui_e2e.py."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import sys

spec = importlib.util.spec_from_file_location('ui_e2e', Path(__file__).with_name('ui_e2e.py'))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def xml(self, body):
        (self.root / 'TEST-fixture.xml').write_text('<testsuite>' + body + '</testsuite>', encoding='utf-8', newline='\n')

    def test_empty_suite_is_not_pass(self):
        self.assertEqual('failed', runner.verdict(self.root, 0)['status'])

    def test_skipped_and_mixed_suite_are_not_pass(self):
        for cases in ['<testcase><skipped/></testcase>', '<testcase/><testcase><skipped/></testcase>']:
            self.xml(cases)
            self.assertEqual('skipped', runner.verdict(self.root, 0)['status'])

    def test_build_error_after_successful_xml_is_not_pass(self):
        self.xml('<testcase/>')
        self.assertEqual('failed', runner.verdict(self.root, 1)['status'])

    def test_timeout_after_xml_is_not_pass(self):
        self.xml('<testcase/>')
        self.assertEqual('failed', runner.verdict(self.root, 0, True)['status'])

    def test_assertion_or_error_is_failed(self):
        for result in ('failure', 'error'):
            self.xml(f'<testcase><{result}/></testcase>')
            self.assertEqual('failed', runner.verdict(self.root, 0)['status'])

    def test_malformed_report_is_failed(self):
        (self.root / 'TEST-fixture.xml').write_text('<invalid', encoding='utf-8', newline='\n')
        self.assertEqual('failed', runner.verdict(self.root, 0)['status'])

    def test_incomplete_report_is_failed(self):
        (self.root / 'TEST-fixture.xml').write_text('<testsuite tests="2"><testcase/></testsuite>', encoding='utf-8', newline='\n')
        self.assertEqual('failed', runner.verdict(self.root, 0)['status'])

    def test_success_requires_testcases_and_zero_exit(self):
        self.xml('<testcase/><testcase/>')
        self.assertEqual(dict(status='passed', reason='All selected scenarios completed',
                              passed=2, failed=0, skipped=0, blocked=0), runner.verdict(self.root, 0))

    def test_status_codes_keep_blocked_and_skipped_distinct_from_pass(self):
        self.assertEqual(0, runner.status_code({'status': 'passed'}))
        self.assertEqual(1, runner.status_code({'status': 'failed'}))
        self.assertEqual(3, runner.status_code({'status': 'skipped'}))
        self.assertEqual(3, runner.status_code({'status': 'blocked'}))

    def test_runner_terminates_timed_out_process(self):
        code, timed_out = runner.execute([sys.executable, '-c', 'import time; time.sleep(60)'], self.root / 'log', .1)
        self.assertTrue(timed_out)
        self.assertNotEqual(0, code)

    def test_process_probe_does_not_terminate_live_children(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'], start_new_session=True)
        try:
            self.assertTrue(runner.process_alive(child.pid))
            self.assertIsNone(child.poll())
        finally:
            runner.stop(child)
        self.assertFalse(runner.process_alive(child.pid))

    def test_cleanup_removes_readonly_git_objects(self):
        root = runner.prepare()
        file = root / 'upstream/object'
        file.write_bytes(b'fixture')
        file.chmod(0o400)
        runner.cleanup(root)
        self.assertFalse(root.exists())

    def test_windows_native_packaged_check_is_blocked_without_building(self):
        with patch.object(runner.sys, 'platform', 'win32'), patch.object(runner, 'execute') as execute:
            result = runner.run_ui(self.root, None, ['dev.ruleblend.e2e.Phase9Test.N11_*'])
        self.assertEqual('blocked', result['status'])
        execute.assert_not_called()

    def test_dispatcher_rejects_unknown_scenarios_and_keeps_failure_status(self):
        import verify_e2e
        with patch.object(verify_e2e.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1)) as run:
            self.assertEqual(2, verify_e2e.main(['--scenario', 'missing']))
            run.assert_not_called()
            self.assertEqual(1, verify_e2e.main(['smoke', 'library']))

    def test_cleanup_rejects_foreign_directory_and_symlink(self):
        with self.assertRaises(ValueError):
            runner.cleanup(self.root)
        link = self.root / 'ruleblend-e2e-link'
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            runner.cleanup(link)

    def test_symlink_escape_and_parent_escape_rejected(self):
        child = self.root / 'child'
        child.mkdir()
        (child / 'escape').symlink_to(self.root, target_is_directory=True)
        for path in (child / 'escape/new-file', child / '../new-file'):
            with self.assertRaises(ValueError):
                runner.inside(child, path)

    def test_owned_cleanup_does_not_follow_nested_symlinks(self):
        root = runner.prepare()
        (root / 'foreign-link').symlink_to(self.root, target_is_directory=True)
        (self.root / 'keep').write_text('neighbor', encoding='utf-8', newline='\n')
        runner.cleanup(root)
        self.assertEqual('neighbor', (self.root / 'keep').read_text(encoding='utf-8'))

    def test_sweep_removes_only_finished_owned_sandboxes(self):
        finished = runner.prepare()
        (finished / 'summary.json').write_text('{}', encoding='utf-8', newline='\n')
        running = runner.prepare()
        self.addCleanup(lambda: runner.cleanup(running))
        foreign = self.root / 'ruleblend-e2e-foreign'
        foreign.mkdir()
        (foreign / 'summary.json').write_text('{}', encoding='utf-8', newline='\n')
        (self.root / 'ruleblend-e2e-link').symlink_to(finished, target_is_directory=True)
        for root in (finished, running):
            root.rename(self.root / root.name)
        running = self.root / running.name
        self.assertEqual((1, 3), runner.sweep(self.root))
        self.assertFalse((self.root / finished.name).exists())
        self.assertTrue(running.exists() and foreign.exists())

    def test_failure_mid_scenario_retains_root_and_artifacts(self):
        root = runner.prepare()
        self.addCleanup(lambda: runner.cleanup(root))
        args = type('Args', (), dict(tests='*', timeout=1))()
        with patch.object(runner, 'execute', return_value=(1, False)):
            result = runner.run_ui(root, args)
        self.assertEqual('failed', result['status'])
        self.assertEqual(1, len(result['runs']))
        self.assertTrue((root / runner.MARKER).exists())

    def test_projects_selects_one_method_and_restart_only_where_required(self):
        with patch.object(sys, 'argv', ['ui_e2e.py', 'projects', '--tests', 'P08']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(['dev.ruleblend.e2e.Phase1Test.P08_*'], run.call_args.args[1])
        self.assertEqual(('first',), run.call_args.args[2])
        with patch.object(sys, 'argv', ['ui_e2e.py', 'smoke']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(['dev.ruleblend.e2e.Phase1Test.P07_*'], run.call_args.args[1])

    def test_library_selects_one_isolated_method(self):
        with patch.object(sys, 'argv', ['ui_e2e.py', 'library', '--tests', 'L10']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(['dev.ruleblend.e2e.Phase2Test.L10_*'], run.call_args.args[1])
        self.assertEqual(('first',), run.call_args.args[2])

    def test_groups_selects_one_method_and_restarts_only_g07(self):
        with patch.object(sys, 'argv', ['ui_e2e.py', 'groups', '--tests', 'G03']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(['dev.ruleblend.e2e.Phase4Test.G03_*'], run.call_args.args[1])
        self.assertEqual(('first',), run.call_args.args[2])
        with patch.object(sys, 'argv', ['ui_e2e.py', 'groups', '--tests', 'G07']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(['dev.ruleblend.e2e.Phase4Test.G07_*'], run.call_args.args[1])
        self.assertEqual(('first', 'restart'), run.call_args.args[2])

    def test_overview_selects_one_isolated_method(self):
        with patch.object(sys, 'argv', ['ui_e2e.py', 'overview', '--tests', 'F05']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(['dev.ruleblend.e2e.Phase5Test.F05_*'], run.call_args.args[1])
        self.assertEqual(('first',), run.call_args.args[2])
        with patch.object(sys, 'argv', ['ui_e2e.py', 'overview', '--tests', 'F09']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(('first', 'restart'), run.call_args.args[2])

    def test_git_sync_selects_one_method_and_restarts_recovery_cases(self):
        with patch.object(sys, 'argv', ['ui_e2e.py', 'git-sync', '--tests', 'Y04']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(['dev.ruleblend.e2e.Phase7Test.Y04_*'], run.call_args.args[1])
        self.assertEqual(('first',), run.call_args.args[2])
        with patch.object(sys, 'argv', ['ui_e2e.py', 'git-sync', '--tests', 'Y10']), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(('first', 'restart'), run.call_args.args[2])

    def test_phase6_selects_stages_per_scenario(self):
        expected = {('archives', 'X01'): ('first', 'restart'), ('archives', 'X04'): ('first',),
                    ('git-skills', 'S02'): ('first',), ('git-skills', 'S04'): ('first', 'restart'),
                    ('git-skills', 'S05'): ('first', 'manual-restart', 'auto-restart')}
        for (suite, case), stages in expected.items():
            with patch.object(sys, 'argv', ['ui_e2e.py', suite, '--tests', case.lower()]), \
                 patch.object(runner, 'run_one', return_value=0) as run:
                self.assertEqual(0, runner.main())
            self.assertEqual([f'dev.ruleblend.e2e.Phase6Test.{case}_*'], run.call_args.args[1])
            self.assertEqual(stages, run.call_args.args[2])

    def test_recovery_scenarios_are_isolated_and_restart_only_when_needed(self):
        for case, stages in (('R01', ('first', 'restart')), ('R02', ('first',)),
                             ('R04', ('first',)), ('R06', ('first', 'restart'))):
            with patch.object(sys, 'argv', ['ui_e2e.py', 'recovery', '--tests', case.lower()]), \
                 patch.object(runner, 'run_one', return_value=0) as run:
                self.assertEqual(0, runner.main())
            self.assertEqual(1, run.call_count)
            self.assertEqual([f'dev.ruleblend.e2e.Phase10Test.{case}_*'], run.call_args.args[1])
            self.assertEqual(stages, run.call_args.args[2])

    def test_r03_repeats_full_smoke_on_clean_roots_and_guards_real_home(self):
        with patch.object(sys, 'argv', ['ui_e2e.py', 'recovery', '--tests', 'R03']), \
             patch.object(runner, 'real_fingerprints', side_effect=[([], ['a']), ([], ['a'])]), \
             patch.object(runner, 'run_one', return_value=0) as run:
            self.assertEqual(0, runner.main())
        self.assertEqual(2, run.call_count)
        for call in run.call_args_list:
            self.assertEqual((['dev.ruleblend.e2e.Phase1Test.P07_*'],), call.args[1:])
        with patch.object(sys, 'argv', ['ui_e2e.py', 'recovery', '--tests', 'R03']), \
             patch.object(runner, 'real_fingerprints', side_effect=[([], ['a']), ([], ['b'])]), \
             patch.object(runner, 'run_one', return_value=0):
            self.assertEqual(1, runner.main())

    def test_r05_uses_report_contract_without_starting_ui(self):
        with patch.object(sys, 'argv', ['ui_e2e.py', 'recovery', '--tests', 'R05']), \
             patch.object(runner, 'run_report_contract', return_value=0) as report, \
             patch.object(runner, 'run_one') as run:
            self.assertEqual(0, runner.main())
        report.assert_called_once_with()
        run.assert_not_called()

    def test_verify_entry_point_maps_aliases_and_scenario_without_running_gradle(self):
        import verify_e2e
        with patch.object(verify_e2e.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(0, verify_e2e.main(['ownership', 'profiles', 'coverage']))
            self.assertEqual(['conflicts', 'groups', 'overview'], [c.args[0][-1] for c in run.call_args_list])
            self.assertTrue(all(c.args[0][:2] == [sys.executable, '-B'] for c in run.call_args_list))
            run.reset_mock()
            self.assertEqual(0, verify_e2e.main(['--scenario', 'R-03']))
            self.assertEqual(['recovery', '--tests', 'R03'], run.call_args.args[0][-3:])
            run.reset_mock()
            self.assertEqual(0, verify_e2e.main(['--all-local']))
            self.assertEqual(list(verify_e2e.LOCAL_SUITES), [c.args[0][-1] for c in run.call_args_list])

    def test_phase6_fixture_advances_only_between_processes(self):
        root = runner.prepare()
        self.addCleanup(lambda: runner.cleanup(root))
        runner.prepare_phase6_git_fixture(root)
        git = runner.shutil.which('git')
        head = lambda: runner.subprocess.check_output(
            [git, '-C', str(root / 'upstream'), 'rev-parse', 'HEAD'], env=runner.environment(root), text=True)
        initial = head()
        mode = runner.subprocess.check_output(
            [git, '-C', str(root / 'upstream'), 'ls-tree', 'HEAD', 'skills/first-skill/scripts/run.sh'],
            env=runner.environment(root), text=True)
        self.assertTrue(mode.startswith('100755 '), mode)
        runner.advance_phase6_git_fixture(root, 'S01')
        self.assertEqual(initial, head())
        runner.advance_phase6_git_fixture(root, 'S03')
        self.assertNotEqual(initial, head())
        self.assertEqual('first-skill reference v2\n',
                         (root / 'upstream/skills/first-skill/references/guide.txt').read_text(encoding='utf-8'))

    def test_git_skill_fixture_has_committed_full_tree(self):
        root = runner.prepare()
        self.addCleanup(lambda: runner.cleanup(root))
        runner.prepare_git_skill_fixture(root)
        skill = root / 'upstream/skills/imported-skill'
        self.assertIn('Upstream body', (skill / 'SKILL.md').read_text(encoding='utf-8'))
        self.assertEqual('upstream resource\n', (skill / 'tool.txt').read_text(encoding='utf-8'))
        git = runner.shutil.which('git')
        tree = runner.subprocess.check_output(
            [git, '-C', str(root / 'upstream'), 'ls-tree', '-r', '--name-only', 'HEAD'],
            env=runner.environment(root), text=True,
        ).splitlines()
        self.assertEqual(['skills/imported-skill/SKILL.md', 'skills/imported-skill/tool.txt'], tree)

    def test_child_environment_does_not_inherit_user_overrides(self):
        root = runner.prepare()
        self.addCleanup(lambda: runner.cleanup(root))
        with patch.dict('os.environ', {'SSH_AUTH_SOCK': '/fixture/socket', 'RULEBLEND_LIBRARY': '/fixture/foreign'}):
            env = runner.environment(root)
        self.assertNotIn('SSH_AUTH_SOCK', env)
        self.assertEqual(str(root / 'home/.ruleblend/library'), env['RULEBLEND_LIBRARY'])
        log = root / 'child-env.log'
        self.assertEqual((0, False), runner.execute(
            [sys.executable, '-c', 'import os,json; print(json.dumps(dict(os.environ)))'], log, 5, root / 'work', env))
        actual = json.loads(log.read_text(encoding='utf-8'))
        if sys.platform == 'darwin':
            # macOS may add this CoreFoundation value during child process startup.
            encoding = actual.pop('__CF_USER_TEXT_ENCODING', None)
            if encoding is not None:
                self.assertRegex(encoding, r'^0x[0-9A-Fa-f]+:0x[0-9A-Fa-f]+:0x[0-9A-Fa-f]+$')
        if os.name == 'nt':
            self.assertEqual({k.upper(): v for k, v in env.items()}, {k.upper(): v for k, v in actual.items()})
        else:
            self.assertEqual(env, actual)

    def native_fixture(self):
        root = runner.prepare()
        self.addCleanup(lambda: runner.cleanup(root))
        result = dict(status='passed', completed=['launch', 'folder-dialog', 'choose-folder',
                                                'keyboard-input', 'screenshot', 'close'])
        (root / 'artifacts/desktop-result.json').write_text(json.dumps(result), encoding='utf-8', newline='\n')
        (root / 'home/.ruleblend/config.json').write_text(json.dumps({'projects': [str(root / 'projects/Проект A')]}), encoding='utf-8', newline='\n')
        (root / 'artifacts/desktop.png').write_bytes(b'fixture image')
        return root

    def test_native_timeout_and_driver_error_override_success_json(self):
        root = self.native_fixture()
        self.assertEqual('failed', runner.desktop_verdict(root, 0, True)['status'])
        self.assertEqual('failed', runner.desktop_verdict(root, 1, False)['status'])

    def test_native_success_requires_correct_path_and_screenshot(self):
        root = self.native_fixture()
        self.assertEqual('passed', runner.desktop_verdict(root, 0, False)['status'])
        (root / 'home/.ruleblend/config.json').write_text(json.dumps({'projects': ['/fixture/wrong']}), encoding='utf-8', newline='\n')
        with self.assertRaisesRegex(AssertionError, 'unexpected path'):
            runner.desktop_verdict(root, 0, False)

    def test_native_incomplete_sequence_is_not_pass(self):
        root = self.native_fixture()
        (root / 'artifacts/desktop-result.json').write_text(json.dumps({'status': 'passed', 'completed': ['launch']}), encoding='utf-8', newline='\n')
        with self.assertRaisesRegex(AssertionError, 'Incomplete'):
            runner.desktop_verdict(root, 0, False)

    def test_native_missing_screenshot_is_not_pass(self):
        root = self.native_fixture()
        (root / 'artifacts/desktop.png').write_bytes(b'')
        with self.assertRaisesRegex(AssertionError, 'screenshot'):
            runner.desktop_verdict(root, 0, False)


if __name__ == '__main__':
    unittest.main()
