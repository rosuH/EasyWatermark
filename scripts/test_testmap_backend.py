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
    def test_manual_run_watch_uses_recorded_qa_device_without_default_probe(self):
        run_id = '20261003T071642-4107cdfe'
        task = {'id': 'edge:launch-to-gallery@android#agent', 'platform': 'android',
                'state': 'running', 'device_request': 'emulator-5556',
                'device': {'id': 'emulator-5556', 'name': 'EWM_E2E_QA_20261003', 'kind': 'emulator'},
                'cmd': ['agent-device', 'test', 'fixture.ad', '--platform', 'android', '--serial', '<device>']}
        record = {'id': run_id, 'state': 'running', 'started': '2026-10-03T07:16:42Z',
                  'source': 'manual', 'device': 'emulator-5556', 'pid': os.getpid(), 'tasks': [task]}
        with tempfile.TemporaryDirectory() as folder, patch.object(runner, 'RUNS_DIR', Path(folder)), patch.object(runner, 'default_watch_slots', side_effect=AssertionError('recorded run must not probe default devices')), patch.object(runner, 'live_snapshot', return_value={}):
            (Path(folder) / (run_id + '.json')).write_text(json.dumps(record))
            handler = object.__new__(console.Handler)
            handler.path = '/api/status'
            replies = []
            handler._json = lambda status, data: replies.append((status, data))
            with patch.object(console, 'MANAGER', runner.RunManager()):
                handler.do_GET()
            status, snap = replies[-1]
            self.assertEqual(200, status)
            self.assertEqual(run_id, snap['id'])
            self.assertEqual('emulator-5556', snap['watch']['device'])
            self.assertEqual('EWM_E2E_QA_20261003', snap['watch']['name'])
            self.assertIsNone(snap['watches']['ios'])
            self.assertEqual('emulator-5556', console._watch_preferred(snap, 'android')['device'])
            self.assertIsNone(console._watch_preferred(snap, 'ios'))

    def test_record_watch_does_not_guess_or_reuse_another_task_device(self):
        old = {'platform': 'android', 'state': 'review_required', 'device': {'id': 'emulator-5554', 'name': 'Old'}}
        current = {'platform': 'android', 'state': 'running', 'cmd': ['agent-device', '--serial', '<device>']}
        with patch.object(runner, 'default_watch_slots', side_effect=AssertionError('no default probe')), patch.object(runner, 'live_snapshot', return_value={}):
            for record in ({'id': 'historical', 'historical': True, 'tasks': [current]},
                           {'id': 'live', 'device': 'auto', 'tasks': [old, current]}):
                self.assertIsNone(runner.project_status(record)['watch'])
            record = {'id': 'explicit', 'device': 'emulator-5556', 'tasks': [current]}
            self.assertEqual('emulator-5556', runner.project_status(record)['watch']['device'])
            ios = {'platform': 'ios', 'state': 'running', 'device': {'id': 'IOS-QA', 'name': 'iOS QA'}}
            record = {'id': 'multi', 'device': 'emulator-5554', 'tasks': [current, ios]}
            snap = runner.project_status(record)
            self.assertIsNone(snap['watch'])
            self.assertEqual('IOS-QA', snap['watches']['ios']['device'])
            self.assertIsNone(runner.watch_from_task({'platform': 'ios', 'device': {'id': 'emulator-5556'}}))
            # Older records can still identify a device explicitly in the command.
            self.assertEqual('emulator-5556', runner.watch_from_task({'platform': 'android', 'cmd': ['agent-device', '--serial', 'emulator-5556']})['device'])

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

    def test_json_bad_first_response_stops_and_restores_fixture(self):
        with tempfile.TemporaryDirectory() as folder:
            script = Path(folder) / 'case.json'
            script.write_text(json.dumps([{'command': 'open', 'input': {'app': 'owned'}},
                                          {'command': 'press', 'input': {'x': 1, 'y': 2}}]))
            spec = {'builder': 'agent-device', 'agent_device_output': folder,
                    'cmd': ['fake', 'batch', '--steps-file', str(script), '--platform', 'android']}
            events, cleanup = [], []
            with patch.object(runner, 'maybe_prepare_ios_runner'), \
                 patch.object(runner, '_apply_agent_setup', return_value={'fixture': True}), \
                 patch.object(runner.subprocess, 'Popen') as spawn, \
                 patch.object(runner, '_tee_child', return_value=0), \
                 patch.object(runner, '_restore_agent_setup', side_effect=lambda *args: cleanup.append('restore')) as restore, \
                 patch.object(runner, 'release_agent_session', side_effect=lambda *args: cleanup.append('close')), \
                 patch.object(runner, '_publish_agent_device_live'):
                code, _, extra = runner.run_task(spec, io.StringIO(),
                    on_step_event=lambda platform, event: events.append(event))
            self.assertEqual(1, code)
            self.assertEqual('failed', extra['agent_device_result']['status'])
            spawn.assert_called_once()
            argv = spawn.call_args.args[0]
            self.assertEqual('open', json.loads(argv[argv.index('--steps') + 1])[0]['command'])
            self.assertEqual(1, argv.count('--json'))
            self.assertEqual([{'type': 'replay_action_start', 'step': 1, 'command': 'open'},
                              {'type': 'replay_action_stop', 'step': 1, 'ok': False}], events)
            restore.assert_called_once_with({'fixture': True}, unittest.mock.ANY)
            self.assertEqual(['restore', 'close'], cleanup)

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
            with patch.object(verify, 'resolve_device', side_effect=lambda platform, request: {'id': request}), patch.object(verify.subprocess, 'Popen'), patch.object(verify, '_tee_child', tee), patch.object(verify, 'load_run', return_value=record):
                rows = verify.collect_stability([{'cmd': cmd, 'platform': 'ios'}], 1, True, ios_device='qa-udid')
            self.assertEqual(verdict, rows[0]['verdict'])
        selected = [{'cmd': cmd, 'platform': 'ios'}]
        with tempfile.TemporaryDirectory() as folder, patch.object(verify, 'select_report', return_value={'agent': selected}), patch.object(verify, 'render_report', return_value='report'), patch.object(verify, 'collect_stability', return_value=[{'verdict': 'flake'}]) as collect, contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, verify.main(['--change', 'test', '--run', '--sha', 'test', '--repeats', '3', '--ios-device', 'qa-udid', '--out', str(Path(folder)/'verify.md')]))
            collect.assert_called_once_with(selected, 3, True, android_device=None, ios_device='qa-udid')

    def test_verify_binds_both_platforms_without_losing_selected_attempts(self):
        selected = [{'cmd': f'edge:test-{n}@{platform}#agent', 'platform': platform}
                    for platform, count in [('android', 25), ('ios', 22)] for n in range(count)]
        run_ids = ['20261010T000000-abcdef12', '20261010T000001-abcdef12']
        records = [{
            'state': 'review_required',
            'tasks': [{'id': item['cmd'], 'state': 'review_required', 'evidence_dir': f'{run_id}/{k}'}
                      for item in selected if item['platform'] == platform for k in range(3)],
        } for platform, run_id in zip(['android', 'ios'], run_ids)]
        outputs = iter(run_ids)
        def tee(proc, output, tee_stdout):
            output.write(f'testmap run {next(outputs)}\n')
            return 0
        with patch.object(verify, 'resolve_device', side_effect=lambda platform, request: {'id': request}) as resolve, patch.object(verify.subprocess, 'Popen') as spawn, patch.object(verify, '_tee_child', tee), patch.object(verify, 'load_run', side_effect=records):
            rows = verify.collect_stability(selected, 3, True, android_device='emulator-5556', ios_device='qa-udid')
        self.assertEqual([('android', 'emulator-5556'), ('ios', 'qa-udid')], [call.args for call in resolve.call_args_list])
        self.assertEqual(2, spawn.call_count)
        for call, platform, device in zip(spawn.call_args_list, ['android', 'ios'], ['emulator-5556', 'qa-udid']):
            argv = call.args[0]
            self.assertEqual(device, argv[argv.index('--device') + 1])
            self.assertEqual('verify', argv[argv.index('--source') + 1])
            self.assertEqual('3', argv[argv.index('--repeat') + 1])
            self.assertEqual([item['cmd'] for item in selected if item['platform'] == platform], argv[argv.index('--repeat') + 2:])
            self.assertEqual('1', call.kwargs['env']['TESTMAP_NO_EXPAND'])
            with patch.dict(os.environ, call.kwargs['env']):
                self.assertEqual(argv[argv.index('--repeat') + 2:], runner.expand_mobile_parallel(argv[argv.index('--repeat') + 2:], 'physical-serial'))
        self.assertEqual(47, len(rows))
        self.assertEqual(141, sum(row['ok'] for row in rows))
        for item, row in zip(selected, rows):
            self.assertEqual('stable', row['verdict'])
            self.assertEqual([run_ids[0 if item['platform'] == 'android' else 1]] * 3, row['run_ids'])
            self.assertEqual(3, len(row['evidence']))

    def test_verify_requires_all_bindings_before_resolution_or_spawn(self):
        selected = [{'cmd': f'edge:test@{platform}#agent', 'platform': platform} for platform in ['android', 'ios']]
        for invalid in [None, '', ' ', 'auto', ' AUTO ']:
            with patch.object(verify, 'resolve_device') as resolve, patch.object(verify.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(ValueError, '--ios-device'):
                    verify.collect_stability(selected, 3, True, android_device='emulator-5556', ios_device=invalid)
                resolve.assert_not_called()
                spawn.assert_not_called()
        with patch.object(verify, 'resolve_device') as resolve, patch.object(verify.subprocess, 'Popen') as spawn:
            rows = verify.collect_stability(selected, 3, False)
            self.assertEqual(['not_run', 'not_run'], [row['verdict'] for row in rows])
            self.assertEqual([], verify.collect_stability([], 3, True))
            resolve.assert_not_called()
            spawn.assert_not_called()
        with patch.object(verify, 'select_report', return_value={'agent': selected}), patch.object(verify, 'resolve_device') as resolve, patch.object(verify.subprocess, 'Popen') as spawn, contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(2, verify.main(['--change', 'test', '--sha', 'test', '--run', '--android-device', 'emulator-5556']))
            resolve.assert_not_called()
            spawn.assert_not_called()

    def test_verify_uses_existing_resolver_to_refuse_unknown_or_wrong_platform(self):
        devices = sys.modules[verify.resolve_device.__module__]
        catalog = {'android': [{'id': 'emulator-5556'}], 'ios': [{'id': 'qa-udid'}]}
        selected = [{'cmd': f'edge:test@{platform}#agent', 'platform': platform} for platform in ['android', 'ios']]
        for invalid in ['unknown-udid', 'emulator-5556']:
            with patch.object(devices, 'list_devices', return_value=catalog), patch.object(verify.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(ValueError, 'unknown ios device'):
                    verify.collect_stability(selected, 3, True, android_device='emulator-5556', ios_device=invalid)
                spawn.assert_not_called()
        with patch.object(verify, 'resolve_device') as resolve, patch.object(verify.subprocess, 'Popen') as spawn:
            with self.assertRaisesRegex(ValueError, 'invalid selected agent platform'):
                verify.collect_stability([{'cmd': 'edge:test@ios#agent', 'platform': 'android'}], 3, True, android_device='emulator-5556')
            resolve.assert_not_called()
            spawn.assert_not_called()

    def test_verify_lane_failure_survives_success_in_other_lane(self):
        selected = [{'cmd': f'edge:test@{platform}#agent', 'platform': platform} for platform in ['android', 'ios']]
        outputs = iter([(2, '20261010T000000-abcdef12'), (0, '20261010T000001-abcdef12')])
        def tee(proc, output, tee_stdout):
            code, run_id = next(outputs)
            output.write(f'testmap run {run_id}\n')
            return code
        records = [{'state': state, 'tasks': [{'id': item['cmd'], 'state': 'review_required'}]}
                   for item, state in zip(selected, ['failed', 'review_required'])]
        with patch.object(verify, 'resolve_device', side_effect=lambda platform, request: {'id': request}), patch.object(verify.subprocess, 'Popen'), patch.object(verify, '_tee_child', tee), patch.object(verify, 'load_run', side_effect=records):
            rows = verify.collect_stability(selected, 1, True, android_device='emulator-5556', ios_device='qa-udid')
        self.assertEqual(['block', 'stable'], [row['verdict'] for row in rows])
        self.assertNotEqual(rows[0]['run_ids'], rows[1]['run_ids'])

    def test_verify_second_spawn_failure_preserves_first_lane_evidence(self):
        selected = [{'cmd': f'edge:test@{platform}#agent', 'platform': platform} for platform in ['android', 'ios']]
        def tee(proc, output, tee_stdout):
            output.write('testmap run 20261010T000000-abcdef12\n')
            return 0
        record = {'state': 'review_required', 'tasks': [{'id': selected[0]['cmd'], 'state': 'review_required'}]}
        with patch.object(verify, 'resolve_device', side_effect=lambda platform, request: {'id': request}), patch.object(verify.subprocess, 'Popen', side_effect=[object(), OSError('cannot spawn')]), patch.object(verify, '_tee_child', tee), patch.object(verify, 'load_run', return_value=record), contextlib.redirect_stderr(io.StringIO()):
            rows = verify.collect_stability(selected, 1, True, android_device='emulator-5556', ios_device='qa-udid')
        self.assertEqual(['stable', 'block'], [row['verdict'] for row in rows])
        self.assertEqual(['20261010T000000-abcdef12'], rows[0]['run_ids'])
        self.assertEqual([], rows[1]['run_ids'])

    def test_verify_refuses_avd_alias_even_in_second_lane_before_any_spawn(self):
        devices = sys.modules[verify.resolve_device.__module__]
        catalog = {'android': [
            {'id': 'emulator-5556', 'avd': 'QA_AVD'},
            {'id': 'emulator-5558', 'avd': 'QA_AVD'},
        ], 'ios': [{'id': 'qa-udid'}]}
        for platforms in [['android'], ['ios', 'android']]:
            selected = [{'cmd': f'edge:test@{platform}#agent', 'platform': platform} for platform in platforms]
            with patch.object(devices, 'list_devices', return_value=catalog) as inventory, patch.object(verify.subprocess, 'Popen') as spawn:
                with self.assertRaisesRegex(ValueError, '--android-device requires an exact device ID, not an alias; use emulator-5556'):
                    verify.collect_stability(selected, 3, True, android_device='QA_AVD', ios_device='qa-udid')
                self.assertEqual(len(platforms), inventory.call_count)
                spawn.assert_not_called()


    def test_fixture_damage_and_restore_stream_in_place_with_no_create_and_fail_closed(self):
        import shlex
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            marker = 'exportfailurerec-' + 'b' * 32
            local = setup.write_png(root / f'ewm-suite-{marker}-A.png', 2, 2, lambda y: b'\x10\x20\x30' * 2)
            valid = local.read_bytes()
            remote = f'/sdcard/{setup.FIXTURE_FOLDER}/{local.name}'
            damaged = f'EWM deliberate source decode failure {marker}'.encode()
            with patch.object(setup, 'adb') as adb:
                self.assertEqual(remote, setup.damage_source('fixture-serial', local.name, marker, root))
                setup.restore_source('fixture-serial', local, remote)
                self.assertEqual(2, adb.call_count)
                for call, expected in zip(adb.call_args_list, (damaged, valid)):
                    serial, argv = call.args
                    self.assertEqual('fixture-serial', serial)
                    self.assertEqual('shell', argv[0])
                    command = shlex.split(argv[1])
                    self.assertEqual(['sh', '-c'], command[:2])
                    self.assertEqual(['testmap-fixture-write', remote], command[3:])
                    self.assertEqual('test -f "$1" && test ! -L "$1" && exec 3<"$1" && test /proc/self/fd/3 -ef "$1" && cat > /proc/self/fd/3', command[2])
                    self.assertEqual({'input_data': expected, 'timeout': 5}, call.kwargs)
                self.assertEqual(damaged, (root / f'ewm-invalid-{marker}.bin').read_bytes())
            unsafe = ['/sdcard/other.png', remote.replace('-A.png', '-B.png'),
                      remote.replace('/ewm-suite-', '/../ewm-suite-'), remote + '; echo unsafe']
            with patch.object(setup, 'adb') as adb:
                for target in unsafe:
                    with self.subTest(target=target), self.assertRaises(ValueError):
                        setup.restore_source('fixture-serial', local, target)
                with self.assertRaises(ValueError):
                    setup.damage_source('fixture-serial', local.name, 'different-' + 'a' * 32, root)
                for payload in (b'', b'x' * (1024 * 1024 + 1)):
                    local.write_bytes(payload)
                    with self.assertRaises(ValueError):
                        setup.restore_source('fixture-serial', local, remote)
                local.unlink()
                backing = root / 'source.png'
                backing.write_bytes(valid)
                local.symlink_to(backing)
                with self.assertRaises(ValueError):
                    setup.restore_source('fixture-serial', local, remote)
                adb.assert_not_called()
            local.unlink()
            local.write_bytes(valid)
            # Missing target, symlink or a device write failure must propagate.
            # There is no retry via push or a direct path write that could recreate it.
            failure = ValueError('device refused existing-file write')
            with patch.object(setup, 'adb', side_effect=failure) as adb:
                for write in (lambda: setup.damage_source('fixture-serial', local.name, marker, root),
                              lambda: setup.restore_source('fixture-serial', local, remote)):
                    with self.assertRaises(ValueError) as caught:
                        write()
                    self.assertIs(failure, caught.exception)
                self.assertEqual(2, adb.call_count)


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
        if (root / 'fail-before-mv').exists() and operation[:2] == ['sh', '-c'] and 'cat >' in operation[2]:
            cat = operation[2].split(' && mv ', 1)[0]
            subprocess.run(['sh', '-c', cat], cwd=root / 'android', input=sys.stdin.buffer.read(), check=True)
            (root / 'cat-completed').touch()
            sys.exit('deliberate failure after cat before mv')
        if (root / 'fail-temp-cleanup').exists() and operation[:2] == ['rm', '-f'] and operation[-1].endswith('.testmap-restore'):
            sys.exit('deliberate temp cleanup failure')
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
        self.stack.enter_context(patch.object(setup, '_device_clock', return_value={"device_ms": 100000, "offset_min_ms": 10, "offset_max_ms": 20}))
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.stack.close)

    def apply_ios_control(self, mode="hold-next", run_id="run-1"):
        with patch.object(setup, "_ios_device_clock", return_value={"device_ms": 100000, "offset_min_ms": -2, "offset_max_ms": 2}):
            return setup.apply_setup("editor", platform="ios", folder=self.root / "evidence",
                                     marker="exportcancel" if mode == "hold-next" else "exportfailurerec",
                                     udid="AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", ios_export_run_id=run_id,
                                     ios_export_mode=mode)

    def test_ios_export_both_modes_restore_five_original_files_and_unique_fixture(self):
        import hashlib
        original = {name: (b"old events" if name == setup.IOS_EXPORT_EVENTS else None)
                    for name in setup.IOS_CONFIG + setup.IOS_EXPORT_PATHS}
        (self.root / "ios" / setup.IOS_EXPORT_EVENTS).write_bytes(b"old events")
        hashes = []
        for mode in ("hold-next", "fail-next"):
            state = self.apply_ios_control(mode)
            self.assertEqual(original, state["private_backup"])
            png = (self.root / "ios" / setup.IOS_EXPORT_FIXTURE).read_bytes()
            runner._verify_cancel_png(self.root / "ios" / setup.IOS_EXPORT_FIXTURE)
            self.assertRegex(png, rb"testmap-run\x00run-1:[a-f0-9]{32}")
            hashes.append(hashlib.sha256(png).hexdigest())
            marker = json.loads((self.root / "ios" / setup.IOS_EXPORT_CONTROL).read_text())
            self.assertEqual({"mode", "run_id", "fixture_id", "expires_at_ms"}, set(marker))
            self.assertEqual(mode, marker["mode"])
            (self.root / "ios" / setup.IOS_EXPORT_CONTROL).unlink()
            events = [{"run_id": "old", "private": "must not escape"}] + [
                {"run_id": "run-1", "event": event, "timestamp_ms": 100100 + n}
                for n, event in enumerate(("ready", "entered", "cancelled" if mode == "hold-next" else "failed", "cleared"))]
            (self.root / "ios" / setup.IOS_EXPORT_EVENTS).write_text("\n".join(map(json.dumps, events)))
            with patch.object(setup, "_ios_device_clock", return_value={"device_ms": 101000}):
                setup.restore_setup(state)
            public = json.loads((self.root / "evidence/export-control-events.json").read_text())
            self.assertTrue(public["fixture_matches"])
            self.assertTrue(public["marker_absent"])
            self.assertEqual(events[1:], public["events"])
            self.assertNotIn("must not escape", json.dumps(public))
            for name, data in original.items():
                self.assertEqual(data, setup._read_ios_private(self.root / "ios", name))
            self.assertFalse(Path(state["lock"]).exists())
        self.assertNotEqual(*hashes)

    def test_ios_active_control_and_foreign_temp_are_preserved(self):
        marker = self.root / "ios" / setup.IOS_EXPORT_CONTROL
        marker.write_text(json.dumps({"mode": "hold-next", "run_id": "other", "fixture_id": "testmap-export-fixture", "expires_at_ms": 150000}))
        with patch.object(setup, "ios_terminate") as terminate:
            with self.assertRaisesRegex(ValueError, "active or unrecognised"):
                self.apply_ios_control()
            terminate.assert_not_called()
        self.assertEqual("other", json.loads(marker.read_text())["run_id"])
        marker.unlink()
        temporary = marker.with_name(marker.name + ".testmap-restore")
        temporary.write_bytes(b"foreign")
        with self.assertRaises(setup.SetupRestoreError): self.apply_ios_control()
        self.assertEqual(b"foreign", temporary.read_bytes())

    def test_ios_publish_failure_cleans_owned_temp_and_restores_absence(self):
        real_replace = setup.os.replace
        def fail_publish(source, target):
            if str(source).endswith("testmap-export-control.json.testmap-restore"):
                raise OSError("after write before replace")
            return real_replace(source, target)
        with patch.object(setup.os, "replace", side_effect=fail_publish):
            with self.assertRaisesRegex(OSError, "after write"):
                self.apply_ios_control()
        for name in setup.IOS_EXPORT_PATHS:
            self.assertIsNone(setup._read_ios_private(self.root / "ios", name))
            self.assertFalse((self.root / "ios" / (name + ".testmap-restore")).exists())
        self.assertFalse(list((self.root / "locks").glob("*.json")))

    def test_ios_control_restore_refuses_missing_backup_foreign_temp_and_new_container(self):
        state = self.apply_ios_control()
        incomplete = dict(state, private_backup=dict(state["private_backup"]))
        incomplete["private_backup"].pop(setup.IOS_EXPORT_FIXTURE)
        with self.assertRaises(ValueError): setup.restore_setup(incomplete)
        temporary = self.root / "ios" / (setup.IOS_EXPORT_EVENTS + ".testmap-restore")
        temporary.write_bytes(b"foreign")
        with self.assertRaises(setup.SetupRestoreError): setup.restore_setup(state)
        self.assertTrue(Path(state["lock"]).exists())
        self.assertEqual(b"foreign", temporary.read_bytes())
        temporary.unlink()
        alias = self.root / "container-alias"
        alias.symlink_to(self.root / "ios", target_is_directory=True)
        with patch.object(setup, "simctl", return_value=str(alias)):
            with self.assertRaisesRegex(setup.SetupRestoreError, "Unsafe iOS restore container"):
                setup.restore_setup(state)
        self.assertTrue(Path(state["lock"]).exists())
        other = self.root / "other-container"
        other.mkdir()
        with patch.object(setup, "simctl", return_value=str(other)):
            with self.assertRaises(setup.SetupRestoreError): setup.restore_setup(state)
        self.assertTrue(Path(state["lock"]).exists())
        with patch.object(setup, "_ios_device_clock", return_value={"device_ms": 101000}): setup.restore_setup(state)

    def test_ios_private_symlink_and_failed_owned_temp_cleanup_fail_closed(self):
        target = self.root / "untouched"
        target.write_bytes(b"private original")
        control_path = self.root / "ios" / setup.IOS_EXPORT_CONTROL
        control_path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "Unsafe iOS"):
            self.apply_ios_control()
        self.assertEqual(b"private original", target.read_bytes())
        control_path.unlink()
        real_replace, real_unlink = setup.os.replace, Path.unlink
        def fail_publish(source, destination):
            if str(source).endswith("testmap-export-control.json.testmap-restore"):
                raise OSError("publish failed")
            return real_replace(source, destination)
        def fail_cleanup(path, *args, **kwargs):
            if str(path).endswith("testmap-export-control.json.testmap-restore"):
                raise OSError("owned temp cleanup failed")
            return real_unlink(path, *args, **kwargs)
        with patch.object(setup.os, "replace", side_effect=fail_publish), patch.object(Path, "unlink", fail_cleanup):
            with self.assertRaises(setup.SetupRestoreError): self.apply_ios_control()
        journals = [setup.load_setup_backup(path) for path in (self.root / "evidence").glob("setup-backup-*.json")]
        state = next(state for state in journals if state["phase"] == "restoring")
        self.assertTrue(Path(state["lock"]).exists())
        temporary = control_path.with_name(control_path.name + ".testmap-restore")
        self.assertTrue(temporary.is_file())
        temporary.unlink()  # Fake-device recovery only; never delete foreign real files.
        setup.restore_setup(state)

    def test_ios_clock_pairs_reject_disjoint_kernel_clock_and_wall_jumps(self):
        base = 1700000000000
        def sample(pair="1180 1700000000180 1180", wall=(base, base+200), mono=(500, 700)):
            with patch.object(setup, "simctl", return_value=pair), \
                 patch.object(setup.time, "clock_gettime_ns", side_effect=[n*1000000 for n in (1000,1000,1200,1200)]), \
                 patch.object(setup.time, "time_ns", side_effect=[n*1000000 for n in wall]), \
                 patch.object(setup.time, "monotonic_ns", side_effect=[n*1000000 for n in mono]):
                return setup._ios_device_clock("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        self.assertEqual((-2,2), tuple(sample()[key] for key in ("offset_min_ms","offset_max_ms")))
        for kwargs in ({"pair":"180 1700000000180 180"}, {"wall":(base,base+201)}, {"mono":(500,900)}, {"pair":"bad"}):
            with self.assertRaises(ValueError): sample(**kwargs)

    def fixtures(self, folder, marker):
        path = Path(folder) / f'ewm-suite-{marker}-A.png'
        path.write_bytes(b'synthetic image')
        return {'A': path}

    def apply(self, platform='android', kind='home'):
        return setup.apply_setup(kind, platform=platform, folder=self.root / 'evidence', marker='edge',
                                 serial='fake-serial' if platform == 'android' else None,
                                 udid='AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE' if platform == 'ios' else None)

    def test_private_write_failure_after_cat_cleans_temp_when_original_absent(self):
        name = setup.ANDROID_CONFIG[0]
        destination = self.root / "android" / name
        temporary = destination.with_name(destination.name + ".testmap-restore")
        (self.root / "fail-before-mv").touch()
        with self.assertRaisesRegex(ValueError, "after cat before mv"):
            setup.write_private("fake-serial", name, b"partial new bytes")
        self.assertTrue((self.root / "cat-completed").exists())
        self.assertFalse(destination.exists())
        self.assertFalse(temporary.exists())
        setup.restore_private("fake-serial", {name: None})
        self.assertFalse(destination.exists())
        self.assertFalse(temporary.exists())

    def test_existing_private_temp_is_never_overwritten_or_removed(self):
        name = setup.ANDROID_CONFIG[0]
        destination = self.root / "android" / name
        temporary = destination.with_name(destination.name + ".testmap-restore")
        temporary.write_bytes(b"another unfinished writer")
        with self.assertRaises(setup.SetupRestoreError):
            setup.write_private("fake-serial", name, b"new bytes")
        with self.assertRaises(setup.SetupRestoreError):
            setup.restore_private("fake-serial", {name: None})
        self.assertFalse(destination.exists())
        self.assertEqual(b"another unfinished writer", temporary.read_bytes())

    def test_cancel_temp_cleanup_failure_retains_recovery_lock_for_absent_original(self):
        (self.root / "fail-before-mv").touch()
        (self.root / "fail-temp-cleanup").touch()
        with self.assertRaises(setup.SetupRestoreError): self.apply_cancel()
        journals = list((self.root / "evidence").glob("setup-backup-*.json"))
        self.assertEqual(1, len(journals))
        state = setup.load_setup_backup(journals[0])
        self.assertIsNone(state["private_backup"][setup.EXPORT_EVENTS])
        self.assertTrue(Path(state["lock"]).exists())
        self.assertTrue(state["restore_errors"])
        temporary = self.root / "android" / (setup.EXPORT_EVENTS + ".testmap-restore")
        self.assertTrue(temporary.exists())
        # This is a fake-device fixture: simulate separately reviewed recovery.
        temporary.unlink()
        (self.root / "fail-before-mv").unlink()
        (self.root / "fail-temp-cleanup").unlink()
        setup.restore_setup(state)
        self.assertFalse(Path(state["lock"]).exists())
        self.assertFalse((self.root / "android" / setup.EXPORT_EVENTS).exists())

    def apply_cancel(self):
        with patch.object(setup, "android_wait_editor_ready"), patch.object(setup, "_device_clock", return_value={"device_ms": 100000, "offset_min_ms": 10, "offset_max_ms": 20}):
            return setup.apply_setup("editor", platform="android", folder=self.root / "evidence",
                                     marker="exportcancel", serial="fake-serial", export_cancel_run_id="run-1")

    def test_cancel_marker_roundtrip_absence_and_original_bytes(self):
        marker = self.root / "android" / setup.EXPORT_CONTROL
        events = self.root / "android" / setup.EXPORT_EVENTS
        expired = b'{ "mode":"hold-next", "run_id":"old", "fixture_uri":"content://media/external/images/media/9", "expires_at_ms":1 }'
        for original in (None, expired):
            with self.subTest(original_present=original is not None):
                if original is not None: marker.write_bytes(original)
                events.write_bytes(b"old private event bytes\n")
                state = self.apply_cancel()
                payload = json.loads(marker.read_text())
                self.assertEqual({"mode": "hold-next", "run_id": "run-1", "fixture_uri": setup.MEDIA + "/1", "expires_at_ms": 210000}, payload)
                self.assertEqual(b"", events.read_bytes())
                saved = setup.load_setup_backup(Path(state["journal"]))
                self.assertEqual(original, saved["private_backup"][setup.EXPORT_CONTROL])
                rows = [{"run_id": "run-1", "event": name, "timestamp_ms": 100100 + n} for n, name in enumerate(["ready", "entered", "cancelled", "cleared"])]
                events.write_text(json.dumps({"run_id": "other", "private": "must not escape"}) + "\n" + "\n".join(json.dumps(row) for row in rows))
                marker.unlink()  # Product atomically consumes only the current marker.
                setup.restore_setup(state)
                self.assertEqual(original, marker.read_bytes() if marker.exists() else None)
                self.assertEqual(b"old private event bytes\n", events.read_bytes())
                public = json.loads((self.root / "evidence/export-control-events.json").read_text())
                self.assertEqual(rows, public["events"])
                self.assertTrue(public["marker_absent"])
                self.assertNotIn("must not escape", json.dumps(public))
                self.assertFalse(Path(state["lock"]).exists())
                marker.unlink(missing_ok=True)

    def test_cancel_refuses_active_control_without_stopping_app(self):
        marker = self.root / "android" / setup.EXPORT_CONTROL
        for payload in (b"unrecognised", json.dumps({"mode": "hold-next", "run_id": "another-run", "fixture_uri": setup.MEDIA + "/9", "expires_at_ms": 200000}).encode()):
            marker.write_bytes(payload)
            with self.assertRaisesRegex(ValueError, "Existing active or unrecognised"):
                self.apply_cancel()
            self.assertEqual(payload, marker.read_bytes())
            self.assertNotIn("force-stop", (self.root / "calls.jsonl").read_text())
            self.assertFalse(list((self.root / "locks").glob("*.json")))

    def test_cancel_partial_arm_failure_and_stop_restore_original_events(self):
        events = self.root / "android" / setup.EXPORT_EVENTS
        original = b"prior private events"
        events.write_bytes(original)
        write = setup.write_private
        def fail_control(serial, path, data, **kwargs):
            if path == setup.EXPORT_CONTROL: raise ValueError("arm interrupted")
            return write(serial, path, data, **kwargs)
        with patch.object(setup, "write_private", side_effect=fail_control):
            with self.assertRaisesRegex(ValueError, "arm interrupted"): self.apply_cancel()
        self.assertEqual(original, events.read_bytes())
        self.assertFalse((self.root / "android" / setup.EXPORT_CONTROL).exists())
        arm = setup._arm_export_control
        def stop_after_arm(state):
            arm(state)
            raise InterruptedError("stopped after arm")
        with patch.object(setup, "_arm_export_control", side_effect=stop_after_arm):
            with self.assertRaises(InterruptedError): self.apply_cancel()
        self.assertEqual(original, events.read_bytes())
        self.assertFalse((self.root / "android" / setup.EXPORT_CONTROL).exists())
        self.assertFalse(list((self.root / "locks").glob("*.json")))

    def test_cancel_missing_backup_keys_rejected_before_any_device_access(self):
        state = self.apply_cancel()
        journal = Path(state["journal"])
        original = journal.read_text()
        for name in (*setup.ANDROID_CONFIG, *setup.EXPORT_CONTROL_PATHS):
            modified = json.loads(original)
            del modified["private_backup"][name]
            journal.write_text(json.dumps(modified))
            calls = (self.root / "calls.jsonl").read_text()
            with self.assertRaisesRegex(ValueError, "missing preferences"):
                setup.load_setup_backup(journal)
            self.assertEqual(calls, (self.root / "calls.jsonl").read_text())
        journal.write_text(original)
        setup.restore_setup(state)

    def test_cancel_restore_failure_retains_original_control_backup(self):
        events = self.root / "android" / setup.EXPORT_EVENTS
        events.write_bytes(b"original events")
        state = self.apply_cancel()
        (self.root / "fail-restore").touch()
        with self.assertRaises(setup.SetupRestoreError): setup.restore_setup(state)
        self.assertTrue(Path(state["lock"]).exists())
        self.assertEqual(b"original events", setup.load_setup_backup(Path(state["journal"]))["private_backup"][setup.EXPORT_EVENTS])
        (self.root / "fail-restore").unlink()
        setup.restore_setup(setup.load_setup_backup(Path(state["journal"])))
        self.assertFalse(Path(state["lock"]).exists())

    def test_cancel_scope_and_non_cancel_setup_do_not_arm(self):
        with self.assertRaisesRegex(ValueError, "restricted"):
            setup.apply_setup("home", platform="android", folder=self.root / "evidence", marker="exportcancel", serial="fake-serial", export_cancel_run_id="run-1")
        marker = self.root / "android" / setup.EXPORT_CONTROL
        marker.write_bytes(b"unrelated control")
        state = self.apply()
        self.assertNotIn("export_control", state)
        self.assertNotIn(setup.EXPORT_CONTROL, state["private_backup"])
        setup.restore_setup(state)
        self.assertEqual(b"unrelated control", marker.read_bytes())

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
        case = agent.agent_device_cases_by_id()['ios-library-read-upsell']
        self.assertEqual('home', case['setup'])
        self.assertTrue(case['supported']['ios'])
        self.assertFalse(case['supported']['android'])
        state = self.apply('ios', case['setup'])
        self.assertFalse(path.exists())
        (self.root / 'ios' / setup.IOS_CONFIG[1]).write_bytes(b'test-created')
        setup.restore_setup(state)
        self.assertEqual(b'original iOS prefs', path.read_bytes())
        self.assertFalse((self.root / 'ios' / setup.IOS_CONFIG[1]).exists())
        for item in leftovers: self.assertEqual(b'keep', item.read_bytes())
        calls = (self.root / 'calls.jsonl').read_text()
        self.assertNotIn('privacy', calls)
        with patch.object(setup, '_claim_setup') as claim:
            with self.assertRaisesRegex(ValueError, 'permission precondition'):
                self.apply('ios', 'ios')
            claim.assert_not_called()
        self.assertEqual(calls, (self.root / 'calls.jsonl').read_text())

    def test_ios_library_upsell_script_requires_visible_prompt_before_continue(self):
        import shlex
        case = agent.agent_device_cases_by_id()['ios-library-read-upsell']
        rows = runner.parse_script(setup.REPO_ROOT / case['script']['ios'], 'ios')
        # The flat replay must block on the actual upsell, not skip an absent prompt.
        self.assertTrue(all(row['command'] in {'open', 'wait', 'press', 'close'} for row in rows))
        prompts = [row for row in rows if row['command'] == 'wait'
                   and 'iosLibraryReadUpsellContinue' in row['args']]
        self.assertEqual(1, len(prompts))
        selector, timeout = shlex.split(prompts[0]['args'])
        self.assertEqual('id="iosLibraryReadUpsellContinue" visible', selector)
        self.assertGreater(int(timeout), 0)
        self.assertLessEqual(int(timeout), 20000)
        pick = next(row['n'] for row in rows if row['command'] == 'press'
                    and 'launchPickImageButton' in row['args'])
        continue_press = next(row['n'] for row in rows if row['command'] == 'press'
                              and 'iosLibraryReadUpsellContinue' in row['args'])
        close = next(row['n'] for row in rows if row['command'] == 'close')
        self.assertLess(pick, prompts[0]['n'])
        self.assertLess(prompts[0]['n'], continue_press)
        self.assertLess(continue_press, close)

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
        hang_reached = threading.Event()
        precondition_failed = threading.Event()
        requested_at = []
        def request_stop():
            deadline = time.monotonic() + 8
            while not (self.root / 'start-waiting').exists() and time.monotonic() < deadline:
                time.sleep(.02)
            if (self.root / 'start-waiting').exists():
                hang_reached.set()
                requested_at.append(time.monotonic())
                stop_requested.set()
            else:
                precondition_failed.set()
        def should_stop():
            if precondition_failed.is_set():
                raise AssertionError('fake am start hang was not reached within 8s')
            return stop_requested.is_set()
        worker = threading.Thread(target=request_stop)
        worker.start()
        try:
            with self.assertRaises(InterruptedError):
                try:
                    setup.apply_setup('home', platform='android', folder=self.root / 'evidence', marker='stop', serial='fake-serial', should_stop=should_stop)
                finally:
                    ended_at = time.monotonic()  # Includes rollback; excludes the subsequent join.
        finally:
            worker.join(timeout=10)
        self.assertFalse(worker.is_alive())
        self.assertTrue(hang_reached.is_set())
        self.assertFalse(precondition_failed.is_set())
        self.assertEqual(1, len(requested_at))
        # This measures Stop response, not the preceding fixture/setup preparation.
        self.assertLess(ended_at - requested_at[0], 5)
        self.assertEqual(b'original before slow setup', path.read_bytes())
        self.assertFalse(list((self.root / 'locks').glob('*.json')))
        journals = list((self.root / 'evidence').glob('setup-backup-*.json'))
        self.assertEqual(1, len(journals))
        restored = setup.load_setup_backup(journals[0])
        self.assertEqual('restored', restored['phase'])
        self.assertFalse(restored.get('restore_errors'))
        self.assertFalse(list(self.root.rglob('*.testmap-restore')))
        self.assertFalse(list((self.root / 'evidence').glob('*.tmp')))

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


class CancelGateEvidenceChecks(unittest.TestCase):
    def setUp(self):
        self.make_evidence()

    def make_evidence(self, platform="android", edge="export-cancel"):
        from datetime import datetime, timezone
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / "sdk"
        self.output.mkdir()
        self.evidence = self.root / "run-1"
        self.source = self.root / "cancel.ad"
        self.source.write_text((Path(runner.REPO_ROOT) / f"docs/testing/agent-device/scripts/{edge}@{platform}.ad").read_text())
        rows = runner.parse_script(self.source, platform)
        self.derived = self.evidence / "scripts/cancel.ad"
        manifest = runner.materialize_evidence_script(self.source, self.evidence, self.derived, {r["n"]: f"step-{r['n']}.png" for r in rows})
        self.spec = {"edge_id": edge, "agent_platform": platform, "agent_device_output": str(self.output), "step_evidence_root": str(self.evidence), "step_evidence_manifest": str(self.derived.with_suffix(".mapping.json"))}
        self.base = 1700000000000
        self.timeline = []
        for m in manifest["mapping"]:
            screenshot = m["kind"] == "screenshot"
            if screenshot:
                Path(m["path"]).parent.mkdir(parents=True, exist_ok=True)
                setup.write_png(Path(m["path"]), 2, 2, lambda y: bytes((10, 20, 30)) * 2)
            for kind, delta in [("replay_action_start", 550 if screenshot else 0), ("replay_action_stop", 600 if screenshot else 500)]:
                stamp = self.base + m["step"] * 1000 + delta
                self.timeline.append({"type": kind, "step": m["replay_step"], "command": "screenshot" if screenshot else m["command"], "ok": True, "ts": datetime.fromtimestamp(stamp / 1000, timezone.utc).isoformat(), "replayPath": str(self.derived)})
        self.control = {"mode": "hold-next", "run_id": "run-1", "fixture_uri": setup.MEDIA + "/1", "marker_absent": True, "clock": {"device_ms": self.base + 2000, "offset_min_ms": 1990, "offset_max_ms": 2010, "host_wall_ms": self.base, "host_monotonic_ms": 100000}, "clock_end": {"device_ms": self.base + 22000, "offset_min_ms": 1995, "offset_max_ms": 2015, "host_wall_ms": self.base + 20000, "host_monotonic_ms": 120000}, "expires_at_ms": self.base + 110000,
                        "events": [{"run_id": "run-1", "event": event, "timestamp_ms": self.base + when + 2000} for event, when in [("ready", 1800), ("entered", 2000), ("cancelled", 6100), ("cleared", 6200)]]}
        if platform == "ios":
            failure = edge == "export-failure-recovery"
            press = next(row["n"] for row in rows if row["command"] == "press" and
                         ("sharedComposeExportPrimary" if failure else "sharedComposeExportCancel") in row["args"])
            self.control.update(mode="fail-next" if failure else "hold-next", fixture_id="testmap-export-fixture",
                                fixture_sha256="a"*64, fixture_matches=True)
            for name, elapsed in (("clock",0),("clock_end",60000)):
                host = self.base + elapsed
                mono = 100000 + elapsed
                self.control[name] = {"device_ms":host+2005,"offset_min_ms":1998,"offset_max_ms":2002,
                    "host_wall_ms":host,"host_monotonic_ms":mono,"scope":"simulator-only-shared-posix-monotonic",
                    "host_pair_before":[mono,host,mono],"device_pair":[mono+5,host+2005,mono+5],
                    "host_pair_after":[mono+10,host+10,mono+10]}
            phases = [("ready", press*1000+100), ("entered",press*1000+120), ("failed",press*1000+200), ("cleared",press*1000+250)] if failure else [
                ("ready",press*1000-300), ("entered",press*1000-200), ("cancelled",press*1000+100), ("cleared",press*1000+200)]
            self.control["events"] = [{"run_id":"run-1", "event":event, "timestamp_ms":self.base+when+2000} for event,when in phases]
        self.write_evidence()

    def test_ios_cancel_and_failure_require_current_fixture_events_and_real_retry(self):
        for edge in ("export-cancel", "export-failure-recovery"):
            self.make_evidence("ios", edge)
            verdict = runner._cancel_gate_evidence(self.spec, 0)
            self.assertEqual("evidence_complete", verdict["status"])
            self.assertEqual("evidence_complete", verdict["retry_status"])
            self.assertEqual("pending_independent_device_evidence", verdict["photos_status"])
            original = json.dumps(self.control)
            for change in (lambda c:c.update(fixture_matches=False), lambda c:c.update(marker_absent=False),
                           lambda c:c["events"][2].update(event="watchdog"), lambda c:c["events"][0].update(run_id="old"),
                           lambda c:c["events"].append(c["events"][-1]), lambda c:c.update(mode="wrong"),
                           lambda c:c["clock"].pop("scope"), lambda c:c["clock_end"].pop("device_pair"),
                           lambda c:c["clock"].update(offset_min_ms=2000,offset_max_ms=2001),
                           lambda c:c["clock"]["device_pair"].__setitem__(0,1),
                           lambda c:c["clock"]["host_pair_before"].__setitem__(0,True),
                           lambda c:c["clock_end"].update(device_ms=123),
                           lambda c:c["clock_end"]["host_pair_after"].__setitem__(1,self.base+70000)) :
                self.control=json.loads(original); change(self.control); self.write_evidence()
                self.assertEqual("incomplete",runner._cancel_gate_evidence(self.spec,0)["status"])
            self.control=json.loads(original); self.write_evidence()
            timeline=list(self.timeline)
            for event in timeline:
                if event["type"] != "replay_action_stop": continue
                self.timeline=[row for row in timeline if row is not event]; self.write_evidence()
                self.assertEqual("incomplete",runner._cancel_gate_evidence(self.spec,0)["status"])
            self.timeline=timeline; self.write_evidence()
            first=self.evidence/"steps/step-1.png"
            first.write_bytes(first.read_bytes()[:8])
            self.assertEqual("incomplete",runner._cancel_gate_evidence(self.spec,0)["status"])

    def write_evidence(self):
        (self.output / "export-control-events.json").write_text(json.dumps(self.control))
        (self.output / "replay-timing.ndjson").write_text("\n".join(json.dumps(e) for e in self.timeline))

    def test_real_cancel_actions_and_bounded_event_order_are_required(self):
        self.assertEqual("evidence_complete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        original = json.dumps(self.control)
        changes = [lambda c: c["events"].pop(),
                   lambda c: c["events"][2].update(event="watchdog"),
                   lambda c: c["events"][2].update(timestamp_ms=self.base + 5000),
                   lambda c: c["events"][1].update(timestamp_ms=self.base + 8100),
                   lambda c: c["events"][2].update(timestamp_ms=self.base + 8700),
                   lambda c: c["events"][3].update(timestamp_ms=self.base + 9800),
                   lambda c: c["events"][0].update(run_id="old"),
                   lambda c: c["events"][0].update(private="forbidden"),
                   lambda c: c.update(capture_error="missing"),
                   lambda c: c.update(expires_at_ms=self.base),
                   lambda c: c["clock"].update(offset_max_ms=10000),
                   lambda c: c["clock_end"].update(offset_min_ms=7000, offset_max_ms=7020),
                   lambda c: c.pop("clock_end"),
                   lambda c: c["clock_end"].update(host_wall_ms=self.base + 25000),
                   lambda c: c.update(marker_absent=False),
                   lambda c: c.pop("marker_absent"),
                   lambda c: c["events"].append({"run_id": "run-1", "event": "entered", "timestamp_ms": self.base + 15000})]
        for change in changes:
            self.control = json.loads(original)
            change(self.control)
            self.write_evidence()
            self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        self.control = json.loads(original)
        self.write_evidence()
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 130)["status"])
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 1)["status"])

    def test_process_zero_without_real_press_or_assertions_never_turns_green(self):
        original = json.dumps(self.timeline)
        # Original steps 6/7/8/9 are actual press, cancelled, no Share, no gallery.
        for replay_step in (11, 13, 15, 17):
            self.timeline = json.loads(original)
            next(e for e in self.timeline if e["step"] == replay_step and e["type"] == "replay_action_stop")["ok"] = False
            self.write_evidence()
            extra = {"agent_device_state": "review_required", "layers": {"business": "review_required"}, "cases": []}
            with patch.object(runner, "_ingest_agent_device_result", return_value=extra):
                verdict = runner.ingest_agent_device_result(self.spec, 0)
            self.assertEqual("uncovered", verdict["agent_device_state"])
            self.assertEqual("failed", verdict["layers"]["business"])
            self.assertFalse(verdict["layers"]["green_from_process_zero"])
        self.timeline = json.loads(original)
        self.write_evidence()
        (self.evidence / "steps/step-6.png").unlink()
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])

    def test_truncated_and_corrupt_pngs_cannot_satisfy_cancel_evidence(self):
        shot = self.evidence / "steps/step-6.png"
        original = shot.read_bytes()
        for invalid in (original[:8], original[:-1], original[:-12], original[:48] + bytes([original[48] ^ 1]) + original[49:]):
            shot.write_bytes(invalid)
            self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        shot.write_bytes(original)
        self.assertEqual("evidence_complete", runner._cancel_gate_evidence(self.spec, 0)["status"])

    def test_retry_missing_actions_and_wrong_order_never_complete_evidence(self):
        original = json.dumps(self.timeline)
        self.assertEqual("evidence_complete", runner._cancel_gate_evidence(self.spec, 0)["retry_status"])
        for replay_step in (21, 23, 25, 27):  # Retry press, exact counts, Share, gallery.
            self.timeline = [e for e in json.loads(original) if not (e["step"] == replay_step and e["type"] == "replay_action_stop")]
            self.write_evidence()
            self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["retry_status"])
        self.timeline = json.loads(original)
        # Move the retry press before the original no-gallery assertion completes.
        earlier = next(e["ts"] for e in self.timeline if e["step"] == 17 and e["type"] == "replay_action_start")
        next(e for e in self.timeline if e["step"] == 21 and e["type"] == "replay_action_start")["ts"] = earlier
        self.write_evidence()
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["retry_status"])
        self.timeline = json.loads(original)
        self.write_evidence()
        (self.evidence / "steps/step-14.png").unlink()
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["retry_status"])

    def test_all_screenshots_need_start_and_must_precede_the_next_action(self):
        original = json.dumps(self.timeline)
        # Both an ordinary startup shot and the cancellation endpoint need starts.
        for replay_step in (2, 14):
            self.timeline = [e for e in json.loads(original) if not (e["step"] == replay_step and e["type"] == "replay_action_start")]
            self.write_evidence()
            self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        # A valid PNG captured at the Retry success endpoint is not a cancel shot.
        self.timeline = json.loads(original)
        late = next(e["ts"] for e in self.timeline if e["step"] == 24 and e["type"] == "replay_action_stop")
        for event in self.timeline:
            if event["step"] == 14: event["ts"] = late
        self.write_evidence()
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        # Capture may neither start before its action ends nor finish before start.
        for event_type, boundary_step, boundary_type in [("replay_action_start", 13, "replay_action_start"), ("replay_action_stop", 13, "replay_action_stop")]:
            self.timeline = json.loads(original)
            boundary = next(e["ts"] for e in self.timeline if e["step"] == boundary_step and e["type"] == boundary_type)
            next(e for e in self.timeline if e["step"] == 14 and e["type"] == event_type)["ts"] = boundary
            self.write_evidence()
            self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])

    def test_non_business_png_and_close_action_cannot_be_omitted(self):
        shot = self.evidence / "steps/step-2.png"
        original_png = shot.read_bytes()
        shot.unlink()
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        shot.write_bytes(original_png[:8])
        self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        shot.write_bytes(original_png)
        original_timing = json.dumps(self.timeline)
        close_step = next(e["step"] for e in self.timeline if e["command"] == "close")
        for event_type in ("replay_action_start", "replay_action_stop"):
            self.timeline = [e for e in json.loads(original_timing) if not (e["step"] == close_step and e["type"] == event_type)]
            self.write_evidence()
            self.assertEqual("incomplete", runner._cancel_gate_evidence(self.spec, 0)["status"])
        self.timeline = json.loads(original_timing)
        self.write_evidence()
        self.assertFalse((self.evidence / "steps/step-15.png").exists())
        self.assertEqual("evidence_complete", runner._cancel_gate_evidence(self.spec, 0)["status"])

    def test_completion_surface_exception_requires_both_stages_and_successful_sdk(self):
        (self.output / "cancel-surface.txt").write_text('{"label":"Share"}')
        task = {"edge_id": "export-cancel", "platform": "android", "state": "review_required", "exit_code": 0,
                "evidence_dir": str(self.output), "export_control": {"status": "evidence_complete", "retry_status": "evidence_complete"},
                "layers": {"execution": "ok", "script_checks": "executed_review_required", "agent_observation": "completed"}}
        runner._mark_cancel_uncovered(task)
        self.assertEqual("review_required", task["state"])
        for change in (lambda t: t["export_control"].pop("retry_status"),
                       lambda t: t["export_control"].update(status="incomplete"),
                       lambda t: t.update(platform="ios"), lambda t: t.update(state="failed"),
                       lambda t: t.update(exit_code=1), lambda t: t["layers"].update(script_checks="failed")):
            candidate = json.loads(json.dumps(task))
            change(candidate)
            runner._mark_cancel_uncovered(candidate)
            self.assertEqual("uncovered", candidate["state"])

    def test_device_clock_rejects_wall_jump_against_monotonic_elapsed(self):
        with patch.object(setup, "adb_shell", return_value="1700000000020000000"), patch.object(setup.time, "time_ns", side_effect=[1700000000000000000, 1700000000200000000]), patch.object(setup.time, "monotonic_ns", side_effect=[1000000000, 1020000000]):
            with self.assertRaisesRegex(ValueError, "bounded device clock"):
                setup._device_clock("fake-device")
        with patch.object(setup, "adb_shell", return_value="1700000000010000000"), patch.object(setup.time, "time_ns", side_effect=[1700000000000000000, 1700000000020000000]), patch.object(setup.time, "monotonic_ns", side_effect=[1000000000, 1020000000]):
            sample = setup._device_clock("fake-device")
            self.assertEqual(1000, sample["host_monotonic_ms"])
            self.assertEqual(1700000000000, sample["host_wall_ms"])

    def test_ios_control_dispatch_is_limited_to_two_export_edges(self):
        spec = {**self.spec, "agent_platform":"ios", "setup":"editor", "device":{"id":"AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"}}
        with patch.object(runner,"apply_setup",return_value={"journal":"private.json"}) as apply:
            for edge, mode, kind in (("export-cancel","hold-next","editor"), ("export-failure-recovery","fail-next","failure")):
                spec.update(edge_id=edge,setup=kind)
                runner._apply_agent_setup(spec,io.StringIO())
                self.assertEqual("editor",apply.call_args.args[0])
                self.assertEqual(mode,apply.call_args.kwargs["ios_export_mode"])
                self.assertEqual("run-1",apply.call_args.kwargs["ios_export_run_id"])
                self.assertIn((apply.call_args.kwargs["marker"],mode),{("exportcancel","hold-next"),("exportfailurerec","fail-next")})
            spec.update(edge_id="editor-style-then-export",setup="editor")
            runner._apply_agent_setup(spec,io.StringIO())
            self.assertNotIn("ios_export_mode",apply.call_args.kwargs)
            spec.update(setup="failure")
            apply.reset_mock()
            self.assertIsNone(runner._apply_agent_setup(spec,io.StringIO()))
            apply.assert_not_called()

    def test_gate_is_only_armed_for_android_cancel(self):
        spec = {**self.spec, "setup": "editor", "device": {"id": "fake-device"}}
        with patch.object(runner, "apply_setup", return_value={"journal": "private.json"}) as apply:
            runner._apply_agent_setup(spec, io.StringIO())
            self.assertEqual("run-1", apply.call_args.kwargs["export_cancel_run_id"])
            spec["edge_id"] = "editor-style-then-export"
            runner._apply_agent_setup(spec, io.StringIO())
            self.assertNotIn("export_cancel_run_id", apply.call_args.kwargs)


class AndroidFailureRecoveryChecks(unittest.TestCase):
    """Only local synthetic files and mocked device I/O; never a connected device."""

    def setUp(self):
        import hashlib
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(setup, 'SETUP_BACKUPS', self.root / 'locks'))
        self.marker = 'exportfailurerec-' + 'a' * 32
        self.local = setup.write_png(self.root / f'ewm-suite-{self.marker}-A.png', 2, 2, lambda y: b'\x00\x80\xff' * 2)
        self.valid = self.local.read_bytes()
        self.damaged = f'EWM deliberate source decode failure {self.marker}'.encode()
        self.remote_bytes = self.damaged
        self.state = dict(setup='failure', platform='android', serial='fake-serial', udid=None,
                          marker=self.marker, fixtures={'A': str(self.local)}, display_backup={},
                          private_backup={name: None for name in setup.ANDROID_CONFIG}, source_id='1',
                          damaged_remote=f'/sdcard/{setup.FIXTURE_FOLDER}/{self.local.name}',
                          source_recovery={'run_id': 'run-1', 'status': 'armed', 'size': len(self.valid),
                                           'valid_sha256': hashlib.sha256(self.valid).hexdigest(),
                                           'damaged_sha256': hashlib.sha256(self.damaged).hexdigest()})
        setup._claim_setup(self.state, self.root)
        self.state.update(phase='active', mutated=True)
        setup._save_setup(self.state)
        self.media_id = '1'
        def shell(serial, *args, **kwargs):
            self.assertEqual('fake-serial', serial)
            callback = setup._SETUP_STOP.get()
            if callback and callback(): raise InterruptedError('stopped')
            if args[:2] == ('content', 'query'):
                self.assertIn(f"_id=1 AND _display_name='{self.local.name}'", args[-1])
                return f'Row: 0 _id={self.media_id}'
            self.assertEqual(('sh', '-c'), args[:2])
            return 'regular'
        self.stack.enter_context(patch.object(setup, 'adb_shell', side_effect=shell))
        self.stack.enter_context(patch.object(setup, 'adb', side_effect=lambda *a, **kw: self.remote_bytes))
        def restore(serial, local, remote):
            self.assertEqual(('fake-serial', self.local, self.state['damaged_remote']), (serial, local, remote))
            self.remote_bytes = local.read_bytes()
        self.restore = self.stack.enter_context(patch.object(setup, 'restore_source', side_effect=restore))

    def repair(self, **kwargs):
        return setup.repair_failure_source(self.state, serial='fake-serial', run_id='run-1', **kwargs)

    def test_failure_source_restores_exact_bytes_once_and_preserves_journal(self):
        proof = self.repair()
        self.assertEqual(self.valid, self.remote_bytes)
        self.assertEqual(proof['valid_sha256'], proof['readback_sha256'])
        self.assertEqual('source_restored', setup.load_setup_backup(Path(self.state['journal']))['source_recovery']['status'])
        self.assertTrue(Path(self.state['lock']).is_file())  # Preference rollback still belongs to finally.
        with self.assertRaisesRegex(ValueError, 'one-shot'): self.repair()
        self.restore.assert_called_once()

    def test_failure_source_refuses_identity_drift_or_stop_before_write(self):
        for kind in ('local', 'remote', 'media', 'serial', 'run', 'journal', 'lock', 'stop'):
            with self.subTest(kind=kind):
                self.local.write_bytes(b'changed' if kind == 'local' else self.valid)
                self.remote_bytes = b'foreign bytes' if kind == 'remote' else self.damaged
                self.media_id = '2' if kind == 'media' else '1'
                setup._save_setup(self.state)
                if kind == 'journal':
                    data = json.loads(Path(self.state['journal']).read_text())
                    data['phase'] = 'restored'
                    Path(self.state['journal']).write_text(json.dumps(data))
                Path(self.state['lock']).write_text(json.dumps({'journal': 'other' if kind == 'lock' else self.state['journal']}))
                with self.assertRaises((ValueError, InterruptedError)):
                    setup.repair_failure_source(self.state, serial='other' if kind == 'serial' else 'fake-serial',
                                                run_id='other' if kind == 'run' else 'run-1', should_stop=lambda: kind == 'stop')
                self.restore.assert_not_called()

    def test_failure_source_readback_failure_consumes_repair_and_never_retries(self):
        self.restore.side_effect = lambda *a: None
        with self.assertRaisesRegex(ValueError, 'restored bytes'): self.repair()
        self.assertEqual('restoring_source', setup.load_setup_backup(Path(self.state['journal']))['source_recovery']['status'])
        with self.assertRaisesRegex(ValueError, 'one-shot'): self.repair()
        self.restore.assert_called_once()

    def batch_fixture(self):
        source = runner.REPO_ROOT / 'docs/testing/agent-device/scripts/export-failure-recovery@android.json'
        root = self.root / 'run-1'
        derived = root / 'scripts' / 'failure.json'
        manifest = runner.materialize_evidence_script(source, root, derived, {n: f'step-{n}.png' for n in range(1, 12)})
        evidence = runner.EvidenceEvents(manifest, root, lambda event: None)
        spec = {'edge_id': 'export-failure-recovery', 'agent_platform': 'android',
                'serial': 'fake-serial', 'step_evidence_root': str(root)}
        return spec, manifest, evidence, runner.parse_script(derived, 'android')

    def test_failure_batch_repairs_between_failure_shots_and_real_retry(self):
        for missing_shot, stopped, broken_restore in [(False, False, False), (True, False, False), (False, True, False), (False, False, True)]:
            with self.subTest(missing_shot=missing_shot, stopped=stopped, broken_restore=broken_restore), tempfile.TemporaryDirectory() as folder:
                # A distinct evidence root prevents reuse of a previous step image.
                old_root, self.root = self.root, Path(folder)
                spec, manifest, evidence, rows = self.batch_fixture()
                self.root = old_root
                calls = []
                def run_batch(batch):
                    calls.append(('batch', batch[0]['n'], batch[-1]['n']))
                    for row in batch:
                        if row['command'] == 'screenshot':
                            if missing_shot: continue
                            setup.write_png(Path(row['input']['path']), 2, 2, lambda y: b'\x00\x80\xff' * 2)
                        evidence({'type': 'replay_action_stop', 'step': row['n'], 'command': row['command'], 'ok': True})
                    return 0
                def repair(*args, **kwargs):
                    self.assertEqual(set(range(1, 8)), evidence.evidenced)
                    calls.append(('repair',))
                    if broken_restore: raise ValueError('readback mismatch')
                    return {'run_id': 'run-1'}
                with patch.object(runner, 'repair_failure_source', side_effect=repair):
                    code = runner._run_android_failure_recovery(spec, self.state, manifest, evidence, rows,
                                                               run_batch, lambda: stopped, io.StringIO())
                if missing_shot or stopped or broken_restore:
                    self.assertNotEqual(0, code)
                    self.assertNotIn(('batch', 15, 21), calls)
                    self.assertEqual('unverified', spec['source_recovery']['status'])
                else:
                    self.assertEqual(0, code)
                    self.assertEqual([('batch', 1, 14), ('repair',), ('batch', 15, 21)], calls)
                    self.assertEqual('evidence_complete', spec['source_recovery']['status'])

    def test_failure_sdk_zero_without_repair_or_rollback_is_uncovered(self):
        spec = {'edge_id': 'export-failure-recovery', 'agent_platform': 'android'}
        for control in (None, {'status': 'evidence_complete', 'private_restored': False}):
            spec['source_recovery'] = control
            with patch.object(runner, '_ingest_agent_device_result', return_value={'agent_device_state': 'review_required', 'layers': {}, 'cases': [{}]}):
                extra = runner.ingest_agent_device_result(spec, 0)
            self.assertEqual('uncovered', extra['agent_device_state'])
            self.assertEqual('failed', extra['cases'][0]['status'])

    def test_failure_runner_wires_repair_then_rollback_and_records_evidence(self):
        source = runner.REPO_ROOT / 'docs/testing/agent-device/scripts/export-failure-recovery@android.json'
        output = self.root / 'output'
        spec = {'builder': 'agent-device', 'edge_id': 'export-failure-recovery', 'agent_platform': 'android',
                'serial': 'fake-serial', 'agent_device_output': str(output), 'step_evidence_root': str(self.root / 'run-1'),
                'step_evidence_task': {'edge': 'export-failure-recovery', 'repeat': {'k': 1, 'n': 1}},
                'cmd': ['fake-sdk', 'batch', '--steps-file', str(source), '--platform', 'android', '--serial', 'fake-serial', '--session', 'owned']}
        calls = []
        def batch(cmd, rows, logf, tee_stdout, on_proc, on_step_event, should_stop, platform, env):
            calls.append(('batch', rows[0]['n'], rows[-1]['n']))
            for row in rows:
                if row['command'] == 'screenshot':
                    setup.write_png(Path(row['input']['path']), 2, 2, lambda y: b'\x00\x80\xff' * 2)
                on_step_event(platform, {'type': 'replay_action_stop', 'step': row['n'], 'command': row['command'], 'ok': True})
            return 0
        def repair(*args, **kwargs):
            calls.append(('repair',))
            return self.repair()
        with patch.object(runner, 'maybe_prepare_ios_runner'), patch.object(runner, '_apply_agent_setup', return_value=self.state), \
             patch.object(runner, '_run_batched_steps', side_effect=batch), patch.object(runner, 'repair_failure_source', side_effect=repair), \
             patch.object(runner, '_restore_agent_setup', side_effect=lambda *a: calls.append(('rollback',))), \
             patch.object(runner, 'release_agent_session'), patch.object(runner, '_publish_agent_device_live'), \
             patch.object(runner, '_ingest_agent_device_result', return_value={'agent_device_state': 'review_required', 'cases': [], 'layers': {}}):
            code, _, extra = runner.run_task(spec, io.StringIO())
        self.assertEqual(0, code)
        self.assertEqual([('batch', 1, 14), ('repair',), ('batch', 15, 21), ('rollback',)], calls)
        proof = json.loads((output / 'android-failure-recovery.json').read_text())
        self.assertTrue(proof['private_restored'])
        self.assertEqual('evidence_complete', extra['export_control']['status'])
        self.assertLessEqual(proof['failure_observed_monotonic_ns'], proof['repair']['started_monotonic_ns'])
        self.assertLessEqual(proof['repair']['finished_monotonic_ns'], proof['retry_dispatch_monotonic_ns'])



class AndroidTemplateCrudChecks(unittest.TestCase):
    """Real phase files/PNG mappings; fake SDK responses only, never a device."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = runner.REPO_ROOT / 'docs/testing/agent-device/scripts/editor-to-template-sheet@android.json'
        self.state = {'setup': 'editor', 'platform': 'android', 'serial': 'owned-serial',
                      'marker': 'editortotemplate-' + 'a' * 32, 'source_id': '123', 'phase': 'active',
                      'journal': str(self.root / 'backup.json'), 'lock': str(self.root / 'lock.json')}
        Path(self.state['lock']).write_text(json.dumps({'journal': self.state['journal']}))
        self.spec = {'builder': 'agent-device', 'edge_id': 'editor-to-template-sheet', 'agent_platform': 'android',
                     'serial': 'owned-serial', 'step_evidence_root': str(self.root / 'run-1'),
                     'agent_device_output': str(self.root / 'output'),
                     'step_evidence_task': {'edge': 'editor-to-template-sheet', 'repeat': {'k': 1, 'n': 1}},
                     'cmd': ['fake-sdk', 'batch', '--steps-file', str(self.source), '--platform', 'android',
                             '--serial', 'owned-serial', '--session', 'owned-session']}
        self.nonce = 'EWM ' + self.state['marker']
        self.exists = self.in_list = self.applied = self.stopped = self.inline = False
        self.fault = None
        self.calls, self.events, self.restores = [], [], []
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(setup, 'load_setup_backup', return_value=dict(self.state)))
        self.stack.enter_context(patch.object(runner, '_run_batched_steps', side_effect=self.batch))
        self.stack.enter_context(patch.object(runner, '_apply_agent_setup', return_value=self.state))
        self.stack.enter_context(patch.object(runner, 'maybe_prepare_ios_runner'))
        self.stack.enter_context(patch.object(runner, '_restore_agent_setup', side_effect=lambda state, log: self.restores.append(state)))
        self.release = self.stack.enter_context(patch.object(runner, 'release_agent_session'))
        self.stack.enter_context(patch.object(runner, '_publish_agent_device_live'))
        self.stack.enter_context(patch.object(runner, '_ingest_agent_device_result',
            return_value={'agent_device_state': 'review_required', 'cases': [{}], 'layers': {}}))

    def snapshot(self):
        def node(index, tag='', label=None, parent=10, y=100, kind='android.view.View'):
            result = {'index': index, 'identifier': tag, 'parentIndex': parent, 'type': kind,
                      'rect': {'x': 0, 'y': y, 'width': 300, 'height': 50}, 'hittable': True}
            if label is not None:
                result['label'] = label
            return result
        if self.in_list:
            nodes = [node(10, 'templateListSheet', parent=None), node(11, 'templateAddButton'), node(12)]
            if self.exists:
                nodes += [node(13, 'templateRow-2', self.nonce, 12), node(14, 'templateDeleteButton-2')]
                if self.fault == 'ambiguous':
                    nodes += [node(16, 'templateRow-3', self.nonce, 12, 125), node(17, 'templateDeleteButton-3')]
            nodes += [node(20, 'templateRow-1', 'Existing user template', 12, 150 if self.exists else 100),
                      node(21, 'templateDeleteButton-1')]
            if self.fault == 'prefix' and self.applied:
                nodes[-2]['label'] = 'Concurrent edit'
        else:
            if self.inline:
                nodes = [node(10, 'watermarkTextContentInline', parent=None), node(12, 'watermarkTextTemplateIcon'),
                         node(11, 'watermarkTextEditField', self.nonce if self.applied else 'original text', kind='android.widget.EditText')]
            else:
                nodes = [node(10, 'watermarkTextContent', parent=None),
                         node(11, label=self.nonce if self.applied else 'original text', kind='android.widget.TextView')]
        return {'appBundleId': 'me.rosuh.easywatermark.debug', 'truncated': False,
                'visibility': {'partial': False}, 'snapshotQuality': {'state': 'healthy'}, 'nodes': nodes}

    def batch(self, cmd, rows, log, tee, on_proc, on_event, should_stop, platform, env, deadline_s=420):
        self.assertLessEqual(deadline_s, 20)
        self.assertEqual('owned-serial', runner._cmd_flag(cmd, '--serial'))
        for row in rows:
            command, inp = row['command'], row['input']
            self.calls.append((command, inp))
            on_event(platform, {'type': 'replay_action_start', 'step': row['n'], 'command': command})
            selector = inp.get('target', {}).get('selector', '')
            if command == 'open':
                self.assertTrue(inp['relaunch'])
                self.assertIn('content://media/external_primary/images/media/123', inp['launchArgs'])
                self.in_list = False
                if self.fault == 'derived' and 'add-persist-sdk-' in str(log.secondary.name):
                    derived = Path(log.secondary.name).parent / 'add-persist.json'
                    derived.write_text(derived.read_text() + ' ')
            elif command == 'press':
                if selector == 'id="watermarkTextTemplateIcon"':
                    self.in_list = True
                elif selector == 'id="templateEditConfirm"':
                    self.exists = True
                    if self.fault in {'stop', 'stop-cleanup-fails'}:
                        self.stopped = True
                elif selector == 'id="templateUseConfirm"':
                    self.applied, self.in_list = True, False
                elif selector == 'id="templateDeleteConfirm"':
                    if self.fault == 'stop-cleanup-fails':
                        return 1
                    self.exists = False
                elif 'templateDeleteButton-' in selector:
                    self.assertEqual('id="templateDeleteButton-2"', selector)
            elif command == 'fill':
                self.assertEqual(self.nonce, inp['text'])
            elif command == 'screenshot':
                setup.write_png(Path(inp['path']), 2, 2, lambda y: b'\x00\x80\xff' * 2)
            if not (self.fault == 'bad-open' and command == 'open'):
                log.write(json.dumps({'success': True, 'data': {'total': 1, 'executed': 1, 'results': [
                    {'step': 1, 'command': command, 'ok': True, 'data': self.snapshot() if command == 'snapshot' else {}}]}}) + '\n')
            on_event(platform, {'type': 'replay_action_stop', 'step': row['n'], 'command': command, 'ok': True})
        return 0

    def run_case(self):
        return runner.run_task(self.spec, io.StringIO(), on_step_event=lambda p, e: self.events.append(e),
                               should_stop=lambda: self.stopped)

    def test_template_two_immutable_phases_exact_crud_and_global_evidence(self):
        import hashlib
        original = self.source.read_bytes()
        code, _, extra = self.run_case()
        self.assertEqual(0, code)
        proof = extra['template_crud']
        self.assertEqual('evidence_complete', proof['status'])
        self.assertTrue(proof['persisted_after_relaunch'])
        self.assertTrue(proof['applied_exactly'])
        self.assertEqual('verified_deleted', proof['cleanup'])
        self.assertTrue(proof['private_restored'])
        self.assertFalse(self.exists)
        self.assertEqual(original, self.source.read_bytes())
        self.assertEqual(2, len(proof['phases']))
        mapped = []
        for phase in proof['phases']:
            self.assertEqual(hashlib.sha256(Path(phase['source']).read_bytes()).hexdigest(), phase['source_sha256'])
            self.assertEqual(hashlib.sha256(Path(phase['script']).read_bytes()).hexdigest(), phase['script_sha256'])
            self.assertEqual(hashlib.sha256(original).hexdigest(), phase['canonical_source_sha256'])
            mapped.extend(m['step'] for m in phase['mapping'] if m['kind'] == 'action')
        self.assertEqual(list(range(1, 53)), mapped)
        self.assertEqual(49, len([e for e in self.events if e.get('shot')]))
        selectors = [v.get('target', {}).get('selector') for c, v in self.calls if c == 'press']
        self.assertLess(selectors.index('id="templateEditConfirm"'), selectors.index('id="templateRow-2" label="' + self.nonce + '"'))
        self.assertLess(selectors.index('id="templateUseConfirm"'), selectors.index('id="templateDeleteButton-2"'))
        self.assertEqual(4, len([c for c, _ in self.calls if c == 'open']))
        self.assertEqual([self.state], self.restores)
        self.release.assert_called_once()

    def test_template_ambiguous_nonce_refuses_use_and_delete_but_restores(self):
        self.fault = 'ambiguous'
        code, _, extra = self.run_case()
        self.assertNotEqual(0, code)
        self.assertEqual('failed', extra['template_crud']['cleanup'])
        self.assertFalse(self.applied)
        self.assertFalse(any('templateDeleteButton-' in v.get('target', {}).get('selector', '') for c, v in self.calls))
        self.assertEqual([self.state], self.restores)
        self.release.assert_called_once()

    def test_template_inline_entry_has_exact_content_and_contiguous_global_mapping(self):
        self.inline = True
        code, _, extra = self.run_case()
        self.assertEqual(0, code)
        proof = extra['template_crud']
        self.assertEqual('inline', proof['entry'])
        self.assertEqual([4, 5, 20, 21, 32, 45, 46], proof['omitted_source_steps'])
        mapping = [m for p in proof['phases'] for m in p['mapping'] if m['kind'] == 'action']
        self.assertEqual(list(range(1, 46)), [m['step'] for m in mapping])
        self.assertEqual([n for n in range(1, 53) if n not in proof['omitted_source_steps']], [m['source_step'] for m in mapping])
        self.assertFalse(any(v.get('target', {}).get('selector') == 'id="watermarkTextContent"' for _, v in self.calls))
        self.assertTrue(proof['applied_exactly'])
        self.assertEqual(42, len([e for e in self.events if e.get('shot')]))

    def test_template_stop_cleans_only_owned_record_and_remains_stopped(self):
        self.fault = 'stop'
        code, _, extra = self.run_case()
        self.assertEqual(130, code)
        self.assertEqual('unverified', extra['template_crud']['status'])
        self.assertEqual('verified_deleted', extra['template_crud']['cleanup'])
        self.assertFalse(self.exists)
        self.assertFalse(self.applied)
        self.assertEqual([self.state], self.restores)
        self.release.assert_called_once()

    def test_template_stop_cleanup_failure_is_visible_and_private_restore_still_runs(self):
        self.fault = 'stop-cleanup-fails'
        code, _, extra = self.run_case()
        self.assertEqual(130, code)
        self.assertEqual('failed', extra['template_crud']['cleanup'])
        self.assertTrue(extra['template_crud']['private_restored'])
        self.assertTrue(self.exists)
        self.assertEqual([self.state], self.restores)
        self.release.assert_called_once()

    def test_template_source_hash_guard_prevents_any_sdk_mutation(self):
        with patch.object(runner, '_TEMPLATE_SOURCE_SHA256', 'wrong'):
            code, _, extra = self.run_case()
        self.assertNotEqual(0, code)
        self.assertEqual([], self.calls)
        self.assertIn('source', extra['template_crud']['reason'])
        self.assertEqual([self.state], self.restores)

    def test_template_changed_prefix_does_not_count_absence_as_database_scan(self):
        self.fault = 'prefix'
        code, _, extra = self.run_case()
        self.assertNotEqual(0, code)
        self.assertIn('prefix', extra['template_crud']['reason'])
        self.assertEqual('failed', extra['template_crud']['cleanup'])
        self.assertEqual([self.state], self.restores)

    def test_template_sdk_zero_without_owned_cleanup_is_uncovered(self):
        result = runner.ingest_agent_device_result(self.spec, 0)
        self.assertEqual('uncovered', result['agent_device_state'])
        self.assertEqual('failed', result['cases'][0]['status'])

    def test_template_derived_hash_drift_stops_before_add(self):
        self.fault = 'derived'
        code, _, extra = self.run_case()
        self.assertNotEqual(0, code)
        self.assertIn('derived', extra['template_crud']['reason'])
        self.assertFalse(self.exists)
        self.assertFalse(any(v.get('target', {}).get('selector') == 'id="templateEditConfirm"' for _, v in self.calls))
        self.assertEqual([self.state], self.restores)

    def test_template_private_restore_failure_keeps_false_and_still_closes_session(self):
        with patch.object(runner, '_restore_agent_setup', side_effect=setup.SetupRestoreError('synthetic restore failed')):
            with self.assertRaises(setup.SetupRestoreError):
                self.run_case()
        self.release.assert_called_once()
        manifest = next((self.root / 'run-1').glob('scripts/*/template-crud.json'))
        self.assertFalse(json.loads(manifest.read_text())['private_restored'])

    def test_single_batch_response_requires_one_actual_matching_action(self):
        valid = {'success': True, 'data': {'total': 1, 'executed': 1, 'results': [
            {'step': 1, 'command': 'open', 'ok': True, 'data': {'session': 'owned'}}]}}
        self.assertEqual({'session': 'owned'}, runner._single_batch_response(json.dumps(valid), 'open'))
        self.assertTrue(runner._batch_ok('warning\n' + json.dumps(valid), 0, ' Open '))
        self.assertFalse(runner._batch_ok(json.dumps(valid), 1, 'open'))
        invalid = ['', '{broken', 'finished', '{"success":true}',
                   'warning\n{"success":false}', json.dumps(valid) + '\n' + json.dumps(valid)]
        for field, value in [('executed', 0), ('total', 2), ('executed', True)]:
            changed = json.loads(json.dumps(valid))
            changed['data'][field] = value
            invalid.append(json.dumps(changed))
        for field, value in [('command', 'close'), ('ok', False), ('step', 2)]:
            changed = json.loads(json.dumps(valid))
            changed['data']['results'][0][field] = value
            invalid.append(json.dumps(changed))
        for output in invalid:
            with self.subTest(output=output), self.assertRaises(ValueError):
                runner._single_batch_response(output, 'open')
            self.assertFalse(runner._batch_ok(output, 0, 'open'))

    def test_template_zero_exit_without_open_response_cannot_reach_add(self):
        self.fault = 'bad-open'
        code, _, extra = self.run_case()
        self.assertNotEqual(0, code)
        self.assertEqual('unverified', extra['template_crud']['status'])
        self.assertEqual(['open'], [command for command, _ in self.calls])
        self.assertEqual([self.state], self.restores)
        self.release.assert_called_once()

    def test_template_cli_and_console_persist_proof_and_live_manifest_on_failure_and_stop(self):
        for mode in ('cli', 'console'):
            for stopped in (False, True):
                with self.subTest(mode=mode, stopped=stopped):
                    self.exists = self.in_list = self.applied = self.stopped = False
                    self.fault = 'stop-cleanup-fails' if stopped else None
                    task = {'id': 'edge:editor-to-template-sheet@android#agent', 'edge': 'editor-to-template-sheet',
                            'platform': 'android', 'state': 'pending'}
                    rec = {'id': mode + ('-stopped' if stopped else '-complete'), 'state': 'running',
                           'started': '2026-10-10T00:00:00Z', 'tasks': [task]}
                    holder = runner.ActiveRun(rec) if mode == 'cli' else runner.RunManager()
                    if mode == 'console':
                        holder.active = rec
                    def sdk(*args, **kwargs):
                        self.assertIn('template_crud_manifest', task)  # on_spec runs before any SDK action.
                        result = self.batch(*args, **kwargs)
                        if self.stopped:
                            holder.stop_requested = True
                        return result
                    with patch.object(runner, 'RUNS_DIR', self.root / 'records'), \
                         patch.object(runner, 'task_run_spec', return_value=dict(self.spec)), \
                         patch.object(runner, '_ingest_agent_device_result', return_value={
                             'agent_device_state': 'review_required', 'cases': [{}], 'layers': {}}), \
                         patch.object(runner, '_run_batched_steps', side_effect=sdk):
                        if mode == 'cli':
                            runner._execute_task(holder, rec, task, io.StringIO())
                        else:
                            holder._execute_one_task(rec, task, io.StringIO())
                        runner.finalize_record(rec)
                        saved = json.loads((runner.RUNS_DIR / (rec['id'] + '.json')).read_text())['tasks'][0]
                    proof = saved['template_crud']
                    self.assertEqual(self.nonce, proof['nonce'])
                    self.assertEqual('2', proof['row_id'])
                    self.assertTrue(proof['private_restored'])
                    self.assertEqual(proof['manifest'], saved['template_crud_manifest'])
                    self.assertTrue(Path(saved['template_crud_manifest']).is_file())
                    self.assertGreaterEqual(len(proof['phases']), 1)
                    self.assertEqual('stopped' if stopped else 'review_required', saved['state'])
                    self.assertEqual('failed' if stopped else 'verified_deleted', proof['cleanup'])
                    if stopped:
                        self.assertIn('cleanup_reason', proof)

if __name__ == '__main__':
    unittest.main()
