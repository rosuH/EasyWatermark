#!/usr/bin/env python3
"""Validate the existing failure setup before hybrid can dispatch Retry."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path
import testmap_setup as setup

ROOT = Path(__file__).resolve().parents[1]


def recover(state_file: Path) -> dict:
    if state_file.is_symlink():
        raise ValueError("Recovery descriptor must not be a symlink")
    state_file = state_file.resolve()
    if state_file.name != "ai-source-recovery-state.json" or not state_file.is_relative_to(ROOT.resolve() / "build/agent-device"):
        raise ValueError("Expected a runner-owned recovery descriptor")
    descriptor = json.loads(state_file.read_text())
    if descriptor.get("_ai_recovery_active") is not True or descriptor.get("_ai_recovery_state_path") != str(state_file):
        raise ValueError("Recovery descriptor is inactive or mismatched")
    state = setup.load_setup_backup(Path(descriptor["journal"]))
    journal, lock = Path(state["journal"]), Path(state["lock"])
    if (journal.is_symlink() or lock.is_symlink() or not journal.is_file() or not lock.is_file()
            or json.loads(lock.read_text()).get("journal") != str(journal)
            or state.get("phase") != "active" or state.get("mutated") is not True
            or state.get("platform") != descriptor.get("platform")):
        raise ValueError("Failure setup journal/lock is not active or owned")
    platform, run_id = state["platform"], descriptor["run_id"]
    if descriptor.get("_ai_source_recovered"):
        raise ValueError("Recovery is one-shot and has already completed")
    if platform == "android":
        if state.get("serial") != descriptor.get("serial"):
            raise ValueError("Recovery serial changed")
        proof = setup.repair_failure_source(state, serial=descriptor["serial"], run_id=run_id)
        proof = {"success": True, "method": "owned-android-source-repair", **proof}
    elif platform == "ios":
        control = state.get("export_control") or {}
        if state.get("udid") != descriptor.get("udid") or control.get("mode") != "fail-next" or control.get("run_id") != run_id:
            raise ValueError("Expected this run's iOS fail-next seam")
        data = setup._read_ios_private(Path(state["container"]), setup.IOS_EXPORT_EVENTS)
        if not data or len(data) > 65536:
            raise ValueError("Missing or oversized iOS failure receipt")
        events = [json.loads(line) for line in data.splitlines()]
        if ([row.get("event") for row in events] != ["ready", "entered", "failed", "cleared"]
                or any(set(row) != {"run_id", "event", "timestamp_ms"} or row["run_id"] != run_id
                       or type(row["timestamp_ms"]) is not int for row in events)):
            raise ValueError("iOS failure receipt does not match the current run")
        stamps = [row["timestamp_ms"] for row in events]
        if stamps != sorted(stamps) or stamps[-1] > control["expires_at_ms"]:
            raise ValueError("iOS failure receipt expired or reordered")
        proof = {"success": True, "method": "ios-fail-next-seam-consumed", "run_id": run_id,
                 "physical_source_rewritten": False, "events_sha256": hashlib.sha256(data).hexdigest()}
    else:
        raise ValueError("Unsupported recovery platform")
    descriptor.update(_ai_source_recovered=True, proof=proof)
    temporary = state_file.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(descriptor, indent=2) + "\n")
    temporary.replace(state_file)
    return proof


if __name__ == "__main__":
    try:
        print(json.dumps(recover(Path(sys.argv[1]))))
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print(json.dumps({"success": False, "error": str(error)}))
        raise SystemExit(2)
