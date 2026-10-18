#!/usr/bin/env python3
"""Device-free contracts for optional Agent Device hybrid routing and evidence."""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.dont_write_bytecode = True
SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
REPO = SCRIPTS.parent
from testmap_agent_device import agent_device_mode
from testmap_run import assert_cmd_legal, expand_task

class HybridContracts(unittest.TestCase):
    def test_agent_device_defaults_to_replay(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertEqual(agent_device_mode(), "replay")


    def test_hybrid_pilot_uses_existing_harness_and_is_android_only(self):
        with mock.patch.dict(os.environ, {"TESTMAP_AGENT_DEVICE_MODE": "ai-sdk",
                                          "TESTMAP_AGENT_DEVICE_PILOT_ARM": "hybrid"}):
            cmd = expand_task("edge:launch-to-about@android#agent", "auto")[0][1]["cmd"]
            self.assertEqual(cmd[cmd.index("--pilot-arm") + 1], "hybrid")
            assert_cmd_legal(cmd)
            pick_cmd = expand_task("edge:pick-to-editor@android#agent", "auto")[0][1]["cmd"]
            self.assertEqual(pick_cmd[pick_cmd.index("--pilot-arm") + 1], "hybrid")
            assert_cmd_legal(pick_cmd)
            with self.assertRaises(ValueError):
                expand_task("edge:launch-to-about@ios#agent", "auto")


    def test_ai_sdk_defaults_to_hybrid_with_explicit_grok_override(self):
        with mock.patch.dict(os.environ, {"TESTMAP_AGENT_DEVICE_MODE": "ai-sdk"}, clear=True):
            for edge in ("launch-to-about", "pick-to-editor"):
                cmd = expand_task(f"edge:{edge}@android#agent", "auto")[0][1]["cmd"]
                self.assertEqual(cmd[cmd.index("--controller") + 1], "hybrid")
                self.assertNotIn("--pilot-arm", cmd)
                assert_cmd_legal(cmd)
            for task in ("edge:editor-to-about@android#agent", "edge:launch-to-about@ios#agent"):
                cmd = expand_task(task, "auto")[0][1]["cmd"]
                self.assertEqual(cmd[cmd.index("--controller") + 1], "hybrid")
                self.assertNotIn("--pilot-arm", cmd)
            os.environ["TESTMAP_AGENT_DEVICE_AI_CONTROLLER"] = "grok"
            for task in ("edge:editor-to-about@android#agent", "edge:launch-to-about@ios#agent"):
                cmd = expand_task(task, "auto")[0][1]["cmd"]
                self.assertEqual(cmd[cmd.index("--controller") + 1], "grok")
                self.assertNotIn("--case-contract", cmd)
                assert_cmd_legal(cmd)
            os.environ["TESTMAP_AGENT_DEVICE_AI_CONTROLLER"] = "unknown"
            with self.assertRaisesRegex(ValueError, "must be hybrid or grok"):
                expand_task("edge:launch-to-about@android#agent", "auto")
            os.environ["TESTMAP_AGENT_DEVICE_PILOT_ARM"] = "grok"
            cmd = expand_task("edge:launch-to-about@android#agent", "auto")[0][1]["cmd"]
            self.assertEqual(cmd[cmd.index("--pilot-arm") + 1], "grok")
        with mock.patch.dict(os.environ, {"TESTMAP_AGENT_DEVICE_AI_CONTROLLER": "unknown"}, clear=True):
            cmd = expand_task("edge:launch-to-about@android#agent", "auto")[0][1]["cmd"]
            self.assertEqual(agent_device_mode(), "replay")
            self.assertNotIn("--controller", cmd)


    def test_all_supported_mobile_cases_expand_to_hybrid_with_existing_payload_context(self):
        import testmap_agent_device as agent
        import testmap_run as tr
        counts = {"android": 0, "ios": 0}
        with tempfile.TemporaryDirectory() as td, mock.patch.dict(
            os.environ, {"TESTMAP_AGENT_DEVICE_MODE": "ai-sdk"}, clear=True
        ), mock.patch.object(tr, "evidence_dir_for", return_value=Path(td)):
            for case in agent.agent_device_cases_by_id().values():
                for platform in agent.case_platforms(case):
                    task = f"edge:{case['id']}@{platform}#agent"
                    with self.subTest(task=task):
                        if not agent.case_supports_platform(case, platform):
                            with self.assertRaises(ValueError):
                                expand_task(task, "auto")
                            continue
                        counts[platform] += 1
                        spec = expand_task(task, "auto")[0][1]
                        cmd = spec["cmd"]
                        self.assertEqual(cmd[cmd.index("--controller") + 1], "hybrid")
                        self.assertNotIn("--pilot-arm", cmd)
                        contract = json.loads(cmd[cmd.index("--case-contract") + 1])
                        self.assertEqual(contract["edgeId"], case["id"])
                        self.assertEqual(contract["platform"], platform)
                        self.assertEqual(contract["setup"], spec["setup"])
                        self.assertEqual(contract["verify"], case["verify"])
                        self.assertEqual(contract["script"], agent.case_script_path(case, platform).read_text())
                        self.assertEqual(contract["honesty"], case["honesty"])
                        self.assertTrue(contract["supported"])
                        if case["id"] == "export-failure-recovery":
                            self.assertEqual(contract["recovery"]["action"], "restore-source")
                            self.assertIn("Succeeded 1", contract["recovery"]["script"])
                        assert_cmd_legal(cmd)
        self.assertTrue(all(counts.values()), counts)


    def test_default_hybrid_pick_prepares_fixture_but_not_replay_grok_or_explicit_pilot(self):
        import testmap_run as tr
        import testmap_pick_export_evidence as owned
        with tempfile.TemporaryDirectory() as td, mock.patch.dict(os.environ, {}, clear=True):
            spec = {"builder": "agent-device", "edge_id": "pick-to-editor", "setup": "home",
                    "agent_platform": "android", "serial": "owned-device", "agent_device_output": td,
                    "env": {"KEEP": "value"}, "cmd": ["node", "testmap_agent_device_ai.mjs", "--controller", "hybrid"]}
            state = {"folder": str(Path(td) / "pick-export"), "marker_text": "EWM BENCH auto-check",
                     "source": {"picker_epoch_seconds": 1, "picker_timezone": "UTC"}}
            with mock.patch.object(owned, "prepare", return_value=state) as prepare:
                self.assertIs(tr._apply_agent_setup(spec, None), state)
                prepare.assert_called_once()
            self.assertEqual(spec["env"]["KEEP"], "value")
            self.assertEqual(spec["env"]["TESTMAP_PICK_EXPORT_TEXT"], state["marker_text"])
            self.assertEqual(spec["env"]["TESTMAP_PICK_EXPORT_PHOTO_LABEL"], "Photo taken on Jan 1, 1970, 12:00:01 AM")
            for cmd in (["agent-device", "replay", "pick.json"],
                        ["node", "testmap_agent_device_ai.mjs", "--controller", "grok"],
                        ["node", "testmap_agent_device_ai.mjs", "--pilot-arm", "hybrid"]):
                self.assertFalse(tr._owns_pick_export_setup({**spec, "cmd": cmd}))


    def test_default_hybrid_pick_requires_file_verification_and_cleanup_even_after_cancel(self):
        import io
        import testmap_run as tr
        import testmap_pick_export_evidence as owned
        for exit_code, file_ok, cleanup_ok in ((0, True, True), (0, False, True), (0, True, False), (130, True, True)):
            with self.subTest(exit_code=exit_code, file_ok=file_ok, cleanup_ok=cleanup_ok), tempfile.TemporaryDirectory() as td:
                folder = Path(td)
                initial = {"status": "executed_review_required", "controller": "hybrid", "edge_id": "pick-to-editor", "platform": "android"}
                (folder / "result.json").write_text(json.dumps(initial))
                (folder / "pilot-trace.json").write_text(json.dumps({"arm": "hybrid", "success": True}))
                spec = {"builder": "agent-device", "edge_id": "pick-to-editor", "agent_platform": "android",
                        "agent_device_output": td, "cmd": ["node", "testmap_agent_device_ai.mjs", "--controller", "hybrid"]}
                state = {"_pick_export_owned": True, "folder": str(folder / "pick-export")}
                events = []
                def verify(current, output):
                    events.append("verify")
                    self.assertEqual(current["ui_success"], exit_code == 0)
                    return {"success": file_ok and current["ui_success"]}
                def restore(*args):
                    events.append("restore")
                    return {"success": cleanup_ok}
                with mock.patch.object(tr, "maybe_prepare_ios_runner"), mock.patch.object(tr, "_apply_agent_setup", return_value=state), mock.patch.object(
                    tr.subprocess, "Popen", return_value=mock.Mock()
                ), mock.patch.object(tr, "_tee_child", return_value=exit_code), mock.patch.object(owned, "verify", side_effect=verify), mock.patch.object(
                    owned, "restore", side_effect=restore
                ), mock.patch.object(tr, "ingest_agent_device_result", return_value={}), mock.patch.object(tr, "_publish_agent_device_live"):
                    code, _, _ = tr.run_task(spec, io.StringIO())
                self.assertEqual(events, ["verify", "restore"])
                self.assertEqual(code, exit_code or (0 if file_ok and cleanup_ok else 2))
                result = json.loads((folder / "result.json").read_text())
                self.assertEqual(result["pick_export_cleanup"]["success"], cleanup_ok)
                self.assertEqual(result["status"] == "executed_review_required", exit_code == 0 and file_ok and cleanup_ok)


    def test_ios_owned_pick_requires_explicit_device_scope_and_passes_journal(self):
        import testmap_run as tr
        import testmap_ios_pick_export_evidence as owned
        with tempfile.TemporaryDirectory() as td, mock.patch.dict(os.environ, {}, clear=True):
            spec = {"builder": "agent-device", "edge_id": "pick-to-editor", "setup": "home",
                    "agent_platform": "ios", "udid": "owned-ios", "agent_device_output": td,
                    "cmd": ["node", "testmap_agent_device_ai.mjs", "--controller", "hybrid"]}
            self.assertFalse(tr._owns_pick_export_setup(spec))
            state = {"platform": "ios", "folder": str(Path(td) / "pick-export"),
                     "state_path": str(Path(td) / "prepare.json"), "marker_text": "owned text",
                     "source": {"picker_photo_label": "owned synthetic photo"}}
            with mock.patch.dict(os.environ, {"TESTMAP_IOS_OWNED_DEVICE": "/owned/device.json"}), mock.patch.object(
                    owned, "prepare", return_value=state) as prepare:
                self.assertTrue(tr._owns_pick_export_setup(spec))
                self.assertIs(tr._apply_agent_setup(spec, None), state)
                self.assertEqual(prepare.call_args.args[0], "owned-ios")
            self.assertEqual(spec["env"]["TESTMAP_IOS_PICKER_STATE"], state["state_path"])
            self.assertEqual(spec["env"]["TESTMAP_PICK_EXPORT_PHOTO_LABEL"], "owned synthetic photo")


    def test_ios_owned_pick_cleanup_failure_cannot_report_completed(self):
        import io
        import testmap_run as tr
        import testmap_ios_pick_export_evidence as owned
        with tempfile.TemporaryDirectory() as td:
            folder = Path(td)
            (folder / "result.json").write_text(json.dumps({"status": "executed_review_required", "controller": "hybrid",
                                                          "edge_id": "pick-to-editor", "platform": "ios"}))
            (folder / "pilot-trace.json").write_text(json.dumps({"arm": "hybrid", "success": True}))
            spec = {"builder": "agent-device", "edge_id": "pick-to-editor", "agent_platform": "ios",
                    "agent_device_output": td, "cmd": ["node", "testmap_agent_device_ai.mjs", "--controller", "hybrid"]}
            state = {"platform": "ios", "_pick_export_owned": True, "folder": str(folder / "pick-export")}
            with mock.patch.object(tr, "maybe_prepare_ios_runner"), mock.patch.object(tr, "_apply_agent_setup", return_value=state), mock.patch.object(
                    tr.subprocess, "Popen", return_value=mock.Mock()), mock.patch.object(tr, "_tee_child", return_value=0), mock.patch.object(
                    owned, "verify", return_value={"success": True}), mock.patch.object(owned, "restore", return_value={"success": False}), mock.patch.object(
                    tr, "ingest_agent_device_result", return_value={}), mock.patch.object(tr, "_publish_agent_device_live"):
                code, _, _ = tr.run_task(spec, io.StringIO())
            self.assertTrue(state["ui_success"])
            self.assertEqual(code, 2)
            self.assertEqual(json.loads((folder / "result.json").read_text())["status"], "failed")


    def test_agent_device_ai_mode_is_explicit_and_pinned(self):
        with mock.patch.dict(
            os.environ, {"TESTMAP_AGENT_DEVICE_MODE": "ai-sdk"}, clear=False
        ):
            _tid, spec = expand_task("edge:launch-to-about@android#agent", "auto")[0]
        cmd = spec["cmd"]
        self.assertEqual(spec["agent_mode"], "ai-sdk")
        self.assertEqual(Path(cmd[0]).name, "node")
        self.assertEqual(Path(cmd[1]).name, "testmap_agent_device_ai.mjs")
        self.assertEqual(cmd[cmd.index("--model") + 1], "cursor/grok-4.7@500k")
        self.assertEqual(
            cmd[cmd.index("--base-url") + 1], "http://127.0.0.1:3425/v1"
        )
        self.assertIn("--serial", cmd)
        self.assertNotIn("--device", cmd)
        self.assertIn("完成 EasyWatermark Testmap 路径", cmd[cmd.index("--goal") + 1])
        assert_cmd_legal(cmd)
        missing_device = list(cmd)
        serial_index = missing_device.index("--serial")
        del missing_device[serial_index : serial_index + 2]
        with self.assertRaises(ValueError):
            assert_cmd_legal(missing_device)


    def test_agent_device_mode_rejects_unknown_value(self):
        with mock.patch.dict(
            os.environ, {"TESTMAP_AGENT_DEVICE_MODE": "surprise"}, clear=False
        ), self.assertRaises(ValueError):
            expand_task("edge:launch-to-about@android#agent", "auto")


    def test_agent_device_ai_dependencies_match_cli_pin(self):
        package = json.loads((REPO / "package.json").read_text(encoding="utf-8"))
        self.assertEqual(package["dependencies"]["agent-device"], "0.21.2")
        self.assertEqual(package["dependencies"]["ai"], "7.0.68")
        self.assertEqual(
            package["dependencies"]["@ai-sdk/openai-compatible"], "3.0.71"
        )


class FixtureFolderContract(unittest.TestCase):
    def test_hybrid_album_is_isolated_and_existing_default_is_preserved(self):
        import testmap_setup as setup
        source = Path("fixture.png")
        for folder in (setup.FIXTURE_FOLDER, "Pictures/EwmHybridBench"):
            with self.subTest(folder=folder), mock.patch.object(setup, "adb") as adb, mock.patch.object(setup, "adb_shell") as shell:
                args = [] if folder == setup.FIXTURE_FOLDER else [folder]
                setup.push_android_fixtures("serial", {"A": source}, *args)
                adb.assert_called_once_with("serial", ["push", str(source), f"/sdcard/{folder}/fixture.png"])
                self.assertEqual(shell.call_args_list[0].args, ("serial", "mkdir", "-p", f"/sdcard/{folder}"))
                self.assertEqual(shell.call_args_list[-1].args[-1], f"file:///sdcard/{folder}/fixture.png")


class RecoveryContracts(unittest.TestCase):
    def test_recovery_is_owned_one_shot_and_delegates_android_checks(self):
        import testmap_hybrid_recovery as recovery
        for platform in ("android", "ios"):
            with self.subTest(platform=platform), tempfile.TemporaryDirectory() as td:
                root = Path(td).resolve()
                folder = root / "build/agent-device/run/path"
                folder.mkdir(parents=True)
                journal, lock = folder / "setup.json", folder / "lock.json"
                journal.write_text("{}")
                lock.write_text(json.dumps({"journal": str(journal)}))
                state_file = folder / "ai-source-recovery-state.json"
                descriptor = {"journal": str(journal), "_ai_recovery_active": True,
                              "_ai_recovery_state_path": str(state_file), "platform": platform,
                              "serial": "serial", "udid": "udid", "run_id": "run"}
                state = {**descriptor, "lock": str(lock), "phase": "active", "mutated": True,
                         "container": str(folder), "export_control": {"mode": "fail-next", "run_id": "run", "expires_at_ms": 10}}
                state_file.write_text(json.dumps(descriptor))
                events = [dict(run_id="run", event=e, timestamp_ms=i) for i,e in enumerate(("ready", "entered", "failed", "cleared"))]
                data = b"\n".join(json.dumps(row).encode() for row in events)
                with mock.patch.object(recovery, "ROOT", root), mock.patch.object(recovery.setup, "load_setup_backup", return_value=state), mock.patch.object(
                        recovery.setup, "repair_failure_source", return_value={"run_id": "run"}) as repair, mock.patch.object(
                        recovery.setup, "_read_ios_private", return_value=data) as read:
                    if platform == "ios":
                        read.return_value = data.replace(b'"run"', b'"foreign"')
                        with self.assertRaisesRegex(ValueError, "current run"):
                            recovery.recover(state_file)
                        self.assertNotIn("_ai_source_recovered", json.loads(state_file.read_text()))
                        read.return_value = data
                    proof = recovery.recover(state_file)
                    self.assertTrue(proof["success"])
                    if platform == "android":
                        repair.assert_called_once_with(state, serial="serial", run_id="run")
                        read.assert_not_called()
                    else:
                        repair.assert_not_called()
                        self.assertFalse(proof["physical_source_rewritten"])
                    with self.assertRaisesRegex(ValueError, "one-shot"):
                        recovery.recover(state_file)

    def test_inactive_recovery_never_loads_a_setup_or_touches_device(self):
        import testmap_hybrid_recovery as recovery
        with tempfile.TemporaryDirectory() as td:
            root = Path(td).resolve()
            folder = root / "build/agent-device/run/path"
            folder.mkdir(parents=True)
            state_file = folder / "ai-source-recovery-state.json"
            state_file.write_text(json.dumps({"_ai_recovery_active": False, "_ai_recovery_state_path": str(state_file)}))
            with mock.patch.object(recovery, "ROOT", root), mock.patch.object(recovery.setup, "load_setup_backup") as load:
                with self.assertRaisesRegex(ValueError, "inactive"):
                    recovery.recover(state_file)
                load.assert_not_called()


if __name__ == "__main__":
    unittest.main()
