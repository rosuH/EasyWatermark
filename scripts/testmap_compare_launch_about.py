#!/usr/bin/env python3
"""Alternate bounded Jev/Grok pilots through the existing testmap runner."""
import argparse
import hashlib
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import sys
from testmap_stop import terminate_process_group

ROOT = Path(__file__).resolve().parents[1]


def run_attempt(command, env):
    proc = subprocess.Popen(command, cwd=ROOT, env=env, text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            start_new_session=True)
    timed_out = False
    try:
        stdout, _ = proc.communicate(timeout=int(env.get("TESTMAP_AGENT_DEVICE_TIMEOUT_MS", "120000")) / 1000 + 120)
    except subprocess.TimeoutExpired:
        timed_out = True
        terminate_process_group(proc)
        stdout, _ = proc.communicate()
    except BaseException:
        terminate_process_group(proc)
        raise
    return proc.returncode, stdout, timed_out


def read_attempt(arm, code, stdout, timed_out, *, edge=None, serial=None):
    match = re.search(r"testmap run (\d{8}T\d{6}-[\w-]+)", stdout)
    row = {"arm": arm, "exit_code": code, "success": False, "timed_out": timed_out}
    try:
        if not match:
            raise ValueError("runner did not emit a run id")
        record = json.loads((ROOT / "docs/testmap/runs" / f"{match[1]}.json").read_text())
        task = record["tasks"][0]
        evidence = Path(task["agent_device_output"])
        pilot_path = evidence / "pilot-trace.json"
        pilot = json.loads(pilot_path.read_text())
        harness = json.loads((evidence / "result.json").read_text())
        if edge is not None and (pilot.get("arm") != arm or harness.get("pilot_arm") != arm
                or harness.get("edge_id") != edge or harness.get("platform") != "android"
                or task.get("id") != f"edge:{edge}@android#agent"
                or str(evidence) != task["cmd"][task["cmd"].index("--output-dir") + 1]
                or record.get("device") != serial or task.get("device_request") != serial):
            raise ValueError("evidence does not match current arm, edge, device and output path")
        calls = [pilot.get("planner"), *[call for s in pilot.get("steps", []) for call in (s, s.get("handoff"))]]
        calls = [c for c in calls if c is not None]
        row.update(run_id=record["id"], task_seconds=task.get("duration_s"),
                   controller_seconds=pilot["controllerMs"] / 1000 if "controllerMs" in pilot else None,
                   provider_seconds=sum(s["providerMs"] for s in calls
                                        if s.get("providerMs") is not None) / 1000 if calls else None,
                   success=not timed_out and code == 0 and pilot.get("success") is True
                           and harness.get("status") == "executed_review_required",
                   evidence=str(evidence), error=pilot.get("error"),
                   failure_stage=pilot.get("failureStage"), error_code=pilot.get("errorCode"),
                   steps=[{k: s.get(k) for k in ("answer", "nativeSelected", "unselectedCheckPassed", "gatePassed", "selectedBy", "injectedGateAnswer", "handoff")}
                          for s in pilot.get("steps", [])])
        if harness.get("evidence", {}).get("closeError"):
            row.update(success=False, error=harness["evidence"]["closeError"], failure_stage="cleanup")
    except (OSError, ValueError, KeyError, IndexError, TypeError) as error:
        row.update(error=f"missing or invalid runner evidence: {error}", failure_stage="infrastructure")
    return row


def stop_after(row):
    return row.get("timed_out") or row.get("failure_stage") in {"provider", "planning", "handoff", "cleanup", "infrastructure"} or row.get("error_code") == "DEVICE_IN_USE" or any(
        term in str(row.get("error")) for term in ("parent-owned touch point", "TypeSafe HTTP", "fetch failed"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--edge", choices=("launch-to-about", "pick-to-editor"), default="launch-to-about")
    parser.add_argument("--env-file", type=Path, default=ROOT / "build/agent-device/jev-pilot.env")
    parser.add_argument("--repeats", type=int, choices=range(1, 11), default=3)
    parser.add_argument("--arms", nargs="+", choices=("jev", "grok", "hybrid"), default=["jev", "grok"])
    parser.add_argument("--force-handoff-step", type=int, choices=(1, 2),
                        help="hybrid-only test fault; preserve raw Jev response, lower effective confidence to .89")
    args = parser.parse_args()
    if args.edge == "pick-to-editor" and ("jev" in args.arms or args.force_handoff_step):
        parser.error("pick-to-editor requires --arms hybrid grok without fault injection")
    if args.force_handoff_step and args.arms != ["hybrid"]:
        parser.error("--force-handoff-step requires --arms hybrid")
    env = os.environ.copy()
    if any(arm in {"jev", "hybrid"} for arm in args.arms):
        # Read data, never source shell code or print credentials.
        if not env.get("TYPESAFE_AI_API_KEY") and args.env_file.is_file():
            for line in args.env_file.read_text().splitlines():
                if line.startswith("TYPESAFE_AI_API_KEY="):
                    env["TYPESAFE_AI_API_KEY"] = line.split("=", 1)[1].strip().strip("\"'")
        if not env.get("TYPESAFE_AI_API_KEY"):
            parser.error(f"fill TYPESAFE_AI_API_KEY in {args.env_file} or the environment")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    output = ROOT / "build/agent-device" / f"{args.edge}-pilot-{stamp}"
    output.mkdir(parents=True)
    order = [arm for i in range(args.repeats) for arm in (args.arms if i % 2 == 0 else list(reversed(args.arms)))]
    plan = {
        "edge": args.edge, "serial": args.serial, "order": order,
        "repeats_per_arm": args.repeats, "confidence_threshold": 0.9,
        "jev_model": "jev-latest", "grok_model": "cursor/grok-4.7@500k",
        "oracle": "visible Launch, then About, then Launch; both clicked controls explicitly selected=false",
        "comparison": "same fixed two-phase host controller, candidates, executor and oracle; not unrestricted Grok agent",
        "candidate_policy": "native action nodes with ancestor identifiers and sibling labels; never wrapper refs",
        "source_sha256": {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in
                          ("scripts/testmap_launch_about_pilot.mjs", "scripts/testmap_agent_device_ai.mjs")},
        "stop_rule": "no automatic retry; stop comparison on infrastructure failure or missing artifacts",
        "human_confirmation": False,
        "force_handoff_step": args.force_handoff_step,
    }
    if "hybrid" in args.arms:
        plan["comparison"] = "Grok plans 2-4 navigation steps; Jev selects targets; only valid low-confidence decisions hand back to Grok on a fresh snapshot."
    if args.edge == "pick-to-editor":
        plan.update(comparison="Both arms use a fresh Grok whole-task plan. Hybrid uses Jev target selection with valid low-confidence Grok handoff; pure Grok selects every target. Identical host candidates, guards and executor.",
                    oracle="Unique MediaStore timestamp matches the exact real-picker accessibility label, exact watermark edit/readback, PNG export UI counts 1/0 AND one fresh app-owned decoded 960x640 PNG retaining source bands with changed pixels and persisted marker.",
                    candidate_policy="App + system photo picker only; leaf native actions with inherited semantics, refreshed pinned refs. Explicit selected=false before clicks; selected Content/PNG are skipped; exact source timestamp required.",
                    timing="Task excludes fixture preparation, independent export-file verification and restoration; provider includes planner + selectors + handoffs. Success medians only.")
        for name in ("scripts/testmap_pick_export_pilot.mjs", "scripts/testmap_pick_export_evidence.py", "scripts/testmap_compare_launch_about.py"):
            plan["source_sha256"][name] = hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
    (output / "plan.json").write_text(json.dumps(plan, indent=2) + "\n")
    rows = []
    env.update(TESTMAP_AGENT_DEVICE_MODE="ai-sdk", TESTMAP_AGENT_DEVICE_TIMEOUT_MS="120000",
               TESTMAP_AGENT_DEVICE_AI_MODEL="cursor/grok-4.7@500k")
    env.pop("TESTMAP_AGENT_DEVICE_PILOT_FORCE_HANDOFF_STEP", None)
    if args.edge == "pick-to-editor":
        env.update(TESTMAP_AGENT_DEVICE_SKIP_SETUP="1", TESTMAP_AGENT_DEVICE_TIMEOUT_MS="480000")
    if args.force_handoff_step:
        env["TESTMAP_AGENT_DEVICE_PILOT_FORCE_HANDOFF_STEP"] = str(args.force_handoff_step)
    for index, arm in enumerate(order):
        env["TESTMAP_AGENT_DEVICE_PILOT_ARM"] = arm
        print(f"pilot {index + 1}/{len(order)} {arm}", flush=True)
        state = None
        row = {"arm": arm, "success": False, "timed_out": False}
        trial = output / f"{index + 1}-{arm}"
        trial.mkdir()
        try:
            if args.edge == "pick-to-editor":
                from testmap_pick_export_evidence import prepare, verify, restore
                state = prepare(args.serial, trial, f"{stamp}-{index + 1}")
                env["TESTMAP_PICK_EXPORT_TEXT"] = state["marker_text"]
                from zoneinfo import ZoneInfo
                moment = datetime.fromtimestamp(state["source"]["picker_epoch_seconds"], ZoneInfo(state["source"]["picker_timezone"]))
                env["TESTMAP_PICK_EXPORT_PHOTO_LABEL"] = f"Photo taken on {moment.strftime('%b')} {moment.day}, {moment.year}, {moment.hour % 12 or 12}:{moment.minute:02}:{moment.second:02} {moment.strftime('%p')}"
                if rows and state["preference_baseline_sha256"] != rows[0]["preference_baseline_sha256"]:
                    raise ValueError("preference baseline changed between arms")
            code, stdout, timed_out = run_attempt([sys.executable, "scripts/testmap_run.py",
                                   f"edge:{args.edge}@android#agent", "--device", args.serial], env)
            (output / f"{index + 1}-{arm}.log").write_text(stdout)
            row = read_attempt(arm, code, stdout, timed_out, edge=args.edge, serial=args.serial)
            if state:
                state["ui_success"] = row["success"]
                evidence = Path(row.get("evidence", trial))
                verification = verify(state, evidence)
                row.update(ui_success=row["success"], success=verification["success"],
                           export_verification=verification["report_path"],
                           preference_baseline_sha256=state["preference_baseline_sha256"])
                if not verification["success"] and row["ui_success"]:
                    row.update(error=verification.get("error", "independent export checks failed"), failure_stage="file_oracle")
        except Exception as error:
            row.update(success=False, error=str(error), failure_stage="infrastructure")
        finally:
            if state:
                cleanup = restore(state, trial)
                row["cleanup"] = cleanup["report_path"]
                if not cleanup["success"]:
                    row.update(success=False, error=f"restore failed: {cleanup['report_path']}", failure_stage="cleanup")
        rows.append(row)
        (output / "comparison.json").write_text(json.dumps({"plan": plan, "runs": rows}, indent=2) + "\n")
        print(json.dumps(row, ensure_ascii=False), flush=True)
        if stop_after(row):
            break
    lines = [f"# {args.edge} controller pilot", "", plan["comparison"], "",
             "| Controller | Attempts | Oracle successes | Median task s | Median controller s | Median provider s |",
             "|---|---:|---:|---:|---:|---:|"]
    for arm in dict.fromkeys(args.arms):
        group = [r for r in rows if r["arm"] == arm]
        def median(key):
            values = [r[key] for r in group if r["success"] and r.get(key) is not None]
            return f"{statistics.median(values):.3f}" if values else "pending"
        lines.append(f"| {arm} | {len(group)} | {sum(r['success'] for r in group)}/{len(group)} | "
                     f"{median('task_seconds')} | {median('controller_seconds')} | {median('provider_seconds')} |")
    lines += ["", "Small bounded pilot; no production reliability or product PASS claim. Human confirmation remains pending.", "",
              "Confidence gate: Choice confidence >= 0.9; valid distribution and known candidate required. "
              "Native selected=false is required immediately before each click; absence is unknown. "
              "This checks navigation controls, not checkbox checked-state semantics.", "",
              *[f"- {r['arm']}: `{r.get('run_id', 'missing')}` — {r.get('evidence', 'no evidence')}" for r in rows]]
    if args.edge == "pick-to-editor":
        lines += ["", plan["oracle"], "", plan["timing"], "", "This is a bounded planned controller benchmark, not an unrestricted Grok ToolLoopAgent comparison. OCR is supplemental; exact rendered text needs visual review. All fixture/config cleanup results are saved per attempt."]
    (output / "report.md").write_text("\n".join(lines) + "\n")
    print(output / "report.md")
    return 0 if len(rows) == len(order) and all(r["success"] for r in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
