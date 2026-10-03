#!/usr/bin/env python3
"""Device-free checks: catalog/routes, deadline ownership, SDK verdict, verify gate."""
import contextlib
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
import e2e_verify as verify
import generate_testmap as gen
import testmap_agent_device as agent
import testmap_console as console
import testmap_run as runner
import testmap_stop as stop
import testmap_setup as setup


class BackendChecks(unittest.TestCase):
    def _fake_start(self, script, processes):
        popen = subprocess.Popen

        def launch(cmd, **kwargs):
            proc = popen([sys.executable, '-u', '-c', script], **kwargs)
            processes.append(proc)
            return proc

        return patch.object(runner.subprocess, 'Popen', side_effect=launch)

    def test_start_waits_past_warnings_and_uses_one_output_reader(self):
        processes = []
        log = io.StringIO()
        run_id = '20261003T063940-e17629e6'
        script = ('import time\nprint("SyntaxWarning: legacy generator")\n'
                  'print("testmap run not-a-valid-id")\ntime.sleep(.05)\n'
                  f'print("testmap run {run_id}  (not a CI gate)")\n'
                  'print("post-handshake output")\n')
        with self._fake_start(script, processes), patch.object(runner, 'load_status_record', return_value=None), patch.object(runner, 'load_run', return_value={'state': 'review_required'}), patch.object(runner, '_drain_runner_output') as old_drainer, contextlib.redirect_stderr(log):
            result = runner.RunManager().start(['fake-offline-task'])
            self.assertEqual(run_id, result['id'])
            self.assertEqual('review_required', result['state'])
            processes[0].wait(timeout=2)
            deadline = time.monotonic() + 2
            while not processes[0].stdout.closed and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(processes[0].stdout.closed)
            old_drainer.assert_not_called()
        self.assertIn('SyntaxWarning: legacy generator', log.getvalue())
        self.assertIn('post-handshake output', log.getvalue())

    def test_start_eof_without_run_id_is_an_error_and_reaps_child(self):
        processes = []
        log = io.StringIO()
        with self._fake_start('print("startup failed"); raise SystemExit(7)', processes), patch.object(runner, 'load_status_record', return_value=None), contextlib.redirect_stderr(log):
            with self.assertRaisesRegex(RuntimeError, 'exited without a run id.*startup failed'):
                runner.RunManager().start(['fake-offline-task'])
        self.assertEqual(7, processes[0].returncode)
        self.assertTrue(processes[0].stdout.closed)
        self.assertIn('startup failed', log.getvalue())

    def test_start_timeout_ends_owned_group_even_after_leader_exit(self):
        # A child that inherits stdout must not keep a failed UI launch alive.
        for leader_exits in (False, True):
            processes = []
            script = ('import os,signal,time\n'
                      'signal.signal(signal.SIGTERM,signal.SIG_IGN)\n'
                      'print("warning before a missing handshake")\n')
            if leader_exits:
                script += 'pid=os.fork()\nif pid: os._exit(0)\n'
            script += 'time.sleep(30)\n'
            started = time.monotonic()
            try:
                with self._fake_start(script, processes), patch.object(runner, 'load_status_record', return_value=None), patch.object(runner.RunManager, 'START_TIMEOUT_S', .15), patch.object(runner, 'terminate_process', lambda proc: stop.terminate_process_group(proc, term_s=.1, kill_s=.2)), contextlib.redirect_stderr(io.StringIO()):
                    with self.assertRaisesRegex(RuntimeError, 'runner start timed out'):
                        runner.RunManager().start(['fake-offline-task'])
                self.assertLess(time.monotonic() - started, 2)
                self.assertIsNotNone(processes[0].poll())
                self.assertTrue(processes[0].stdout.closed)
                deadline = time.monotonic() + 1
                while time.monotonic() < deadline:
                    try:
                        os.killpg(processes[0].pid, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(.02)
                else:
                    self.fail('failed start left its owned process group alive')
            finally:
                for proc in processes:
                    stop.terminate_process_group(proc, term_s=.1, kill_s=.2)

    def test_process_failure_cannot_be_hidden_by_success_json(self):
        with tempfile.TemporaryDirectory() as folder:
            evidence = Path(folder)
            for status in ('passed', 'ok', 'completed', 'success'):
                self.assertEqual('failed', agent._coerced_replay_result({'status': status}, 1, evidence)['status'])
                self.assertEqual('executed_review_required', agent._coerced_replay_result({'status': status}, 0, evidence)['status'])
            (evidence / 'replay.log').write_text('REPLAY_DIVERGENCE')
            self.assertEqual('failed', agent._coerced_replay_result({'status': 'passed'}, 0, evidence)['status'])

    def test_missing_historical_source_does_not_create_a_pass(self):
        empty = {'id': '20260912T140000-000c3f44', 'historical': True, 'tasks': [], 'state': 'passed'}
        with tempfile.TemporaryDirectory() as folder, patch.object(runner, 'RUNS_DIR', Path(folder)), patch.object(runner, 'historical_projection', return_value=empty):
            self.assertIsNone(runner.write_historical_projection())
            self.assertEqual([], list(Path(folder).iterdir()))
            (Path(folder) / (empty['id'] + '.json')).write_text(json.dumps(empty))
            self.assertEqual([], runner.run_summaries())
            self.assertEqual(empty, json.loads((Path(folder) / (empty['id'] + '.json')).read_text()))

    def test_catalog_and_page_routes(self):
        nodes, edges = runner.load_map()
        data = gen.catalog_payload(nodes, edges, gen.load_copy())
        legacy = json.loads(gen._html_payload(nodes, edges, gen.load_copy()))
        for key in ('nodes', 'edges', 'summary', 'total', 'edge_plans', 'copy', 'agent', 'artemis'):
            self.assertEqual(data[key], legacy[key])
        self.assertNotIn('layout', data)
        self.assertNotIn('generated', data)
        handler = object.__new__(console.Handler)
        replies = []
        handler._send = lambda *args: replies.append(args)
        with tempfile.TemporaryDirectory() as folder:
            page = Path(folder) / 'index.html'
            page.write_text('<html><head></head><body>new-page</body></html>')
            with patch.object(console, 'WEB_HTML', page), patch.object(console, 'MAP_HTML', page):
                for path in ('/', '/legacy', '/map.html', '/api/catalog'):
                    handler.path = path
                    handler.do_GET()
                    self.assertEqual(200, replies[-1][0])
                    if path == '/api/catalog':
                        self.assertEqual(json.loads(json.dumps(data)), json.loads(replies[-1][1]))
                    else:
                        self.assertIn(b'ewm-confirm-token', replies[-1][1])
                        self.assertIn(b'new-page', replies[-1][1])
        # Execute the actual generated queue-progress branch with a nonempty queue.
        html = gen.render_html(nodes, edges, gen.load_copy())
        begin = html.index('  var q = st.queue || [];', html.index('function renderRunStatus'))
        end = html.index('    var queueSig =', begin)
        snippet = html[begin:end] + '\n}'
        js = 'const st={queue:[{state:"running"}],pause_queue:true}; const $=()=>({style:{},dataset:{}}); const t=x=>x;\n' + snippet
        subprocess.run(['node', '-e', js], check=True, capture_output=True, text=True)

    def test_deadline_owns_descendants_after_leader_exit(self):
        scripts = [
            'import time; time.sleep(30)',
            'import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)',
            'import os,signal,time; p=os.fork();\nif p: os._exit(0)\nsignal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)',
        ]
        sentinel = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], start_new_session=True)
        try:
            for script in scripts:
                proc = subprocess.Popen([sys.executable, '-c', script], stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True, start_new_session=True)
                log = io.StringIO()
                started = time.monotonic()
                try:
                    with patch.object(runner, 'terminate_process_group',
                                      lambda p: stop.terminate_process_group(p, term_s=.15, kill_s=.2)):
                        code = runner._tee_child(proc, log, False, deadline_s=.15)
                    self.assertEqual(124, code)
                    self.assertIn('HARNESS_TIMEOUT', log.getvalue())
                    self.assertLess(time.monotonic() - started, 2)
                    self.assertIsNotNone(proc.poll())
                    deadline = time.monotonic() + 1
                    while time.monotonic() < deadline:
                        try:
                            os.killpg(proc.pid, 0)
                        except ProcessLookupError:
                            break
                        time.sleep(.02)
                    else:
                        self.fail(f'owned process group {proc.pid} survived timeout')
                    self.assertIsNone(sentinel.poll())
                finally:
                    stop.terminate_process_group(proc, term_s=.1, kill_s=.2)
        finally:
            stop.terminate_process_group(sentinel, term_s=.1, kill_s=.2)

    def test_captured_command_has_same_deadline_boundary(self):
        script = 'import os,signal,time; p=os.fork();\nif p: os._exit(0)\nsignal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)'
        terminate = stop.terminate_process_group
        started = time.monotonic()
        with patch.object(stop, 'terminate_process_group', lambda p: terminate(p, term_s=.15, kill_s=.2)):
            with self.assertRaises(subprocess.TimeoutExpired):
                stop.run_captured([sys.executable, '-c', script], timeout=.15, text=True)
        self.assertLess(time.monotonic() - started, 2)

    def test_sdk_timeout_is_failed_even_if_outer_process_returned(self):
        with tempfile.TemporaryDirectory() as folder:
            evidence = Path(folder)
            spec = {'edge_id': 'test-edge', 'agent_device_output': folder}
            sdk_error = {'error': {'message': 'TIMEOUT after 180000ms',
                                  'details': {'reason': 'timeout_cleanup_pending', 'timeoutCleanupPending': True}}}
            for exit_code, log in [(1, json.dumps(sdk_error)), (0, json.dumps(sdk_error)), (124, '')]:
                (evidence / 'replay.log').write_text(log)
                (evidence / 'result.json').unlink(missing_ok=True)
                result = agent.ingest_agent_device_result(spec, exit_code)
                self.assertTrue(result['agent_device_result']['timed_out'])
                self.assertEqual('failed', result['agent_device_state'])
                self.assertEqual('timed_out', result['layers']['execution'])
                self.assertFalse(result['human_confirmation'])
            (evidence / 'replay.log').write_text('request: {"timeoutMs": 180000}\ncompleted')
            self.assertFalse(agent._coerced_replay_result(None, 0, evidence)['timed_out'])

    def test_streaming_delivers_output_before_process_exit(self):
        proc = subprocess.Popen([sys.executable, '-c', 'import time; print("ready", flush=True); time.sleep(.2)'],
                                stdout=subprocess.PIPE, text=True, start_new_session=True)
        class Output(io.StringIO):
            saw_live = False
            def write(self, text):
                self.saw_live |= 'ready' in text and proc.poll() is None
                return super().write(text)
        output = Output()
        self.assertEqual(0, runner._tee_child(proc, output, False, deadline_s=2))
        self.assertTrue(output.saw_live)

    def test_optional_capture_cancel_leaves_time_for_restore(self):
        script = 'import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); print("capture started",flush=True); time.sleep(30)'
        started = time.monotonic()
        result = stop.run_captured([sys.executable, '-c', script], timeout=40,
                                   should_stop=lambda: time.monotonic() - started > .15, stop_grace_s=.2)
        self.assertEqual(130, result.returncode)
        self.assertIn('capture started', result.stdout)
        self.assertLess(time.monotonic() - started, 1.5)
        self.assertLess(.25 + .2 * 3 + stop.RESTORE_BUDGET_S, stop.RUNNER_TERM_S)

    def test_prepare_stop_terminates_owned_command_and_skips_setup(self):
        with tempfile.TemporaryDirectory() as folder:
            spec = {'builder': 'agent-device', 'agent_platform': 'ios', 'udid': 'fake-stop', 'edge_id': 'test', 'agent_device_output': folder}
            pid_path = Path(folder) / 'child.pid'
            script = 'import os,signal,time; from pathlib import Path; Path(' + repr(str(pid_path)) + ').write_text(str(os.getpid())); print("preparing",flush=True); signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)'
            started = time.monotonic()
            terminate = stop.terminate_process_group
            with patch.object(agent, 'ios_prepare_needed', return_value=True), patch.object(agent, 'prepare_ios_runner_cmd', return_value=[sys.executable, '-c', script]), patch.object(agent, '_prepared_udids', set()) as prepared, patch.object(stop, 'terminate_process_group', lambda proc, **kw: terminate(proc, term_s=.1, kill_s=.2)), patch.object(runner, '_apply_agent_setup') as setup_call:
                code, _, extra = runner.run_task(spec, io.StringIO(), should_stop=lambda: time.monotonic() - started > .2)
                self.assertNotIn('fake-stop', prepared)
            self.assertEqual(130, code)
            self.assertEqual('interrupted', extra['layers']['execution'])
            setup_call.assert_not_called()
            self.assertLess(time.monotonic() - started, 2)
            self.assertIn('HARNESS_CANCELLED', (Path(folder) / 'prepare.log').read_text())
            self.assertIn('preparing', (Path(folder) / 'prepare.log').read_text())
            with self.assertRaises(ProcessLookupError):
                os.killpg(int(pid_path.read_text()), 0)

    def test_prepare_success_and_failure_preserve_actual_exit_status(self):
        for code in (0, 7):
            with tempfile.TemporaryDirectory() as folder:
                spec = {'agent_platform': 'ios', 'udid': 'fake-exit', 'agent_device_output': folder}
                script = 'print("prepare result"); raise SystemExit(' + str(code) + ')'
                with patch.object(agent, 'ios_prepare_needed', return_value=True), patch.object(agent, 'prepare_ios_runner_cmd', return_value=[sys.executable, '-c', script]), patch.object(agent, '_prepared_udids', set()) as prepared:
                    if code:
                        with self.assertRaisesRegex(RuntimeError, 'exit 7'):
                            agent.maybe_prepare_ios_runner(spec, io.StringIO(), should_stop=lambda: False)
                        self.assertNotIn('fake-exit', prepared)
                    else:
                        agent.maybe_prepare_ios_runner(spec, io.StringIO(), should_stop=lambda: False)
                        self.assertIn('fake-exit', prepared)
                self.assertIn('prepare result', (Path(folder) / 'prepare.log').read_text())

    def test_prepare_timeout_preserves_log_and_failed_state(self):
        with tempfile.TemporaryDirectory() as folder:
            spec = {'builder': 'agent-device', 'agent_platform': 'ios', 'udid': 'fake', 'edge_id': 'test', 'agent_device_output': folder}
            error = subprocess.TimeoutExpired(['fake-prepare'], 300, output=b'preparing runner')
            with patch.object(agent, 'ios_prepare_needed', return_value=True), patch.object(agent, 'run_captured', side_effect=error):
                code, cases, extra = runner.run_task(spec, io.StringIO())
            self.assertEqual(124, code)
            self.assertTrue(extra['agent_device_result']['timed_out'])
            self.assertIn('preparing runner', (Path(folder)/'prepare.log').read_text())

    def test_batched_failure_still_releases_session_and_fixture(self):
        with tempfile.TemporaryDirectory() as folder:
            script = Path(folder)/'case.json'
            script.write_text('[]')
            spec = {'builder': 'agent-device', 'agent_device_output': folder, 'cmd': ['fake']}
            with patch.object(runner, 'maybe_prepare_ios_runner'), patch.object(runner, '_apply_agent_setup', return_value={'fixture': True}), patch.object(runner, '_script_from_cmd', return_value=script), patch.object(runner, 'parse_script', return_value=[{'n': 1}]), patch.object(runner, '_run_batched_steps', side_effect=RuntimeError('pipe failed')), patch.object(runner, 'release_agent_session') as release, patch.object(runner, '_restore_agent_setup') as restore:
                with self.assertRaises(RuntimeError):
                    runner.run_task(spec, io.StringIO())
                release.assert_called_once()
                restore.assert_called_once()

    def test_verify_streams_and_preserves_failure(self):
        cmd = 'edge:test@ios#agent'
        records = [
            ({'state': 'failed', 'tasks': [{'id': cmd, 'state': 'failed'}, {'id': cmd, 'state': 'review_required'}]}, 2, 'flake'),
            ({'state': 'failed', 'tasks': [{'id': cmd, 'state': 'review_required'}]}, 2, 'block'),
            (None, 0, 'block'),
            ({'state': 'review_required', 'tasks': [{'id': cmd, 'state': 'review_required'}]}, 0, 'stable'),
        ]
        for record, code, verdict in records:
            def tee(proc, output, tee_stdout):
                self.assertTrue(tee_stdout)
                output.write('testmap run 20261002T000000-abcdef12\n')
                return code
            with patch.object(verify.subprocess, 'Popen'), patch.object(verify, '_tee_child', tee), patch.object(verify, 'load_run', return_value=record):
                rows = verify.collect_stability([{'cmd': cmd}], 1, True)
            self.assertEqual(verdict, rows[0]['verdict'])
        with tempfile.TemporaryDirectory() as folder, patch.object(verify, 'select_report', return_value={'agent': [{'cmd': cmd}]}), patch.object(verify, 'render_report', return_value='report'), patch.object(verify, 'collect_stability', return_value=[{'verdict': 'flake'}]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, verify.main(['--change', 'test', '--run', '--sha', 'test', '--out', str(Path(folder)/'verify.md')]))


# Fake command-line tools use only this test's temporary directories. They do
# not forward any command to adb, simctl, an installed app, or a device.
FAKE_DEVICE_CLI = r"""#!/usr/bin/env python3
import json, os, pathlib, shlex, shutil, subprocess, sys
root = pathlib.Path(os.environ['FAKE_DEVICE_ROOT'])
args = sys.argv[1:]
with (root / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps(args) + '\n')
if args[:1] == ['simctl']:
    if args[1] == 'get_app_container': print(root / 'ios')
    elif args[1] == 'terminate': pass
    else: sys.exit('unexpected simctl operation: ' + repr(args))
    sys.exit(0)
args = args[2:]  # adb -s SERIAL
if args == ['get-state']: print('device')
elif args[0] == 'push':
    target = root / args[2].lstrip('/')
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args[1], target)
elif args[0] == 'exec-out':
    if (root / 'fail-read').exists(): sys.exit('deliberate backup read failure')
    sys.stdout.buffer.write((root / 'android' / args[4]).read_bytes())
elif args[0] == 'shell':
    command = shlex.split(args[1])
    if command[:1] == ['run-as']:
        operation = command[2:]
        if (root / 'fail-restore').exists() and operation[:2] == ['sh', '-c'] and 'cat >' in operation[2]:
            sys.exit('deliberate restore failure')
        code = subprocess.run(operation, cwd=root / 'android', input=sys.stdin.buffer.read(), stdout=sys.stdout.buffer).returncode
        sys.exit(code)
    elif command[:2] == ['getprop', 'ro.build.version.sdk']: print('37')
    elif command[:2] == ['pm', 'path']: print('package:/fake.apk')
    elif command[:2] == ['dumpsys', 'package']: print('versionCode=123')
    elif command[:2] == ['content', 'query']: print('Row: 0 _id=1')
    elif command[:2] == ['content', 'delete']: pass
    elif command[:1] == ['wm']:
        if len(command) == 2: print('Physical ' + command[1] + ': 1080x1920')
    elif command[:1] == ['mkdir']:
        (root / command[-1].lstrip('/')).mkdir(parents=True, exist_ok=True)
    elif command[:1] == ['rm']:
        for path in command[2:]: (root / path.lstrip('/')).unlink(missing_ok=True)
    elif command[:2] == ['am', 'start'] and (root / 'hang-start').exists():
        (root / 'start-waiting').touch()
        import time
        time.sleep(30)
    elif command[:2] == ['am', 'start'] and (root / 'fail-start').exists(): sys.exit('deliberate start failure')
    elif command[:1] not in [['am'], ['input']]: sys.exit('unexpected shell command: ' + repr(command))
else: sys.exit('unexpected fake device command: ' + repr(args))
"""


class SetupSafetyChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for name in ('android/files/datastore', 'android/shared_prefs', 'ios/Documents', 'tools', 'evidence'):
            (self.root / name).mkdir(parents=True)
        self.cli = self.root / 'tools' / 'adb'
        self.cli.write_text(FAKE_DEVICE_CLI)
        self.cli.chmod(0o700)
        (self.root / 'tools' / 'xcrun').symlink_to(self.cli)
        self.stack = contextlib.ExitStack()
        self.stack.enter_context(patch.dict(os.environ, {'FAKE_DEVICE_ROOT': str(self.root), 'PATH': str(self.root / 'tools') + os.pathsep + os.environ['PATH']}))
        self.stack.enter_context(patch.object(setup, 'adb_bin', return_value=str(self.cli)))
        self.stack.enter_context(patch.object(setup, 'SETUP_BACKUPS', self.root / 'locks'))
        self.stack.enter_context(patch.object(setup, 'make_fixtures', side_effect=self.fixtures))
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.stack.close)

    def fixtures(self, folder, marker):
        path = Path(folder) / f'ewm-suite-{marker}-A.png'
        path.write_bytes(b'synthetic image')
        return {'A': path}

    def apply(self, platform='android', kind='home'):
        return setup.apply_setup(kind, platform=platform, folder=self.root / 'evidence', marker='edge',
                                 serial='fake-serial' if platform == 'android' else None,
                                 udid='AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE' if platform == 'ios' else None)

    def test_android_roundtrip_binary_absent_crash_and_unique_media(self):
        original = b'\x00\xffprivate prefs\n'
        path = self.root / 'android' / setup.ANDROID_CONFIG[0]
        path.write_bytes(original)
        crash = self.root / 'android' / setup.CRASH_PREF
        crash.write_bytes(b'<map><string name="user">keep</string></map>')
        state = self.apply(kind='crash')
        self.assertFalse(path.exists())
        self.assertIn(b'crash_count', crash.read_bytes())
        saved = setup.load_setup_backup(Path(state['journal']))
        self.assertEqual(original, saved['private_backup'][setup.ANDROID_CONFIG[0]])
        self.assertIsNone(saved['private_backup'][setup.ANDROID_CONFIG[1]])
        (self.root / 'android' / setup.ANDROID_CONFIG[1]).write_bytes(b'created during test')
        fixture = self.root / 'sdcard' / setup.FIXTURE_FOLDER / Path(state['fixtures']['A']).name
        untouched = fixture.parent / 'user.png'
        untouched.write_bytes(b'user photo')
        setup.restore_setup(state)
        self.assertEqual(original, path.read_bytes())
        self.assertFalse((self.root / 'android' / setup.ANDROID_CONFIG[1]).exists())
        self.assertEqual(b'<map><string name="user">keep</string></map>', crash.read_bytes())
        self.assertFalse((self.root / 'android' / (setup.CRASH_PREF + '.bak')).exists())
        self.assertFalse(fixture.exists())
        self.assertEqual(b'user photo', untouched.read_bytes())
        self.assertFalse(Path(state['lock']).exists())
        self.assertEqual('restored', json.loads(Path(state['journal']).read_text())['phase'])
        second = self.apply()
        self.assertNotEqual(state['marker'], second['marker'])
        setup.restore_setup(second)

    def test_android_share_intent_grants_the_same_stream_uri(self):
        with patch.object(setup, "adb_shell") as shell:
            setup.android_share_in("test-device", "123")
        serial, *args = shell.call_args.args
        self.assertEqual("test-device", serial)
        uri = setup.MEDIA + "/123"
        self.assertEqual(uri, args[args.index("-d") + 1])
        self.assertEqual(["android.intent.extra.STREAM", uri], args[args.index("--eu") + 1:args.index("--eu") + 3])
        self.assertEqual("android.intent.action.SEND", args[args.index("-a") + 1])
        self.assertIn("--grant-read-uri-permission", args)
        self.assertEqual("1", args[args.index("-f") + 1])

    def test_partial_setup_failure_restores_and_backup_failure_never_deletes(self):
        path = self.root / 'android' / setup.ANDROID_CONFIG[0]
        path.write_bytes(b'original')
        (self.root / 'fail-start').touch()
        with self.assertRaisesRegex(ValueError, 'start failure'):
            self.apply()
        self.assertEqual(b'original', path.read_bytes())
        (self.root / 'fail-start').unlink()
        (self.root / 'fail-read').touch()
        with self.assertRaisesRegex(ValueError, 'backup read failure'):
            self.apply()
        self.assertEqual(b'original', path.read_bytes())
        self.assertFalse(list((self.root / 'locks').glob('*.json')))

    def test_failed_restore_retains_backup_and_blocks_following_setup(self):
        path = self.root / 'android' / setup.ANDROID_CONFIG[0]
        path.write_bytes(b'original')
        state = self.apply()
        (self.root / 'fail-restore').touch()
        with self.assertRaisesRegex(setup.SetupRestoreError, 'restore failure'):
            setup.restore_setup(state)
        self.assertTrue(Path(state['lock']).exists())
        with self.assertRaisesRegex(ValueError, 'Unfinished setup'):
            self.apply()
        self.assertEqual(b'original', setup.load_setup_backup(Path(state['journal']))['private_backup'][setup.ANDROID_CONFIG[0]])
        (self.root / 'fail-restore').unlink()
        setup.restore_setup(setup.load_setup_backup(Path(state['journal'])))
        self.assertEqual(b'original', path.read_bytes())

    def test_ios_roundtrip_preserves_tmp_saved_state_and_permissions(self):
        path = self.root / 'ios' / setup.IOS_CONFIG[0]
        path.write_bytes(b'original iOS prefs')
        leftovers = [self.root / 'ios/tmp/ewm_src_original.png', self.root / 'ios/Library/Saved Application State/state']
        for item in leftovers:
            item.parent.mkdir(parents=True, exist_ok=True)
            item.write_bytes(b'keep')
        state = self.apply('ios')
        self.assertFalse(path.exists())
        (self.root / 'ios' / setup.IOS_CONFIG[1]).write_bytes(b'test-created')
        setup.restore_setup(state)
        self.assertEqual(b'original iOS prefs', path.read_bytes())
        self.assertFalse((self.root / 'ios' / setup.IOS_CONFIG[1]).exists())
        for item in leftovers: self.assertEqual(b'keep', item.read_bytes())
        calls = (self.root / 'calls.jsonl').read_text()
        self.assertNotIn('privacy', calls)
        with self.assertRaisesRegex(ValueError, 'permission precondition'):
            self.apply('ios', 'ios')
        self.assertEqual(calls, (self.root / 'calls.jsonl').read_text())

    def test_runner_stop_after_setup_restores_without_starting_child(self):
        with tempfile.TemporaryDirectory() as folder:
            spec = {'builder': 'agent-device', 'agent_device_output': folder, 'cmd': ['never-spawn']}
            with patch.object(runner, 'maybe_prepare_ios_runner'), patch.object(runner, '_apply_agent_setup', return_value={'backup': True}), patch.object(runner, '_restore_agent_setup') as restore, patch.object(runner.subprocess, 'Popen') as spawn:
                code, _, _ = runner.run_task(spec, io.StringIO(), should_stop=lambda: True)
                self.assertEqual(130, code)
                restore.assert_called_once()
                spawn.assert_not_called()
            with patch.object(runner, 'maybe_prepare_ios_runner'), patch.object(runner, '_apply_agent_setup', return_value={'backup': True}), patch.object(runner, 'restore_setup', side_effect=ValueError('recovery backup: fake.json')):
                with self.assertRaisesRegex(setup.SetupRestoreError, 'fake.json'):
                    runner.run_task(spec, io.StringIO(), should_stop=lambda: True)

    def test_stop_during_setup_command_rolls_back_without_waiting_for_command_timeout(self):
        import threading
        path = self.root / 'android' / setup.ANDROID_CONFIG[0]
        path.write_bytes(b'original before slow setup')
        (self.root / 'hang-start').touch()
        stop_requested = threading.Event()
        def request_stop():
            deadline = time.monotonic() + 8
            while not (self.root / 'start-waiting').exists() and time.monotonic() < deadline:
                time.sleep(.02)
            stop_requested.set()
        worker = threading.Thread(target=request_stop)
        worker.start()
        started = time.monotonic()
        try:
            with self.assertRaises(InterruptedError):
                setup.apply_setup('home', platform='android', folder=self.root / 'evidence', marker='stop', serial='fake-serial', should_stop=stop_requested.is_set)
        finally:
            worker.join(timeout=10)
        self.assertLess(time.monotonic() - started, 5)
        self.assertEqual(b'original before slow setup', path.read_bytes())
        self.assertFalse(list((self.root / 'locks').glob('*.json')))

    def test_reviewed_backup_rejects_unrelated_paths_before_device_access(self):
        state = self.apply()
        journal = Path(state['journal'])
        original = journal.read_text()
        for key, value in [('platform', 'other'), ('serial', 'invalid serial'), ('private_backup', {'/outside': 'eA=='}), ('lock', '/private/tmp/unrelated-lock.json')]:
            modified = json.loads(original)
            modified[key] = value
            journal.write_text(json.dumps(modified))
            calls = (self.root / 'calls.jsonl').read_text()
            with self.assertRaises(ValueError):
                setup.load_setup_backup(journal)
            self.assertEqual(calls, (self.root / 'calls.jsonl').read_text())
        journal.write_text(original)
        setup.restore_setup(state)

    def test_cleanup_failure_wins_over_other_stopped_tasks(self):
        rec = {'state': 'running', 'tasks': [{'state': 'failed', 'setup_restore_failed': True}, {'state': 'stopped'}]}
        with patch.object(runner, 'write_record'):
            runner.finalize_record(rec)
        self.assertEqual('failed', rec['state'])
        self.assertNotEqual(0, runner.cli_exit_code(rec))

    def test_signal_cleanup_and_hard_kill_recovery_lock(self):
        path = self.root / 'android' / setup.ANDROID_CONFIG[0]
        path.write_bytes(b'original across signals')
        code = """
import os, pathlib, signal, sys, time
import testmap_setup as setup
root = pathlib.Path(os.environ['FAKE_DEVICE_ROOT'])
setup.adb_bin = lambda: str(root / 'tools/adb')
setup.SETUP_BACKUPS = root / 'locks'
def fixtures(folder, marker):
    path = pathlib.Path(folder) / ('ewm-suite-' + marker + '-A.png')
    path.write_bytes(b'fixture')
    return {'A': path}
setup.make_fixtures = fixtures
def stop(signum, frame): raise SystemExit(0)
signal.signal(signal.SIGTERM, stop)
state = setup.apply_setup('home', platform='android', folder=root / 'evidence', marker='signal', serial='fake-serial')
try:
    print(state['journal'], flush=True)
    while True: time.sleep(1)
finally:
    setup.restore_setup(state)
"""
        env = dict(os.environ, PYTHONPATH=str(Path(setup.__file__).parent))
        for hard in (False, True):
            proc = subprocess.Popen([sys.executable, '-c', code], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
            try:
                import select
                ready, _, _ = select.select([proc.stdout], [], [], 10)
                self.assertTrue(ready, 'fake setup did not reach the prepared boundary')
                journal = proc.stdout.readline().strip()
                self.assertTrue(journal)
                self.assertFalse(path.exists())
                proc.send_signal(signal.SIGKILL if hard else signal.SIGTERM)
                _, err = proc.communicate(timeout=10)
                if hard:
                    with self.assertRaisesRegex(ValueError, 'Unfinished setup'):
                        self.apply()
                    setup.restore_setup(setup.load_setup_backup(Path(journal)))
                else:
                    self.assertEqual(0, proc.returncode, err)
                self.assertEqual(b'original across signals', path.read_bytes())
            finally:
                if proc.poll() is None: proc.kill()
                proc.communicate(timeout=5)

    def test_runner_restore_failure_is_not_a_successful_run(self):
        with tempfile.TemporaryDirectory() as folder:
            script = Path(folder) / 'case.json'
            script.write_text('[]')
            spec = {'builder': 'agent-device', 'edge_id': 'test', 'agent_device_output': folder, 'cmd': ['fake']}
            with patch.object(runner, 'maybe_prepare_ios_runner'), patch.object(runner, '_apply_agent_setup', return_value={'backup': True}), patch.object(runner, '_script_from_cmd', return_value=script), patch.object(runner, 'parse_script', return_value=[{'n': 1}]), patch.object(runner, '_run_batched_steps', return_value=0), patch.object(runner, 'release_agent_session'), patch.object(runner, 'restore_setup', side_effect=ValueError('recovery backup: fake.json')):
                with self.assertRaisesRegex(setup.SetupRestoreError, 'fake.json'):
                    runner.run_task(spec, io.StringIO())


if __name__ == '__main__':
    unittest.main()
