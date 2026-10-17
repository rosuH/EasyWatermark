#!/usr/bin/env python3
"""Device-free regression for route identity, continuity, and owned cleanup."""
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import testmap_route as route
import testmap_run as runner
import testmap_console as console


class RouteCheck(unittest.TestCase):
    def test_continuity_identity_and_cleanup(self):
        tasks = ["edge:editor-to-text-edit-sheet@android#agent", "edge:editor-typeface-style@android#agent"]
        plan = route.route_plan(tasks)
        self.assertTrue(plan["supported"])
        for rejected in [tasks[::-1], tasks[:1], [tasks[0], "edge:export-save-success@android#agent"],
                         [tasks[0], "edge:editor-to-about@android#l2"], [tasks[0], tasks[0]]]:
            self.assertFalse(route.route_plan(rejected)["supported"])
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            metadata = route.materialize_route(plan, root)
            script = Path(metadata["route_script"]).read_text()
            self.assertEqual(1, sum(line.startswith("open ") for line in script.splitlines()))
            self.assertNotIn("\nclose", script)
            self.assertEqual(17, len(metadata["route_provenance"]))
            self.assertEqual(tasks[1], metadata["route_provenance"][-1]["source_task_id"])
            self.assertEqual(9, metadata["route_provenance"][-1]["source_step"])
            spec = {**runner._agent_device_spec("editor-to-text-edit-sheet", "android", "emulator-fixture"),
                    **metadata, "edge_id": metadata["route_id"], "needs_device": None,
                    "agent_device_output": str(root / "output"), "step_evidence_root": str(root),
                    "step_evidence_task": {"id": "route:fixture@android#agent", "repeat": {"k": 1, "n": 1}},
                    "device": {"id": "emulator-fixture"},
                    "cmd": ["agent-device", "replay", metadata["route_script"], "--platform", "android",
                            "--serial", "emulator-fixture", "--session", metadata["route_session"]]}
            task = runner._record_task("route:fixture@android#agent", spec)
            self.assertIsNone(task["edge"])
            self.assertIsNone(runner.edge_result_in_run({"tasks": [{**task, "state": "review_required"}]}, "editor-to-text-edit-sheet"))
            record = {"id": "route-fixture", "tasks": [task], "selection": tasks, "state": "running"}
            with patch.object(runner, "new_record", return_value=record), patch.object(runner, "RUNS_DIR", root), patch.object(runner, "REPO_ROOT", root), \
                 patch.object(runner, "write_record"), patch.object(runner, "run_record", return_value=0):
                self.assertEqual(0, runner.run_foreground(tasks, repeats=2, execution_mode="route"))
            self.assertEqual("route", record["execution_mode"])
            self.assertEqual(2, len(record["tasks"]))
            self.assertEqual({"k": 2, "n": 2}, record["tasks"][1]["repeat"])
            self.assertNotEqual(record["tasks"][0]["route_session"], record["tasks"][1]["route_session"])
            self.assertTrue(all(item["edge"] is None for item in record["tasks"]))
            handler = object.__new__(console.Handler)
            replies = []
            handler._json = lambda status, body: replies.append((status, body))
            with patch.object(console, "MANAGER") as manager:
                handler.path = "/api/route-plan"
                handler._read_json = lambda: {"tasks": tasks, "device": "auto"}
                handler.do_POST()
                self.assertEqual(200, replies[-1][0])
                self.assertTrue(replies[-1][1]["supported"])
                handler.path = "/api/run"
                for invalid in ([], {}, None):
                    handler._read_json = lambda: {"tasks": tasks, "execution_mode": invalid}
                    handler.do_POST()
                    self.assertEqual(400, replies[-1][0])
                manager.start.assert_not_called()
            for scenario in ("success", "failure", "stop", "exception", "restore_failure"):
                with self.subTest(scenario=scenario):
                    branch = {**spec, "step_evidence_root": str(root / scenario)}
                    setup = {"journal": str(root / "recovery.json")}
                    tee = Mock(side_effect=RuntimeError("spawned child failed")) if scenario == "exception" else Mock(return_value=1 if scenario == "failure" else 0)
                    restore = Mock(side_effect=RuntimeError("retain recovery journal")) if scenario == "restore_failure" else Mock()
                    published_rows = []
                    with patch.object(runner, "_apply_agent_setup", return_value=setup) as prepare, \
                         patch.object(runner, "maybe_prepare_ios_runner"), \
                         patch.object(runner, "restore_setup", restore), \
                         patch.object(runner, "release_agent_session") as close, \
                         patch.object(runner.subprocess, "Popen", return_value=Mock(stdout=io.StringIO())), \
                         patch.object(runner, "_tee_child", tee), \
                         patch.object(runner, "_tail_timing"), \
                         patch.object(runner, "ingest_agent_device_result", return_value={"agent_device_state": "review_required"}), \
                         patch.object(runner, "_publish_agent_device_live"):
                        if scenario in {"exception", "restore_failure"}:
                            with self.assertRaises(runner.SetupRestoreError if scenario == "restore_failure" else RuntimeError):
                                runner.run_task(branch, io.StringIO(), should_stop=lambda: False)
                        else:
                            code, _, _ = runner.run_task(branch, io.StringIO(), should_stop=lambda: scenario == "stop",
                                                         on_steps=lambda platform, rows: published_rows.extend(rows))
                            self.assertEqual({"success": 0, "failure": 1, "stop": 130}[scenario], code)
                        prepare.assert_called_once()
                        restore.assert_called_once_with(setup)
                        close.assert_called_once()
                        if published_rows:
                            self.assertEqual(tasks[1], published_rows[-1]["source_task_id"])
                            public = runner._step_public(published_rows[-1], task)
                            self.assertEqual(9, public["source_step"])
            original = Path(metadata["route_script"]).read_text()
            Path(metadata["route_script"]).write_text(original + "press label=wrong\n")
            with patch.object(runner, "_apply_agent_setup") as prepare:
                with self.assertRaisesRegex(ValueError, "identity changed"):
                    runner.run_task(spec, io.StringIO())
                prepare.assert_not_called()


if __name__ == "__main__":
    unittest.main()
