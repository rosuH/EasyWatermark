#!/usr/bin/env python3
"""No devices: evidence provenance, strict mapping, failure and runner integration."""
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock

import testmap_steps as steps
import testmap_run as runner

PNG = b'\x89PNG\r\n\x1a\nfixture'


class EvidenceChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source.ad'
        self.source.write_text('context platform=ios\nenv TEXT="a b"\n# unchanged\nopen app\npress label="Next"\nclose\n')
        self.run = self.root / 'run'
        self.manifest = steps.materialize_evidence_script(self.source, self.run,
            self.run / 'scripts' / 'actual.ad', {1: 'one.png', 2: 'two.png', 3: 'three.png'})
        self.events = []
        self.sink = steps.EvidenceEvents(self.manifest, self.run, self.events.append)

    def test_malformed_events_do_not_interrupt_later_valid_evidence(self):
        invalid = [
            {'type': [], 'step': 1},
            {'type': 'replay_action_start', 'step': []},
            {'type': 'replay_action_start', 'step': True},
            {'type': 'replay_action_start', 'step': 1.0},
            {'type': 'replay_action_start', 'step': 0},
            {'type': 'replay_action_stop', 'step': 1, 'replayPath': 7, 'ok': True},
            {'type': 'replay_action_stop', 'step': 1, 'replayPath': {}, 'ok': True},
            {'type': 'replay_action_stop', 'step': 1, 'replayPath': 'bad\x00path', 'ok': True},
        ]
        for payload in invalid:
            self.sink(json.dumps(payload))
        self.assertEqual([], self.events)
        self.sink({'type': 'replay_action_stop', 'step': 1, 'ok': True})
        (self.run / 'steps' / 'one.png').write_bytes(PNG)
        self.sink({'type': 'replay_action_stop', 'step': 2, 'ok': True})
        self.assertEqual('one.png', self.events[-1]['shot'])

    def test_source_preserved_and_exact_map(self):
        self.assertNotIn('screenshot', self.source.read_text())
        derived = Path(self.manifest['script']).read_text()
        self.assertEqual(['open', 'screenshot', 'press', 'screenshot', 'close'],
                         [row['command'] for row in steps.parse_ad(derived)])
        self.assertEqual([1, 1, 2, 2, 3], [m['step'] for m in self.manifest['mapping']])
        self.assertNotEqual(self.manifest['source_sha256'], self.manifest['script_sha256'])
        self.assertNotIn('planDigest', self.manifest)
        self.assertIn('source digest is not interchangeable', self.manifest['resume'])

    def test_exact_artifact_only_metadata(self):
        self.sink({'type': 'replay_action_stop', 'step': 1, 'ok': True})
        self.assertNotIn('shot', self.events[-1])
        (self.run / 'steps' / 'one.png').write_bytes(PNG)
        self.sink({'type': 'replay_action_stop', 'step': 2, 'ok': True, 'ts': 'completion'})
        self.assertEqual('one.png', self.events[-1]['shot'])
        self.assertEqual('completion', self.events[-1]['shot_capture']['completed_at'])
        self.sink({'type': 'replay_action_start', 'step': 3})
        self.assertEqual(2, self.events[-1]['step'])
        rows = steps.parse_script(self.source, 'ios')
        for event in self.events:
            steps.apply_event(rows, event)
        self.assertEqual('one.png', steps.public_steps(rows)[0]['shot'])
        self.assertEqual(self.events[1]['shot_capture'], runner._step_public(rows[0], {})['shot_capture'])

    def test_failure_close_and_missing_capture_have_no_substitute(self):
        for step, ok in ((1, True), (3, False), (5, True)):
            self.sink({'type': 'replay_action_stop', 'step': step, 'ok': ok})
        self.sink.finish()
        errors = [e for e in self.events if e['type'] == 'step_evidence']
        self.assertEqual({1, 2, 3}, {e['step'] for e in errors})
        self.assertTrue(all(e.get('shot_error') and not e.get('shot') for e in errors))

    def test_missing_invalid_and_escaped_png_rejected(self):
        for target in ('missing', 'invalid', 'symlink'):
            image = self.run / 'steps' / 'one.png'
            if image.exists() or image.is_symlink():
                image.unlink()
            if target == 'invalid':
                image.write_text('not a PNG')
            if target == 'symlink':
                outside = self.root / 'outside.png'
                outside.write_bytes(PNG)
                image.symlink_to(outside)
            self.sink({'type': 'replay_action_stop', 'step': 2, 'ok': True})
            self.assertIn('shot_error', self.events[-1])
            self.assertNotIn('shot_capture', self.events[-1])

    def test_reused_target_and_unknown_syntax_refused(self):
        (self.run / 'steps' / 'one.png').write_bytes(PNG)
        with self.assertRaises(ValueError):
            steps.materialize_evidence_script(self.source, self.run, self.run / 'other.ad', {1:'one.png'})
        self.source.write_text('include other.ad\n')
        with self.assertRaises(ValueError):
            steps.materialize_evidence_script(self.source, self.run, self.run / 'other.ad', {})
        with self.assertRaises(ValueError):
            steps.confined_path(self.run, self.root / 'escape.png')

    def test_batch_uses_sdk_input_preserves_actions_and_serial_order(self):
        source = self.root / 'source.json'
        source.write_text(json.dumps([{'command':'press', 'input':{'x':1,'y':2}}, {'command':'close'}]))
        manifest = steps.materialize_evidence_script(source, self.run, self.run / 'scripts' / 'batch.json', {1:'batch.png'})
        rows = steps.parse_script(Path(manifest['script']))
        self.assertEqual(['press','screenshot','close'], [r['command'] for r in rows])
        command = runner._batch_one(['agent-device','batch','--session','owned','--json'], rows[1])
        payload = json.loads(command[command.index('--steps') + 1])
        self.assertEqual({'command': 'screenshot', 'input': {'path': str((self.run / 'steps' / 'batch.png').resolve()), 'stabilize': False}}, payload[0])
        calls = []
        def child(proc, logf, tee, **kwargs):
            calls.append('complete')
            logf.write('{"success":true}')
            return 0
        with patch.object(runner.subprocess, 'Popen', side_effect=lambda cmd, **kw: calls.append(json.loads(cmd[3])[0]['command']) or MagicMock()), patch.object(runner, '_tee_child', side_effect=child):
            self.assertEqual(0, runner._run_batched_steps(['agent-device','batch'], rows, io.StringIO(), False, None, None, None, 'ios', {}))
        self.assertEqual(['press','complete','screenshot','complete','close','complete'], calls)

    def test_mapping_symlink_cannot_overwrite_outside_file(self):
        mapping = Path(self.manifest['script']).with_suffix('.mapping.json')
        mapping.unlink()
        outside = self.root / 'outside.json'
        outside.write_text('keep')
        mapping.symlink_to(outside)
        with self.assertRaisesRegex(ValueError, 'symlink'):
            steps.materialize_evidence_script(self.source, self.run, Path(self.manifest['script']), {1:'one.png',2:'two.png',3:'three.png'})
        with self.assertRaisesRegex(ValueError, 'symlink'):
            steps.record_sdk_plan_digest(mapping, self.root, self.run)
        self.assertEqual('keep', outside.read_text())

    def test_stop_during_cancel_surface_does_not_start_android_fallback(self):
        stopped = [False]
        calls = []
        def capture(cmd, **kwargs):
            self.assertFalse(kwargs['should_stop']())
            self.assertEqual(.2, kwargs['stop_grace_s'])
            stopped[0] = True
            calls.append(cmd)
            return runner.subprocess.CompletedProcess(cmd, 130, 'partial capture', '')
        with patch.object(runner, 'run_captured', side_effect=capture):
            runner._capture_cancel_surface(['fake', '--session', 'owned', '--serial', 'fake'],
                {'agent_device_output':str(self.root / 'cancel')}, io.StringIO(), should_stop=lambda: stopped[0])
        self.assertEqual(1, len(calls))
        self.assertIn('snapshot', calls[0])

    def test_only_sdk_reported_plan_digest_is_saved(self):
        path = Path(self.manifest['script']).with_suffix('.mapping.json')
        sdk = self.root / 'sdk'
        sdk.mkdir()
        steps.record_sdk_plan_digest(path, sdk, self.run)
        self.assertIsNone(json.loads(path.read_text())['sdk_plan_digest'])
        digest = 'b' * 64
        (sdk / 'replay.log').write_text(json.dumps({'resume': {'planDigest':digest}}))
        steps.record_sdk_plan_digest(path, sdk, self.run)
        saved = json.loads(path.read_text())
        self.assertEqual(digest, saved['sdk_plan_digest'])
        self.assertNotEqual(saved['source_sha256'], saved['sdk_plan_digest'])

    def test_runner_materializes_before_spawn_and_stop_restores_first(self):
        calls, observed = [], []
        output = self.root / 'sdk'
        spec = {'builder':'agent-device', 'cmd':['agent-device','replay',str(self.source),'--platform','ios'],
                'agent_device_output':str(output), 'edge_id':'export-cancel',
                'step_evidence_root':str(self.root / 'integration'), 'step_evidence_task':{'edge':'edge'}}
        stopped = [False]
        def child(*args, **kwargs):
            stopped[0] = True
            return 130
        with patch.object(runner, 'maybe_prepare_ios_runner'), patch.object(runner, '_apply_agent_setup', return_value={}), patch.object(runner.subprocess, 'Popen', return_value=MagicMock()) as spawn, patch.object(runner, '_tee_child', side_effect=child), patch.object(runner, '_tail_timing'), patch.object(runner, '_restore_agent_setup', side_effect=lambda *args: calls.append('restore')), patch.object(runner, 'release_agent_session', side_effect=lambda *args: calls.append('release')), patch.object(runner, '_capture_cancel_surface', side_effect=lambda *args: calls.append('capture')), patch.object(runner, 'ingest_agent_device_result', return_value={}), patch.object(runner, '_publish_agent_device_live'):
            runner.run_task(spec, io.StringIO(), on_spec=observed.append, should_stop=lambda: stopped[0])
        actual = spawn.call_args.args[0]
        self.assertEqual('test', actual[1])
        self.assertNotEqual(str(self.source), actual[2])
        self.assertIn('screenshot', Path(actual[2]).read_text())
        self.assertEqual(['restore','release'], calls)
        self.assertTrue(any(s.get('step_evidence_manifest') for s in observed))


if __name__ == '__main__':
    unittest.main()
