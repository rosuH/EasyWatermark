#!/usr/bin/env python3
"""Focused, device-free lifecycle and evidence checks for Android E2E tasks."""

from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
import testmap_run as runner
import testmap_console as console
import testmap_setup as setup


class AndroidE2EContractTests(unittest.TestCase):
    def _journey_export_state(self, root: Path):
        root = root.resolve()
        run_id = "20261011T120000-abcdef12"
        marker = "e2ejourney-" + "a" * 32
        setup_folder = root / run_id / "e2e-setup"
        setup_folder.mkdir(parents=True)
        archive = root / run_id / "e2e"
        archive.mkdir()
        fixture = setup.make_fixtures(setup_folder, marker, keys=("A",))["A"]
        sentinel = "EWM-EXIF-" + marker
        value = sentinel.encode("ascii") + b"\0"
        tiff = (b"II" + setup.struct.pack("<HIH", 42, 8, 1)
                + setup.struct.pack("<HHII", 270, 2, len(value), 26)
                + setup.struct.pack("<I", 0) + value)
        data = fixture.read_bytes()
        fixture.write_bytes(data[:33] + setup._png_chunk(b"eXIf", tiff) + data[33:])
        source_bytes = fixture.read_bytes()
        journal = setup_folder / ("setup-backup-" + "b" * 32 + ".json")
        run_state = {
            "setup": "home", "platform": "android", "serial": "emulator-5554",
            "marker": marker, "fixtures": {"A": str(fixture)},
            "display_backup": {},
            "private_backup": {path: None for path in setup.ANDROID_CONFIG + setup.EXPORT_CONTROL_PATHS},
            "source_id": "7", "export_source": {
                "sentinel": sentinel, "sha256": runner.hashlib.sha256(source_bytes).hexdigest(),
            },
            "android_ai_journey_run_id": run_id,
            "journey_control_files_cleared": True,
            "journey_output_ids_before": ["1", "2"],
            "phase": "active", "mutated": True,
            "journal": str(journal),
            "lock": str(setup._setup_lock_path("android", "emulator-5554")),
        }
        return run_state, run_id, setup.MEDIA + "/7", source_bytes, archive

    def test_review_requires_real_export_and_restoration(self):
        result = {"status": "passed"}
        proof = {"restore": {"status": "verified"}, "export": {"status": "verified"}}
        self.assertEqual("review_required", runner._e2e_final_status(0, "", result, proof))
        proof["export"]["status"] = "unverified"
        self.assertEqual("failed", runner._e2e_final_status(0, "", result, proof))
        proof["export"]["status"] = "verified"
        proof["restore"]["status"] = "failed"
        self.assertEqual("failed", runner._e2e_final_status(0, "", result, proof))

    def test_started_runner_cannot_report_blocked_prerequisite(self):
        result = {"status": "blocked", "reason": "native step timed out"}
        self.assertEqual("failed", runner._e2e_final_status(
            1, "runner exited after a native step timeout", result, {"runner_started": True}
        ))
        self.assertEqual("blocked", runner._e2e_final_status(
            2, "provider is not ready", result, {"runner_started": False}
        ))

    def test_stop_is_failed_when_restoration_failed(self):
        proof = {"restore": {"status": "failed", "journal": "setup-backup-owned.json"}}
        extra = {"e2e_runner": {"status": "failed", **proof}}
        self.assertEqual("failed", runner._e2e_final_status(130, "stop", None, proof))
        self.assertTrue(runner._e2e_restore_failed(extra))
        self.assertEqual("failed", runner._e2e_stop_task_state(extra))
        self.assertEqual("failed", runner._e2e_task_state(130, extra))
        safe_stop = {"e2e_runner": {"restore": {"status": "verified"}}}
        self.assertEqual("stopped", runner._e2e_stop_task_state(safe_stop))
        self.assertEqual("stopped", runner._e2e_final_status(130, "stop", None,
                         {"restore": {"status": "verified"}}))

    def test_successful_result_must_bind_owned_fixture(self):
        fixture = {"name": "ewm-suite-e2ejourney-abc-A.png",
                   "uri": "content://media/external_primary/images/media/7",
                   "sha256": "a" * 64}
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            (output / "report.json").write_text(json.dumps({"tests": []}), encoding="utf-8")
            trace = output / "results" / "journey" / "trace.md"
            trace.parent.mkdir(parents=True)
            trace.write_text("agent actions and screenshots", encoding="utf-8")
            result = {
                "status": "passed", "provider": "magpie", "model": "cursor/auto",
                "report_path": "report.json", "trace_path": "results/journey/trace.md",
                "fixture": {**fixture, "marker": "e2ejourney-abc"},
                "steps": [{"text": "Choose the owned photo", "state": "done",
                           "screenshot": "results/journey/step.png"}],
            }
            (output / "testmap-result.json").write_text(json.dumps(result), encoding="utf-8")
            self.assertEqual("passed", runner._e2e_load_result(output, fixture)["status"])
            result["fixture"]["sha256"] = "b" * 64
            (output / "testmap-result.json").write_text(json.dumps(result), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "owned picker fixture"):
                runner._e2e_load_result(output, fixture)

    def test_selection_is_one_explicit_android_run(self):
        runner._validate_e2e_selection([runner.E2E_TASK_ID], "emulator-5554", 1, "independent")
        runner._validate_e2e_selection([runner.E2E_LARGE_IMAGE_TASK_ID], "emulator-5554", 1, "independent")
        for args in (
            ([runner.E2E_TASK_ID, "guard"], "emulator-5554", 1, "independent"),
            ([runner.E2E_TASK_ID, runner.E2E_LARGE_IMAGE_TASK_ID], "emulator-5554", 1, "independent"),
            ([runner.E2E_LARGE_IMAGE_TASK_ID], "auto", 1, "independent"),
            ([runner.E2E_LARGE_IMAGE_TASK_ID], "emulator-5554", 2, "independent"),
            ([runner.E2E_TASK_ID], "auto", 1, "independent"),
            ([runner.E2E_TASK_ID], "emulator-5554", 2, "independent"),
            ([runner.E2E_TASK_ID], "emulator-5554", 1, "route"),
        ):
            with self.subTest(args=args), self.assertRaises(ValueError):
                runner._validate_e2e_selection(*args)

    def test_large_image_crash_proof_requires_fresh_same_pid_decode_and_native_failure(self):
        started_ms = 1_800_000_000_000
        ended_ms = started_ms + 10_000
        logs = "\n".join((
            f"1800000000.100 28296 30063 I BitmapUtils: reqW x reqH = 544 x 700, outWidth x outHeight = 49861 x 1350, inSampleSize = 1",
            f"1800000001.000 28296 28296 E AndroidRuntime: FATAL EXCEPTION: main",
            f"1800000001.000 28296 28296 E AndroidRuntime: Process: me.rosuh.easywatermark.debug, PID: 28296",
            f"1800000001.000 28296 28296 E AndroidRuntime: java.lang.RuntimeException: Canvas: trying to draw too large(269249400bytes) bitmap.",
        ))
        match = runner._e2e_canvas_crash_match(logs, started_ms, ended_ms)
        self.assertEqual(28296, match["process_id"])
        self.assertEqual({
            "req_width": 544, "req_height": 700, "width": 49861, "height": 1350, "sample": 1,
        }, match["request"])

        stale_decode = logs.replace("1800000000.100", "1799999999.999")
        self.assertIsNone(runner._e2e_canvas_crash_match(stale_decode, started_ms, ended_ms))
        split_process = logs.replace("28296 30063 I BitmapUtils", "28297 30063 I BitmapUtils")
        self.assertIsNone(runner._e2e_canvas_crash_match(split_process, started_ms, ended_ms))
        wrong_sample = logs.replace("inSampleSize = 1", "inSampleSize = 2")
        self.assertIsNone(runner._e2e_canvas_crash_match(wrong_sample, started_ms, ended_ms))

        fixture = {"name": "ewm-suite-e2ejourney-" + "a" * 32 + "-A.png",
                   "uri": "content://media/external_primary/images/media/7",
                   "sha256": "a" * 64, "marker": "e2ejourney-" + "a" * 32}
        result = {"status": "failed", "fixture": dict(fixture)}
        with patch.object(setup, "adb", return_value=logs), \
             patch.object(setup, "_device_clock", return_value={"device_ms": ended_ms}):
            self.assertTrue(runner._e2e_canvas_crash_proof(
                "emulator-5554", "20261013T120000-abcdef12", started_ms, result, fixture,
            )["bug_reproduced"])
            result["status"] = "passed"
            self.assertFalse(runner._e2e_canvas_crash_proof(
                "emulator-5554", "20261013T120000-abcdef12", started_ms, result, fixture,
            )["bug_reproduced"])

    def test_home_journey_fixture_set_contains_only_a(self):
        with tempfile.TemporaryDirectory() as folder:
            fixtures = setup.make_fixtures(Path(folder), "e2ejourney-" + "a" * 32, keys=("A",))
            self.assertEqual({"A"}, set(fixtures))
            self.assertEqual(["ewm-suite-e2ejourney-" + "a" * 32 + "-A.png"],
                             [path.name for path in fixtures.values()])

    def test_android_journey_export_claim_requires_unique_source_bound_delta(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            state, run_id, source_uri, source_bytes, archive = self._journey_export_state(root)
            Image = runner.require_clamp_pixels()
            output = root / "candidate.png"
            with Image.open(state["fixtures"]["A"]) as opened:
                pixels = opened.convert("RGB")
                raster = pixels.load()
                for y in range(300, 321):
                    for x in range(320, 621):
                        raster[x, y] = (210, 160, 45)
                pixels.save(output, format="PNG")
            output_bytes = output.read_bytes()
            output_row = {
                "_id": "3", "_display_name": "ewm_1700000000000.png",
                "relative_path": "Pictures/EasyWaterMark/",
                "owner_package_name": setup.ANDROID_PACKAGE, "mime_type": "image/png",
                "is_pending": "0", "_size": str(len(output_bytes)),
                "width": "960", "height": "640",
            }

            def query(serial, uri, projection, where=None, *, timeout=10):
                if uri == setup.MEDIA and where == setup.EXPORT_OUTPUT_FILTER:
                    return [{"_id": value} for value in ("1", "2", "3")]
                if uri == setup.MEDIA and where and where.startswith("_id=7 "):
                    return [{"_id": "7"}]
                if uri == setup.MEDIA + "/3":
                    return [output_row.copy()]
                raise AssertionError((uri, projection, where))

            def read(serial, uri, limit=16 * 1024 * 1024, *, timeout=10):
                return source_bytes if uri == source_uri else output_bytes

            with patch.object(runner, "_e2e_query", side_effect=query), \
                 patch.object(setup, "export_provider_read", side_effect=read):
                proof = {}
                runner._e2e_claim_export(
                    state, run_id, "emulator-5554", source_uri, {"1", "2"}, archive, proof
                )

            self.assertEqual("verified", proof["export"]["status"])
            self.assertEqual({"1", "2", "3"}, set(proof["export"]["output_ids_after"]))
            self.assertEqual(output_bytes, (archive / "export-file.png").read_bytes())
            self.assertEqual("claimed", state["export_output"]["cleanup"])
            self.assertTrue(proof["export"]["png_structure"]["metadata_stripped"])

    def test_android_journey_export_does_not_claim_ambiguous_delta(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            state, run_id, source_uri, source_bytes, archive = self._journey_export_state(root)

            def query(serial, uri, projection, where=None, *, timeout=10):
                if uri == setup.MEDIA and where == setup.EXPORT_OUTPUT_FILTER:
                    return [{"_id": value} for value in ("1", "2", "3", "4")]
                if uri == setup.MEDIA and where and where.startswith("_id=7 "):
                    return [{"_id": "7"}]
                raise AssertionError((uri, projection, where))

            with patch.object(runner, "_e2e_query", side_effect=query), \
                 patch.object(setup, "export_provider_read", return_value=source_bytes):
                with self.assertRaisesRegex(ValueError, "unique new app-owned"):
                    runner._e2e_claim_export(
                        state, run_id, "emulator-5554", source_uri, {"1", "2"}, archive, {}
                    )

            self.assertNotIn("export_output", state)

    def test_android_journey_does_not_extend_the_legacy_observer(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, "restricted to the Android save success case"):
                setup._apply_setup(
                    "home", platform="android", folder=Path(folder) / "e2e-setup",
                    marker="e2ejourney", serial="emulator-5554",
                    export_success_run_id="20261011T120000-abcdef12",
                )
            state, _, _, _, _ = self._journey_export_state(Path(folder))
            state["export_control"] = {
                "mode": "observe-next", "run_id": "20261011T120000-abcdef12",
            }
            with self.assertRaisesRegex(ValueError, "Invalid export control recovery scope"):
                setup._validate_setup(state)

    def test_restore_deadline_is_extended_only_for_validated_android_ai_journey(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            journey, _, _, _, _ = self._journey_export_state(root / "journey")
            remaining = []
            with patch.object(setup, "_restore_setup",
                              side_effect=lambda state: remaining.append(
                                  setup._CLEANUP_DEADLINE.get() - setup.time.monotonic())):
                setup.restore_setup(journey)
            self.assertGreater(remaining[-1], 14.5)
            self.assertLessEqual(remaining[-1], 15)
            self.assertEqual(8, setup.RESTORE_BUDGET_S)

            legacy, _, _, _, _ = self._journey_export_state(root / "legacy")
            legacy.pop("android_ai_journey_run_id")
            remaining.clear()
            with patch.object(setup, "_restore_setup",
                              side_effect=lambda state: remaining.append(
                                  setup._CLEANUP_DEADLINE.get() - setup.time.monotonic())):
                setup.restore_setup(legacy)
            self.assertGreater(remaining[-1], 7.5)
            self.assertLessEqual(remaining[-1], 8)

    def test_android_journey_fixture_cleanup_is_scoped_to_owned_source_id(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            state, _, _, _, _ = self._journey_export_state(root)
            lock = root / "device-lock.json"
            lock.write_text(json.dumps({"journal": state["journal"]}), encoding="utf-8")
            state["lock"] = str(lock)
            calls = []

            def adb_shell(serial, *args, **kwargs):
                calls.append((args, kwargs))
                if args[:2] == ("content", "delete"):
                    return ""
                if args[:2] == ("content", "query"):
                    if args[5] == "_display_name":
                        return "No result found."
                    if args[5] == "_id":
                        return "Row: 0 _id=1\nRow: 1 _id=2"
                if args[:2] == ("rm", "-f"):
                    return ""
                if args[:2] == ("sh", "-c"):
                    return "testmap-journey-fixtures-absent"
                raise AssertionError(args)

            deadline_token = setup._CLEANUP_DEADLINE.set(setup.time.monotonic() + 60)
            try:
                with patch.object(setup, "_setup_lock_path", return_value=lock), \
                     patch.object(setup, "_save_setup"), \
                     patch.object(setup, "_capture_export_control"), \
                     patch.object(setup, "_restore_export_output"), \
                     patch.object(setup, "restore_private"), \
                     patch.object(setup, "adb_shell", side_effect=adb_shell):
                    setup._restore_setup(state)
            finally:
                setup._CLEANUP_DEADLINE.reset(deadline_token)

            delete_args = next(args for args, _ in calls if args[:2] == ("content", "delete"))
            self.assertIn("_id=7 AND ", delete_args[-1])
            self.assertIn("relative_path='" + setup.FIXTURE_FOLDER + "/'", delete_args[-1])
            self.assertIn(state["fixtures"]["A"].split("/")[-1], delete_args[-1])
            absence_query = next(args for args, _ in calls
                                 if args[:2] == ("content", "query") and args[5] == "_display_name")
            self.assertNotIn("_id=", absence_query[-1])
            self.assertFalse(lock.exists())

    def test_archived_artifact_paths_are_run_scoped_and_allowlisted(self):
        run_id = "20261011T120000-abcdef12"
        with tempfile.TemporaryDirectory() as folder, patch.object(runner, "RUNS_DIR", Path(folder)):
            root = Path(folder) / run_id / "e2e"
            trace = root / "results" / "journey" / "trace.md"
            trace.parent.mkdir(parents=True)
            trace.write_text("trace", encoding="utf-8")
            crash_proof = root / "canvas-crash-proof.json"
            crash_proof.write_text("{}", encoding="utf-8")
            (root / "testmap-result.json").write_text(json.dumps({
                "scenario": runner.E2E_LARGE_IMAGE_SCENARIO,
                "trace_path": "results/journey/trace.md", "steps": []
            }), encoding="utf-8")
            self.assertEqual(trace.resolve(), runner.e2e_artifact_path(run_id, "results/journey/trace.md"))
            self.assertEqual(crash_proof.resolve(), runner.e2e_artifact_path(run_id, "canvas-crash-proof.json"))
            self.assertIsNone(runner.e2e_artifact_path(run_id, "../trace.md"))
            self.assertIsNone(runner.e2e_artifact_path(run_id, "results/journey/trace.js"))
            self.assertIsNone(runner.e2e_artifact_path("../../outside", "report.json"))
            (root / "testmap-result.json").write_text(json.dumps({"scenario": runner.E2E_EXPORT_SCENARIO}), encoding="utf-8")
            self.assertIsNone(runner.e2e_artifact_path(run_id, "canvas-crash-proof.json"))

    def test_task_api_exposes_builder_and_device_requirement(self):
        handler = object.__new__(console.Handler)
        handler.path = "/api/tasks"
        replies = []
        handler._json = lambda code, value: replies.append((code, value))
        handler.do_GET()
        code, payload = replies[-1]
        self.assertEqual(200, code)
        task = next(row for row in payload["runnable"] if row["id"] == runner.E2E_TASK_ID)
        self.assertEqual("e2e-runner", task["builder"])
        self.assertEqual("android", task["needs_device"])
        self.assertEqual(["android"], task["platforms"])
        diagnostic = next(row for row in payload["runnable"] if row["id"] == runner.E2E_LARGE_IMAGE_TASK_ID)
        self.assertEqual("e2e-runner", diagnostic["builder"])
        self.assertEqual("android", diagnostic["needs_device"])
        self.assertEqual(["android"], diagnostic["platforms"])

    def test_artifact_api_serves_only_a_run_owned_e2e_artifact(self):
        run_id = "20261011T120000-abcdef12"
        with tempfile.TemporaryDirectory() as folder, patch.object(runner, "RUNS_DIR", Path(folder)):
            root = Path(folder) / run_id / "e2e"
            trace = root / "results" / "journey" / "trace.md"
            trace.parent.mkdir(parents=True)
            trace.write_text("trace", encoding="utf-8")
            (root / "report.json").write_text("{}", encoding="utf-8")
            shot = root / "results" / "journey" / "approved.png"
            shot.write_bytes(b"approved screenshot")
            (root / "results" / "journey" / "unlisted.png").write_bytes(b"unlisted screenshot")
            (root / "testmap-result.json").write_text(json.dumps({
                "report_path": "report.json",
                "trace_path": "results/journey/trace.md",
                "steps": [{"screenshot": "results/journey/approved.png"}],
            }), encoding="utf-8")
            record = {"id": run_id, "tasks": [{"builder": "e2e-runner"}]}
            handler = object.__new__(console.Handler)
            replies = []
            handler._send = lambda *args: replies.append(args)
            handler._json = lambda *args: replies.append(args)
            with patch.object(console, "load_run", return_value=record):
                handler.path = f"/api/runs/{run_id}/e2e/results/journey/trace.md"
                handler.do_GET()
                self.assertEqual((200, b"trace", "text/markdown; charset=utf-8"), replies[-1])
                handler.path = f"/api/runs/{run_id}/e2e/../secret.md"
                handler.do_GET()
                self.assertEqual(404, replies[-1][0])
                handler.path = f"/api/runs/{run_id}/e2e/results/journey/approved.png"
                handler.do_GET()
                self.assertEqual((200, b"approved screenshot", "image/png"), replies[-1])
                handler.path = f"/api/runs/{run_id}/e2e/results/journey/unlisted.png"
                handler.do_GET()
                self.assertEqual(404, replies[-1][0])
            with patch.object(console, "load_run", return_value={"id": run_id, "tasks": []}):
                handler.path = f"/api/runs/{run_id}/e2e/results/journey/trace.md"
                handler.do_GET()
                self.assertEqual(404, replies[-1][0])

    def test_output_archiver_accepts_only_a_precreated_empty_run_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "runner-output"
            source.mkdir()
            (source / "report.json").write_text("{}", encoding="utf-8")
            destination = root / "archive"
            destination.mkdir()
            self.assertEqual(2, runner._archive_e2e_output(source, destination))
            self.assertEqual("{}", (destination / "report.json").read_text(encoding="utf-8"))
            with self.assertRaisesRegex(ValueError, "empty run-owned"):
                runner._archive_e2e_output(source, destination)


if __name__ == "__main__":
    unittest.main()
