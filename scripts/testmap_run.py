#!/usr/bin/env python3
"""ADR-0032 testmap run engine (optional Pillow Android pixels). Never a CI gate.

Importable by the local console, and runnable as a foreground CLI:

    python3 scripts/testmap_run.py --list
    python3 scripts/testmap_run.py guard
    python3 scripts/testmap_run.py guard l1-desktop
"""

from __future__ import annotations

import argparse
import codecs
import hashlib
import io
import json
import os
import re
import select
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

sys.dont_write_bytecode = True

SCRIPTS = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))

from generate_testmap import (  # noqa: E402
    MAP_PATH,
    classify_edge_cases,
    device_runnable,
    edge_no_run_message,
    host_runnable,
    load_map_yaml,
    resolve_edge_device_meta,
    resolve_edge_spec,
    validate_map,
)
from testmap_artemis import (  # noqa: E402
    artemis_cmd,
    DEFAULT_ARTEMIS_DIR,
    evidence_links_for,
    historical_projection,
    is_artemis_python,
    load_result_json,
    load_reviewed_results,
    newest_png,
    recording_index,
    sandbox_artemis_file,
    task_state_from_layers,
    verdict_layers,
)
from testmap_agent_device import (  # noqa: E402
    agent_device_cases_by_id,
    agent_device_cmd,
    agent_device_enabled,
    agent_platforms_for_edge,
    case_platforms,
    case_supports_platform,
    evidence_dir_for,
    agent_device_bin,
    ensure_pinned_agent_device,
    ingest_agent_device_result as _ingest_agent_device_result,
    is_agent_device_cmd,
    maybe_prepare_ios_runner,
    validate_agent_device_binding,
)
from testmap_setup import (  # noqa: E402
    ANDROID_SETUPS,
    IOS_SETUPS,
    SetupRestoreError,
    android_installed,
    apply_setup,
    ios_installed,
    product_version,
    repair_failure_source,
    restore_setup,
)
from testmap_devices import (  # noqa: E402
    default_watch_slots,
    ensure_device_ready,
    resolve_device,
)
from testmap_steps import (  # noqa: E402
    apply_timing_line,
    apply_event,
    parse_script,
    parse_steps_json,
    public_steps,
    materialize_evidence_script,
    EvidenceEvents,
    record_sdk_plan_digest,
    require_clamp_pixels,
    clamp_pixels,
    clamp_pan,
    compare_clamp_pixels,
)
from testmap_stop import (  # noqa: E402
    RUNNER_KILL_S,
    RUNNER_TERM_S,
    terminate_process_group,
    run_captured,
)

HOST = "127.0.0.1"
DEFAULT_PORT = 8931
RUNS_DIR = REPO_ROOT / "docs" / "testmap" / "runs"
CONFIRMATIONS_PATH = RUNS_DIR / "confirmations.json"
MAP_HTML = REPO_ROOT / "docs" / "testmap" / "map.html"
TEST_RESULTS = REPO_ROOT / "shared" / "build" / "test-results"
WITNESS_DIR = REPO_ROOT / "shared" / "build" / "l1-witness"
ARTIFACTS_DIR = REPO_ROOT / "docs" / "testmap" / "artifacts"
WITNESS_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*\.png$")
RUN_ID_RE = re.compile(r"^[0-9T]{15}-[0-9a-f]+(-[0-9]+)?$")
STEP_SHOT_RE = re.compile(
    r"^(?:[A-Za-z0-9]+-)*(android|ios|desktop)-(?:r[0-9]+-)?[0-9]+\.png$"
)
EDGE_TASK_RE = re.compile(
    r"^edge:([^@#]+)(?:@(desktop|ios|android))?(?:#(l2|artemis|agent))?$"
)

TASK_SPECS: dict[str, dict] = {
    "guard": {
        "label": "Guard (TestMapGuardTest)",
        "parse_xml": True,
        "heavy": False,
        "platforms": ["desktop"],
        "cmd": [
            "./gradlew",
            ":shared:desktopTest",
            "--tests",
            "me.rosuh.easywatermark.uitest.TestMapGuardTest",
            "--max-workers=8",
            "--rerun-tasks",
        ],
        "xml_dir": "desktopTest",
        "xml_base": "shared/build/test-results",
    },
    "l1-desktop": {
        "label": "L1 desktop (uitest.*)",
        "parse_xml": True,
        "heavy": False,
        "platforms": ["desktop"],
        "cmd": [
            "./gradlew",
            ":shared:desktopTest",
            "--tests",
            "me.rosuh.easywatermark.uitest.*",
            "--max-workers=8",
            "--rerun-tasks",
        ],
        "xml_dir": "desktopTest",
        "xml_base": "shared/build/test-results",
    },
    "l0l1-desktop-full": {
        "label": "L0/L1 desktop full (:shared:desktopTest)",
        "parse_xml": True,
        "heavy": False,
        "platforms": ["desktop"],
        "cmd": ["./gradlew", ":shared:desktopTest", "--max-workers=8", "--rerun-tasks"],
        "xml_dir": "desktopTest",
        "xml_base": "shared/build/test-results",
    },
    "l1-ios-gate": {
        "label": "L1 iOS gate (heavy: boots iOS simulator runtime)",
        "parse_xml": True,
        "heavy": True,
        "platforms": ["ios"],
        "cmd": ["./gradlew", ":shared:iosSimulatorArm64Test", "--max-workers=8", "--rerun-tasks"],
        "xml_dir": "iosSimulatorArm64Test",
        "xml_base": "shared/build/test-results",
    },
    "desktop-headless": {
        "label": "Desktop headless spine",
        "parse_xml": False,
        "heavy": False,
        "platforms": ["desktop"],
        "cmd": ["./gradlew", ":desktopApp:run", "--args=--headless"],
        "xml_dir": None,
    },
    "l0-android": {
        "label": "Android host L0 (:app:testDebugUnitTest)",
        "parse_xml": True,
        "heavy": False,
        "platforms": ["android"],
        "cmd": ["./gradlew", ":app:testDebugUnitTest", "--max-workers=8", "--rerun-tasks"],
        "xml_dir": "testDebugUnitTest",
        "xml_base": "app/build/test-results",
    },
    "l2-ios-xcuitest": {
        "label": "L2 iOS XCUITest (PickerFlowUITests)",
        "parse_xml": False,
        "heavy": True,
        "platforms": ["ios"],
        "needs_device": "ios",
        "builder": "xcuitest",
        "only_testing": ["iosAppUITests/PickerFlowUITests"],
        "cmd": [
            "xcodebuild",
            "-project",
            "iosApp/iosApp.xcodeproj",
            "-scheme",
            "iosApp",
            "-destination",
            "platform=iOS Simulator,id=<device>",
            "-only-testing:iosAppUITests/PickerFlowUITests",
            "test",
        ],
    },
    "l2-android-instrumented": {
        "label": "L2 Android instrumented (:app:connectedDebugAndroidTest)",
        "parse_xml": True,
        "heavy": True,
        "platforms": ["android"],
        "needs_device": "android",
        "builder": "instrumented",
        "cmd": ["./gradlew", ":app:connectedDebugAndroidTest", "--max-workers=8", "--rerun-tasks"],
        "xml_dir": "connected",
        "xml_base": "app/build/outputs/androidTest-results",
    },
    "l2-android-journeys": {
        "label": "L2 Android ProductJourneys (:macrobenchmark)",
        "parse_xml": True,
        "heavy": True,
        "platforms": ["android"],
        "needs_device": "android",
        "builder": "journey",
        "cmd": [
            "./gradlew",
            ":macrobenchmark:connectedBenchmarkAndroidTest",
            "-Pandroid.testInstrumentationRunnerArguments.class="
            "me.rosuh.macrobenchmark.baselineprofile.ProductBaselineProfileGenerator,"
            "me.rosuh.macrobenchmark.editor.EditorJourneyBenchmark",
            "--max-workers=8",
            "--rerun-tasks",
        ],
        "xml_dir": "connected",
        "xml_base": "macrobenchmark/build/outputs/androidTest-results",
    },
}

MANUAL_TASKS: list[dict] = []

# DeviceProviderInstrumentTestTask accepts --serial, not Gradle Test's --tests.
_CONNECTED_TASKS = frozenset(
    {
        ":app:connectedDebugAndroidTest",
        ":macrobenchmark:connectedBenchmarkAndroidTest",
    }
)
_INSTR_CLASS_PROP = "-Pandroid.testInstrumentationRunnerArguments.class="
_JOURNEY_CLASSES = (
    "me.rosuh.macrobenchmark.baselineprofile.ProductBaselineProfileGenerator",
)


def is_connected_android_task(token: str) -> bool:
    return token in _CONNECTED_TASKS or (
        token.startswith(":") and "connected" in token and token.endswith("AndroidTest")
    )


def android_connected_cmd(task: str, classes: list[str] | None = None) -> list[str]:
    cmd = ["./gradlew", task]
    if classes:
        cmd.append(_INSTR_CLASS_PROP + ",".join(classes))
    cmd.extend(["--max-workers=8", "--rerun-tasks"])
    assert_cmd_legal(cmd)
    return cmd


def with_android_serial(cmd: list[str], serial: str) -> list[str]:
    out: list[str] = []
    i = 0
    while i < len(cmd):
        if cmd[i] == "--serial":
            i += 2
            continue
        out.append(cmd[i])
        i += 1
    for idx, tok in enumerate(out):
        if is_connected_android_task(tok):
            return out[: idx + 1] + ["--serial", serial] + out[idx + 1 :]
    return out


def assert_cmd_legal(cmd: list[str]) -> None:
    if not cmd:
        raise ValueError("empty spawn command")
    if cmd[0] == "./gradlew":
        connected = [tok for tok in cmd if is_connected_android_task(tok)]
        if connected and "--tests" in cmd:
            raise ValueError(
                f"{connected[0]} rejects --tests; use {_INSTR_CLASS_PROP}<class>[,<class>]"
            )
        return
    if cmd[0] == "xcodebuild":
        if "test" not in cmd:
            raise ValueError("xcodebuild L2 must include test")
        if "-destination" not in cmd:
            raise ValueError("xcodebuild missing -destination")
        return
    if is_agent_device_cmd(cmd):
        _assert_agent_device_argv(cmd)
        return
    if is_artemis_python(cmd) or os.environ.get("TESTMAP_ALLOW_PYTHON") == "1":
        return
    raise ValueError(f"unexpected spawn binary: {cmd[0]}")


def _assert_agent_device_argv(cmd: list[str]) -> None:
    if any(tok == "npx" or str(tok).endswith("npx") for tok in cmd):
        raise ValueError("do not invoke agent-device via npx")
    if any("@latest" in str(tok) for tok in cmd):
        raise ValueError("do not invoke agent-device via npx @latest")
    if "--device" in cmd:
        raise ValueError("agent-device must pin --serial or --udid, not --device")
    if "replay" in cmd or "batch" in cmd:
        has_serial = "--serial" in cmd
        has_udid = "--udid" in cmd
        if has_serial == has_udid:
            raise ValueError("agent-device replay requires exactly one of --serial or --udid")
        if "--session" not in cmd:
            raise ValueError("agent-device replay requires --session")
        if "--platform" not in cmd:
            raise ValueError("agent-device replay requires --platform")
    if "prepare" in cmd and "--udid" not in cmd:
        raise ValueError("prepare ios-runner requires --udid")


def validate_all_console_cmds() -> list[str]:
    """Expand every suite + edge@platform and reject illegal spawn argv."""
    errors: list[str] = []
    for tid, spec in TASK_SPECS.items():
        try:
            assert_cmd_legal(list(spec.get("cmd") or []))
        except ValueError as exc:
            errors.append(f"{tid}: {exc}")
    try:
        _nodes, edges = load_map()
    except ValueError as exc:
        return errors + [f"map: {exc}"]
    for edge in edges:
        for plat in ("desktop", "ios", "android"):
            tid = f"edge:{edge['id']}@{plat}"
            try:
                rows = expand_task(tid, "auto")
            except ValueError as exc:
                if "has no" in str(exc):
                    continue
                errors.append(f"{tid}: {exc}")
                continue
            for eid, spec in rows:
                try:
                    assert_cmd_legal(list(spec.get("cmd") or []))
                except ValueError as exc:
                    errors.append(f"{eid}: {exc}")
    agent_cases: dict[str, dict] = {}
    if agent_device_enabled():
        try:
            validate_agent_device_binding([edge["id"] for edge in edges])
            agent_cases = agent_device_cases_by_id()
        except ValueError as exc:
            errors.append(f"agent-device: {exc}")
            agent_cases = {}
        for edge in edges:
            case = agent_cases.get(edge["id"]) or {}
            try:
                plats = case_platforms(case) if case else []
            except ValueError as exc:
                errors.append(f"edge:{edge['id']}#agent: {exc}")
                continue
            for plat in plats:
                if not case_supports_platform(case, plat):
                    continue
                tid = f"edge:{edge['id']}@{plat}#agent"
                try:
                    rows = expand_task(tid, "auto")
                    for eid, spec in rows:
                        assert_cmd_legal(list(spec.get("cmd") or []))
                except ValueError as exc:
                    errors.append(f"{tid}: {exc}")
    return errors

SEMANTICS = (
    "Stop: SIGTERM the current test process group only — never an already-live "
    "emulator or Simulator, including one this console booted. "
    "The Gradle daemon is left running after a run so repeats are faster. "
    "Not a CI gate."
)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def git_info() -> dict:
    def run(args: list[str]) -> str:
        try:
            return subprocess.check_output(
                ["git", *args], cwd=REPO_ROOT, text=True, stderr=subprocess.DEVNULL
            ).strip()
        except subprocess.CalledProcessError:
            return ""

    sha = run(["rev-parse", "--short", "HEAD"]) or "unknown"
    dirty = bool(run(["status", "--porcelain"]))
    return {"sha": sha, "dirty": dirty}


def load_map() -> tuple[list[dict], list[dict]]:
    data = load_map_yaml(MAP_PATH.read_text(encoding="utf-8"))
    return validate_map(data)


def case_ref(classname: str, name: str) -> str:
    simple = (classname or "").rsplit(".", 1)[-1]
    method = (name or "").split("[", 1)[0]
    return f"{simple}.{method}"


def parse_junit(
    xml_dir: str | None, since: float, xml_base: str | None = None
) -> list[dict]:
    if not xml_dir:
        return []
    base = REPO_ROOT / (xml_base or "shared/build/test-results")
    root = base / xml_dir
    if not root.is_dir():
        return []
    results: list[dict] = []
    for path in sorted(root.rglob("TEST-*.xml")):
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        if mtime < since - 1.0:
            continue
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        for tc in tree.iter("testcase"):
            classname = tc.get("classname") or ""
            name = tc.get("name") or ""
            status = "passed"
            fail_el = tc.find("failure")
            if fail_el is None:
                fail_el = tc.find("error")
            if fail_el is not None:
                status = "failed"
            elif tc.find("skipped") is not None:
                status = "skipped"
            try:
                duration = float(tc.get("time") or 0)
            except ValueError:
                duration = 0.0
            results.append(
                {
                    "ref": case_ref(classname, name),
                    "classname": classname,
                    "name": name,
                    "status": status,
                    "duration": duration,
                    "message": _junit_message(fail_el) if fail_el is not None else "",
                }
            )
    return results


def _junit_message(el: ET.Element) -> str:
    msg = (el.get("message") or "").strip()
    body = (el.text or "").strip()
    blob = "\n".join(part for part in (msg, body) if part)
    lines = [ln for ln in blob.splitlines() if ln.strip()][:6]
    return "\n".join(lines)[:600]


def allocate_run_id(git: dict) -> str:
    stamp = utc_now().strftime("%Y%m%dT%H%M%S")
    run_id = f"{stamp}-{git['sha']}"
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    n = 2
    while (RUNS_DIR / f"{run_id}.json").exists():
        run_id = f"{stamp}-{git['sha']}-{n}"
        n += 1
    return run_id


def resolve_task_spec(tid: str) -> dict:
    entries = expand_task(tid, None)
    if len(entries) != 1:
        raise ValueError(f"{tid} expands to {len(entries)} tasks; use expand_task")
    return entries[0][1]


def _record_task(tid: str, spec: dict) -> dict:
    platform = spec.get("agent_platform") or spec.get("needs_device") or ""
    return {
        "id": tid,
        "edge": spec.get("edge_id") or task_edge_id(tid),
        "platform": platform or "",
        "repeat": {"k": 1, "n": 1},
        "label": spec["label"],
        "state": "pending",
        "steps": [],
        "cmd": list(spec.get("cmd") or []),
        "exit_code": None,
        "duration_s": None,
        "cases": [],
        "skipped_refs": list(spec.get("skipped_refs") or []),
        "runnable_refs": list(spec.get("runnable_refs") or []),
        "parse_xml": spec.get("parse_xml", False),
        "xml_dir": spec.get("xml_dir"),
        "xml_base": spec.get("xml_base"),
        "needs_device": spec.get("needs_device"),
        "builder": spec.get("builder"),
        "only_testing": list(spec.get("only_testing") or []),
        "device_request": spec.get("device_request") or "auto",
        "env": dict(spec.get("env") or {}),
        "edge_id": spec.get("edge_id"),
        "artemis_output": spec.get("artemis_output"),
        "agent_device_output": spec.get("agent_device_output"),
        "agent_platform": spec.get("agent_platform"),
        "setup": spec.get("setup"),
    }


def xcodebuild_cmd(device: dict, only_testing: list[str]) -> list[str]:
    if device.get("kind") == "physical":
        dest = f"platform=iOS,id={device['id']}"
        signing: list[str] = []
    else:
        dest = f"platform=iOS Simulator,id={device['id']}"
        signing = ["CODE_SIGNING_ALLOWED=NO"]
    cmd = [
        "xcodebuild",
        "-project",
        "iosApp/iosApp.xcodeproj",
        "-scheme",
        "iosApp",
        "-destination",
        dest,
        "-derivedDataPath",
        str(RUNS_DIR / ".derived"),
    ]
    for ident in only_testing or ["iosAppUITests/PickerFlowUITests"]:
        cmd.append(f"-only-testing:{ident}")
    cmd.extend(signing)
    cmd.append("test")
    return cmd


def apply_device(spec: dict, device: dict) -> dict:
    spec = dict(spec)
    spec["device"] = {
        "id": device["id"],
        "name": device.get("name"),
        "kind": device.get("kind"),
    }
    env = dict(spec.get("env") or {})
    builder = spec.get("builder")
    if builder == "xcuitest":
        spec["cmd"] = xcodebuild_cmd(device, list(spec.get("only_testing") or []))
    elif builder in {"instrumented", "journey"}:
        env["ANDROID_SERIAL"] = device["id"]
        spec["env"] = env
        spec["cmd"] = with_android_serial(list(spec.get("cmd") or []), device["id"])
        assert_cmd_legal(spec["cmd"])
    elif builder == "artemis":
        env["ANDROID_SERIAL"] = device["id"]
        env["ADB_DEVICE_SERIAL"] = device["id"]
        spec["env"] = env
        edge_id = spec.get("edge_id") or ""
        output = Path(spec.get("artemis_output") or (RUNS_DIR / "artemis-pending" / edge_id))
        dry = os.environ.get("TESTMAP_ARTEMIS_DRY") == "1"
        spec["cmd"] = artemis_cmd(
            edge_id,
            serial=device["id"],
            output_dir=output,
            artemis_dir=Path(os.environ.get("ARTEMIS_DIR") or DEFAULT_ARTEMIS_DIR),
            dry_run=dry,
        )
        spec["artemis_output"] = str(output)
        assert_cmd_legal(spec["cmd"])
    elif builder == "agent-device":
        platform = spec.get("agent_platform") or spec.get("needs_device")
        edge_id = spec.get("edge_id") or ""
        output = Path(
            spec.get("agent_device_output")
            or (REPO_ROOT / "build" / "agent-device" / "pending" / edge_id)
        )
        output.mkdir(parents=True, exist_ok=True)
        ensure_pinned_agent_device()
        if platform == "android":
            env["ANDROID_SERIAL"] = device["id"]
            env["ADB_DEVICE_SERIAL"] = device["id"]
            spec["cmd"] = agent_device_cmd(
                edge_id,
                platform="android",
                serial=device["id"],
                output_dir=output,
            )
        elif platform == "ios":
            spec["cmd"] = agent_device_cmd(
                edge_id,
                platform="ios",
                udid=device["id"],
                output_dir=output,
            )
        else:
            raise ValueError(
                f"Agent Device is android/ios only, got {platform}"
            )
        spec["env"] = env
        spec["agent_device_output"] = str(output)
        spec["udid"] = device["id"] if platform == "ios" else spec.get("udid")
        spec["serial"] = device["id"] if platform == "android" else spec.get("serial")
        assert_cmd_legal(spec["cmd"])
    label = spec.get("label") or ""
    tag = f"{device.get('name') or device['id']}"
    if tag and tag not in label:
        spec["label"] = f"{label} · {tag}"
    return spec


def expand_task(tid: str, device_request: str | None) -> list[tuple[str, dict]]:
    request = device_request or "auto"
    if tid in TASK_SPECS:
        spec = dict(TASK_SPECS[tid])
        spec.setdefault("skipped_refs", [])
        spec.setdefault("runnable_refs", [])
        spec["device_request"] = request
        return [(tid, spec)]
    match = EDGE_TASK_RE.fullmatch(tid)
    if not match:
        raise ValueError(f"unknown task: {tid}")
    edge_id = match.group(1)
    platform = match.group(2)
    slice_name = match.group(3)
    slice_l2 = slice_name == "l2"
    if slice_name == "agent":
        if not agent_device_enabled():
            raise ValueError("Agent Device adapter disabled (TESTMAP_AGENT_DEVICE=0)")
        if platform == "desktop":
            raise ValueError("Agent Device is not used for Compose Desktop")
        if platform and platform not in {"android", "ios"}:
            raise ValueError(f"Agent Device is android/ios only; got {platform}")
        if platform:
            plats = [platform]
        else:
            case = agent_device_cases_by_id().get(edge_id)
            if not case:
                raise ValueError(f"unknown Agent Device case: {edge_id}")
            plats = [
                plat
                for plat in agent_platforms_for_edge(edge_id)
                if case_supports_platform(case, plat)
            ]
        if not plats:
            raise ValueError(f"no Agent Device platform for {edge_id}")
        out_agent: list[tuple[str, dict]] = []
        for plat in plats:
            out_agent.append(
                (f"edge:{edge_id}@{plat}#agent", _agent_device_spec(edge_id, plat, request))
            )
        return out_agent
    if slice_name == "artemis":
        if platform and platform != "android":
            raise ValueError(f"Artemis adapter is Android-only; got {platform}")
        return [(f"edge:{edge_id}@android#artemis", _artemis_spec(edge_id, request))]
    platform = platform or "desktop"
    _nodes, edges = load_map()
    edge = next((item for item in edges if item["id"] == edge_id), None)
    if not edge:
        raise ValueError(f"unknown edge: {edge_id}")
    runnable, skipped = classify_edge_cases(edge, platform=platform)
    host = host_runnable(runnable)
    device = device_runnable(runnable)
    if not host and not device:
        raise ValueError(edge_no_run_message(edge_id, skipped, platform))
    out: list[tuple[str, dict]] = []
    if host and not slice_l2:
        spec = resolve_edge_spec(edge, platform=platform)
        spec["device_request"] = request
        out.append((f"edge:{edge_id}@{platform}", spec))
    if device:
        meta = resolve_edge_device_meta(edge, platform=platform)
        if meta:
            spec = _device_spec_from_meta(meta, skipped, request)
            out.append((f"edge:{edge_id}@{platform}#l2", spec))
    if not out:
        raise ValueError(edge_no_run_message(edge_id, skipped, platform))
    return out


def task_edge_id(task_id: str) -> str:
    """Parse `edge:<id>@os#lane` into the map edge id. Empty if not an edge task."""
    raw = str(task_id or "")
    if not raw.startswith("edge:"):
        return ""
    return raw[5:].split("@", 1)[0].split("#", 1)[0]


def _pinned_platform(device_request: str | None) -> str | None:
    """A concrete serial or UDID runs only that device's platform."""
    request = (device_request or "").strip()
    if not request or request == "auto":
        return None
    if request.startswith("emulator-"):
        return "android"
    if re.fullmatch(r"[0-9A-Fa-f-]{25,}", request):
        return "ios"
    return None


def _task_platform(task_id: str) -> str | None:
    if "@android" in task_id:
        return "android"
    if "@ios" in task_id:
        return "ios"
    return None


def expand_mobile_parallel(task_ids: list[str], device_request: str | None = None) -> list[str]:
    """If a mobile #agent edge is queued, also queue the other OS when supported."""
    pinned = _pinned_platform(device_request)
    if pinned:
        return [tid for tid in task_ids if _task_platform(tid) in {None, pinned}]
    if os.environ.get("TESTMAP_NO_EXPAND") == "1":
        return list(task_ids)
    out = list(task_ids)
    seen = set(out)
    for tid in list(task_ids):
        if "#agent" not in tid:
            continue
        other = None
        if "@android" in tid:
            other = tid.replace("@android", "@ios", 1)
        elif "@ios" in tid:
            other = tid.replace("@ios", "@android", 1)
        if not other or other in seen:
            continue
        try:
            expand_task(other, None)
        except ValueError:
            continue
        out.append(other)
        seen.add(other)
    return out


def _task_lane(spec: dict) -> str:
    plat = spec.get("agent_platform") or spec.get("needs_device")
    if plat in {"android", "ios"}:
        return str(plat)
    return "host"


def _artemis_spec(edge_id: str, request: str) -> dict:
    stamp = utc_now().strftime("%Y%m%dT%H%M%S")
    output = REPO_ROOT / "build" / "artemis-suite" / f"testmap-{stamp}-{edge_id}"
    n = 2
    while output.exists():
        output = REPO_ROOT / "build" / "artemis-suite" / f"testmap-{stamp}-{edge_id}-{n}"
        n += 1
    dry = os.environ.get("TESTMAP_ARTEMIS_DRY") == "1"
    cmd = artemis_cmd(
        edge_id,
        serial="<device>",
        output_dir=output,
        dry_run=dry,
    )
    return {
        "label": f"Artemis {edge_id}",
        "parse_xml": False,
        "heavy": True,
        "builder": "artemis",
        "needs_device": None if dry else "android",
        "edge_id": edge_id,
        "artemis_output": str(output),
        "runnable_refs": [f"Artemis.{edge_id}"],
        "skipped_refs": [],
        "device_request": request,
        "cmd": cmd,
        "platforms": ["android"],
    }


def _agent_device_spec(edge_id: str, platform: str, request: str) -> dict:
    if platform == "desktop":
        raise ValueError("Agent Device is not used for Compose Desktop")
    if platform not in {"android", "ios"}:
        raise ValueError(f"Agent Device is android/ios only; got {platform}")
    case = agent_device_cases_by_id().get(edge_id)
    if not case:
        raise ValueError(f"unknown Agent Device case: {edge_id}")
    plats = case_platforms(case)
    if platform not in plats:
        raise ValueError(f"Agent Device case {edge_id} does not support {platform}")
    if not case_supports_platform(case, platform):
        reason = ""
        raw = case.get("reason")
        if isinstance(raw, dict):
            reason = str(raw.get(platform) or "")
        elif isinstance(raw, str):
            reason = raw
        suffix = f": {reason}" if reason else ""
        raise ValueError(
            f"Agent Device case {edge_id} is unsupported on {platform}{suffix}"
        )
    output = evidence_dir_for(edge_id)
    dry = os.environ.get("TESTMAP_AGENT_DEVICE_DRY") == "1"
    cmd = agent_device_cmd(
        edge_id,
        platform=platform,
        serial="<device>" if platform == "android" else None,
        udid="<device>" if platform == "ios" else None,
        output_dir=output,
    )
    return {
        "label": f"Agent Device {edge_id} ({platform})",
        "parse_xml": False,
        "heavy": True,
        "builder": "agent-device",
        "needs_device": None if dry else platform,
        "edge_id": edge_id,
        "agent_platform": platform,
        "setup": case.get("setup") or "home",
        "agent_device_output": str(output),
        "runnable_refs": [f"AgentDevice.{edge_id}"],
        "skipped_refs": [],
        "device_request": request,
        "cmd": cmd,
        "platforms": [platform],
    }


def _device_spec_from_meta(meta: dict, skipped: list[dict], request: str) -> dict:
    builder = meta["builder"]
    spec: dict = {
        "label": meta["label"],
        "parse_xml": builder in {"journey", "instrumented"},
        "heavy": meta.get("heavy", True),
        "builder": builder,
        "only_testing": list(meta.get("only_testing") or []),
        "runnable_refs": list(meta.get("runnable_refs") or []),
        "skipped_refs": skipped,
        "device_request": request,
        "cmd": [],
        "needs_device": None,
    }
    if builder == "headless":
        spec["cmd"] = list(TASK_SPECS["desktop-headless"]["cmd"])
        spec["parse_xml"] = False
        spec["heavy"] = False
    elif builder == "journey":
        spec["needs_device"] = "android"
        spec["cmd"] = android_connected_cmd(
            ":macrobenchmark:connectedBenchmarkAndroidTest",
            list(_JOURNEY_CLASSES),
        )
        spec["xml_dir"] = "connected"
        spec["xml_base"] = "macrobenchmark/build/outputs/androidTest-results"
    elif builder == "xcuitest":
        spec["needs_device"] = "ios"
        spec["cmd"] = [
            "xcodebuild",
            "-project",
            "iosApp/iosApp.xcodeproj",
            "-scheme",
            "iosApp",
            "-destination",
            "platform=iOS Simulator,id=<device>",
            "test",
        ]
    else:
        raise ValueError(f"unknown device builder: {builder}")
    return spec


def task_run_spec(task: dict) -> dict:
    return {
        "cmd": list(task.get("cmd") or []),
        "parse_xml": task.get("parse_xml", False),
        "xml_dir": task.get("xml_dir"),
        "xml_base": task.get("xml_base"),
        "needs_device": task.get("needs_device"),
        "builder": task.get("builder"),
        "only_testing": list(task.get("only_testing") or []),
        "device_request": task.get("device_request") or "auto",
        "env": dict(task.get("env") or {}),
        "label": task.get("label"),
        "edge_id": task.get("edge_id"),
        "artemis_output": task.get("artemis_output"),
        "agent_device_output": task.get("agent_device_output"),
        "agent_platform": task.get("agent_platform"),
        "setup": task.get("setup"),
    }


def new_record(task_ids: list[str], device: str | None = None) -> dict:
    if not task_ids:
        raise ValueError("selection is empty")
    resolved: list[tuple[str, dict]] = []
    for tid in task_ids:
        resolved.extend(expand_task(tid, device))
    git = git_info()
    run_id = allocate_run_id(git)
    log_path = RUNS_DIR / f"{run_id}.log"
    reset_live_artifacts()
    return {
        "id": run_id,
        "started": iso(utc_now()),
        "finished": None,
        "git": git,
        "selection": list(task_ids),
        "device": device or "auto",
        "state": "running",
        "tasks": [_record_task(tid, spec) for tid, spec in resolved],
        "log": str(log_path.relative_to(REPO_ROOT)),
        "pass_count": 0,
        "fail_count": 0,
    }


def step_shot_path(run_id: str, name: str) -> Path | None:
    if not RUN_ID_RE.match(run_id or "") or not STEP_SHOT_RE.match(name or ""):
        return None
    path = (RUNS_DIR / run_id / "steps" / name).resolve()
    root = (RUNS_DIR / run_id / "steps").resolve()
    if path.parent != root or not path.is_file():
        return None
    return path


def _public_record(rec: dict) -> dict:
    out = {key: value for key, value in rec.items() if not str(key).startswith("_")}
    tasks = []
    for task in rec.get("tasks") or []:
        item = {key: value for key, value in task.items() if not str(key).startswith("_")}
        tasks.append(item)
    out["tasks"] = tasks
    return out


def write_record(rec: dict) -> Path:
    """Atomically replace runs/<id>.json. Private keys are not persisted."""
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    path = RUNS_DIR / f"{rec['id']}.json"
    payload = json.dumps(_public_record(rec), indent=2) + "\n"
    tmp = path.with_name(f".{path.stem}.{os.getpid()}.tmp")
    tmp.write_text(payload, encoding="utf-8")
    os.replace(tmp, path)
    return path


def outcome_counts(tasks: list) -> tuple[int, int, int]:
    """Pass is review_required or passed. Uncovered is its own bucket, not fail."""
    passed = failed = uncovered = 0
    for task in tasks or []:
        if not isinstance(task, dict):
            continue
        state = task.get("state")
        if state in {"passed", "review_required"}:
            passed += 1
        elif state == "uncovered":
            uncovered += 1
        elif state == "failed":
            failed += 1
    return passed, failed, uncovered


def finalize_record(rec: dict) -> None:
    rec["finished"] = iso(utc_now())
    skip_n = 0
    for task in rec["tasks"]:
        if task.get("state") in {"running", "paused"}:
            task["state"] = "interrupted"
        for case in task.get("cases") or []:
            if case.get("status") == "skipped":
                skip_n += 1
    pass_n, fail_n, uncovered_n = outcome_counts(rec.get("tasks") or [])
    rec["pass_count"] = pass_n
    rec["fail_count"] = fail_n
    rec["uncovered_count"] = uncovered_n
    rec["skip_count"] = skip_n
    started_ts = _iso_ts(rec.get("started"))
    finished_ts = _iso_ts(rec.get("finished"))
    if started_ts is not None and finished_ts is not None:
        rec["duration_s"] = round(finished_ts - started_ts, 2)
    if any(t.get("setup_restore_failed") for t in rec["tasks"]):
        rec["state"] = "failed"
    elif any(t["state"] == "stopped" for t in rec["tasks"]) or rec["state"] == "paused":
        rec["state"] = "stopped"
    elif any(t["state"] in {"failed", "uncovered", "interrupted"} for t in rec["tasks"]):
        rec["state"] = "failed"
    elif any(t["state"] == "blocked" for t in rec["tasks"]):
        rec["state"] = "blocked"
    elif any(t["state"] == "review_required" for t in rec["tasks"]):
        rec["state"] = "review_required"
    else:
        rec["state"] = "passed"
    for task in rec["tasks"]:
        task.pop("_started_mono", None)
    write_record(rec)


def cli_exit_code(rec: dict, prior: int = 0) -> int:
    """CLI exit after finalize_record. Interrupted or failed tasks are non-zero."""
    states = [str(t.get("state") or "") for t in rec.get("tasks") or [] if isinstance(t, dict)]
    if any(state in {"failed", "interrupted", "blocked"} for state in states):
        return prior if prior else 1
    if rec.get("state") == "stopped" or any(state == "stopped" for state in states):
        return prior if prior else 130
    return prior


def write_historical_projection() -> Path | None:
    rec = historical_projection()
    if not rec.get("tasks"):
        return None
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    path = RUNS_DIR / f"{rec['id']}.json"
    if path.is_file():
        try:
            existing = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            existing = {}
        if existing.get("historical") and existing.get("source") == rec.get("source"):
            changed = False
            for key in ("started", "finished"):
                if rec.get(key) and existing.get(key) != rec.get(key):
                    existing[key] = rec[key]
                    changed = True
            if changed:
                path.write_text(json.dumps(existing, indent=2) + "\n", encoding="utf-8")
            return path
    path.write_text(json.dumps(rec, indent=2) + "\n", encoding="utf-8")
    return path


def run_summaries() -> list[dict]:
    write_historical_projection()
    if not RUNS_DIR.is_dir():
        return []
    items = []
    for path in sorted(RUNS_DIR.glob("*.json"), reverse=True):
        if not RUN_ID_RE.match(path.stem):
            continue
        try:
            rec = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(rec, dict) or (rec.get("historical") and not rec.get("tasks")):
            continue
        rec = reap_stale_run(rec)
        passed_n, failed_n, uncovered_n = outcome_counts(rec.get("tasks") or [])
        plats: list[str] = []
        for task in rec.get("tasks") or []:
            plat = str(task.get("platform") or "")
            if plat and plat not in plats:
                plats.append(plat)
        items.append(
            {
                "id": rec.get("id", path.stem),
                "started": rec.get("started"),
                "finished": rec.get("finished"),
                "state": rec.get("state"),
                "git": rec.get("git"),
                "selection": rec.get("selection"),
                "source": rec.get("source") or "",
                "device": rec.get("device") or "",
                "platforms": plats,
                "pass_count": passed_n,
                "fail_count": failed_n,
                "uncovered_count": uncovered_n,
                "skip_count": rec.get("skip_count", 0),
                "duration_s": rec.get("duration_s"),
                "historical": bool(rec.get("historical")),
            }
        )
    items.sort(key=_summary_sort_key, reverse=True)
    return items


def load_run(run_id: str) -> dict | None:
    if not RUN_ID_RE.match(run_id):
        return None
    path = RUNS_DIR / f"{run_id}.json"
    if not path.is_file():
        return None
    try:
        rec = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(rec, dict):
        return None
    return reap_stale_run(rec)


def latest_run() -> dict | None:
    summaries = run_summaries()
    if not summaries:
        return None
    return load_run(summaries[0]["id"])


def _iso_ts(value: str | None) -> float | None:
    if not value:
        return None
    raw = str(value).strip()
    try:
        if raw.endswith("Z") and len(raw) >= 20:
            return datetime.strptime(raw[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc).timestamp()
        return datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(timezone.utc).timestamp()
    except ValueError:
        return None


def _run_id_ts(run_id: str | None) -> float | None:
    if not run_id:
        return None
    head = str(run_id).split("-", 1)[0]
    try:
        return datetime.strptime(head, "%Y%m%dT%H%M%S").replace(tzinfo=timezone.utc).timestamp()
    except ValueError:
        return None


def _summary_sort_key(item: dict) -> tuple:
    """Newest actual start time first. Do not let a synthetic filename win."""
    started = _iso_ts(item.get("started"))
    if started is None:
        started = _run_id_ts(item.get("id")) or 0.0
    return (started, str(item.get("id") or ""))


def _task_counts(task: dict) -> dict[str, int]:
    cases = task.get("cases") or []
    return {
        "total": len(cases),
        "passed": sum(1 for c in cases if c.get("status") == "passed"),
        "failed": sum(1 for c in cases if c.get("status") == "failed"),
        "skipped": sum(1 for c in cases if c.get("status") == "skipped"),
    }


def _case_matches_ref(case: dict, ref: str) -> bool:
    got = str(case.get("ref") or "")
    if got == ref or got.endswith(ref) or ref.endswith(got):
        return True
    name = str(case.get("name") or "").split("[", 1)[0]
    return bool(name) and ref.endswith("." + name)


def map_refs_for_task(task: dict) -> list[dict] | None:
    tid = str(task.get("id") or "")
    match = EDGE_TASK_RE.fullmatch(tid)
    if not match:
        return None
    wanted = list(task.get("runnable_refs") or [])
    if not wanted:
        edge_id = match.group(1)
        platform = match.group(2) or "desktop"
        try:
            _nodes, edges = load_map()
        except ValueError:
            edges = []
        edge = next((item for item in edges if item.get("id") == edge_id), None)
        if edge:
            runnable, _skipped = classify_edge_cases(edge, platform=platform)
            wanted = [item["ref"] for item in runnable]
    cases = task.get("cases") or []
    out: list[dict] = []
    for ref in wanted:
        hit = next((c for c in cases if _case_matches_ref(c, ref)), None)
        if hit:
            out.append(
                {
                    "ref": ref,
                    "status": hit.get("status") or "not-in-report",
                    "duration": hit.get("duration"),
                    "message": hit.get("message") or "",
                }
            )
        else:
            out.append({"ref": ref, "status": "not-in-report"})
    return out


def witnesses_for_run(rec: dict) -> list[dict]:
    started = _iso_ts(rec.get("started"))
    finished = _iso_ts(rec.get("finished")) or time.time()
    methods: set[str] = set()
    for task in rec.get("tasks") or []:
        for case in task.get("cases") or []:
            name = str(case.get("name") or "").split("[", 1)[0]
            if name:
                methods.add(f"{name}.png")
            ref = str(case.get("ref") or "")
            if "." in ref:
                methods.add(ref.rsplit(".", 1)[-1] + ".png")
    if not WITNESS_DIR.is_dir():
        return []
    out: list[dict] = []
    seen: set[str] = set()
    for path in sorted(WITNESS_DIR.glob("*.png")):
        if not WITNESS_NAME_RE.match(path.name) or path.name in seen:
            continue
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        in_window = started is not None and (started - 2) <= mtime <= (finished + 8)
        if path.name in methods or in_window:
            seen.add(path.name)
            out.append({"file": path.name, "mtime": int(mtime)})
    return out


def enrich_run(rec: dict) -> dict:
    view = dict(rec)
    view["witnesses"] = witnesses_for_run(rec)
    if view.get("skip_count") is None:
        view["skip_count"] = sum(
            1
            for task in rec.get("tasks") or []
            for case in (task.get("cases") or [])
            if case.get("status") == "skipped"
        )
    started = _iso_ts(rec.get("started"))
    finished = _iso_ts(rec.get("finished"))
    if view.get("duration_s") is None and started is not None and finished is not None:
        view["duration_s"] = round(finished - started, 2)
    tasks = []
    run_links: list[dict] = []
    for task in rec.get("tasks") or []:
        item = dict(task)
        item["counts"] = _task_counts(task)
        mapped = map_refs_for_task(task)
        if mapped is not None:
            item["map_refs"] = mapped
        links = evidence_links_for(item)
        if links:
            item["evidence_links"] = links
            run_links.extend(links)
        tasks.append(item)
    view["tasks"] = tasks
    if run_links:
        view["evidence_links"] = run_links
    return view


def edge_badges(edges: list[dict]) -> dict[str, str]:
    """Newest covering run wins per edge (L1 + guard refs only)."""
    runs = []
    for item in run_summaries():
        rec = load_run(item["id"])
        if rec:
            runs.append(rec)
    badges: dict[str, str] = {}
    for edge in edges:
        wanted = {
            c["ref"]
            for c in (edge.get("cases") or [])
            if c.get("layer") == "L1" or str(c.get("ref", "")).startswith("TestMapGuardTest")
        }
        if not wanted:
            continue
        for rec in runs:
            statuses = []
            for task in rec.get("tasks") or []:
                for case in task.get("cases") or []:
                    if case.get("ref") in wanted:
                        statuses.append(case.get("status"))
            if not statuses:
                continue
            badges[edge["id"]] = (
                "failed" if any(s == "failed" for s in statuses) else "passed"
            )
            break
    return badges


_EDGE_RESULT_RANK = {
    "failed": 60,
    "blocked": 50,
    "stopped": 40,
    "interrupted": 40,
    "pending": 40,
    "uncovered": 35,
    "review_required": 30,
    "passed": 20,
    "running": 40,
    "paused": 40,
    "skipped": 5,
}
_CONFIRMABLE_RESULTS = frozenset({"passed", "review_required"})
_UNFINISHED_RUN_STATES = frozenset({"running", "paused", "pending"})
_REJECT_RUN_STATES = frozenset({"running", "paused", "pending", "interrupted"})
_edge_result_cache: tuple[float, dict[str, str], dict[str, str]] | None = None


def edge_results_in_run(rec: dict, wanted: set[str] | None = None) -> dict[str, str]:
    """Worst task state per edge in one run. Same definition as /api/map results.

    A task belongs to an edge when `task.edge` or `task_edge_id(task.id)` equals that id.
    """
    seen: dict[str, str] = {}
    for task in rec.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        eid = str(task.get("edge") or task_edge_id(str(task.get("id") or "")) or "")
        if not eid:
            continue
        if wanted is not None and eid not in wanted:
            continue
        state = str(task.get("state") or "")
        if not state:
            continue
        if state == "stopped" and task.get("duration_s") is None:
            continue
        prev = seen.get(eid, "")
        if _EDGE_RESULT_RANK.get(state, 100) >= _EDGE_RESULT_RANK.get(prev, 100 if prev else 0):
            seen[eid] = state
    return seen


def edge_result_in_run(rec: dict, edge_id: str) -> str | None:
    return edge_results_in_run(rec, {edge_id}).get(edge_id)


def _latest_edge_projection(edges: list[dict]) -> tuple[dict[str, str], dict[str, str]]:
    """Newest non-interrupted run that has a result for each edge: (results, result_runs)."""
    global _edge_result_cache
    now = time.monotonic()
    if _edge_result_cache and now - _edge_result_cache[0] < 8:
        return _edge_result_cache[1], _edge_result_cache[2]
    wanted = {str(edge.get("id") or "") for edge in edges if edge.get("id")}
    found: dict[str, str] = {}
    result_runs: dict[str, str] = {}
    for item in run_summaries():
        if wanted <= found.keys():
            break
        rec = load_run(str(item.get("id") or ""))
        if not rec or rec.get("state") == "interrupted":
            continue
        rid = str(rec.get("id") or item.get("id") or "")
        remaining = {eid for eid in wanted if eid not in found}
        seen = edge_results_in_run(rec, remaining)
        for eid, state in seen.items():
            if state:
                found[eid] = state
                result_runs[eid] = rid
    _edge_result_cache = (now, found, result_runs)
    return found, result_runs


def latest_edge_results(edges: list[dict]) -> dict[str, str]:
    """Read projection: newest record that contains the edge, worst task state in it.

    Does not write the run files. Cached briefly because the page polls /api/map.
    """
    return _latest_edge_projection(edges)[0]


def edge_result_runs(edges: list[dict]) -> dict[str, str]:
    """Newest run id per edge that has a result under `edge_results_in_run`."""
    return _latest_edge_projection(edges)[1]


def list_witness_files() -> list[str]:
    if not WITNESS_DIR.is_dir():
        return []
    return sorted(p.name for p in WITNESS_DIR.glob("*.png") if WITNESS_NAME_RE.match(p.name))


def witness_file(name: str) -> Path | None:
    if not name or "/" in name or "\\" in name or ".." in name:
        return None
    base = Path(name).name
    if base != name or not WITNESS_NAME_RE.match(base):
        return None
    path = WITNESS_DIR / base
    if not path.is_file():
        return None
    return path


def reset_live_artifacts() -> None:
    if ARTIFACTS_DIR.exists():
        shutil.rmtree(ARTIFACTS_DIR)
    (ARTIFACTS_DIR / "live").mkdir(parents=True)
    (ARTIFACTS_DIR / "keyframes").mkdir(parents=True)


def artifact_png(kind: str, name: str) -> Path | None:
    if kind == "live" and name == "preview.png":
        path = ARTIFACTS_DIR / "live" / "preview.png"
        return path if path.is_file() else None
    if kind != "keyframes":
        return None
    if not name or "/" in name or "\\" in name or ".." in name:
        return None
    base = Path(name).name
    if base != name or not WITNESS_NAME_RE.match(base):
        return None
    path = ARTIFACTS_DIR / "keyframes" / base
    return path if path.is_file() else None


def live_snapshot() -> dict:
    preview_png = ARTIFACTS_DIR / "live" / "preview.png"
    preview_meta: dict = {}
    meta_path = ARTIFACTS_DIR / "live" / "preview.json"
    if meta_path.is_file():
        try:
            loaded = json.loads(meta_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                preview_meta = loaded
        except (OSError, json.JSONDecodeError):
            preview_meta = {}
    frames: list[dict] = []
    index_path = ARTIFACTS_DIR / "index.json"
    if index_path.is_file():
        try:
            loaded = json.loads(index_path.read_text(encoding="utf-8"))
            raw = loaded.get("keyframes") if isinstance(loaded, dict) else None
            if isinstance(raw, list):
                frames = [item for item in raw if isinstance(item, dict) and item.get("file")]
        except (OSError, json.JSONDecodeError):
            frames = []
    if not frames:
        key_dir = ARTIFACTS_DIR / "keyframes"
        if key_dir.is_dir():
            for path in sorted(key_dir.glob("*.png")):
                if WITNESS_NAME_RE.match(path.name):
                    frames.append({"file": path.name})
    mtime_ms = 0
    if preview_png.is_file():
        mtime_ms = int(preview_png.stat().st_mtime * 1000)
    return {
        "preview": {
            "exists": preview_png.is_file(),
            "mtime_ms": mtime_ms,
            "tag": preview_meta.get("tag") or "",
            "test": preview_meta.get("test") or "",
        },
        "keyframes": frames[-24:],
    }


def load_confirmations() -> dict[str, dict]:
    if not CONFIRMATIONS_PATH.is_file():
        return {}
    try:
        data = json.loads(CONFIRMATIONS_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    if isinstance(data, list):
        out: dict[str, dict] = {}
        for item in data:
            if isinstance(item, dict) and item.get("edge_id"):
                out[str(item["edge_id"])] = item
        return out
    if not isinstance(data, dict):
        return {}
    return {str(k): v for k, v in data.items() if isinstance(v, dict)}


def save_confirmations(data: dict[str, dict]) -> None:
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    CONFIRMATIONS_PATH.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def confirmation_views(
    edges: list[dict],
    projection: tuple[dict[str, str], dict[str, str]] | None = None,
) -> dict[str, dict]:
    if projection is None:
        _results, result_runs = _latest_edge_projection(edges)
    else:
        _results, result_runs = projection
    out: dict[str, dict] = {}
    for eid, rec in load_confirmations().items():
        cover = result_runs.get(eid)
        confirmed_run = rec.get("run_id")
        stale = bool(cover and cover != confirmed_run)
        out[eid] = {
            "edge_id": eid,
            "run_id": confirmed_run,
            "confirmed_at": rec.get("confirmed_at"),
            "actor": rec.get("actor") or "human",
            "stale": stale,
            "latest_run": cover,
        }
    return out


def record_confirmation(
    edge_id: str,
    run_id: str,
    edges: list[dict] | None = None,
) -> dict:
    if edges is None:
        _nodes, edges = load_map()
    ids = {e["id"] for e in (edges or [])}
    if edge_id not in ids:
        raise ValueError(f"unknown edge_id: {edge_id}")
    if not isinstance(run_id, str) or not run_id.strip():
        raise ValueError("run_id is required")
    run_id = run_id.strip()
    rec = load_run(run_id)
    if not rec:
        raise ValueError(f"unknown run_id: {run_id}")
    run_state = str(rec.get("state") or "pending")
    if run_state in _REJECT_RUN_STATES:
        raise ValueError(f"run {run_id} is {run_state}")
    counted = 0
    for task in rec.get("tasks") or []:
        if not isinstance(task, dict):
            continue
        eid = str(task.get("edge") or task_edge_id(str(task.get("id") or "")) or "")
        if eid != edge_id:
            continue
        state = str(task.get("state") or "")
        if not state:
            continue
        if state == "stopped" and task.get("duration_s") is None:
            continue
        counted += 1
        if state not in _CONFIRMABLE_RESULTS:
            raise ValueError(f"run {run_id} result for {edge_id} is {state}")
    if not counted:
        raise ValueError(f"run {run_id} result for {edge_id} is missing")
    row = {
        "edge_id": edge_id,
        "run_id": run_id,
        "confirmed_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "actor": "human",
    }
    data = load_confirmations()
    data[edge_id] = row
    save_confirmations(data)
    return row


def revoke_confirmation(edge_id: str) -> bool:
    data = load_confirmations()
    if edge_id not in data:
        return False
    del data[edge_id]
    save_confirmations(data)
    return True


def log_tail(path: Path | None, n: int = 40) -> list[str]:
    if not path or not path.is_file():
        return []
    try:
        data = path.read_bytes()
    except OSError:
        return []
    if len(data) > 65536:
        data = data[-65536:]
    text = data.decode("utf-8", errors="replace")
    return text.splitlines()[-n:]


def terminate_process(
    proc: subprocess.Popen | None,
    *,
    term_s: float = RUNNER_TERM_S,
    kill_s: float = RUNNER_KILL_S,
) -> None:
    terminate_process_group(proc, term_s=term_s, kill_s=kill_s)


class _DupWrite:
    def __init__(self, primary, secondary) -> None:
        self.primary = primary
        self.secondary = secondary

    def write(self, data):
        self.primary.write(data)
        if self.secondary is not None:
            self.secondary.write(data)

    def flush(self) -> None:
        self.primary.flush()
        if self.secondary is not None:
            self.secondary.flush()


def _tee_child(
    proc: subprocess.Popen,
    logf,
    tee_stdout: bool,
    deadline_s: float | None = None,
) -> int:
    assert proc.stdout is not None
    timer = None
    expired = threading.Event()
    finished = threading.Event()
    if deadline_s:
        def _expire() -> None:
            if not finished.is_set():
                expired.set()
                terminate_process_group(proc)

        timer = threading.Timer(deadline_s, _expire)
        timer.daemon = True
        timer.start()
    try:
        decoder = codecs.getincrementaldecoder(proc.stdout.encoding or "utf-8")(errors="replace")
        while True:
            ready, _, _ = select.select([proc.stdout], [], [], 0.2)
            if ready:
                raw = os.read(proc.stdout.fileno(), 4096)
                chunk = decoder.decode(raw, final=not raw)
                if chunk:
                    logf.write(chunk)
                    logf.flush()
                    if tee_stdout:
                        sys.stdout.write(chunk)
                        sys.stdout.flush()
                if not raw:
                    break
            if expired.is_set() and timer is not None and not timer.is_alive():
                # Even a detached descendant retaining stdout cannot hold this run open.
                break
        code = proc.wait(timeout=RUNNER_KILL_S) if expired.is_set() else proc.wait()
        finished.set()
        if expired.is_set():
            logf.write(f"\nHARNESS_TIMEOUT after {deadline_s}s (process group {proc.pid})\n")
            logf.flush()
            return 124
        return code
    except BaseException:
        terminate_process_group(proc)
        raise
    finally:
        finished.set()
        if timer is not None:
            timer.cancel()
            if expired.is_set():
                timer.join()
        proc.stdout.close()


def ingest_artemis_result(spec: dict, exit_code: int | None) -> dict:
    edge_id = spec.get("edge_id") or ""
    output = Path(spec.get("artemis_output") or "")
    result = load_result_json(output / edge_id / "result.json")
    if result is None and output.is_dir():
        # suite writes <output>/<id>/result.json; dry-run uses the same layout.
        nested = list(output.glob("*/result.json"))
        if nested:
            result = load_result_json(nested[0])
    review_file = (output / edge_id / "review.json") if edge_id else output / "review.json"
    independent = None
    if review_file.is_file():
        loaded = load_result_json(review_file) or {}
        independent = loaded.get("status")
    layers = verdict_layers(
        edge_id,
        exit_code=exit_code,
        result=result,
        independent_review=independent,
        human_confirmed=False,
    )
    state = task_state_from_layers(layers)
    recs = recording_index(output / edge_id) if edge_id else recording_index(output)
    case = {
        "ref": f"Artemis.{edge_id}",
        "name": edge_id,
        "status": layers["business"],
        "raw_status": (result or {}).get("status"),
        "message": (result or {}).get("error") or "",
        "layers": layers,
    }
    return {
        "cases": [case],
        "layers": layers,
        "artemis_state": state,
        "artemis_result": result,
        "recordings": recs,
        "evidence_dir": str(output / edge_id) if edge_id else str(output),
        "human_confirmation": False,
        "independent_review": independent,
    }


def _publish_artemis_live(spec: dict) -> None:
    output = Path(spec.get("artemis_output") or "")
    edge_id = spec.get("edge_id") or ""
    folder = output / edge_id if edge_id else output
    png = newest_png(folder)
    if png is None:
        return
    dest_dir = ARTIFACTS_DIR / "live"
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "preview.png"
    try:
        shutil.copyfile(png, dest)
        (dest_dir / "preview.json").write_text(
            json.dumps({"tag": "artemis", "test": spec.get("edge_id") or "", "source": str(png)})
            + "\n",
            encoding="utf-8",
        )
    except OSError:
        return


def _apply_agent_setup(spec: dict, logf, should_stop=None) -> dict | None:
    """Harness preconditions for Agent Device. Skip dry-run and unknown setup keys."""
    if os.environ.get("TESTMAP_AGENT_DEVICE_DRY") == "1":
        return None
    if os.environ.get("TESTMAP_AGENT_DEVICE_SKIP_SETUP") == "1":
        return None
    setup = spec.get("setup")
    if not setup:
        return None
    platform = spec.get("agent_platform") or spec.get("needs_device")
    if platform == "ios" and spec.get("edge_id") == "export-failure-recovery" and setup == "failure":
        setup = "editor"
    if platform == "android" and setup not in ANDROID_SETUPS:
        return None
    if platform == "ios" and setup not in IOS_SETUPS:
        return None
    if platform not in {"android", "ios"}:
        return None
    device = spec.get("device") if isinstance(spec.get("device"), dict) else {}
    serial = None
    udid = None
    if platform == "android":
        serial = str(device.get("id") or spec.get("serial") or "")
        if not serial or serial == "<device>":
            return None
    else:
        udid = str(device.get("id") or spec.get("udid") or "")
        if not udid or udid == "<device>":
            return None
    output = Path(spec.get("agent_device_output") or "")
    folder = output if str(output) else REPO_ROOT / "build" / "agent-device" / "setup"
    edge_id = str(spec.get("edge_id") or "edge")
    marker = re.sub(r"[^A-Za-z0-9]", "", edge_id)[:16] or "edge"
    if logf is not None:
        logf.write(f"\n## setup {setup} {platform}\n")
        logf.flush()
    cancel_options = {}
    if edge_id == "export-save-success" and platform == "android":
        cancel_options["export_success_run_id"] = Path(str(spec.get("step_evidence_root") or "")).name
    if edge_id == "export-cancel" and platform == "android":
        run_id = Path(str(spec.get("step_evidence_root") or "")).name
        if not run_id:
            raise ValueError("Android export cancel requires a run identity")
        cancel_options["export_cancel_run_id"] = run_id
    if edge_id == "export-failure-recovery" and platform == "android":
        cancel_options["failure_run_id"] = Path(str(spec.get("step_evidence_root") or "")).name
    if platform == "ios" and edge_id in {"export-cancel", "export-failure-recovery"}:
        cancel_options.update(ios_export_run_id=Path(str(spec.get("step_evidence_root") or "")).name,
                              ios_export_mode="hold-next" if edge_id == "export-cancel" else "fail-next")
    state = apply_setup(
        str(setup),
        platform=str(platform),
        folder=folder,
        marker=marker,
        serial=serial,
        udid=udid,
        should_stop=should_stop,
        **cancel_options,
    )
    if logf is not None:
        logf.write(f"setup recovery backup: {state['journal']}\n")
        logf.flush()
    return state


def _restore_agent_setup(state: dict | None, logf) -> None:
    if not state:
        return
    try:
        restore_setup(state)
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
        if logf is not None:
            logf.write(f"setup restore failed: {exc}\n")
            logf.flush()
        raise SetupRestoreError(f"setup restore failed: {exc}") from exc


def _publish_agent_device_live(spec: dict) -> None:
    folder = Path(spec.get("agent_device_output") or "")
    png = newest_png(folder)
    if png is None:
        return
    dest_dir = ARTIFACTS_DIR / "live"
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / "preview.png"
    try:
        shutil.copyfile(png, dest)
        (dest_dir / "preview.json").write_text(
            json.dumps(
                {
                    "tag": "agent-device",
                    "test": spec.get("edge_id") or "",
                    "source": str(png),
                }
            )
            + "\n",
            encoding="utf-8",
        )
    except OSError:
        return


# Completion-page markers. Matched only against cancel-surface.txt, not logs.
_CANCEL_COMPLETION = (
    'text="Share"',
    'content-desc="Share"',
    '"label": "Share"',
    '"label":"Share"',
    "View in gallery",
    "sharedComposeExportCounts",
    'text="分享"',
    '"label": "分享"',
    '"label":"分享"',
)


def _cancel_surface_text(task: dict) -> str:
    """The snapshot taken when the export-cancel script finished or failed."""
    evidence = Path(str(task.get("evidence_dir") or ""))
    if not str(evidence):
        return ""
    roots = [evidence, evidence.parent]
    seen: set[Path] = set()
    parts: list[str] = []
    for root in roots:
        if not root.is_dir():
            continue
        direct = root / "cancel-surface.txt"
        candidates = [direct] if direct.is_file() else []
        candidates.extend(root.rglob("cancel-surface.txt"))
        for path in candidates:
            if path in seen or not path.is_file():
                continue
            seen.add(path)
            try:
                if path.stat().st_size > 1_500_000:
                    continue
                parts.append(path.read_text(encoding="utf-8", errors="replace"))
            except OSError:
                continue
    return "\n".join(parts)


def _capture_cancel_surface(cmd: list[str], spec: dict, logf, should_stop=None) -> None:
    """Read the screen after a missed Cancel export, before the session closes."""
    output = Path(str(spec.get("agent_device_output") or ""))
    if not str(output):
        return
    try:
        output.mkdir(parents=True, exist_ok=True)
    except OSError:
        return
    chunks: list[str] = []
    session = _cmd_flag(cmd, "--session")
    serial = _cmd_flag(cmd, "--serial")
    udid = _cmd_flag(cmd, "--udid")
    if session and not (should_stop and should_stop()):
        snap = [agent_device_bin()]
        if serial:
            snap.extend(["--serial", serial])
        if udid:
            snap.extend(["--udid", udid])
        snap.extend(["--session", session, "snapshot", "--json"])
        try:
            proc = run_captured(snap, cwd=REPO_ROOT, text=True, timeout=40, should_stop=should_stop, stop_grace_s=.2)
            chunks.append(proc.stdout or "")
            chunks.append(proc.stderr or "")
        except (OSError, subprocess.TimeoutExpired) as exc:
            chunks.append(str(exc))
    if serial and not (should_stop and should_stop()):
        try:
            from testmap_setup import adb_bin

            base = [adb_bin(), "-s", serial]
            dump = run_captured(base + ["shell", "uiautomator", "dump", "/sdcard/ewm-cancel.xml"],
                                timeout=45, should_stop=should_stop, stop_grace_s=.2)
            if dump.returncode == 0 and not (should_stop and should_stop()):
                xml = run_captured(base + ["exec-out", "cat", "/sdcard/ewm-cancel.xml"],
                                   timeout=25, should_stop=should_stop, stop_grace_s=.2)
                if xml.returncode == 0:
                    chunks.append(xml.stdout or "")
                else:
                    chunks.append(xml.stderr or f"cancel surface read exit {xml.returncode}")
            else:
                chunks.append(dump.stderr or f"cancel surface dump exit {dump.returncode}")
        except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
            chunks.append(str(exc))
    text = "\n".join(chunks)
    try:
        (output / "cancel-surface.txt").write_text(text, encoding="utf-8")
    except OSError as exc:
        if logf is not None:
            logf.write(f"cancel surface write failed: {exc}\n")
            logf.flush()
        return
    if logf is not None:
        logf.write(f"cancel surface bytes {len(text)}\n")
        logf.flush()


def _mark_cancel_uncovered(task: dict) -> None:
    """A completion page is not a passed cancel. Judge only the captured snapshot."""
    if str(task.get("edge_id") or "") != "export-cancel":
        return
    if task.get("state") not in {"failed", "review_required", "passed"}:
        return
    control = task.get("export_control") or {}
    layers = task.get("layers") or {}
    if (task.get("platform") in {"android", "ios"} and task["state"] in {"review_required", "passed"}
            and task.get("exit_code") == 0 and layers.get("execution") == "ok"
            and layers.get("script_checks") == "executed_review_required"
            and layers.get("agent_observation") == "completed"
            and control.get("status") == control.get("retry_status") == "evidence_complete"
            and (task.get("platform") != "ios" or (control.get("platform") == "ios" and control.get("mode") == "hold-next"))):
        return  # This completion surface follows an evidenced cancellation and Retry.
    surface = _cancel_surface_text(task)
    if surface and any(token in surface for token in _CANCEL_COMPLETION):
        task["state"] = "uncovered"
        task["note"] = "未覆盖取消"


_IOS_EXPORT_UNCOVERED = {
    "export-failure-recovery": "iOS 受控失败及真实 Retry 证据未完整",
    "export-cancel": "iOS 受控取消及真实 Retry 证据未完整",
}


def _mark_ios_export_uncovered(task: dict) -> None:
    """Only complete scoped control and real Retry evidence close these iOS gaps."""
    if task.get("platform") != "ios":
        return
    reason = _IOS_EXPORT_UNCOVERED.get(str(task.get("edge_id") or ""))
    if not reason:
        return
    if task.get("state") not in {"failed", "review_required", "passed", "uncovered"}:
        return
    control, layers = task.get("export_control") or {}, task.get("layers") or {}
    if (task["state"] in {"review_required", "passed"} and task.get("exit_code") == 0
            and layers.get("execution") == "ok" and layers.get("script_checks") == "executed_review_required"
            and layers.get("agent_observation") == "completed"
            and control.get("status") == control.get("retry_status") == "evidence_complete"
            and control.get("platform") == "ios"
            and control.get("mode") == ("hold-next" if task.get("edge_id") == "export-cancel" else "fail-next")):
        return
    task["state"] = "uncovered"
    task["note"] = reason


def _verify_cancel_png(path: Path) -> None:
    """Decode the bounded, non-interlaced 8-bit PNGs emitted by SDK shots."""
    import struct
    import zlib
    if path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("Cancel screenshot exceeds image bound")
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Cancel screenshot is not a PNG")
    position, compressed, header, ended = 8, bytearray(), None, False
    while position + 12 <= len(data):
        size = struct.unpack_from(">I", data, position)[0]
        end = position + 12 + size
        if end > len(data):
            raise ValueError("Truncated cancel PNG chunk")
        kind, payload = data[position + 4:position + 8], data[position + 8:end - 4]
        if zlib.crc32(kind + payload) & 0xffffffff != struct.unpack_from(">I", data, end - 4)[0]:
            raise ValueError("Cancel PNG checksum mismatch")
        if position == 8:
            if kind != b"IHDR" or size != 13:
                raise ValueError("Cancel PNG lacks its image header")
            header = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IHDR":
            raise ValueError("Duplicate PNG image header")
        if kind == b"IDAT":
            compressed.extend(payload)
        if kind == b"IEND":
            ended = size == 0 and end == len(data)
            break
        position = end
    if not ended or header is None:
        raise ValueError("Cancel PNG is incomplete")
    width, height, depth, color, compression, filtering, interlace = header
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color, 0)
    stride = 1 + width * channels
    expected = height * stride
    if not (width > 0 and height > 0 and channels and depth == 8
            and compression == filtering == interlace == 0 and expected <= 64 * 1024 * 1024):
        raise ValueError("Unsupported cancel PNG raster")
    decoder = zlib.decompressobj()
    try:
        pixels = decoder.decompress(compressed, expected + 1)
    except zlib.error as exc:
        raise ValueError("Cancel PNG compressed data is invalid") from exc
    if (len(pixels) != expected or not decoder.eof or decoder.unused_data
            or decoder.unconsumed_tail or any(pixels[y * stride] > 4 for y in range(height))):
        raise ValueError("Cancel PNG raster is corrupt or truncated")


def _cancel_gate_evidence(spec: dict, code: int) -> dict:
    """Validate real SDK actions against the bounded, fixture-scoped gate."""
    platform = spec.get("agent_platform", "android")
    failure = platform == "ios" and spec.get("edge_id") == "export-failure-recovery"
    output = Path(spec["agent_device_output"])
    public_path = output / "export-control-events.json"
    verdict = {"status": "incomplete", "retry_status": "incomplete", "evidence": str(public_path)}
    if platform == "ios":
        # Successful render counts and Share do not establish Photos persistence.
        verdict["photos_status"] = "pending_independent_device_evidence"
    try:
        if code != 0:
            raise ValueError("SDK cancel script did not complete")
        control = json.loads(public_path.read_text())
        run_id = Path(str(spec.get("step_evidence_root") or "")).name
        if (not isinstance(control, dict) or control.get("run_id") != run_id or control.get("mode") != ("fail-next" if failure else "hold-next")
                or control.get("capture_error")):
            raise ValueError("Current-run gate evidence is missing or invalid")
        if platform == "ios" and (control.get("fixture_id") != "testmap-export-fixture"
                or control.get("fixture_matches") is not True
                or not re.fullmatch(r"[a-f0-9]{64}", str(control.get("fixture_sha256", "")))):
            raise ValueError("Current iOS fixture identity is unverified")
        events = control["events"]
        if not isinstance(events, list) or any(not isinstance(row, dict) for row in events):
            raise ValueError("Invalid gate event list")
        if [row.get("event") for row in events] != ["ready", "entered", "failed" if failure else "cancelled", "cleared"]:
            raise ValueError("Gate did not enter, cancel and clear without watchdog")
        if any(set(row) != {"run_id", "event", "timestamp_ms"}
               or row["run_id"] != run_id or type(row["timestamp_ms"]) is not int for row in events):
            raise ValueError("Invalid gate event fields")
        stamps = [row["timestamp_ms"] for row in events]
        if stamps != sorted(stamps) or stamps[-1] > control["expires_at_ms"]:
            raise ValueError("Gate event ordering or expiry is invalid")
        clocks = [control["clock"], control["clock_end"]]
        if platform == "ios":
            for clock in clocks:
                if clock.get("scope") != "simulator-only-shared-posix-monotonic":
                    raise ValueError("iOS evidence requires the paired Simulator clock")
                pairs = [clock[key] for key in ("host_pair_before", "device_pair", "host_pair_after")]
                if any(not isinstance(pair, list) or len(pair) != 3
                       or any(type(value) is not int or value < 0 for value in pair) for pair in pairs):
                    raise ValueError("Invalid paired iOS clock fields")
                (hm0, before, hm1), (dm0, device_ms, dm1), (hm2, after, hm3) = pairs
                host_low = max(before - hm1, after - hm3)
                host_high = min(before - hm0, after - hm2)
                expected = (device_ms - dm1 - host_high - 2, device_ms - dm0 - host_low + 2)
                if (not hm0 <= hm1 <= dm0 <= dm1 <= hm2 <= hm3 or host_low > host_high
                        or not 0 <= after - before <= 2000
                        or abs((after - before) - (hm3 - hm0)) > 50
                        or clock["device_ms"] != device_ms or clock["host_wall_ms"] != before
                        or (clock["offset_min_ms"], clock["offset_max_ms"]) != expected):
                    raise ValueError("iOS clock bounds do not match their raw pairs")
        elapsed = [clocks[1][key] - clocks[0][key] for key in ("host_wall_ms", "host_monotonic_ms")]
        if any(type(value) is not int or value < 0 for value in elapsed) or abs(elapsed[0] - elapsed[1]) > 50:
            raise ValueError("Host wall clock changed during the cancel run")
        bounds = [(clock["offset_min_ms"], clock["offset_max_ms"]) for clock in clocks]
        if any(type(low) is not int or type(high) is not int or not 0 <= high - low <= 2001
               for low, high in bounds):
            raise ValueError("Unbounded device clock offset")
        if max(low for low, _ in bounds) > min(high for _, high in bounds):
            raise ValueError("Device clock offset drifted during the cancel run")
        low, high = min(low for low, _ in bounds), max(high for _, high in bounds)
        if not clocks[0]["device_ms"] <= stamps[0] <= stamps[-1] <= clocks[1]["device_ms"]:
            raise ValueError("Gate events fall outside the sampled device clock window")
        manifest = json.loads(Path(spec["step_evidence_manifest"]).read_text())
        source = Path(manifest["source"])
        derived = Path(manifest["script"])
        import hashlib
        if any(hashlib.sha256(path.read_bytes()).hexdigest() != manifest[key]
               for path, key in ((source, "source_sha256"), (derived, "script_sha256"))):
            raise ValueError("Cancel source/derived digest mismatch")
        rows = parse_script(source, platform)
        required = [
            [r for r in rows if r["command"] == "press" and "Cancel export" in r["args"]],
            [r for r in rows if r["command"] == "wait" and "Export cancelled." in r["args"]],
            [r for r in rows if r["command"] == "wait" and "absent" in r["args"] and "Share" in r["args"]],
            [r for r in rows if r["command"] == "wait" and "absent" in r["args"] and "View in gallery" in r["args"]],
        ]
        retry_required = [
            [r for r in rows if r["command"] == "press" and "Retry failed" in r["args"]],
            [r for r in rows if r["command"] == "wait" and "absent" not in r["args"]
             and "Processed 1 · Succeeded 1 · Failed 0" in r["args"]],
            [r for r in rows if r["command"] == "wait" and "absent" not in r["args"] and "Share" in r["args"]],
            [r for r in rows if r["command"] == "wait" and "absent" not in r["args"] and "View in gallery" in r["args"]],
        ]
        if platform == "ios":
            def matching(command, *tokens, absent=False):
                return [r for r in rows if r["command"] == command and all(t in r["args"] for t in tokens)
                        and ("absent" in r["args"]) == absent]
            if failure:
                required = [matching("press", "sharedComposeExportPrimary"),
                            matching("wait", "sharedComposeExportCounts", "Processed 1 · Succeeded 0 · Failed 1"),
                            matching("wait", "sharedComposeExportRetryFailed")]
            else:
                required[0] = matching("press", "sharedComposeExportCancel")
                required.insert(3, matching("wait", "分享", absent=True))
            retry_required = [matching("press", "sharedComposeExportRetryFailed"),
                              matching("wait", "sharedComposeExportCounts", "Processed 1 · Succeeded 1 · Failed 0"),
                              matching("wait", "sharedComposeExportPrimary", "Share")]
        if any(len(found) != 1 for found in required + retry_required):
            raise ValueError("Cancel and subsequent Retry business actions are required")
        indices = [found[0]["n"] for found in required]
        retry_indices = [found[0]["n"] for found in retry_required]
        all_indices = indices + retry_indices
        if all_indices != sorted(all_indices) or len(set(all_indices)) != len(all_indices):
            raise ValueError("Cancel and Retry business actions are out of order")
        if control.get("marker_absent") is not True:
            raise ValueError("Consumed fixture control marker is still present or unverified")
        timing_files = list(output.rglob("replay-timing.ndjson"))
        if len(timing_files) != 1:
            raise ValueError("Expected exactly one SDK attempt timeline")
        timeline = [json.loads(line) for line in timing_files[0].read_text().splitlines() if line.strip()]
        if any(not isinstance(row, dict) for row in timeline):
            raise ValueError("Invalid SDK timeline event")
        actions = {m["step"]: m for m in manifest["mapping"] if m["kind"] == "action"}
        shots = {m["step"]: m for m in manifest["mapping"] if m["kind"] == "screenshot"}
        def sdk_event(n, kind, screenshot=False):
            mapping = shots[n] if screenshot else actions[n]
            matches = [e for e in timeline if e.get("type") == kind
                       and e.get("step") == mapping["replay_step"]
                       and isinstance(e, dict) and isinstance(e.get("replayPath"), str)
                       and Path(e["replayPath"]).resolve() == derived.resolve()
                       and e.get("command") == ("screenshot" if screenshot else mapping["command"])]
            if len(matches) != 1 or (kind == "replay_action_stop" and matches[0].get("ok") is not True):
                raise ValueError("Missing successful SDK action or screenshot")
            return datetime.fromisoformat(matches[0]["ts"].replace("Z", "+00:00")).timestamp() * 1000
        for index, row in enumerate(rows):
            n = row["n"]
            if actions[n]["command"] != row["command"]:
                raise ValueError("SDK action mapping does not match the source script")
            start, end = sdk_event(n, "replay_action_start"), sdk_event(n, "replay_action_stop")
            next_start = (sdk_event(rows[index + 1]["n"], "replay_action_start")
                          if index + 1 < len(rows) else float("inf"))
            if not start <= end <= next_start:
                raise ValueError("Invalid SDK source action ordering")
            if row["command"] == "close":
                continue  # A closed session has no attributable screenshot.
            shot_start = sdk_event(n, "replay_action_start", True)
            shot_stop = sdk_event(n, "replay_action_stop", True)
            if not end <= shot_start <= shot_stop <= next_start:
                raise ValueError("SDK screenshot is outside its source action interval")
            _verify_cancel_png(Path(shots[n]["path"]))
        click_start = sdk_event(indices[0], "replay_action_start")
        click_end = sdk_event(indices[0], "replay_action_stop")
        assertion_end = sdk_event(indices[1], "replay_action_stop")
        # Use interval bounds, never pretend host and device clocks are identical.
        if failure:
            if stamps[0] - high < click_start or stamps[3] - low > assertion_end:
                raise ValueError("Injected failure was not bounded by Export press and failure assertion")
        else:
            if stamps[1] - low > click_start or stamps[2] - high < click_start:
                raise ValueError("Cannot establish entered-before-click and cancelled-after-click")
            if stamps[2] - low > click_end:
                raise ValueError("Cancellation was not bounded by the real Cancel press")
        if any(sdk_event(left, "replay_action_stop") > sdk_event(right, "replay_action_start")
               for left, right in zip(all_indices, all_indices[1:])):
            raise ValueError("Cancel and Retry assertions did not follow their real presses")
        if stamps[3] - low > assertion_end:
            raise ValueError("Gate did not clear before the cancellation assertion completed")
        verdict.update(status="evidence_complete", retry_status="evidence_complete", run_id=run_id,
                       platform=platform, mode=control["mode"],
                       clock_offset_interval_ms=[low, high])
    except (ValueError, OSError, KeyError, TypeError, IndexError) as exc:
        verdict["reason"] = str(exc)
    return verdict


def ingest_agent_device_result(spec: dict, code: int) -> dict:
    extra = _ingest_agent_device_result(spec, code)
    if spec.get("edge_id") == "export-save-success" and spec.get("agent_platform") == "android":
        gate = spec.get("export_file") or {"status":"unverified", "reason":"Missing real returned-URI file acceptance"}
        extra["export_file"] = gate
        if (code != 0 or gate.get("status") != "evidence_complete" or gate.get("cleanup") != "verified_deleted"
                or gate.get("baseline_preserved") is not True or gate.get("private_restored") is not True):
            extra["agent_device_state"] = "uncovered" if code == 0 else "failed"
            layers = dict(extra.get("layers") or {})
            layers.update(business="failed",agent_observation="export_file_unverified",green_from_process_zero=False,green_from_sdk_completed=False)
            extra["layers"] = layers
            for case in extra.get("cases") or []: case.update(status="failed",layers=layers)
        return extra
    if spec.get("edge_id") == "editor-filmstrip-switch" and spec.get("agent_platform") == "android":
        gate = spec.get("filmstrip_switch") or {"status":"unverified", "reason":"Missing real same-selection A/B/A preview evidence"}
        extra["filmstrip_switch"] = gate
        if code != 0 or gate.get("status") != "evidence_complete" or gate.get("private_restored") is not True:
            extra["agent_device_state"] = "uncovered" if code == 0 else "failed"
            layers = dict(extra.get("layers") or {})
            layers.update(business="failed", agent_observation="filmstrip_switch_unverified",
                          green_from_process_zero=False, green_from_sdk_completed=False)
            extra["layers"] = layers
            for case in extra.get("cases") or []: case.update(status="failed", layers=layers)
        return extra
    if spec.get("edge_id") == "editor-clamp-drag" and spec.get("agent_platform") == "android":
        gate = spec.get("clamp_drag") or {"status": "unverified", "reason": "Missing real CLAMP pixel/gesture evidence"}
        extra["clamp_drag"] = gate
        if code != 0 or gate.get("status") != "evidence_complete" or gate.get("private_restored") is not True:
            extra["agent_device_state"] = "uncovered" if code == 0 else "failed"
            layers = dict(extra.get("layers") or {})
            layers.update(business="failed", agent_observation="clamp_drag_unverified",
                          green_from_process_zero=False, green_from_sdk_completed=False)
            extra["layers"] = layers
            for case in extra.get("cases") or []:
                case.update(status="failed", layers=layers)
        return extra
    if spec.get("edge_id") == "editor-to-template-sheet" and spec.get("agent_platform") == "android":
        gate = spec.get("template_crud") or {"status": "unverified", "reason": "Missing owned-template CRUD evidence"}
        extra["template_crud"] = gate
        if (code != 0 or gate.get("status") != "evidence_complete"
                or gate.get("cleanup") != "verified_deleted" or gate.get("private_restored") is not True):
            extra["agent_device_state"] = "uncovered" if code == 0 else "failed"
            layers = dict(extra.get("layers") or {})
            layers.update(business="failed", agent_observation="template_crud_unverified",
                          green_from_process_zero=False, green_from_sdk_completed=False)
            extra["layers"] = layers
            for case in extra.get("cases") or []:
                case.update(status="failed", layers=layers)
        return extra
    if spec.get("edge_id") == "export-failure-recovery" and spec.get("agent_platform") == "android":
        gate = spec.get("source_recovery") or {"status": "unverified", "reason": "Missing synchronous source repair evidence"}
        extra["export_control"] = gate
        if code != 0 or gate.get("status") != "evidence_complete" or gate.get("private_restored") is not True:
            extra["agent_device_state"] = "uncovered" if code == 0 else "failed"
            layers = dict(extra.get("layers") or {})
            layers.update(business="failed", agent_observation="source_repair_unverified",
                          green_from_process_zero=False, green_from_sdk_completed=False)
            extra["layers"] = layers
            for case in extra.get("cases") or []:
                case.update(status="failed", layers=layers)
        return extra
    if not ((spec.get("edge_id") == "export-cancel" and spec.get("agent_platform") in {"android", "ios"})
            or (spec.get("edge_id") == "export-failure-recovery" and spec.get("agent_platform") == "ios")):
        return extra
    gate = _cancel_gate_evidence(spec, code)
    extra["export_control"] = gate
    if gate["status"] != "evidence_complete" or gate["retry_status"] != "evidence_complete":
        # A process-zero or external Job cancellation is never enough.
        extra["agent_device_state"] = "uncovered" if code == 0 else "failed"
        layers = dict(extra.get("layers") or {})
        layers.update(business="failed", agent_observation="cancel_gate_unverified",
                      green_from_process_zero=False, green_from_sdk_completed=False)
        extra["layers"] = layers
        for case in extra.get("cases") or []:
            case.update(status="failed", layers=layers)
    return extra


def _agent_task_state(spec: dict, extra: dict) -> str:
    builder = spec.get("builder")
    if builder == "artemis":
        state = extra.get("artemis_state") or "failed"
    elif builder == "agent-device":
        state = extra.get("agent_device_state") or "failed"
    else:
        return extra.get("artemis_state") or "failed"
    if state in {"executed", "passed"}:
        return "review_required"
    return state


def watch_from_task(task: dict | None) -> dict | None:
    """Use this task's resolved device; command flags cover older records."""
    if not isinstance(task, dict):
        return None
    cmd = list(task.get("cmd") or [])
    spec_dev = task.get("device") if isinstance(task.get("device"), dict) else {}
    platform = str(task.get("platform") or spec_dev.get("platform") or _cmd_flag(cmd, "--platform"))
    serial, udid = _cmd_flag(cmd, "--serial"), _cmd_flag(cmd, "--udid")
    if not platform:
        platform = "android" if serial else "ios" if udid else ""
    if platform not in {"android", "ios"}:
        return None
    device = str(spec_dev.get("id") or "")
    if device in {"", "auto", "<device>"}:
        device = serial if platform == "android" else udid
    if not device or device in {"auto", "<device>"}:
        return None
    if platform == "ios" and device.startswith("emulator-"):
        return None
    same_device = device == spec_dev.get("id")
    return {
        "platform": platform,
        "device": device,
        "name": (spec_dev.get("name") if same_device else None) or device,
        "kind": spec_dev.get("kind") if same_device else None,
        "task_id": task.get("id"),
        "builder": task.get("builder") or ("agent-device" if is_agent_device_cmd(cmd) else None),
    }


def _cmd_flag(cmd: list[str], name: str) -> str:
    if name not in cmd:
        return ""
    i = cmd.index(name)
    return cmd[i + 1] if i + 1 < len(cmd) else ""


def _script_from_cmd(cmd: list[str]) -> Path | None:
    if "replay" in cmd:
        i = cmd.index("replay")
        if i + 1 < len(cmd) and not str(cmd[i + 1]).startswith("-"):
            return Path(cmd[i + 1])
    if "--steps-file" in cmd:
        return Path(_cmd_flag(cmd, "--steps-file"))
    return None


def _replay_as_test(cmd: list[str], output: Path) -> list[str]:
    """Same replay, plus agent-device's timing file. Not Maestro."""
    if "replay" not in cmd:
        return list(cmd)
    out = list(cmd)
    out[out.index("replay")] = "test"
    out.extend(["--retries", "0", "--artifacts-dir", str(output)])
    return out


def _batch_one(cmd: list[str], step: dict) -> list[str]:
    flags: list[str] = []
    i = 0
    keep = {"--platform", "--session", "--serial", "--udid", "--on-error"}
    while i < len(cmd):
        tok = cmd[i]
        if tok in keep and i + 1 < len(cmd):
            flags.extend([tok, cmd[i + 1]])
            i += 2
            continue
        i += 1
    payload = json.dumps([step.get("batch_step") or {"command": step["command"], "input": step.get("input") or {}}])
    return [cmd[0], "batch", "--steps", payload, *flags, "--json"]


def _batch_ok(text: str, code: int, command: str) -> bool:
    if code != 0:
        return False
    try:
        _single_batch_response(text, command)
    except ValueError:
        return False
    return True


def _read_timing(path: Path | None, offset: int, pending: str, on_line) -> tuple[int, str]:
    if path is None or not path.is_file():
        return offset, pending
    size = path.stat().st_size
    if size < offset:
        offset = 0
        pending = ""
    if size > offset:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            fh.seek(offset)
            pending += fh.read()
            offset = fh.tell()
        if on_line:
            while "\n" in pending:
                line, pending = pending.split("\n", 1)
                on_line(line)
    return offset, pending


def _timing_file(root: Path, platform: str) -> Path | None:
    found = [p for p in root.glob("**/replay-timing.ndjson") if p.is_file()]
    if platform:
        matched = []
        for path in found:
            try:
                head = path.open(encoding="utf-8", errors="replace").readline()
            except OSError:
                continue
            if f"@{platform}." in head or f"-{platform}." in head:
                matched.append(path)
        if matched:
            return sorted(matched)[-1]
    return sorted(found)[-1] if found else None


def _tail_timing(root: Path, on_line, stop: threading.Event, platform: str = "") -> None:
    offset = 0
    pending = ""
    path: Path | None = None
    while True:
        nxt = _timing_file(root, platform)
        if nxt is not None and nxt != path:
            path = nxt
            offset = 0
            pending = ""
        offset, pending = _read_timing(path, offset, pending, on_line)
        if stop.is_set():
            break
        stop.wait(0.15)
    deadline = time.monotonic() + 2.5
    while time.monotonic() < deadline:
        time.sleep(0.2)
        nxt = _timing_file(root, platform)
        if nxt is not None and nxt != path:
            path = nxt
            offset = 0
            pending = ""
        offset, pending = _read_timing(path, offset, pending, on_line)
    if on_line and pending.strip():
        on_line(pending)


def _run_batched_steps(
    cmd: list[str],
    rows: list[dict],
    logf,
    tee_stdout: bool,
    on_proc,
    on_step_event,
    should_stop,
    platform: str,
    env: dict,
    deadline_s: float = 420,
) -> int:
    """One agent-device batch per step so the runner sees start and stop."""
    for row in rows:
        if should_stop and should_stop():
            return 130
        if on_step_event:
            on_step_event(
                platform,
                {"type": "replay_action_start", "step": row["n"], "command": row["command"]},
            )
        step_cmd = _batch_one(cmd, row)
        logf.write(f"\n## step {row['n']}: {' '.join(step_cmd)}\n")
        logf.flush()
        try:
            proc = subprocess.Popen(
                step_cmd,
                cwd=REPO_ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                start_new_session=True,
                env=env,
            )
        except OSError as exc:
            logf.write(f"spawn failed: {exc}\n")
            logf.flush()
            if on_step_event:
                on_step_event(platform, {"type": "replay_action_stop", "step": row["n"], "ok": False})
            return 127
        if on_proc:
            on_proc(proc)
        buf = io.StringIO()
        try:
            code = _tee_child(proc, _DupWrite(logf, buf), tee_stdout, deadline_s=deadline_s)
        finally:
            if on_proc:
                on_proc(None)
        ok = _batch_ok(buf.getvalue(), code, row["command"])
        if on_step_event:
            event = {"type": "replay_action_stop", "step": row["n"], "ok": ok}
            if row.get("point"):
                event["x"] = row["point"]["x"]
                event["y"] = row["point"]["y"]
            on_step_event(platform, event)
        if not ok:
            return code or 1
    return 0


def _run_android_failure_recovery(spec, setup_state, manifest, evidence, rows, run_batch, should_stop, logf):
    """This one case has a synchronous fixture repair boundary before its real Retry."""
    import hashlib
    gate = {"status": "unverified", "mode": "damaged-source", "platform": "android",
            "method": "serial-sdk-batch", "private_restored": False}
    spec["source_recovery"] = gate
    try:
        source = REPO_ROOT / "docs/testing/agent-device/scripts/export-failure-recovery@android.json"
        if not setup_state or not manifest or evidence is None or Path(manifest["source"]) != source:
            raise ValueError("Android failure recovery requires its setup and inline SDK evidence")
        raw = source.read_bytes()
        actions = json.loads(raw)
        expected = [
            ("open", {"app": "me.rosuh.easywatermark.debug"}),
            ("press", {"target": {"kind": "selector", "selector": 'id="sharedComposeSaveButton"'}}),
            ("wait", {"selector": 'label="Export to the album"', "timeoutMs": 15000}),
            ("press", {"target": {"kind": "selector", "selector": 'role="textview" label="Export to the album"'}}),
            ("wait", {"selector": 'label="Processed 1 · Succeeded 0 · Failed 1"', "timeoutMs": 30000}),
            ("wait", {"absent": 'label="Share"', "timeoutMs": 5000}),
            ("wait", {"selector": 'role="textview" label="Retry failed"', "timeoutMs": 15000}),
            ("press", {"target": {"kind": "selector", "selector": 'role="textview" label="Retry failed"'}}),
            ("wait", {"selector": 'label="Processed 1 · Succeeded 1 · Failed 0"', "timeoutMs": 90000}),
            ("wait", {"selector": 'label="Share"', "timeoutMs": 15000}),
            ("close", {}),
        ]
        if actions != [{"command": command, "input": value} for command, value in expected]:
            raise ValueError("Android failure/Retry script no longer matches its bounded evidence contract")
        derived = Path(manifest["script"])
        if (hashlib.sha256(raw).hexdigest() != manifest["source_sha256"]
                or hashlib.sha256(derived.read_bytes()).hexdigest() != manifest["script_sha256"]
                or len(rows) != 21):
            raise ValueError("Android failure source/derived evidence identity changed")
        # Seven actions and their seven awaited screenshots precede the Retry press.
        split = next(m["replay_step"] - 1 for m in manifest["mapping"] if m["step"] == 8 and m["kind"] == "action")
        def require_shots(last):
            if not set(range(1, last + 1)).issubset(evidence.evidenced):
                raise ValueError("Failure/Retry has missing synchronous step evidence")
            for item in manifest["mapping"]:
                if item["kind"] == "screenshot" and item["step"] <= last:
                    _verify_cancel_png(Path(item["path"]))
        code = run_batch(rows[:split])
        if code:
            gate["reason"] = "Initial failure actions or screenshots failed"
            return code
        require_shots(7)
        gate["failure_observed_monotonic_ns"] = time.monotonic_ns()
        if should_stop and should_stop():
            raise InterruptedError("Stopped before fixture repair")
        run_id = Path(spec["step_evidence_root"]).name
        gate["repair"] = repair_failure_source(setup_state, serial=spec["serial"], run_id=run_id, should_stop=should_stop)
        if should_stop and should_stop():
            raise InterruptedError("Stopped before Retry")
        gate["retry_dispatch_monotonic_ns"] = time.monotonic_ns()
        code = run_batch(rows[split:])
        if code:
            gate["reason"] = "Real Retry actions or screenshots failed"
            return code
        require_shots(10)
        gate.update(status="evidence_complete", retry_status="evidence_complete", run_id=run_id,
                    source_sha256=manifest["source_sha256"], script_sha256=manifest["script_sha256"],
                    success_observed_monotonic_ns=time.monotonic_ns())
        return 0
    except (ValueError, OSError, KeyError, TypeError, StopIteration, subprocess.TimeoutExpired) as exc:
        gate["reason"] = str(exc)
        logf.write(f"Android failure recovery refused: {exc}\n")
        return 130 if isinstance(exc, InterruptedError) else 2



_TEMPLATE_SOURCE_SHA256 = "72c3b728aaee98eb8263df4c3362473fb5191343194e633f2144958b8f2c73d4"


def _single_batch_response(output: str, command: str) -> dict:
    # One known batch, including the runner's step header; never parse a log tail.
    candidates = []
    for match in re.finditer(r"(?m)^\{", output):
        try:
            value, _ = json.JSONDecoder().raw_decode(output[match.start():])
            if isinstance(value, dict) and "success" in value:
                candidates.append(value)
        except ValueError:
            continue
    if len(candidates) != 1 or candidates[0].get("success") is not True:
        raise ValueError("Batch action lacks one successful SDK response")
    batch = candidates[0].get("data", {})
    if not isinstance(batch, dict) or any(type(batch.get(key)) is not int or batch[key] != 1 for key in ("total", "executed")):
        raise ValueError("Batch did not execute exactly one action")
    results = batch.get("results", [])
    if (not isinstance(results, list) or len(results) != 1 or not isinstance(results[0], dict)
            or results[0].get("command") != command.strip().lower() or results[0].get("ok") is not True
            or type(results[0].get("step")) is not int or results[0]["step"] != 1
            or not isinstance(results[0].get("data"), dict)):
        raise ValueError("Batch SDK result does not match its action")
    return results[0]["data"]


def _template_snapshot(data: dict) -> dict:
    if (data.get("appBundleId") != "me.rosuh.easywatermark.debug"
            or data.get("truncated") is not False or data.get("visibility", {}).get("partial") is not False
            or data.get("snapshotQuality", {}).get("state") != "healthy"
            or not isinstance(data.get("nodes"), list)):
        raise ValueError("Template snapshot is incomplete or belongs to another app")
    return data


def _template_tag(node: dict) -> str:
    return str(node.get("identifier") or "").removeprefix("me.rosuh.easywatermark.debug:id/")


def _template_node(data: dict, tag: str) -> dict:
    nodes = [node for node in data["nodes"] if _template_tag(node) == tag]
    if len(nodes) != 1:
        raise ValueError("Template identifier is absent or ambiguous: " + tag)
    return nodes[0]


def _template_descendants(data: dict, root: dict) -> list[dict]:
    parents = {node["index"]: node.get("parentIndex") for node in data["nodes"]}
    if len(parents) != len(data["nodes"]):
        raise ValueError("Ambiguous template tree indices")
    def inside(node):
        index, seen = node["index"], set()
        while index in parents and index not in seen:
            if index == root["index"]:
                return True
            seen.add(index)
            index = parents[index]
        return False
    return [node for node in data["nodes"] if inside(node)]


def _template_rows(data: dict) -> list[dict]:
    body = _template_node(data, "templateListSheet")
    _template_node(data, "templateAddButton")
    nodes = _template_descendants(data, body)
    rows = sorted((node for node in nodes if re.fullmatch(r"templateRow-[1-9][0-9]*", _template_tag(node))),
                  key=lambda node: node["rect"]["y"])
    if len({_template_tag(node) for node in rows}) != len(rows):
        raise ValueError("Duplicate template row IDs")
    if rows:
        viewport = next((node for node in nodes if node["index"] == rows[0].get("parentIndex")), {})
        top = viewport.get("rect", {}).get("y")
        if top is None or abs(rows[0]["rect"]["y"] - top) > 2 or rows[0].get("hittable") is not True:
            raise ValueError("Template list is not observably at its first row")
    elif not any(node.get("label") == "Look pretty empty" for node in nodes):
        raise ValueError("No rows without the actual empty-list state")
    return rows


def _template_bind(data: dict, nonce: str, row_id: str | None = None) -> str:
    rows = _template_rows(data)
    matches = [node for node in rows if node.get("label") == nonce]
    if len(matches) != 1 or matches[0] is not rows[0]:
        raise ValueError("Owned nonce must bind uniquely to the first template row")
    found = _template_tag(matches[0]).removeprefix("templateRow-")
    if row_id is not None and found != row_id:
        raise ValueError("Owned template row ID changed")
    if _template_node(data, "templateDeleteButton-" + found).get("hittable") is not True:
        raise ValueError("Owned template Delete is not actionable")
    return found


def _template_prefix(rows: list[dict]) -> list[tuple[str, str]]:
    return [(_template_tag(node), hashlib.sha256(str(node.get("label", "")).encode()).hexdigest())
            for node in rows]


def _template_absent(data: dict, nonce: str, row_id: str, baseline: list) -> None:
    rows = _template_rows(data)
    if any(node.get("label") == nonce or _template_tag(node) == "templateRow-" + row_id for node in rows):
        raise ValueError("Owned template remains after Delete")
    current = _template_prefix(rows)
    # Fresh process/list, creation_date DESC, and the owned row was first. This is
    # a checked visible-prefix invariant, NOT a claim to have scanned the database.
    if bool(current) != bool(baseline) or current != baseline[:len(current)]:
        raise ValueError("Template prefix changed or does not prove owned-row absence")


def _run_android_template_crud(spec, state, source, logf, *, tee_stdout=False,
                               on_proc=None, on_spec=None, on_steps=None, on_step_event=None, should_stop=None) -> int:
    from testmap_setup import load_setup_backup
    proof = {"status": "unverified", "private_restored": False, "cleanup": "not_needed",
             "phases": [], "method": "serial-sdk-batch", "absence_basis": "fresh-process sorted visible prefix; not a database scan"}
    spec["template_crud"] = proof
    row_id, baseline, may_exist, removed = None, None, False, False
    root = Path(spec["step_evidence_root"])
    identity = spec.get("step_evidence_task") or {}
    folder = root / "scripts" / (_shot_name(identity, "android", 0).removesuffix(".png") + "-crud")
    folder.mkdir(parents=True, exist_ok=False)
    record = folder / "template-crud.json"
    spec["template_crud_manifest"] = str(record)
    proof["manifest"] = str(record)
    deadline = time.monotonic() + 240
    source_hash = ""
    cmd = list(spec["cmd"])
    env = {**os.environ, **(spec.get("env") or {})}
    code = 2

    def save():
        record.write_text(json.dumps(proof, indent=2) + "\n")

    def phase(name, actions, offset, *, cleanup=False, precondition=False, inspect=None, source_steps=None):
        if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
            raise ValueError("Template canonical source changed during execution")
        inp, derived = folder / (name + ".input.json"), folder / (name + ".json")
        with inp.open("x") as stream:
            json.dump(actions, stream, indent=2)
            stream.write("\n")
        names = {i: _shot_name(identity, "android", offset + i) if not (cleanup or precondition)
                 else folder.name + "-" + name + "-" + str(i) + ".png" for i in range(1, len(actions) + 1)}
        manifest = materialize_evidence_script(inp, root, derived, names)
        manifest.update(canonical_source=str(source), canonical_source_sha256=source_hash, phase=name,
                        purpose="owned-cleanup" if cleanup else "entry-observation" if precondition else "business")
        for item in manifest["mapping"]:
            item["phase_step"] = item["step"]
            item["source_step"] = (source_steps or list(range(offset + 1, offset + len(actions) + 1)))[item["step"] - 1]
            if not (cleanup or precondition):
                item["step"] += offset
        derived.with_suffix(".mapping.json").write_text(json.dumps(manifest, indent=2) + "\n")
        proof.setdefault("cleanup_phases" if cleanup else "preconditions" if precondition else "phases", []).append(manifest)
        save()
        events = []
        def emit(event):
            events.append(event)
            if not (cleanup or precondition) and on_step_event:
                on_step_event("android", event)
        evidence = EvidenceEvents(manifest, root, emit)
        captured = {}
        try:
            for row in parse_script(derived, "android"):
                if time.monotonic() >= deadline:
                    raise TimeoutError("Template transaction deadline reached")
                if not cleanup and should_stop and should_stop():
                    raise InterruptedError("Stopped during template CRUD")
                if any(hashlib.sha256(path.read_bytes()).hexdigest() != digest for path, digest in (
                        (source, source_hash), (inp, manifest["source_sha256"]), (derived, manifest["script_sha256"]))):
                    raise ValueError("Template source/derived input changed")
                item = manifest["mapping"][row["n"] - 1]
                if not (cleanup or precondition) and item["kind"] == "action" and item["source_step"] == 14:
                    nonlocal may_exist
                    may_exist = True
                    proof["cleanup"] = "pending"
                    save()  # Before the real Add confirmation, including lost responses.
                out = folder / (name + "-sdk-" + str(row["n"]) + ".log")
                pending_stops = []
                def relay(_platform, event):
                    if event.get("type") == "replay_action_stop":
                        pending_stops.append(event)
                    else:
                        evidence(event)
                with out.open("x") as stream:
                    result = _run_batched_steps(cmd, [row], _DupWrite(logf, stream), tee_stdout, on_proc,
                        relay,
                        None if cleanup else should_stop, "android", env,
                        deadline_s=min(20, max(.1, deadline - time.monotonic())))
                if result:
                    evidence({"type": "replay_action_stop", "step": row["n"], "command": row["command"], "ok": False})
                    raise InterruptedError("Template action stopped") if result == 130 else ValueError("Template SDK action failed: " + str(result))
                try:
                    if out.stat().st_size > 8 * 1024 * 1024:
                        raise ValueError("Template SDK response exceeds its bound")
                    data = _single_batch_response(out.read_text(), row["command"])
                except (ValueError, OSError):
                    evidence({"type": "replay_action_stop", "step": row["n"], "command": row["command"], "ok": False})
                    raise
                for event in pending_stops:
                    evidence(event)
                if row["command"] == "snapshot":
                    captured[item["source_step"]] = _template_snapshot(data)
                    proof.setdefault("bindings", []).append({"step": item["step"], "purpose": manifest["purpose"],
                        "source_step": item["source_step"],
                        "snapshot": str(out), "sha256": hashlib.sha256(out.read_bytes()).hexdigest()})
                if item["kind"] == "screenshot":
                    _verify_cancel_png(Path(item["path"]))
                    if inspect and item["source_step"] in captured:
                        inspect(item["source_step"], captured[item["source_step"]])
            return captured
        finally:
            evidence.finish()
            (folder / (name + ".events.json")).write_text(json.dumps(events, indent=2) + "\n")
            save()

    try:
        expected = REPO_ROOT / "docs/testing/agent-device/scripts/editor-to-template-sheet@android.json"
        if source.resolve() != expected.resolve() or source.is_symlink():
            raise ValueError("Template case source is not its canonical JSON")
        source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
        if source_hash != _TEMPLATE_SOURCE_SHA256:
            raise ValueError("Template source no longer matches the bounded CRUD contract")
        if not state or state.get("setup") != "editor" or state.get("platform") != "android":
            raise ValueError("Template CRUD needs its real editor setup")
        backup = load_setup_backup(Path(state["journal"]))
        if (backup.get("phase") != "active" or backup.get("marker") != state.get("marker")
                or backup.get("serial") != spec.get("serial")
                or _cmd_flag(cmd, "--serial") != spec.get("serial")
                or backup.get("source_id") != state.get("source_id")
                or json.loads(Path(state["lock"]).read_text()).get("journal") != state["journal"]
                or not re.fullmatch(r"[A-Za-z0-9]+-[0-9a-f]{32}", str(state.get("marker", "")))
                or not re.fullmatch(r"[1-9][0-9]*", str(state.get("source_id", "")))):
            raise ValueError("Template setup identity/lease is not current")
        nonce = "EWM " + state["marker"]
        uri = "content://media/external_primary/images/media/" + str(state["source_id"])
        raw = source.read_text().replace("__TESTMAP_NONCE__", nonce).replace("__TESTMAP_FIXTURE_URI__", uri)
        actions = json.loads(raw)
        proof.update(source=str(source), source_sha256=source_hash, nonce=nonce, fixture_uri=uri)
        save()
        if on_spec:
            on_spec(spec)

        # Observe the real Content layout before freezing either business phase.
        # This is a finite entry choice, not a replay DSL or a display override.
        observed = phase("entry-observation", actions[:3] + [actions[9]], 0,
                         precondition=True, source_steps=[1, 2, 3, 0])[0]
        icons = [n for n in observed["nodes"] if _template_tag(n) == "watermarkTextTemplateIcon" and n.get("hittable") is True]
        compact = [n for n in observed["nodes"] if _template_tag(n) == "watermarkTextContent" and n.get("hittable") is True]
        if len(icons) == 1 and not compact:
            _template_node(observed, "watermarkTextTemplateIcon")
            field = _template_node(observed, "watermarkTextEditField")
            if field.get("type") != "android.widget.EditText" or field.get("editable") is not True or field.get("hittable") is not True:
                raise ValueError("Template inline field is not editable and hittable")
            entry, omitted = "inline", {4, 5, 20, 21, 32, 45, 46}
            actions[29] = {"command": "wait", "input": {"selector": 'id="watermarkTextEditField" visible', "timeoutMs": 10000}}
        elif len(compact) == 1 and not icons:
            entry, omitted = "compact", set()
        else:
            raise ValueError("Template editor entry is absent or ambiguous")
        proof.update(entry=entry, omitted_source_steps=sorted(omitted))
        def selected(start, end):
            indices = [n for n in range(start, end + 1) if n not in omitted]
            return [actions[n - 1] for n in indices], indices
        if on_steps:
            on_steps("android", parse_steps_json(json.dumps(selected(1, 52)[0]), "android"))

        def inspect_first(n, data):
            nonlocal baseline, row_id
            if n == 10:
                rows = _template_rows(data)
                if any(node.get("label") == nonce for node in rows):
                    raise ValueError("Template nonce already exists")
                baseline = _template_prefix(rows)
            if n == 26:
                row_id = _template_bind(data, nonce)
                # Newest-first must preserve the visible original prefix.
                tail = _template_prefix(_template_rows(data)[1:])
                if tail != baseline[:len(tail)]:
                    raise ValueError("Template prefix changed concurrently")
                proof["row_id"] = row_id
                proof["persisted_after_relaunch"] = True
        first, first_indices = selected(1, 26)
        phase("add-persist", first, 0, inspect=inspect_first, source_steps=first_indices)
        if row_id is None or baseline is None:
            raise ValueError("Template phase one did not establish ownership")
        second, second_indices = selected(27, 52)
        resolved = json.loads(json.dumps(second).replace("__TESTMAP_ROW_ID__", row_id))

        def inspect_second(n, data):
            nonlocal removed
            if n == 31:
                if entry == "inline":
                    field = _template_node(data, "watermarkTextEditField")
                    values = {field.get("value") or field.get("text") or field.get("label")}
                else:
                    values = {node.get("label") for node in _template_descendants(data, _template_node(data, "watermarkTextContent"))
                              if node.get("type") == "android.widget.TextView" and node.get("label")}
                if values != {nonce}:
                    raise ValueError("Editor content does not exactly match the applied template")
                proof["applied_exactly"] = True
            elif n == 37:
                _template_bind(data, nonce, row_id)
                tail = _template_prefix(_template_rows(data)[1:])
                if tail != baseline[:len(tail)]:
                    raise ValueError("Template prefix changed before Delete")
            elif n == 51:
                _template_absent(data, nonce, row_id, baseline)
                removed = True
                proof["cleanup"] = "verified_deleted"
        phase("use-delete-persist", resolved, len(first), inspect=inspect_second, source_steps=second_indices)
        proof["status"] = "evidence_complete"
        code = 0
    except (ValueError, OSError, KeyError, TypeError, IndexError, subprocess.TimeoutExpired) as exc:
        proof["reason"] = str(exc)
        code = 130 if isinstance(exc, InterruptedError) else 2
    finally:
        if may_exist and not removed:
            # Stop blocks business actions. Only finite, identity-checked compensation remains.
            deadline = time.monotonic() + 60
            try:
                locate, locate_indices = selected(1, 10)
                found = phase("cleanup-locate", locate, 0, cleanup=True, source_steps=locate_indices)[10]
                matches = [node for node in _template_rows(found) if node.get("label") == nonce]
                if not matches:
                    if row_id is None:
                        raise ValueError("Cannot establish cleanup absence without an owned row binding")
                    _template_absent(found, nonce, row_id, baseline)
                else:
                    row_id = _template_bind(found, nonce, row_id)
                    proof["row_id"] = row_id
                    save()
                    delete, delete_indices = selected(38, 51)
                    cleanup_actions = json.loads(json.dumps(delete).replace("__TESTMAP_ROW_ID__", row_id))
                    final = phase("cleanup-delete", cleanup_actions, 0, cleanup=True, source_steps=delete_indices)[51]
                    _template_absent(final, nonce, row_id, baseline)
                proof["cleanup"] = "verified_deleted"
            except (ValueError, OSError, KeyError, TypeError, IndexError, subprocess.TimeoutExpired) as exc:
                proof.update(cleanup="failed", cleanup_reason=str(exc))
                if code != 130:
                    code = 2
        if should_stop and should_stop():
            code = 130
        save()
    return code

_EXPORT_SOURCE_SHA256 = "1c3c7dfa368a130d1bfe916d279bb01a379567f482fe2775efdf3553047241c4"


def _export_png_structure(source: Path, output: Path, sentinel: str) -> dict:
    """Bounded PNG/metadata and glyph-shape checks. Exact text needs independent pixel QA."""
    import struct
    Image = require_clamp_pixels()
    _verify_cancel_png(source)
    _verify_cancel_png(output)
    data, pos, chunks = output.read_bytes(), 8, []
    while pos < len(data):
        length = struct.unpack_from(">I", data, pos)[0]
        kind = data[pos + 4:pos + 8].decode("ascii")
        chunks.append(kind)
        pos += length + 12
    # Excluding all text/unknown ancillary chunks excludes PNG EXIF, XMP and IPTC carriers.
    if set(chunks) - {"IHDR", "IDAT", "IEND", "sRGB", "gAMA", "cHRM", "pHYs", "sBIT"}:
        raise ValueError("Export PNG retains metadata or an unsupported ancillary chunk")
    with Image.open(source) as src, Image.open(output) as out:
        src.load(); out.load()
        if src.getexif().get(270) != sentinel or out.getexif() or any(key in out.info for key in ("exif", "XML:com.adobe.xmp", "Raw profile type iptc")):
            raise ValueError("Export metadata sentinel/stripping is unverified")
        if out.size != src.size or out.size != (960, 640):
            raise ValueError("Export geometry differs from owned source")
        a, b = src.convert("RGB"), out.convert("RGB")
        glyphs, white, retained = [], 0, [0, 0]
        for y in range(640):
            for x in range(960):
                original, pixel = a.getpixel((x, y)), b.getpixel((x, y))
                if y < 56 or y >= 584:
                    retained[int(y >= 584)] += int(max(abs(u-v) for u,v in zip(original, pixel)) <= 6)
                elif original == (255, 255, 255):
                    white += int(min(pixel) >= 245)
                    r,g,blue = pixel
                    if r > g > blue and r-blue > 35 and g-blue > 20:
                        glyphs.append((x,y))
        if min(retained) < 960 * 56 * .65 or white < 960 * 528 * .20 or len(glyphs) < 100:
            raise ValueError("Export lacks owned source bands, white background or visible glyph structure")
        # Occupied horizontal runs establish separated glyph strokes, not OCR text equality.
        glyph_rows = {}
        for x,y in glyphs: glyph_rows.setdefault(y,[]).append(x)
        runs = max(1 + sum(right-left > 1 for left,right in zip(xs,xs[1:])) for xs in glyph_rows.values())
        if runs < 3 or max(y for x,y in glyphs)-min(y for x,y in glyphs) < 8:
            raise ValueError("Export glyph strokes are absent or structurally ambiguous")
        return {"size": list(out.size), "png_chunks": chunks, "metadata_stripped": True,
                "glyph_pixels": len(glyphs), "glyph_column_runs": runs,
                "source_band_retained": retained, "white_background_pixels": white,
                "exact_nonce_pixel_review": "pending_independent_image_QA"}


def _export_returned_uri(control: dict, run_id: str, source_uri: str) -> str:
    if (control.get("mode") != "observe-next" or control.get("run_id") != run_id
            or control.get("fixture_uri") != source_uri or control.get("capture_error")
            or control.get("marker_absent") is not True):
        raise ValueError("Export observer identity is missing or mismatched")
    events = control.get("events")
    if not isinstance(events, list) or [row.get("event") for row in events] != ["ready", "entered", "outcome_success", "cleared"]:
        raise ValueError("Real returned export Success was not observed exactly once")
    stamps = []
    for row in events:
        keys = {"run_id", "event", "timestamp_ms"} | ({"output_uri"} if row["event"] == "outcome_success" else set())
        if set(row) != keys or row["run_id"] != run_id or type(row["timestamp_ms"]) is not int:
            raise ValueError("Export outcome event fields are invalid")
        stamps.append(row["timestamp_ms"])
    if stamps != sorted(stamps) or not control["clock"]["device_ms"] <= stamps[0] <= stamps[-1] <= min(control["expires_at_ms"], control["clock_end"]["device_ms"]):
        raise ValueError("Export observer event ordering/expiry is invalid")
    uri = events[2]["output_uri"]
    if not isinstance(uri, str) or not re.fullmatch(r"content://media/(external|external_primary)/images/media/[1-9][0-9]*", uri) or uri.rsplit("/",1)[-1] == source_uri.rsplit("/",1)[-1]:
        raise ValueError("Export returned URI is missing, invalid or aliases its source")
    return uri


def _run_android_export_file(spec, state, source, logf, *, tee_stdout=False, on_proc=None,
                             on_spec=None, on_steps=None, on_step_event=None, should_stop=None):
    import testmap_setup as setup
    proof = {"status": "unverified", "cleanup": "unclaimed", "private_restored": False, "phases": [],
             "independent_image_QA": "pending", "human_confirmation": False}
    spec["export_file"] = proof
    root, identity = Path(spec["step_evidence_root"]), spec.get("step_evidence_task") or {}
    folder = root / "scripts" / (_shot_name(identity, "android", 0).removesuffix(".png") + "-export-file")
    folder.mkdir(parents=True, exist_ok=False)
    record = folder / "export-file.json"
    spec["export_file_manifest"] = proof["manifest"] = str(record)
    cmd, env = list(spec["cmd"]), {**os.environ, **(spec.get("env") or {})}
    deadline, code = time.monotonic() + 180, 2
    live_rows, completed, omitted = [], [], set()
    token = setup._SETUP_STOP.set(should_stop)
    def save(): record.write_text(json.dumps(proof, indent=2) + "\n")
    def budget():
        if should_stop and should_stop(): raise InterruptedError("Stopped during export file acceptance")
        if time.monotonic() >= deadline: raise TimeoutError("Export file transaction deadline reached")
        return min(10, max(.1, deadline-time.monotonic()))
    def query(name, uri, projection, where=None):
        args = ["content", "query", "--uri", uri, "--projection", projection]
        if where is not None: args += ["--where", where]
        raw = setup.adb_shell(serial, *args, timeout=budget())
        path = folder / (name + ".txt"); path.write_text(raw + "\n")
        proof.setdefault("queries", []).append({"path":str(path),"sha256":hashlib.sha256(path.read_bytes()).hexdigest()})
        return setup.export_media_rows(raw, set(projection.split(":")))
    def read(name, uri, limit=16*1024*1024):
        data = setup.export_provider_read(serial, uri, limit, timeout=budget())
        path = folder / (name + ".png"); path.write_bytes(data)
        proof.setdefault("provider_readbacks", []).append({"uri":uri,"path":str(path),"sha256":hashlib.sha256(data).hexdigest(),"bytes":len(data)})
        return data
    def execute(name, start, end):
        indices = [n for n in range(start,end+1) if n not in omitted]
        subset = [actions[n-1] for n in indices]
        inp, derived = folder/(name+".input.json"), folder/(name+".json")
        inp.write_text(json.dumps(subset,indent=2)+"\n")
        offset = len(completed)
        names = {i:_shot_name(identity,"android",offset+i) for i in range(1,len(subset)+1)}
        manifest = materialize_evidence_script(inp,root,derived,names)
        manifest.update(canonical_source=str(source),canonical_source_sha256=_EXPORT_SOURCE_SHA256,phase=name)
        for item in manifest["mapping"]:
            item.update(phase_step=item["step"],source_step=indices[item["step"]-1],step=item["step"]+offset)
        derived.with_suffix(".mapping.json").write_text(json.dumps(manifest,indent=2)+"\n")
        proof["phases"].append(manifest); save()
        events, snapshots = [], {}
        def emit(event):
            events.append(event)
            if on_step_event: on_step_event("android",event)
        evidence = EvidenceEvents(manifest,root,emit)
        try:
            for row in parse_script(derived,"android"):
                budget()
                if any(hashlib.sha256(p.read_bytes()).hexdigest()!=digest for p,digest in
                       ((source,_EXPORT_SOURCE_SHA256),(inp,manifest["source_sha256"]),(derived,manifest["script_sha256"]),(fixture,source_hash))):
                    raise ValueError("Export fixture/source/derived identity changed")
                item = manifest["mapping"][row["n"]-1]
                out = folder/(name+"-sdk-"+str(row["n"])+".log")
                with out.open("x") as stream:
                    result = _run_batched_steps(cmd,[row],_DupWrite(logf,stream),tee_stdout,on_proc,
                        lambda _p,event:evidence(event),should_stop,"android",env,deadline_s=min(20,max(.1,deadline-time.monotonic())))
                if result: raise InterruptedError("Export SDK stopped") if result==130 else ValueError("Export SDK action failed")
                if out.stat().st_size>8*1024*1024: raise ValueError("Export SDK response exceeds bound")
                data = _single_batch_response(out.read_text(),row["command"])
                if row["command"]=="snapshot": snapshots[item["source_step"]] = _template_snapshot(data)
                if item["kind"]=="screenshot": _verify_cancel_png(Path(item["path"]))
            completed.extend(indices)
            return snapshots
        finally:
            evidence.finish(); (folder/(name+".events.json")).write_text(json.dumps(events,indent=2)+"\n"); save()
    try:
        expected = REPO_ROOT/"docs/testing/agent-device/scripts/export-save-success@android.json"
        if source.resolve()!=expected.resolve() or source.is_symlink() or hashlib.sha256(source.read_bytes()).hexdigest()!=_EXPORT_SOURCE_SHA256:
            raise ValueError("Export acceptance requires canonical source")
        if not state: raise ValueError("Export acceptance requires real owned setup")
        setup._validate_setup(state)
        backup = setup.load_setup_backup(Path(state["journal"]))
        serial = spec.get("serial")
        if (state.get("phase")!="active" or state.get("serial")!=serial or _cmd_flag(cmd,"--serial")!=serial
                or state.get("export_control",{}).get("mode")!="observe-next"
                or state["export_control"].get("run_id")!=root.name
                or not re.fullmatch(r"exportsavesucces-[a-f0-9]{32}",state.get("marker",""))
                or not re.fullmatch(r"[1-9][0-9]*",str(state.get("source_id","")))
                or any(backup.get(k)!=state.get(k) for k in ("phase","serial","marker","source_id","fixtures","export_source","export_control"))
                or json.loads(Path(state["lock"]).read_text()).get("journal")!=state["journal"]):
            raise ValueError("Export setup identity/lease is not current")
        fixture = Path(state["fixtures"]["A"])
        if fixture.is_symlink() or not fixture.is_file() or fixture.parent!=Path(state["journal"]).parent or not 0<fixture.stat().st_size<=1024*1024:
            raise ValueError("Export source is not a bounded owned fixture")
        source_hash = state["export_source"]["sha256"]
        source_uri = setup.MEDIA+"/"+str(state["source_id"])
        nonce = "E2E-"+state["marker"][-12:]
        actions = json.loads(source.read_text().replace("__EXPORT_NONCE__",nonce))
        if "__EXPORT_" in json.dumps(actions): raise ValueError("Unresolved export input")
        live_rows[:] = parse_steps_json(json.dumps(actions),"android")
        proof.update(nonce=nonce,source_uri=source_uri,source_sha256=source_hash,sentinel=state["export_source"]["sentinel"],
                     observer_evidence=str(Path(state["journal"]).parent/"export-control-events.json"))
        save()
        if on_spec: on_spec(spec)
        if on_steps: on_steps("android",live_rows)
        rows = query("source-row",setup.MEDIA,"_id", "_id="+str(state["source_id"])+" AND _display_name='"+fixture.name+"' AND relative_path='"+setup.FIXTURE_FOLDER+"/'")
        if rows!=[{"_id":str(state["source_id"])}] or read("source-before",source_uri,1024*1024)!=fixture.read_bytes():
            raise ValueError("Owned export source provider identity differs")
        observed = execute("content",1,4)[4]
        fields = [n for n in observed["nodes"] if _template_tag(n)=="watermarkTextEditField"]
        compact = [n for n in observed["nodes"] if _template_tag(n)=="watermarkTextContent"]
        if len(fields)==1 and not compact and fields[0].get("editable") is True and fields[0].get("hittable") is True:
            omitted={5,6,8}
        elif len(compact)==1 and compact[0].get("hittable") is True and not fields:
            omitted=set()
        else: raise ValueError("Export Content entry is absent or ambiguous")
        proof["omitted_source_steps"]=sorted(omitted)
        live_rows[:]=[r for n,r in enumerate(live_rows,1) if n not in omitted]
        for n,row in enumerate(live_rows,1): row["n"]=n
        observed = execute("nonce",5,9)[9]
        values = ({_template_node(observed,"watermarkTextEditField").get("value") or _template_node(observed,"watermarkTextEditField").get("text") or _template_node(observed,"watermarkTextEditField").get("label")} if omitted else
                  {n.get("label") for n in _template_descendants(observed,_template_node(observed,"watermarkTextContent")) if n.get("type")=="android.widget.TextView" and n.get("label")})
        if values!={nonce}: raise ValueError("Export exact visible editor nonce differs")
        proof["nonce_confirmed"]=True
        execute("format",10,13)
        before = {row["_id"] for row in query("output-before",setup.MEDIA,"_id",setup.EXPORT_OUTPUT_FILTER)}
        proof["baseline_ids"]=sorted(before,key=int)
        setup._arm_export_control(state)
        proof["observer_armed_before_source_step"]=14
        execute("export",14,16)
        setup._capture_export_control(state)
        control_path = Path(state["journal"]).parent/"export-control-events.json"
        control = json.loads(control_path.read_text())
        uri = _export_returned_uri(control,Path(spec["step_evidence_root"]).name,source_uri)
        proof.update(observer_evidence=str(control_path),output_uri=uri,cleanup="returned_uri_unclaimed")
        returned_id = uri.rsplit("/",1)[-1]
        after = {row["_id"] for row in query("output-after",setup.MEDIA,"_id",setup.EXPORT_OUTPUT_FILTER)}
        proof["after_ids"]=sorted(after,key=int)
        if returned_id in before or after!=before|{returned_id}: raise ValueError("Returned output is not the unique new owned row; candidates retained")
        projection = setup.EXPORT_OUTPUT_PROJECTION
        detail = query("output-row-first",uri,projection)
        if len(detail)!=1: raise ValueError("Returned output metadata is missing or ambiguous")
        row = detail[0]
        if (row["_id"]!=returned_id or row["owner_package_name"]!=setup.ANDROID_PACKAGE or row["relative_path"]!="Pictures/EasyWaterMark/"
                or row["mime_type"]!="image/png" or row["is_pending"]!="0" or not re.fullmatch(r"ewm_[0-9]+\.png",row["_display_name"])
                or not re.fullmatch(r"[1-9][0-9]*",row["_size"])):
            raise ValueError("Returned output publication/geometry differs")
        data = read("output-first",uri)
        if len(data)!=int(row["_size"]): raise ValueError("Returned output provider byte count differs")
        detail2 = query("output-row-second",uri,projection)
        data2 = read("output-second",uri)
        if detail2!=detail or data2!=data: raise ValueError("Returned output changed between exact readbacks")
        # Exact returned URI, unique union and both stable row/bytes reads prove ownership.
        # Record it before pixel/metadata acceptance, so a bad owned export is still restored.
        state["export_output"]={"uri":uri,"fields":row,"sha256":hashlib.sha256(data).hexdigest(),"cleanup":"claimed"}
        setup._validate_setup(state); setup._save_setup(state)
        proof.update(cleanup="claimed",output_fields=row,output_sha256=state["export_output"]["sha256"]); save()
        budget()
        if row["width"]!="960" or row["height"]!="640": raise ValueError("Returned output geometry differs")
        if read("source-after",source_uri,1024*1024)!=fixture.read_bytes(): raise ValueError("Owned source changed after export")
        proof["structure"]=_export_png_structure(fixture,folder/"output-first.png",state["export_source"]["sentinel"])
        setup._restore_export_output(state)
        proof["cleanup"]=state["export_output"]["cleanup"]
        final = {row["_id"] for row in query("output-after-cleanup",setup.MEDIA,"_id",setup.EXPORT_OUTPUT_FILTER)}
        if final!=before: raise ValueError("Output cleanup did not preserve the full baseline union")
        proof["baseline_preserved"]=True
        execute("close",17,17)
        if completed!=[n for n in range(1,18) if n not in omitted]: raise ValueError("Export canonical action coverage is incomplete")
        proof["status"],code="evidence_complete",0
    except (ValueError,OSError,KeyError,TypeError,IndexError,subprocess.TimeoutExpired) as exc:
        proof["reason"]=str(exc); code=130 if isinstance(exc,InterruptedError) else 2
    finally:
        setup._SETUP_STOP.reset(token)
        if should_stop and should_stop(): code=130
        save()
    return code


_FILMSTRIP_SOURCE_SHA256 = "1d16ce147b380109db7fc15d9be1aef29d9864545932a513f2b82cf6d03a029d"
_CLAMP_SOURCE_SHA256 = "24269b38a2d5eefcf786f6eb245c8215601fa6ba783a9eab1a37cf72b92a1f06"


def _run_android_clamp(spec, state, source, logf, *, tee_stdout=False, on_proc=None,
                       on_spec=None, on_steps=None, on_step_event=None, should_stop=None):
    import testmap_setup as setup
    from zoneinfo import ZoneInfo
    from testmap_steps import clamp_media_rows, clamp_photo_second, clamp_fixture_identity, clamp_editor_binding
    load_setup_backup = setup.load_setup_backup
    filmstrip = spec.get("edge_id") == "editor-filmstrip-switch"
    edge = "editor-filmstrip-switch" if filmstrip else "editor-clamp-drag"
    proof_key = "filmstrip_switch" if filmstrip else "clamp_drag"
    source_hash = _FILMSTRIP_SOURCE_SHA256 if filmstrip else _CLAMP_SOURCE_SHA256
    placeholder = "__FILMSTRIP_" if filmstrip else "__CLAMP_"
    marker_prefix = "editorfilmstrips" if filmstrip else "editorclampdrag"
    proof = {"status": "unverified", "private_restored": False, "phases": [],
             "scope": "One owned A+B selection, real A pan, B/A offset continuity and same-process SDK reopen; no process-death/DataStore claim"}
    if filmstrip:
        proof["scope"] = "One owned A+B selection with real fresh-thumbnail A to B to A preview identity; no export/persistence/motion timing claim"
    spec[proof_key] = proof
    root, identity = Path(spec["step_evidence_root"]), spec.get("step_evidence_task") or {}
    folder = root / "scripts" / (_shot_name(identity, "android", 0).removesuffix(".png") + ("-filmstrip" if filmstrip else "-clamp"))
    folder.mkdir(parents=True, exist_ok=False)
    record = folder / ("filmstrip-switch.json" if filmstrip else "clamp-drag.json")
    spec[proof_key + "_manifest"] = proof["manifest"] = str(record)
    cmd, env = list(spec["cmd"]), {**os.environ, **(spec.get("env") or {})}
    deadline, code = time.monotonic() + (420 if filmstrip else 480), 2  # Business only; finally budgets are unchanged.
    shots, executed, live_rows = {}, [], []

    def save():
        record.write_text(json.dumps(proof, indent=2) + "\n")

    def phase(name, actions, indices, offset=0, observe=False, inspect=None):
        if placeholder in json.dumps(actions):
            raise ValueError("Unresolved CLAMP input must never reach SDK")
        inp, derived = folder / (name + ".input.json"), folder / (name + ".json")
        with inp.open("x") as stream:
            stream.write(json.dumps(actions, indent=2) + "\n")
        names = {i: folder.name + "-entry-" + str(i) + ".png" if observe else _shot_name(identity, "android", offset + i)
                 for i in range(1, len(actions) + 1)}
        manifest = materialize_evidence_script(inp, root, derived, names)
        manifest.update(canonical_source=str(source), canonical_source_sha256=source_hash,
                        phase=name, purpose="entry-observation" if observe else "business")
        for item in manifest["mapping"]:
            item.update(phase_step=item["step"], source_step=indices[item["step"] - 1])
            if not observe:
                item["step"] += offset
        derived.with_suffix(".mapping.json").write_text(json.dumps(manifest, indent=2) + "\n")
        proof.setdefault("preconditions" if observe else "phases", []).append(manifest)
        save()
        events, snapshots = [], {}
        def emit(event):
            events.append(event)
            if not observe and on_step_event:
                on_step_event("android", event)
        evidence = EvidenceEvents(manifest, root, emit)
        try:
            for row in parse_script(derived, "android"):
                if should_stop and should_stop():
                    raise InterruptedError("Stopped during CLAMP")
                if time.monotonic() >= deadline:
                    raise TimeoutError("CLAMP transaction deadline reached")
                for path, digest in [(source, source_hash), (inp, manifest["source_sha256"]),
                                     (derived, manifest["script_sha256"]), *fixture_hashes.items()]:
                    if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                        raise ValueError("CLAMP fixture/source/derived input changed")
                item = manifest["mapping"][row["n"] - 1]
                out = folder / (name + "-sdk-" + str(row["n"]) + ".log")
                with out.open("x") as stream:
                    result = _run_batched_steps(cmd, [row], _DupWrite(logf, stream), tee_stdout, on_proc,
                        lambda _p, event: evidence(event), should_stop, "android", env,
                        deadline_s=min(20, max(.1, deadline - time.monotonic())))
                if result:
                    raise InterruptedError("CLAMP action stopped") if result == 130 else ValueError("CLAMP SDK action failed")
                if out.stat().st_size > 8 * 1024 * 1024:
                    raise ValueError("CLAMP SDK response exceeds its bound")
                data = _single_batch_response(out.read_text(), row["command"])
                if row["command"] == "snapshot":
                    snapshots[item["source_step"]] = _template_snapshot(data)
                    proof.setdefault("snapshots", []).append({"source_step": item["source_step"], "path": str(out),
                        "sha256": hashlib.sha256(out.read_bytes()).hexdigest()})
                if item["kind"] == "screenshot":
                    shot = Path(item["path"])
                    _verify_cancel_png(shot)
                    shots[item["source_step"]] = shot
                    if inspect and item["source_step"] in snapshots:
                        inspect(item["source_step"], snapshots[item["source_step"]], shot)
            return snapshots
        finally:
            evidence.finish()
            (folder / (name + ".events.json")).write_text(json.dumps(events, indent=2) + "\n")
            save()

    stop_token = setup._SETUP_STOP.set(should_stop)
    try:
        expected = REPO_ROOT / ("docs/testing/agent-device/scripts/" + edge + "@android.json")
        if source.resolve() != expected.resolve() or source.is_symlink() or hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
            raise ValueError("CLAMP requires its unchanged canonical source")
        if not state or state.get("setup") != "editor" or state.get("platform") != "android":
            raise ValueError("CLAMP requires real owned editor setup")
        backup = load_setup_backup(Path(state["journal"]))
        if (backup.get("phase") != "active" or any(backup.get(k) != state.get(k) for k in ("serial", "marker", "source_id", "fixtures"))
                or state.get("serial") != spec.get("serial") or _cmd_flag(cmd, "--serial") != spec.get("serial")
                or json.loads(Path(state["lock"]).read_text()).get("journal") != state["journal"]
                or not re.fullmatch(marker_prefix + r"-[a-f0-9]{32}", str(state.get("marker", "")))
                or not re.fullmatch(r"[1-9][0-9]*", str(state.get("source_id", "")))
                or set(state.get("fixtures", {})) != {"A", "B", "C", "icon"}):
            raise ValueError("CLAMP setup identity/lease is not current")
        fixtures, fixture_hashes = {}, {}
        for key, value in state["fixtures"].items():
            path = Path(value)
            if (path.is_symlink() or path.parent != Path(state["journal"]).parent
                    or path.name != "ewm-suite-" + state["marker"] + "-" + key + ".png"
                    or not path.is_file() or not 0 < path.stat().st_size <= 1024 * 1024):
                raise ValueError("CLAMP fixture is not a bounded owned file")
            fixtures[key], fixture_hashes[path] = path, hashlib.sha256(path.read_bytes()).hexdigest()
        fixture, serial = fixtures["A"], spec["serial"]
        nonce = "E2E-" + state["marker"][-12:]
        actions = json.loads(source.read_text().replace("__CLAMP_NONCE__", nonce))
        proof.update(fixture_hashes={k: fixture_hashes[v] for k, v in fixtures.items()},
                     source_id=state["source_id"], source_sha256=source_hash, omitted_source_steps=[])
        if not filmstrip:
            proof["nonce"] = nonce
        indices = list(range(1, len(actions) + 1))
        live_rows[:] = parse_steps_json(json.dumps(actions), "android")
        save()
        if on_spec:
            on_spec(spec)
        if on_steps:
            on_steps("android", live_rows)

        def budget():
            if should_stop and should_stop():
                raise InterruptedError("Stopped during CLAMP evidence read")
            if time.monotonic() >= deadline:
                raise TimeoutError("CLAMP transaction deadline reached")
            return min(10, max(.1, deadline - time.monotonic()))

        def shell(*args):
            return setup.adb_shell(serial, *args, timeout=budget())

        zone = ZoneInfo(shell("getprop", "persist.sys.timezone").strip())
        proof["timezone"] = str(zone)

        def media():
            for key, media_id in ids.items():
                raw = setup.adb(serial, ["exec-out", "content", "read", "--uri", setup.MEDIA + "/" + media_id], binary=True, timeout=budget())
                digest = hashlib.sha256(raw).hexdigest()
                proof.setdefault("provider_readbacks", []).append({"fixture": key, "uri": setup.MEDIA + "/" + media_id,
                    "sha256": digest, "byte_count": len(raw)})
                save()
                if not 0 < len(raw) <= 1048576 or digest != fixture_hashes[fixtures[key]]:
                    raise ValueError("CLAMP provider bytes changed")

        def query(name, where, expected=None):
            raw = shell("content", "query", "--uri", setup.MEDIA, "--projection", "_id:datetaken:date_added:date_modified", "--where", where)
            path = folder / (name + ".txt")
            with path.open("x") as stream:
                stream.write(raw)
            proof.setdefault("date_queries", []).append({"path": str(path), "sha256": hashlib.sha256(raw.encode()).hexdigest(), "where": where})
            save()
            rows = clamp_media_rows(raw)
            if expected is not None and {r["_id"] for r in rows} != expected:
                raise ValueError("CLAMP collection dates/owned rows are not closed")
            return rows

        # Setup has indexed these four owned files. A single bounded read per file
        # rejects missing/ambiguous rows, rather than entering another polling loop.
        ids, owned = {}, []
        for key, path in fixtures.items():
            rows = query("owned-" + key, "_display_name='" + path.name + "' AND relative_path='" + setup.FIXTURE_FOLDER + "/'")
            if len(rows) != 1:
                raise ValueError("CLAMP owned fixture is not uniquely indexed")
            ids[key] = rows[0]["_id"]
            owned.extend(rows)
        if len(set(ids.values())) != 4 or ids["A"] != state["source_id"]:
            raise ValueError("CLAMP owned MediaStore identities changed")
        proof.update(fixture_ids=ids, owned_dates=owned)
        media()

        def execute(name, start, end):
            selected = [n for n in indices if start <= n <= end]
            if set(selected) & set(executed):
                raise ValueError("CLAMP source action already executed")
            result = phase(name, [actions[n - 1] for n in selected], selected, len(executed))
            executed.extend(selected)
            return result

        def bracket(name, start, end):
            observed = execute(name, start, end)
            pair = [n for n in range(start, end + 1) if n in observed]
            if len(pair) != 2:
                raise ValueError("CLAMP bracket must contain two real snapshots")
            before, after = (observed[n] for n in pair)
            def signature(data):
                return [{k: n.get(k) for k in ("index", "parentIndex", "bundleId", "identifier", "type", "label", "rect", "selected", "hittable", "visibleToUser", "value", "text", "editable")}
                        for n in data["nodes"] if not str(n.get("bundleId", "")).startswith("com.android.systemui")]
            if signature(before) != signature(after):
                raise ValueError("CLAMP AX changed around synchronized PNG")
            shot = shots[pair[0]]
            with require_clamp_pixels().open(shot) as image:
                if image.width * image.height > 16_000_000:
                    raise ValueError("CLAMP screenshot exceeds pixel bound")
                pixels = image.convert("RGB")
            proof.setdefault("ax_brackets", []).append({"source_steps": pair, "png": str(shot),
                "sha256": hashlib.sha256(shot.read_bytes()).hexdigest()})
            save()
            return after, pixels, shot

        def bind_ref(n, node, bracket_step):
            if node.get("hittable") is not True or not re.fullmatch(r"@?e[0-9]+", str(node.get("ref", ""))):
                raise ValueError("CLAMP target is not a fresh hittable ref")
            if actions[n - 1]["command"] != "press" or not actions[n - 1]["input"]["target"]["ref"].startswith(placeholder):
                raise ValueError("CLAMP ref source slot changed")
            actions[n - 1] = {"command": "press", "input": {"target": {"kind": "ref", "ref": "@" + node["ref"].lstrip("@")}}}
            row = parse_steps_json(json.dumps([actions[n - 1]]), "android")[0]
            row["n"] = indices.index(n) + 1
            live_rows[indices.index(n)] = row
            origin = next(v for v in proof["snapshots"] if v["source_step"] == bracket_step)
            proof.setdefault("ref_bindings", []).append({"source_step": n, "snapshot": origin, "png_bracket": proof["ax_brackets"][-1],
                "node": {k: node.get(k) for k in ("ref", "index", "parentIndex", "label", "rect")}})
            save()

        data, pixels, _ = bracket("picker", 1, 6)
        date_sets = [{int(r[k]) // divisor for k, divisor in (("datetaken", 1000), ("date_added", 1), ("date_modified", 1))
                      if r[k] not in ("NULL", "null", "0")} for r in owned]
        visible = {clamp_photo_second(n["label"], zone) for n in data["nodes"] if str(n.get("label", "")).startswith("Photo taken on ")}
        seconds = set.intersection(*date_sets) & visible
        if len(seconds) != 1:
            raise ValueError("CLAMP picker date fallback absent or ambiguous")
        second = seconds.pop()
        where = f"(datetaken>={second * 1000} AND datetaken<{(second + 1) * 1000}) OR date_added={second} OR date_modified={second}"
        proof.update(time_second=second, date_mapping="Three-column collection-wide union; no inferred fallback precedence")
        def union():
            query("date-union-" + str(len(proof["date_queries"])), where, set(ids.values()))

        def picker(data, pixels, selected):
            cards = [n for n in data["nodes"] if n.get("hittable") is True and str(n.get("label", "")).startswith("Photo taken on ")
                     and clamp_photo_second(n["label"], zone) == second]
            if len(cards) != 4:
                raise ValueError("CLAMP picker time group is not four cards")
            bound = {}
            for node in cards:
                key = clamp_fixture_identity(pixels, node["rect"])
                if key in bound:
                    raise ValueError("CLAMP picker fixture pixels are ambiguous")
                bound[key] = node
            if set(bound) != set(fixtures) or any(n.get("selected") is not (k in selected) for k, n in bound.items()):
                raise ValueError("CLAMP picker selection is not the exact owned set")
            return bound

        union()
        cards = picker(data, pixels, set())
        bind_ref(7, cards["A"], 6)
        data, pixels, _ = bracket("pick-A", 7, 10)
        cards = picker(data, pixels, {"A"})
        union()
        bind_ref(11, cards["B"], 10)
        data, pixels, _ = bracket("pick-B", 11, 14)
        picker(data, pixels, {"A", "B"})
        done = [n for n in data["nodes"] if n.get("hittable") is True and n.get("label") == "Add (2)"]
        if len(done) != 1:
            raise ValueError("CLAMP native Add (2) is not unique")
        union()
        bind_ref(15, done[0], 14)
        data, pixels, _ = bracket("add-two", 15, 19)

        def editor(data, pixels, step):
            bound, focused, preview = clamp_editor_binding(data, pixels, fixture)
            proof.setdefault("editor_bindings", []).append({"source_step": step, "focused": focused, "preview_rect": preview["rect"],
                "thumbs": {k: {f: v.get(f) for f in ("ref", "index", "parentIndex", "rect")} for k, v in bound.items()}})
            return bound, focused, preview

        bound, _, _ = editor(data, pixels, 19)
        bind_ref(20, bound["A"], 19)
        data, pixels, _ = bracket("focus-A", 20, 23)
        bound, focused, _ = editor(data, pixels, 23)
        if focused != "A":
            raise ValueError("Owned A focus is not established")
        if filmstrip:
            bind_ref(24, bound["B"], 23)
            data, pixels, _ = bracket("focus-B", 24, 27)
            bound, focused, _ = editor(data, pixels, 27)
            if focused != "B":
                raise ValueError("Filmstrip did not show owned B after its real press")
            bind_ref(28, bound["A"], 27)
            data, pixels, _ = bracket("return-A", 28, 31)
            if editor(data, pixels, 31)[1] != "A":
                raise ValueError("Filmstrip did not return to owned A after its real press")
            media()
            union()
            execute("close", 32, 32)
            if executed != list(range(1, 33)):
                raise ValueError("Filmstrip canonical action coverage is incomplete")
            if should_stop and should_stop():
                raise InterruptedError("Stopped after filmstrip actions")
            proof["status"] = "evidence_complete"
            return 0
        pid = shell("pidof", setup.ANDROID_PACKAGE).strip()
        if not re.fullmatch(r"[1-9][0-9]*", pid):
            raise ValueError("CLAMP unique app process is not observable")
        proof["process_id_before"] = pid
        observed, _, _ = bracket("content", 24, 27)
        fields = [n for n in observed["nodes"] if _template_tag(n) == "watermarkTextEditField"]
        compact = [n for n in observed["nodes"] if _template_tag(n) == "watermarkTextContent"]
        if len(fields) == 1 and not compact and fields[0].get("editable") is True and fields[0].get("hittable") is True:
            omitted = {28, 29, 32}
        elif len(compact) == 1 and compact[0].get("hittable") is True and not fields:
            omitted = set()
        else:
            raise ValueError("CLAMP Content entry is absent or ambiguous")
        proof.update(entry="inline" if omitted else "compact", omitted_source_steps=sorted(omitted))
        indices = [n for n in indices if n not in omitted]
        live_rows[:] = [row for n, row in enumerate(live_rows, 1) if n not in omitted]
        for n, row in enumerate(live_rows, 1):
            row["n"] = n
        observed, _, _ = bracket("nonce", 28, 35)
        if omitted:
            field = _template_node(observed, "watermarkTextEditField")
            values = {field.get("value") or field.get("text") or field.get("label")}
        else:
            values = {v.get("label") for v in _template_descendants(observed, _template_node(observed, "watermarkTextContent"))
                      if v.get("type") == "android.widget.TextView" and v.get("label")}
        if values != {nonce}:
            raise ValueError("CLAMP visible editor text does not match owned nonce")
        proof["nonce_confirmed"] = True
        execute("single", 36, 39)
        layouts = []
        def measure(name, start, end):
            data, pixels, shot = bracket(name, start, end)
            bound, focused, preview = editor(data, pixels, end)
            if focused != "A":
                raise ValueError("CLAMP measurement is not owned A")
            single = [v for v in data["nodes"] if v.get("label") == "Single" and v.get("type") == "android.widget.TextView"]
            if len(single) != 1:
                raise ValueError("CLAMP Single control is absent or ambiguous")
            tile = _template_node(data, "editorControl-TileMode")
            layout = [{k: v.get(k) for k in ("identifier", "type", "label", "rect", "hittable", "selected")} for v in (preview, single[0], tile)]
            if layouts and layout != layouts[0]:
                raise ValueError("CLAMP preview/control layout changed")
            layouts.append(layout)
            measured = clamp_pixels(shot, preview["rect"], fixture)
            proof.setdefault("pixels", {})[str(end)] = {k: v for k, v in measured.items() if not k.startswith("_")}
            return measured, bound

        before1, _ = measure("before-1", 40, 42)
        before2, _ = measure("before-2", 43, 45)
        proof["before_stable"] = compare_clamp_pixels(before1, before2)
        gesture = proof["gesture"] = clamp_pan(before2)
        actions[45] = {"command": "gesture", "input": gesture}
        row = parse_steps_json(json.dumps([actions[45]]), "android")[0]
        row["n"] = indices.index(46) + 1
        live_rows[indices.index(46)] = row
        execute("pan", 46, 46)
        after1, _ = measure("after-1", 47, 49)
        after2, bound = measure("after-2", 50, 52)
        proof["after_stable"] = compare_clamp_pixels(after1, after2)
        proof["pan_observed"] = compare_clamp_pixels(before2, after2, gesture["delta"])
        bind_ref(53, bound["B"], 52)
        data, pixels, _ = bracket("focus-B", 53, 56)
        bound, focused, _ = editor(data, pixels, 56)
        if focused != "B":
            raise ValueError("CLAMP filmstrip did not show owned B")
        bind_ref(57, bound["A"], 56)
        returned1, _ = measure("return-A-1", 57, 60)
        returned2, _ = measure("return-A-2", 61, 63)
        proof["return_stable"] = compare_clamp_pixels(returned1, returned2)
        proof["same_selection_offset"] = compare_clamp_pixels(after2, returned2)
        proof["return_still_displaced"] = compare_clamp_pixels(before2, returned2, gesture["delta"])
        execute("reopen", 64, 66)
        reopened1, _ = measure("reopened-1", 67, 69)
        reopened2, _ = measure("reopened-2", 70, 72)
        proof["reopen_stable"] = compare_clamp_pixels(reopened1, reopened2)
        proof["session_reopen_observed"] = compare_clamp_pixels(after2, reopened2)
        proof["reopen_still_displaced"] = compare_clamp_pixels(before2, reopened2, gesture["delta"])
        proof["process_id_after"] = shell("pidof", setup.ANDROID_PACKAGE).strip()
        if proof["process_id_after"] != pid:
            raise ValueError("CLAMP app process changed across SDK reopen")
        media()
        union()
        execute("close", 73, 73)
        if executed != indices:
            raise ValueError("CLAMP canonical action coverage is incomplete")
        proof["status"], code = "evidence_complete", 0
    except (ValueError, OSError, KeyError, TypeError, IndexError, subprocess.TimeoutExpired) as exc:
        proof["reason"] = str(exc)
        code = 130 if isinstance(exc, InterruptedError) else 2
    finally:
        setup._SETUP_STOP.reset(stop_token)
        if should_stop and should_stop():
            code = 130
        save()
    return code


def _close_named_session(session: str, logf=None) -> None:
    """Close one agent-device session. Never pass --shutdown."""
    if not session or session.startswith("-"):
        return
    try:
        proc = run_captured(
            [agent_device_bin(), "--session", session, "close"],
            text=True,
            timeout=20,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        if logf is not None:
            logf.write(f"session close {session} failed: {exc}\n")
            logf.flush()
        return
    if logf is not None:
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "").strip()
            logf.write(f"session close {session}: {proc.returncode} {detail[:240]}\n")
        else:
            logf.write(f"session close {session}: ok\n")
        logf.flush()


def release_agent_session(cmd: list[str] | None, logf=None) -> None:
    """Drop the agent-device session lock. Never pass --shutdown."""
    if not cmd or "--session" not in cmd:
        return
    index = cmd.index("--session")
    if index + 1 >= len(cmd):
        return
    _close_named_session(cmd[index + 1], logf)


def _listed_agent_sessions() -> list[str]:
    try:
        proc = run_captured(
            [agent_device_bin(), "session", "list", "--json"],
            text=True,
            timeout=20,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    try:
        payload = json.loads(proc.stdout or "")
    except json.JSONDecodeError:
        return []
    data = payload.get("data") if isinstance(payload, dict) else None
    raw = data.get("sessions") if isinstance(data, dict) else None
    if not isinstance(raw, list):
        return []
    names: list[str] = []
    for item in raw:
        if isinstance(item, str) and item:
            names.append(item)
        elif isinstance(item, dict):
            name = str(item.get("name") or item.get("id") or item.get("session") or "")
            if name:
                names.append(name)
    return names


def reap_orphan_ios_runners(logf=None) -> list[int]:
    """Stop AgentDeviceRunner xcodebuild left after its parent exited.

    Only processes reparented to launchd (ppid 1) whose command is the
    agent-device iOS runner. Never touches the simulator or the emulator.
    """
    try:
        proc = subprocess.run(
            ["ps", "-ax", "-o", "pid=,ppid=,command="],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        if logf is not None:
            logf.write(f"ios runner scan failed: {exc}\n")
            logf.flush()
        return []
    killed: list[int] = []
    for line in (proc.stdout or "").splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) < 3:
            continue
        pid_s, ppid_s, cmd = parts
        if ppid_s != "1":
            continue
        if "xcodebuild" not in cmd or "AgentDeviceRunner" not in cmd:
            continue
        if "platform=iOS Simulator" not in cmd and "iOS Simulator" not in cmd:
            continue
        try:
            pid = int(pid_s)
        except ValueError:
            continue
        try:
            os.kill(pid, signal.SIGTERM)
        except OSError as exc:
            if logf is not None:
                logf.write(f"ios runner {pid} term failed: {exc}\n")
            continue
        killed.append(pid)
        if logf is not None:
            logf.write(f"ios runner {pid} SIGTERM (orphaned xcodebuild)\n")
            logf.flush()
    deadline = time.monotonic() + 3
    for pid in killed:
        while time.monotonic() < deadline and _pid_alive(pid):
            time.sleep(0.2)
        if _pid_alive(pid):
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass
    return killed


def close_ios_sessions_after_run(rec: dict | None, logf=None) -> None:
    """Close iOS sessions recorded on this run. Never --shutdown."""
    names: set[str] = set()
    has_ios = False
    for task in (rec or {}).get("tasks") or []:
        if not isinstance(task, dict) or task.get("platform") != "ios":
            continue
        has_ios = True
        cmd = task.get("cmd") if isinstance(task.get("cmd"), list) else []
        session = _cmd_flag(cmd, "--session")
        if session:
            names.add(session)
    if logf is not None:
        if not has_ios:
            logf.write("ios session close: no ios tasks, skip\n")
            logf.flush()
            return
        logf.write(
            "ios session close: "
            + (", ".join(sorted(names)) if names else "(none recorded)")
            + "\n"
        )
        logf.flush()
    elif not has_ios:
        return
    for name in sorted(names):
        _close_named_session(name, logf)
    killed = reap_orphan_ios_runners(logf)
    if logf is not None:
        logf.write(
            "ios runner reap: "
            + (", ".join(str(pid) for pid in killed) if killed else "none")
            + "\n"
        )
        logf.flush()


def run_task(
    spec: dict,
    logf,
    *,
    tee_stdout: bool = False,
    on_proc=None,
    on_spec=None,
    on_steps=None,
    on_step_event=None,
    should_stop=None,
) -> tuple[int, list[dict], dict]:
    """Run one TASK_SPECS entry. Returns (exit_code, parsed cases, extra)."""
    spec = dict(spec)
    if spec.get("needs_device"):
        try:
            chosen = ensure_device_ready(
                spec["needs_device"], spec.get("device_request") or "auto", logf
            )
        except (ValueError, RuntimeError, TimeoutError, OSError) as exc:
            logf.write(f"device prepare failed: {exc}\n")
            logf.flush()
            if tee_stdout:
                print(f"device prepare failed: {exc}", file=sys.stderr)
            return 2, [], {}
        spec = apply_device(spec, chosen)
        if callable(on_spec):
            on_spec(spec)
    elif callable(on_spec):
        on_spec(spec)
    setup_state = None
    if spec.get("builder") == "agent-device":
        output = Path(spec.get("agent_device_output") or "")
        if str(output):
            output.mkdir(parents=True, exist_ok=True)
        try:
            if spec.get("edge_id") in {"editor-clamp-drag", "editor-filmstrip-switch", "export-save-success"} and spec.get("agent_platform") == "android":
                require_clamp_pixels()
            maybe_prepare_ios_runner(spec, logf, should_stop=should_stop)
            setup_state = _apply_agent_setup(spec, logf, should_stop=should_stop)
        except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
            logf.write(f"agent-device prepare/setup failed: {exc}\n")
            logf.flush()
            if tee_stdout:
                print(f"agent-device prepare/setup failed: {exc}", file=sys.stderr)
            code = 124 if isinstance(exc, subprocess.TimeoutExpired) else 130 if isinstance(exc, InterruptedError) else 2
            extra = ingest_agent_device_result(spec, code)
            try:
                _restore_agent_setup(setup_state, logf)
            finally:
                release_agent_session(spec.get("cmd"), logf)
            if isinstance(exc, SetupRestoreError):
                raise
            return code, extra.get("cases") or [], extra
    if callable(should_stop) and should_stop():
        _restore_agent_setup(setup_state, logf)
        return 130, [], {}
    since = time.time()
    cmd = list(spec.get("cmd") or [])
    if not cmd:
        logf.write("spawn failed: empty command\n")
        logf.flush()
        _restore_agent_setup(setup_state, logf)
        return 127, [], {}
    watch_plat = _cmd_flag(cmd, "--platform") or str(spec.get("needs_device") or "")
    script = _script_from_cmd(cmd) if spec.get("builder") == "agent-device" else None
    rows: list[dict] = []
    if script and script.is_file():
        try:
            rows = parse_script(script, watch_plat)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            logf.write(f"step list skipped: {exc}\n")
        if rows and on_steps:
            on_steps(watch_plat, rows)
    evidence_events = None
    evidence_manifest = None
    if spec.get("edge_id") in {"editor-to-template-sheet", "editor-clamp-drag", "editor-filmstrip-switch", "export-save-success"} and watch_plat == "android":
        is_clamp = spec["edge_id"] in {"editor-clamp-drag", "editor-filmstrip-switch"}
        is_filmstrip = spec["edge_id"] == "editor-filmstrip-switch"
        is_export = spec["edge_id"] == "export-save-success"
        proof_key = "export_file" if is_export else "filmstrip_switch" if is_filmstrip else "clamp_drag" if is_clamp else "template_crud"
        execute = _run_android_export_file if is_export else _run_android_clamp if is_clamp else _run_android_template_crud
        try:
            if not script or script.suffix != ".json" or not spec.get("step_evidence_root"):
                raise ValueError("Bounded Android case requires its JSON source and step evidence")
            code = execute(spec, setup_state, script, logf, tee_stdout=tee_stdout,
                on_proc=on_proc, on_spec=on_spec, on_steps=on_steps, on_step_event=on_step_event, should_stop=should_stop)
        finally:
            try:
                _restore_agent_setup(setup_state, logf)
                if spec.get(proof_key):
                    spec[proof_key]["private_restored"] = True
                    if is_export and setup_state.get("export_output"):
                        spec[proof_key]["cleanup"] = setup_state["export_output"]["cleanup"]
            finally:
                try:
                    if spec.get(proof_key + "_manifest"):
                        Path(spec[proof_key + "_manifest"]).write_text(json.dumps(spec[proof_key], indent=2) + "\n")
                finally:
                    release_agent_session(cmd, logf)
        extra = ingest_agent_device_result(spec, code)
        _publish_agent_device_live(spec)
        return code, extra.get("cases") or [], extra
    if rows and script and script.suffix in {".ad", ".json"} and spec.get("step_evidence_root"):
        root = Path(spec["step_evidence_root"])
        identity = spec.get("step_evidence_task") or {}
        names = {row["n"]: _shot_name(identity, watch_plat, row["n"]) for row in rows}
        derived = root / "scripts" / (_shot_name(identity, watch_plat, 0) + script.suffix)
        try:
            evidence_manifest = materialize_evidence_script(script, root, derived, names)
        except (OSError, ValueError, KeyError) as exc:
            logf.write(f"step evidence preparation failed: {exc}\n")
            _restore_agent_setup(setup_state, logf)
            return 2, [], {}
        script_flag = "--steps-file" if script.suffix == ".json" else "replay"
        cmd[cmd.index(script_flag) + 1] = str(derived)
        spec["cmd"] = cmd
        spec["step_evidence_manifest"] = str(derived.with_suffix(".mapping.json"))
        if callable(on_spec):
            on_spec(spec)
        evidence_events = EvidenceEvents(evidence_manifest, root,
            lambda event: on_step_event(watch_plat, event) if on_step_event else apply_event(rows, event))
    env = os.environ.copy()
    env.update(spec.get("env") or {})
    if spec.get("builder") == "agent-device" and rows and script and script.suffix == ".json":
        try:
            def run_batch(batch_rows):
                return _run_batched_steps(cmd, batch_rows, logf, tee_stdout, on_proc,
                    (lambda _plat, event: evidence_events(event)) if evidence_events else on_step_event,
                    should_stop, watch_plat, env)
            batch_rows = parse_script(Path(evidence_manifest["script"]), watch_plat) if evidence_manifest else rows
            if spec.get("edge_id") == "export-failure-recovery" and watch_plat == "android":
                code = _run_android_failure_recovery(spec, setup_state, evidence_manifest, evidence_events,
                                                     batch_rows, run_batch, should_stop, logf)
            else:
                code = run_batch(batch_rows)
        finally:
            try:
                if not (should_stop and should_stop()) and spec.get("edge_id") == "export-cancel":
                    _capture_cancel_surface(cmd, spec, logf, should_stop=should_stop)
            finally:
                try:
                    _restore_agent_setup(setup_state, logf)
                    if spec.get("source_recovery"):
                        spec["source_recovery"]["private_restored"] = True
                        (output / "android-failure-recovery.json").write_text(json.dumps(spec["source_recovery"], indent=2) + "\n")
                finally:
                    if evidence_events is not None:
                        evidence_events.finish()
                    release_agent_session(cmd, logf)
        extra = ingest_agent_device_result(spec, code)
        _publish_agent_device_live(spec)
        return code, extra.get("cases") or [], extra
    output = Path(spec.get("agent_device_output") or "")
    if spec.get("builder") == "agent-device" and "replay" in cmd and output.parts:
        output.mkdir(parents=True, exist_ok=True)
        cmd = _replay_as_test(cmd, output)
        spec["cmd"] = cmd
        if callable(on_spec):
            on_spec(spec)
    logf.write(f"\n## spawn: {' '.join(cmd)}\n")
    logf.flush()
    replay_log = None
    sink = logf
    if spec.get("builder") == "agent-device":
        replay_path = Path(spec.get("agent_device_output") or "") / "replay.log"
        try:
            replay_path.parent.mkdir(parents=True, exist_ok=True)
            replay_log = replay_path.open("a", encoding="utf-8")
            sink = _DupWrite(logf, replay_log)
        except OSError:
            replay_log = None
            sink = logf
    try:
        proc = subprocess.Popen(
            cmd,
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=True,
            env=env,
        )
    except OSError as exc:
        if replay_log is not None:
            replay_log.close()
        logf.write(f"spawn failed: {exc}\n")
        logf.flush()
        if tee_stdout:
            print(f"spawn failed: {exc}", file=sys.stderr)
        extra = (
            ingest_agent_device_result(spec, 127)
            if spec.get("builder") == "agent-device"
            else {}
        )
        try:
            _restore_agent_setup(setup_state, logf)
        finally:
            if spec.get("builder") == "agent-device":
                release_agent_session(cmd, logf)
        return 127, extra.get("cases") or [], extra
    if on_proc:
        on_proc(proc)
    stop_tail = threading.Event()
    tail = None
    if spec.get("builder") == "agent-device" and rows and output.parts:
        tail = threading.Thread(
            target=_tail_timing,
            args=(
                output,
                evidence_events or ((lambda line: on_step_event(watch_plat, line)) if on_step_event else None),
                stop_tail,
                watch_plat,
            ),
            daemon=True,
            name="testmap-steps",
        )
        tail.start()
    code = 1
    try:
        code = _tee_child(
            proc,
            sink,
            tee_stdout,
            deadline_s=420 if spec.get("builder") == "agent-device" else None,
        )
    finally:
        stop_tail.set()
        try:
            # Stop skips optional evidence; rollback precedes slow session shutdown.
            try:
                if spec.get("builder") == "agent-device" and not (should_stop and should_stop()) and spec.get("edge_id") == "export-cancel":
                    _capture_cancel_surface(cmd, spec, logf, should_stop=should_stop)
            finally:
                _restore_agent_setup(setup_state, logf)
        finally:
            if tail is not None:
                tail.join(timeout=3)
            if evidence_events is not None:
                evidence_events.finish()
            if replay_log is not None:
                replay_log.close()
            if evidence_manifest is not None:
                try:
                    record_sdk_plan_digest(Path(spec["step_evidence_manifest"]), output, Path(spec["step_evidence_root"]))
                except (OSError, ValueError) as exc:
                    logf.write(f"SDK plan digest metadata unavailable: {exc}\n")
            if on_proc:
                on_proc(None)
            if spec.get("builder") == "agent-device":
                release_agent_session(cmd, logf)
    cases = (
        parse_junit(spec.get("xml_dir"), since, spec.get("xml_base"))
        if spec.get("parse_xml")
        else []
    )
    extra = {}
    if spec.get("builder") == "artemis":
        extra = ingest_artemis_result(spec, code)
        cases = extra.get("cases") or cases
        _publish_artemis_live(spec)
    elif spec.get("builder") == "agent-device":
        extra = ingest_agent_device_result(spec, code)
        cases = extra.get("cases") or cases
        _publish_agent_device_live(spec)
    return code, cases, extra


def load_status_record(run_id: str | None = None) -> dict | None:
    """The run the console should show. A running record wins over a newer finished one."""
    if run_id:
        return load_run(run_id)
    if not RUNS_DIR.is_dir():
        return None
    running = None
    newest = None
    for path in RUNS_DIR.glob("*.json"):
        if not RUN_ID_RE.match(path.stem):
            continue
        try:
            rec = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(rec, dict) or rec.get("historical"):
            continue
        rec = reap_stale_run(rec)
        started = str(rec.get("started") or "")
        if rec.get("state") in {"running", "paused"} and (
            running is None or started > str(running.get("started") or "")
        ):
            running = rec
        if newest is None or started > str(newest.get("started") or ""):
            newest = rec
    return running or newest


def _watch_from_slots() -> dict[str, dict | None]:
    slots = default_watch_slots()
    watches: dict[str, dict | None] = {}
    for plat in ("android", "ios", "desktop"):
        item = slots.get(plat)
        if isinstance(item, dict) and (item.get("id") or plat == "desktop"):
            watches[plat] = {
                "platform": plat,
                "device": item.get("id") or plat,
                "name": item.get("name") or plat,
                "kind": item.get("kind"),
            }
        else:
            watches[plat] = None
    return watches


def _watch_from_record(rec: dict) -> dict[str, dict | None]:
    """Never attach a global/default device to recorded run evidence."""
    watches: dict[str, dict | None] = {"android": None, "ios": None, "desktop": None}
    tasks = [t for t in rec.get("tasks") or [] if isinstance(t, dict)]
    platforms = {str(t.get("platform") or "") for t in tasks} & {"android", "ios"}
    for platform in platforms:
        lane = [t for t in tasks if t.get("platform") == platform]
        running = [t for t in lane if t.get("state") in {"running", "paused"}]
        # If the active task has no known identity, do not show an earlier
        # task's device in its place. Finished runs use the last resolved task.
        completed = [t for t in reversed(lane) if t.get("state") != "pending"]
        candidates = running or completed or list(reversed(lane))
        for task in candidates:
            watch = watch_from_task(task)
            if watch:
                watches[platform] = watch
                break
        if watches[platform] is None and len(platforms) == 1:
            requested = str(rec.get("device") or "")
            if requested and requested not in {"auto", "<device>"}:
                watches[platform] = watch_from_task({
                    "platform": platform, "device": {"id": requested},
                })
    return watches


def _steps_for_watch(tasks: list[dict]) -> list[dict]:
    chosen: dict[str, dict] = {}
    for task in tasks:
        plat = str(task.get("platform") or "")
        if task.get("state") == "running":
            chosen[plat] = task
    for task in tasks:
        plat = str(task.get("platform") or "")
        if plat not in chosen and task.get("state") not in {"pending"}:
            chosen[plat] = task
    steps: list[dict] = []
    for task in chosen.values():
        for step in task.get("steps") or []:
            if not isinstance(step, dict):
                continue
            item = dict(step)
            item["platform"] = task.get("platform") or item.get("platform") or ""
            steps.append(item)
    return steps


def project_status(rec: dict | None) -> dict:
    watches = _watch_from_record(rec) if rec else _watch_from_slots()
    idle = {
        "active": False,
        "state": "idle",
        "id": None,
        "source": None,
        "pid": None,
        "queue": [],
        "tasks": [],
        "progress": {"done": 0, "total": 0, "failed": 0},
        "current": None,
        "elapsed_s": 0,
        "log_tail": [],
        "pause_queue": False,
        "semantics": SEMANTICS,
        "live": live_snapshot(),
        "watch": watches.get("android"),
        "watches": watches,
        "steps": [],
    }
    if not rec:
        return idle
    tasks = [task for task in (rec.get("tasks") or []) if isinstance(task, dict)]
    queue = []
    current = None
    for task in tasks:
        edge = task.get("edge") or task_edge_id(str(task.get("id") or ""))
        steps = []
        for step in task.get("steps") or []:
            if isinstance(step, dict):
                steps.append({k: v for k, v in step.items() if not str(k).startswith("_")})
        row = {
            "id": task.get("id"),
            "edge": edge,
            "edge_id": edge,
            "platform": task.get("platform") or "",
            "repeat": task.get("repeat") or {"k": 1, "n": 1},
            "label": task.get("label") or "",
            "state": task.get("state") or "pending",
            "exit_code": task.get("exit_code"),
            "duration_s": task.get("duration_s"),
            "note": task.get("note") or "",
            "steps": steps,
        }
        queue.append(row)
        if current is None and row["state"] in {"running", "paused"}:
            current = {"id": row["id"], "edge_id": edge, "elapsed_s": row["duration_s"] or 0}
    done = sum(1 for row in queue if row["state"] not in {"pending", "running", "paused"})
    failed = sum(1 for row in queue if row["state"] == "failed")
    passed_n, failed_n, uncovered_n = outcome_counts(tasks)
    log_path = Path(rec["log"]) if rec.get("log") else None
    if log_path and not log_path.is_absolute():
        log_path = REPO_ROOT / log_path
    return {
        "active": rec.get("state") in {"running", "paused"},
        "state": rec.get("state") or "idle",
        "id": rec.get("id"),
        "source": rec.get("source") or "manual",
        "pid": rec.get("pid"),
        "git": rec.get("git"),
        "packages": rec.get("packages") or {},
        "package_refusal": rec.get("package_refusal") or "",
        "queue": queue,
        "tasks": queue,
        "progress": {"done": done, "total": len(queue), "failed": failed},
        "pass_count": passed_n,
        "fail_count": failed_n,
        "uncovered_count": uncovered_n,
        "current": current,
        "elapsed_s": 0,
        "log_tail": log_tail(log_path) if log_path else [],
        "pause_queue": False,
        "semantics": SEMANTICS,
        "live": live_snapshot(),
        "watch": watches.get("android"),
        "watches": watches,
        "steps": _steps_for_watch(tasks),
    }


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except PermissionError:
        return True
    except OSError:
        return False
    return True


def reap_stale_run(rec: dict) -> dict:
    """A running record whose process is gone is interrupted, not still running."""
    if not isinstance(rec, dict) or rec.get("historical"):
        return rec
    if rec.get("finished"):
        return rec
    if rec.get("state") not in {"running", "paused"}:
        return rec
    pid = rec.get("pid")
    if isinstance(pid, int) and pid > 0 and _pid_alive(pid):
        return rec
    rec["state"] = "interrupted"
    rec["finished"] = iso(utc_now())
    for task in rec.get("tasks") or []:
        if isinstance(task, dict) and task.get("state") in {"pending", "running", "paused", None}:
            task["state"] = "interrupted"
    passed_n, failed_n, uncovered_n = outcome_counts(rec.get("tasks") or [])
    rec["pass_count"] = passed_n
    rec["fail_count"] = failed_n
    rec["uncovered_count"] = uncovered_n
    try:
        write_record(rec)
    except OSError:
        return rec
    return rec


def _drain_runner_output(proc: subprocess.Popen) -> None:
    if proc.stdout is None:
        return
    try:
        for line in proc.stdout:
            sys.stderr.write(line)
            sys.stderr.flush()
    except (OSError, ValueError):
        return


def stop_recorded_run() -> dict:
    rec = load_status_record()
    if not rec or rec.get("state") not in {"running", "paused"}:
        raise ValueError("no active run")
    pid = rec.get("pid")
    if isinstance(pid, int) and _pid_alive(pid):
        try:
            os.kill(pid, signal.SIGTERM)
        except PermissionError as exc:
            raise StopForbiddenError(
                f"cannot signal pid {pid}: {exc}"
            ) from exc
        return {"id": rec.get("id"), "state": "stopping", "pid": pid}
    rec = reap_stale_run(rec)
    return {"id": rec.get("id"), "state": rec.get("state") or "interrupted", "pid": pid}


class BusyError(Exception):
    def __init__(self, run_id: str):
        super().__init__(f"a run is already active ({run_id})")
        self.run_id = run_id


class StopForbiddenError(Exception):
    """Stop was refused (for example PermissionError on os.kill)."""


class RunManager:
    START_TIMEOUT_S = 20

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.cv = threading.Condition(self.lock)
        self.log_lock = threading.Lock()
        self.active: dict | None = None
        self.proc: subprocess.Popen | None = None
        self.procs: dict[str, subprocess.Popen] = {}
        self.worker: threading.Thread | None = None
        self.pause_queue = False
        self.stop_requested = False
        self.step_rows: dict[str, list[dict]] = {}
        self.last_watch: dict | None = None
        self.last_watches: dict[str, dict | None] = {
            "android": None,
            "ios": None,
            "desktop": {"platform": "desktop", "device": "desktop"},
        }

    def _remember_watch(self, rec: dict | None) -> dict | None:
        if not rec:
            return self.last_watch
        running = None
        last = None
        for task in rec.get("tasks") or []:
            w = watch_from_task(task)
            if not w:
                continue
            last = w
            if task.get("state") == "running":
                running = w
                break
        chosen = running or last
        if chosen:
            self.last_watch = chosen
            plat = str(chosen.get("platform") or "")
            if plat in self.last_watches:
                self.last_watches[plat] = chosen
        return chosen or self.last_watch

    def _watch_slots(self, rec: dict | None) -> dict[str, dict | None]:
        return _watch_from_record(rec) if rec else _watch_from_slots()

    def _public_steps(self) -> list[dict]:
        out: list[dict] = []
        for plat in ("android", "ios", "desktop"):
            out.extend(public_steps(self.step_rows.get(plat) or []))
        for plat, rows in self.step_rows.items():
            if plat not in {"android", "ios", "desktop"}:
                out.extend(public_steps(rows))
        return out

    def _set_steps(self, platform: str, rows: list[dict]) -> None:
        with self.lock:
            self.step_rows[platform or ""] = rows

    def _on_step_signal(self, platform: str, payload: object) -> None:
        with self.lock:
            rows = self.step_rows.get(platform or "") or []
            if isinstance(payload, str):
                apply_timing_line(rows, payload)
            elif isinstance(payload, dict):
                apply_event(rows, payload)

    def _finish_steps(self, platform: str, ok: bool) -> None:
        with self.lock:
            for row in self.step_rows.get(platform) or []:
                if row.get("state") == "current":
                    row["state"] = "done" if ok else "failed"

    def _should_stop(self) -> bool:
        with self.lock:
            return self.stop_requested

    def snapshot(self, run_id: str | None = None) -> dict:
        return project_status(load_status_record(run_id))

    def start(
        self,
        task_ids: list[str],
        device: str | None = None,
        repeat: int = 1,
        source: str = "manual",
    ) -> dict:
        active = load_status_record()
        if active and active.get("state") in {"running", "paused"}:
            raise BusyError(str(active.get("id") or ""))
        if not task_ids:
            raise ValueError("selection is empty")
        origin = source if source in {"manual", "select", "verify"} else "manual"
        try:
            count = int(repeat)
        except (TypeError, ValueError) as exc:
            raise ValueError("repeat must be an integer") from exc
        if count < 1 or count > 20:
            raise ValueError("repeat must be from 1 to 20")
        cmd = ["bash", str(REPO_ROOT / "scripts" / "e2e-run.sh")]
        if device:
            cmd.extend(["--device", device])
        cmd.extend(["--source", origin, "--repeat", str(count)])
        cmd.extend(task_ids)
        proc = subprocess.Popen(
            cmd,
            cwd=REPO_ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=True,
            env={**os.environ, "TMPDIR": os.environ.get("TMPDIR") or "/tmp"},
        )
        ready = threading.Event()
        handshake: dict[str, str] = {}
        diagnostics: list[str] = []

        def _read_output() -> None:
            # One reader owns the pipe before and after the handshake. Import
            # warnings are diagnostics, not the runner's run-id announcement.
            try:
                if proc.stdout is None:
                    return
                for line in proc.stdout:
                    sys.stderr.write(line)
                    sys.stderr.flush()
                    if "id" not in handshake:
                        diagnostics.append(line[-2048:])
                        del diagnostics[:-8]
                        match = re.match(r"^testmap run (\S+)(?:\s|$)", line)
                        if match and RUN_ID_RE.fullmatch(match[1]):
                            handshake["id"] = match[1]
                            ready.set()
            except (OSError, ValueError) as exc:
                handshake["error"] = str(exc)
            finally:
                ready.set()
                if proc.stdout is not None:
                    proc.stdout.close()
                proc.poll()

        reader = threading.Thread(target=_read_output, daemon=True, name="testmap-cli-log")
        try:
            reader.start()
            announced = ready.wait(self.START_TIMEOUT_S)
        except BaseException:
            terminate_process(proc)
            if reader.ident is not None:
                reader.join(RUNNER_KILL_S)
            elif proc.stdout is not None:
                proc.stdout.close()
            raise
        run_id = handshake.get("id", "")
        if not announced or not run_id:
            # The CLI can already have children when its announcement is lost.
            # Preserve its normal restore grace, then end the entire owned group.
            terminate_process(proc)
            reader.join(RUNNER_KILL_S)
            reason = "runner start timed out" if not announced else "runner exited without a run id"
            detail = handshake.get("error") or "".join(diagnostics).strip()
            raise RuntimeError(reason + (f": {detail}" if detail else ""))
        rec = load_run(run_id) or {}
        return {
            "id": run_id,
            "state": rec.get("state") or "running",
            "selection": list(rec.get("selection") or task_ids),
        }

    def pause(self) -> dict:
        with self.lock:
            if not self.active or self.active["state"] not in {"running", "paused"}:
                raise ValueError("no active run")
            self.pause_queue = True
            self.active["state"] = "paused"
            return {"id": self.active["id"], "state": "paused"}

    def resume(self) -> dict:
        with self.lock:
            if not self.active or self.active["state"] not in {"running", "paused"}:
                raise ValueError("no active run")
            self.pause_queue = False
            if self.active["state"] == "paused":
                self.active["state"] = "running"
            self.cv.notify_all()
            return {"id": self.active["id"], "state": "running"}

    def stop(self) -> dict:
        return stop_recorded_run()

    def kill_child(self) -> None:
        with self.lock:
            self.stop_requested = True
            proc = self.proc
            procs = list(self.procs.values())
            self.cv.notify_all()
        terminate_process(proc)
        for item in procs:
            terminate_process(item)

    def _log(self, logf, msg: str) -> None:
        with self.log_lock:
            logf.write(msg)
            logf.flush()

    def _execute_one_task(self, rec: dict, task: dict, logf) -> None:
        with self.lock:
            while self.pause_queue and not self.stop_requested:
                rec["state"] = "paused"
                self.cv.wait(timeout=0.5)
            if self.stop_requested:
                task["state"] = "stopped"
                return
            rec["state"] = "running"
            task["state"] = "running"
            task["_started_mono"] = time.monotonic()
            spec = task_run_spec(task)
            spec["step_evidence_root"] = str(RUNS_DIR / rec["id"])
            spec["step_evidence_task"] = dict(task)
            lane = _task_lane(spec)
        t0 = time.monotonic()
        self._log(logf, f"\n## {task['id']}: {' '.join(spec['cmd'])}\n")

        def _bind(proc, lane_ref=lane):
            with self.lock:
                self.proc = proc
                self.procs[lane_ref] = proc

        def _on_spec(updated, task_ref=task):
            with self.lock:
                if updated.get("cmd"):
                    task_ref["cmd"] = list(updated["cmd"])
                if updated.get("device"):
                    task_ref["device"] = updated["device"]
                if updated.get("step_evidence_manifest"):
                    task_ref["step_evidence_manifest"] = updated["step_evidence_manifest"]
                if updated.get("template_crud_manifest"):
                    task_ref["template_crud_manifest"] = updated["template_crud_manifest"]
                if updated.get("export_file_manifest"):
                    task_ref["export_file_manifest"] = updated["export_file_manifest"]
                if updated.get("filmstrip_switch_manifest"):
                    task_ref["filmstrip_switch_manifest"] = updated["filmstrip_switch_manifest"]
                if updated.get("clamp_drag_manifest"):
                    task_ref["clamp_drag_manifest"] = updated["clamp_drag_manifest"]
                self._remember_watch(rec)

        code, cases, extra = run_task(
            spec,
            logf,
            tee_stdout=False,
            on_proc=_bind,
            on_spec=_on_spec,
            on_steps=self._set_steps,
            on_step_event=self._on_step_signal,
            should_stop=self._should_stop,
        )
        with self.lock:
            self.procs.pop(lane, None)
            if self.proc is not None and self.procs.get(lane) is None:
                self.proc = next(iter(self.procs.values()), None)
            stopped = self.stop_requested
        if spec.get("cmd"):
            task["cmd"] = list(spec["cmd"])
        task["duration_s"] = round(time.monotonic() - t0, 2)
        task["exit_code"] = code
        task["cases"] = cases
        if extra:
            task["layers"] = extra.get("layers")
            if extra.get("export_control"):
                task["export_control"] = extra["export_control"]
            if extra.get("template_crud"):
                task["template_crud"] = extra["template_crud"]
            if extra.get("export_file"):
                task["export_file"] = extra["export_file"]
            if extra.get("filmstrip_switch"):
                task["filmstrip_switch"] = extra["filmstrip_switch"]
            if extra.get("clamp_drag"):
                task["clamp_drag"] = extra["clamp_drag"]
            task["evidence_dir"] = extra.get("evidence_dir")
            task["recordings"] = extra.get("recordings")
            task["independent_review"] = extra.get("independent_review")
            task["human_confirmation"] = False
        if stopped:
            task["state"] = "stopped"
        elif spec.get("builder") in {"artemis", "agent-device"}:
            task["state"] = _agent_task_state(spec, extra)
        elif code == 0:
            task["state"] = "passed"
        else:
            task["state"] = "failed"
        plat_m = re.search(r"@(android|ios|desktop)", str(task.get("id") or ""))
        if plat_m:
            self._finish_steps(plat_m.group(1), task["state"] in {"passed", "review_required"})

    def _run_lane(self, rec: dict, tasks: list[dict], logf) -> None:
        for task in tasks:
            t0 = time.monotonic()
            try:
                self._execute_one_task(rec, task, logf)
            except Exception as exc:  # noqa: BLE001
                task["state"] = "failed"
                if task.get("duration_s") is None:
                    task["duration_s"] = round(time.monotonic() - t0, 2)
                task["error"] = f"{type(exc).__name__}: {exc}"
                if isinstance(exc, SetupRestoreError):
                    task["setup_restore_failed"] = True
                    task["note"] = str(exc)
                _unbind_lane_proc(self, task)
                plat_m = re.search(r"@(android|ios|desktop)", str(task.get("id") or ""))
                if plat_m:
                    self._finish_steps(plat_m.group(1), False)

    def _worker(self, rec: dict, log_path: Path) -> None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("a", encoding="utf-8") as logf:
            self._log(logf, f"# run {rec['id']} started {rec['started']}\n")
            lanes: dict[str, list[dict]] = {"android": [], "ios": [], "host": []}
            for task in rec["tasks"]:
                spec = task_run_spec(task)
                lanes[_task_lane(spec)].append(task)
            threads: list[threading.Thread] = []
            for name, group in lanes.items():
                if not group:
                    continue
                thread = threading.Thread(
                    target=self._run_lane,
                    args=(rec, group, logf),
                    daemon=True,
                    name=f"testmap-lane-{name}",
                )
                threads.append(thread)
                thread.start()
            for thread in threads:
                thread.join()
            for task in rec["tasks"]:
                if task["state"] == "pending":
                    task["state"] = "stopped"
        finalize_record(rec)
        with self.lock:
            if self.active is rec:
                self.pause_queue = False
                self.stop_requested = False
                self.procs = {}


def print_task_list() -> None:
    print("Runnable:")
    print("  edge:<edge-id>@android#agent       Android Agent Device (default; replay 0 is not a pass)")
    print("  edge:<edge-id>@ios#agent           iOS Agent Device (default; replay 0 is not a pass)")
    for tid, spec in TASK_SPECS.items():
        heavy = "  [heavy]" if spec["heavy"] else ""
        device = "  [device]" if spec.get("needs_device") else ""
        print(f"  {tid:24}  {spec['label']}{heavy}{device}")
    print("Dynamic (one map transition):")
    print("  edge:<edge-id>                     host cases on desktop")
    print("  edge:<edge-id>@desktop|ios|android host + device L2 on that runner")
    print("  edge:<edge-id>@ios#l2              XCUITest slice only")
    print("  edge:<edge-id>@android#l2          macrobenchmark journeys only")
    print("  edge:<edge-id>@android#artemis     Android Artemis agent (deprecated optional)")
    if MANUAL_TASKS:
        print("Manual (copy-only, not executed):")
        for item in MANUAL_TASKS:
            print(f"  {item['id']:24}  {item['label']}")
            print(f"    {item['cmd']}")


def _repeat_tasks(tasks: list[dict], repeats: int) -> list[dict]:
    count = max(1, int(repeats or 1))
    if count == 1:
        for task in tasks:
            task["repeat"] = {"k": 1, "n": 1}
        return tasks
    out: list[dict] = []
    for task in tasks:
        for k in range(1, count + 1):
            copy = json.loads(json.dumps(task))
            copy["repeat"] = {"k": k, "n": count}
            if k > 1 and copy.get("edge"):
                copy["agent_device_output"] = str(evidence_dir_for(str(copy["edge"])))
            out.append(copy)
    return out


def _shot_name(task: dict, platform: str, n: int) -> str:
    edge = re.sub(r"[^A-Za-z0-9]+", "-", str(task.get("edge") or "task")).strip("-")
    repeat = task.get("repeat") or {}
    total = int(repeat.get("n") or 1)
    k = int(repeat.get("k") or 1)
    if total > 1:
        return f"{edge}-{platform}-r{k}-{n}.png"
    return f"{edge}-{platform}-{n}.png"


def _device_id(value: object) -> str:
    """Serial or UDID. A device dict must not be stringified into the id."""
    if isinstance(value, dict):
        raw = str(value.get("id") or "")
    elif isinstance(value, str):
        raw = value.strip()
    else:
        return ""
    if not raw or raw in {"auto", "<device>"} or raw.startswith("{"):
        return ""
    return raw


def _isolate_agent_outputs(tasks: list[dict]) -> None:
    """Each lane and repeat gets its own evidence directory.

    Android and iOS of one edge otherwise share replay.log, and the other
    platform's divergence is then read as this task's result.
    """
    for task in tasks:
        raw = str(task.get("agent_device_output") or "")
        if not raw:
            continue
        plat = str(task.get("platform") or "host")
        repeat = task.get("repeat") if isinstance(task.get("repeat"), dict) else {}
        k = int((repeat or {}).get("k") or 1)
        n = int((repeat or {}).get("n") or 1)
        leaf = plat if n <= 1 else f"{plat}-r{k}"
        path = Path(raw)
        if path.name == leaf:
            continue
        task["agent_device_output"] = str(path / leaf)


def _step_public(row: dict, task: dict) -> dict:
    command = str(row.get("command") or "")
    args = str(row.get("args") or "")
    item = {
        "n": row.get("n"),
        "text": (command + " " + args).strip(),
        "command": command,
        "args": args,
        "state": row.get("state") or "pending",
        "platform": task.get("platform") or row.get("platform") or "",
    }
    if isinstance(row.get("duration_ms"), int):
        item["duration_ms"] = row["duration_ms"]
    if row.get("shot"):
        item["shot"] = row["shot"]
        if row.get("shot_capture"):
            item["shot_capture"] = row["shot_capture"]
    elif row.get("shot_error"):
        item["shot_error"] = row["shot_error"]
    if row.get("point"):
        item["point"] = row["point"]
    return item


class ActiveRun:
    """One CLI process. The JSON file is the interface the console reads."""

    def __init__(self, rec: dict):
        self.rec = rec
        self.lock = threading.Lock()
        self.devices: dict[str, str] = {}
        self.proc: subprocess.Popen | None = None
        self.procs: dict[str, subprocess.Popen] = {}
        self.stop_requested = False
        self.cv = threading.Condition(self.lock)
        self.log_lock = threading.Lock()

    def flush(self) -> None:
        with self.lock:
            for task in self.rec.get("tasks") or []:
                rows = task.get("_rows")
                if rows is None:
                    continue
                task["steps"] = [_step_public(row, task) for row in rows]
            passed_n, failed_n, uncovered_n = outcome_counts(self.rec.get("tasks") or [])
            self.rec["pass_count"] = passed_n
            self.rec["fail_count"] = failed_n
            self.rec["uncovered_count"] = uncovered_n
            write_record(self.rec)

    def _running(self, platform: str) -> dict | None:
        for task in self.rec.get("tasks") or []:
            if task.get("state") == "running" and (task.get("platform") or "") == platform:
                return task
        return None

    def on_steps(self, platform: str, rows: list[dict]) -> None:
        with self.lock:
            task = self._running(platform)
            if task is None:
                return
            task["_rows"] = rows
        self.flush()

    def on_step(self, platform: str, payload: object) -> None:
        with self.lock:
            task = self._running(platform)
            rows = (task or {}).get("_rows") or []
            if isinstance(payload, str):
                apply_timing_line(rows, payload)
            elif isinstance(payload, dict):
                apply_event(rows, payload)
        self.flush()

    def note_device(self, platform: str, device: str) -> None:
        if platform and device:
            with self.lock:
                self.devices[platform] = device

    def finish_task_rows(self, task: dict, ok: bool) -> None:
        with self.lock:
            for row in task.get("_rows") or []:
                if row.get("state") == "current":
                    row["state"] = "done" if ok else "failed"


def _execute_task(active: ActiveRun, rec: dict, task: dict, logf) -> None:
    with active.lock:
        while active.stop_requested is False and rec.get("state") == "paused":
            active.cv.wait(timeout=0.5)
        if active.stop_requested:
            task["state"] = "stopped"
            return
        rec["state"] = "running"
        task["state"] = "running"
        task["_started_mono"] = time.monotonic()
        spec = task_run_spec(task)
        spec["step_evidence_root"] = str(RUNS_DIR / rec["id"])
        spec["step_evidence_task"] = dict(task)
        lane = _task_lane(spec)
    active.flush()
    t0 = time.monotonic()
    with active.log_lock:
        logf.write(f"\n## {task['id']}: {' '.join(spec['cmd'])}\n")
        logf.flush()

    def _bind(proc, lane_ref=lane):
        with active.lock:
            active.proc = proc
            if proc is None:
                active.procs.pop(lane_ref, None)
            else:
                active.procs[lane_ref] = proc

    def _on_spec(updated, task_ref=task):
        with active.lock:
            if updated.get("cmd"):
                task_ref["cmd"] = list(updated["cmd"])
            if updated.get("device"):
                task_ref["device"] = updated["device"]
                dev = _device_id(updated.get("device"))
                if dev:
                    active.devices[str(task_ref.get("platform") or "")] = dev
            if updated.get("step_evidence_manifest"):
                task_ref["step_evidence_manifest"] = updated["step_evidence_manifest"]
            if updated.get("template_crud_manifest"):
                task_ref["template_crud_manifest"] = updated["template_crud_manifest"]
            if updated.get("export_file_manifest"):
                task_ref["export_file_manifest"] = updated["export_file_manifest"]
            if updated.get("filmstrip_switch_manifest"):
                task_ref["filmstrip_switch_manifest"] = updated["filmstrip_switch_manifest"]
            if updated.get("clamp_drag_manifest"):
                task_ref["clamp_drag_manifest"] = updated["clamp_drag_manifest"]
            if updated.get("agent_device_output"):
                task_ref["agent_device_output"] = updated["agent_device_output"]

    code, cases, extra = run_task(
        spec,
        logf,
        tee_stdout=True,
        on_proc=_bind,
        on_spec=_on_spec,
        on_steps=active.on_steps,
        on_step_event=active.on_step,
        should_stop=lambda: active.stop_requested,
    )
    task["duration_s"] = round(time.monotonic() - t0, 2)
    task["exit_code"] = code
    task["cases"] = cases
    if extra:
        task["layers"] = extra.get("layers")
        if extra.get("export_control"):
            task["export_control"] = extra["export_control"]
        if extra.get("template_crud"):
            task["template_crud"] = extra["template_crud"]
        if extra.get("export_file"):
            task["export_file"] = extra["export_file"]
        if extra.get("filmstrip_switch"):
            task["filmstrip_switch"] = extra["filmstrip_switch"]
        if extra.get("clamp_drag"):
            task["clamp_drag"] = extra["clamp_drag"]
        task["evidence_dir"] = extra.get("evidence_dir")
        task["recordings"] = extra.get("recordings")
        task["independent_review"] = extra.get("independent_review")
        task["human_confirmation"] = False
    if active.stop_requested:
        task["state"] = "stopped"
    elif spec.get("builder") in {"artemis", "agent-device"}:
        task["state"] = _agent_task_state(spec, extra)
    elif code == 0:
        task["state"] = "passed"
    else:
        task["state"] = "failed"
    _mark_cancel_uncovered(task)
    _mark_ios_export_uncovered(task)
    active.finish_task_rows(task, task["state"] in {"passed", "review_required"})
    active.flush()


def _unbind_lane_proc(holder, task: dict) -> None:
    lanes: list[str] = []
    try:
        lanes.append(_task_lane(task_run_spec(task)))
    except Exception:  # noqa: BLE001
        pass
    plat = str(task.get("platform") or "")
    if plat and plat not in lanes:
        lanes.append(plat)
    if not lanes:
        lanes.append("host")
    lock = getattr(holder, "lock", None)
    procs = getattr(holder, "procs", None)
    if not isinstance(procs, dict):
        return

    def _drop() -> None:
        for lane in lanes:
            procs.pop(lane, None)
        if getattr(holder, "proc", None) is not None and holder.proc not in procs.values():
            holder.proc = next(iter(procs.values()), None)

    if lock is None:
        _drop()
        return
    with lock:
        _drop()


def _safe_execute_task(active: ActiveRun, rec: dict, task: dict, logf) -> None:
    t0 = time.monotonic()
    try:
        _execute_task(active, rec, task, logf)
    except Exception as exc:  # noqa: BLE001
        task["state"] = "failed"
        if task.get("duration_s") is None:
            task["duration_s"] = round(time.monotonic() - t0, 2)
        task["error"] = f"{type(exc).__name__}: {exc}"
        if isinstance(exc, SetupRestoreError):
            task["setup_restore_failed"] = True
            task["note"] = str(exc)
        _unbind_lane_proc(active, task)
        active.finish_task_rows(task, False)
        active.flush()


def _task_platforms(rec: dict) -> list[str]:
    found: list[str] = []
    for task in rec.get("tasks") or []:
        plat = str(task.get("platform") or "")
        if plat in {"android", "ios"} and plat not in found:
            found.append(plat)
    return found


def _read_installed_packages(rec: dict, logf) -> str:
    """Fill rec['packages']. Return a refusal reason when a version does not match."""
    expected_name, expected_code = product_version()
    rec["product_version"] = {"name": expected_name, "code": expected_code}
    packages: dict[str, dict] = {}
    reasons: list[str] = []
    device = str(rec.get("device") or "auto")
    wanted = None if device in {"", "auto"} else device
    for plat in _task_platforms(rec):
        try:
            chosen = resolve_device(plat, wanted)
            if plat == "android":
                info = android_installed(str(chosen["id"]))
            else:
                info = ios_installed(str(chosen["id"]))
        except (OSError, ValueError, subprocess.TimeoutExpired, RuntimeError) as exc:
            reasons.append(f"{plat}: {exc}")
            continue
        packages[plat] = info
        if info.get("version") != expected_name or info.get("version_code") != expected_code:
            reasons.append(
                f"{plat} {info.get('package')} is {info.get('version')} "
                f"({info.get('version_code')}), ProductVersion is {expected_name} ({expected_code})"
            )
    rec["packages"] = packages
    if not reasons:
        logf.write(f"packages match ProductVersion {expected_name} ({expected_code})\n")
        for plat, info in packages.items():
            logf.write(
                f"  {plat} {info.get('package')} {info.get('version')} "
                f"{info.get('version_code')} sha256 {info.get('sha256')}\n"
            )
        logf.flush()
        return ""
    reason = " ".join(reasons)
    rec["package_refusal"] = reason
    logf.write(f"package refused: {reason}\n")
    logf.flush()
    for task in rec.get("tasks") or []:
        if task.get("state") in {None, "pending"}:
            task["state"] = "blocked"
            task["note"] = reason
    return reason


def run_record(rec: dict) -> int:
    """Run every task in this process. One failure does not skip the rest."""
    active = ActiveRun(rec)
    log_path = REPO_ROOT / rec["log"]
    log_path.parent.mkdir(parents=True, exist_ok=True)
    exit_code = 0

    def _interrupt(_signum=None, _frame=None) -> None:
        active.stop_requested = True
        with active.lock:
            procs = list(active.procs.values())
            proc = active.proc
            active.cv.notify_all()
        terminate_process(proc)
        for item in procs:
            terminate_process(item)

    prev_int = signal.signal(signal.SIGINT, _interrupt)
    prev_term = signal.signal(signal.SIGTERM, _interrupt)
    try:
        with log_path.open("a", encoding="utf-8") as logf:
            with active.log_lock:
                logf.write(f"# run {rec['id']} started {rec['started']} pid {rec.get('pid')}\n")
                logf.flush()
            if _read_installed_packages(rec, logf):
                finalize_record(rec)
                active.flush()
                print(f"state={rec['state']}", flush=True)
                return 1
            write_record(rec)
            lanes: dict[str, list[dict]] = {"android": [], "ios": [], "host": []}
            for task in rec["tasks"]:
                lanes[_task_lane(task_run_spec(task))].append(task)
            threads: list[threading.Thread] = []
            for name, group in lanes.items():
                if not group:
                    continue

                def _lane(tasks=group) -> None:
                    for task in tasks:
                        if active.stop_requested:
                            task["state"] = "stopped"
                            active.flush()
                            continue
                        _safe_execute_task(active, rec, task, logf)

                thread = threading.Thread(target=_lane, daemon=True, name=f"testmap-lane-{name}")
                threads.append(thread)
                thread.start()
            for thread in threads:
                thread.join()
            for task in rec["tasks"]:
                if task["state"] == "pending":
                    task["state"] = "stopped"
                    if exit_code == 0:
                        exit_code = 130
                elif task["state"] in {"failed", "blocked"} and exit_code == 0:
                    exit_code = 1
                elif task.get("exit_code") not in (0, None) and exit_code == 0 and task["state"] == "failed":
                    exit_code = int(task["exit_code"])
        finalize_record(rec)
        exit_code = cli_exit_code(rec, exit_code)
        active.flush()
        print(f"state={rec['state']}", flush=True)
        return exit_code
    finally:
        signal.signal(signal.SIGINT, prev_int)
        signal.signal(signal.SIGTERM, prev_term)
        try:
            with log_path.open("a", encoding="utf-8") as logf:
                close_ios_sessions_after_run(rec, logf)
        except Exception as exc:  # noqa: BLE001
            print(f"ios session close failed: {exc}", file=sys.stderr)


def run_foreground(
    task_ids: list[str],
    device: str | None = None,
    source: str = "manual",
    repeats: int = 1,
) -> int:
    """Run tasks in this process. The record is on disk before the first case."""
    origin = source if source in {"manual", "select", "verify"} else "manual"
    rec = new_record(expand_mobile_parallel(task_ids, device), device)
    rec["source"] = origin
    rec["pid"] = os.getpid()
    rec["tasks"] = _repeat_tasks(rec["tasks"], repeats)
    _isolate_agent_outputs(rec["tasks"])
    write_record(rec)
    print(f"testmap run {rec['id']}  (not a CI gate)", flush=True)
    code = run_record(rec)
    print(f"wrote {(RUNS_DIR / (rec['id'] + '.json')).relative_to(REPO_ROOT)}", flush=True)
    return code


def _self_check_confirm_cover() -> list[str]:
    """Function-level confirm/cover checks. Writes only under a temp RUNS_DIR."""
    global RUNS_DIR, CONFIRMATIONS_PATH, _edge_result_cache, write_historical_projection
    global _execute_task, load_status_record
    errors: list[str] = []
    tmp = Path(tempfile.mkdtemp(prefix="ewm-confirm-"))
    old_runs = RUNS_DIR
    old_conf = CONFIRMATIONS_PATH
    old_cache = _edge_result_cache
    old_hist = write_historical_projection
    synth = [{"id": "about"}, {"id": "about-x"}]

    def write_run(
        run_id: str,
        state: str,
        tasks: list[dict],
        selection: list[str] | None = None,
        pid: int | None = None,
        bust_cache: bool = True,
    ) -> None:
        global _edge_result_cache
        rec = {
            "id": run_id,
            "state": state,
            "selection": list(selection or []),
            "tasks": tasks,
            "started": "2026-09-25T00:00:00Z",
            "finished": None if state in _UNFINISHED_RUN_STATES else "2026-09-25T00:01:00Z",
        }
        if pid:
            rec["pid"] = pid
        (RUNS_DIR / f"{run_id}.json").write_text(json.dumps(rec) + "\n", encoding="utf-8")
        if bust_cache:
            _edge_result_cache = None

    def task(edge: str, state: str, plat: str = "android", duration_s: float | None = None) -> dict:
        row = {
            "id": f"edge:{edge}@{plat}#agent",
            "edge": edge,
            "state": state,
            "platform": plat,
        }
        if duration_s is not None:
            row["duration_s"] = duration_s
        return row

    def expect_reject(
        run_id: str,
        tasks: list[dict],
        state: str,
        needle: str,
        pid: int | None = None,
    ) -> None:
        write_run(run_id, state, tasks, pid=pid)
        try:
            record_confirmation("about", run_id, edges=synth)
            errors.append(f"{run_id} should reject")
        except ValueError as exc:
            if needle not in str(exc):
                errors.append(f"{run_id} error {exc!r} missing {needle!r}")

    try:
        RUNS_DIR = tmp
        CONFIRMATIONS_PATH = tmp / "confirmations.json"
        _edge_result_cache = None
        write_historical_projection = lambda: RUNS_DIR  # noqa: E731

        write_run(
            "20260925T010000-aaa0001",
            "passed",
            [task("about", "passed")],
            ["edge:about@android#agent"],
        )
        row = record_confirmation("about", "20260925T010000-aaa0001", edges=synth)
        if row.get("run_id") != "20260925T010000-aaa0001":
            errors.append("passed run should confirm")

        write_run("20260925T010100-aaa0002", "passed", [task("about", "review_required")])
        record_confirmation("about", "20260925T010100-aaa0002", edges=synth)

        expect_reject("20260925T010200-aaa0003", [task("about", "failed")], "failed", "is failed")
        expect_reject("20260925T010300-aaa0004", [task("about", "uncovered")], "passed", "is uncovered")
        expect_reject(
            "20260925T010400-aaa0005",
            [task("about", "stopped", duration_s=0.2)],
            "stopped",
            "is stopped",
        )
        expect_reject(
            "20260925T010500-aaa0006",
            [task("about", "running")],
            "running",
            "is running",
            pid=os.getpid(),
        )
        expect_reject(
            "20260925T010600-aaa0007",
            [task("about", "passed")],
            "paused",
            "is paused",
            pid=os.getpid(),
        )
        expect_reject("20260925T010700-aaa0008", [task("about", "pending")], "pending", "is pending")
        expect_reject(
            "20260925T010800-aaa0009",
            [task("about", "passed", "android"), task("about", "failed", "ios")],
            "failed",
            "is failed",
        )

        write_run(
            "20260925T010900-aaa0010",
            "passed",
            [task("about-x", "passed")],
            ["edge:about-x@android#agent"],
        )
        rec_x = load_run("20260925T010900-aaa0010")
        if rec_x and edge_result_in_run(rec_x, "about"):
            errors.append("about-x must not yield a result for about")
        if rec_x and not edge_result_in_run(rec_x, "about-x"):
            errors.append("about-x should yield a result for about-x")
        try:
            record_confirmation("about", "20260925T010900-aaa0010", edges=synth)
            errors.append("about-x run should not confirm about")
        except ValueError as exc:
            if "missing" not in str(exc):
                errors.append(f"about-x confirm about: {exc}")

        write_run("20260925T011000-aaa0011", "passed", [task("about", "passed")])
        record_confirmation("about", "20260925T011000-aaa0011", edges=synth)
        write_run(
            "20260925T011100-aaa0012",
            "passed",
            [{"id": "l1-desktop", "state": "passed", "cases": [{"ref": "TestMapGuardTest.foo"}]}],
            ["l1-desktop"],
        )
        views = confirmation_views(synth)
        about = views.get("about") or {}
        if about.get("stale"):
            errors.append("L1 desktop run must not stale about confirmation")
        if about.get("latest_run") != "20260925T011000-aaa0011":
            errors.append(f"latest_run for about is {about.get('latest_run')}")

        expect_reject(
            "20260925T011200-aaa0013",
            [task("about", "passed", "android"), task("about", "interrupted", "ios")],
            "passed",
            "is interrupted",
        )
        expect_reject(
            "20260925T011300-aaa0014",
            [task("about", "passed", "android"), task("about", "pending", "ios")],
            "passed",
            "is pending",
        )

        write_run("20260925T011400-aaa0015", "passed", [task("about", "passed")])
        record_confirmation("about", "20260925T011400-aaa0015", edges=synth)
        write_run(
            "20260925T011500-aaa0016",
            "interrupted",
            [task("about", "interrupted")],
        )
        views = confirmation_views(synth)
        about = views.get("about") or {}
        if about.get("stale"):
            errors.append("interrupted run must not stale about confirmation")
        if about.get("latest_run") != "20260925T011400-aaa0015":
            errors.append(f"interrupted run became latest_run {about.get('latest_run')}")
        latest = edge_result_runs(synth)
        if latest.get("about") == "20260925T011500-aaa0016":
            errors.append("interrupted run appeared in result_runs")

        views = confirmation_views(synth)
        runs = edge_result_runs(synth)
        for eid, row in views.items():
            if row.get("latest_run") != runs.get(eid):
                errors.append(
                    f"{eid} confirmation latest_run {row.get('latest_run')} != result_runs {runs.get(eid)}"
                )

        _ = latest_edge_results(synth)
        old_results = dict(latest_edge_results(synth))
        old_runs = dict(edge_result_runs(synth))
        old_cover = (confirmation_views(synth).get("about") or {}).get("latest_run")
        write_run(
            "20260925T011700-aaa0017",
            "passed",
            [task("about", "failed")],
            bust_cache=False,
        )
        if latest_edge_results(synth) != old_results:
            errors.append("results changed while cache is warm")
        if edge_result_runs(synth) != old_runs:
            errors.append("result_runs changed while cache is warm")
        if (confirmation_views(synth).get("about") or {}).get("latest_run") != old_cover:
            errors.append("confirmation latest_run changed while cache is warm")
        _edge_result_cache = None
        if latest_edge_results(synth).get("about") != "failed":
            errors.append("results did not update after cache expired")
        if edge_result_runs(synth).get("about") != "20260925T011700-aaa0017":
            errors.append("result_runs did not update after cache expired")
        if (confirmation_views(synth).get("about") or {}).get("latest_run") != "20260925T011700-aaa0017":
            errors.append("confirmation latest_run did not update after cache expired")

        import testmap_console as console

        tok_a = console._issue_confirm_token()
        tok_b = console._issue_confirm_token()
        if not console._confirm_token_ok(tok_a) or not console._confirm_token_ok(tok_b):
            errors.append("two issued tokens should both be valid")
        issued = [console._issue_confirm_token() for _ in range(65)]
        if console._confirm_token_ok(issued[0]):
            errors.append("oldest token should drop after 64 new ones")
        if not all(console._confirm_token_ok(tok) for tok in issued[-64:]):
            errors.append("newest 64 tokens should stay valid")

        expect_reject(
            "20260925T011800-aaa0018",
            [task("about", "passed"), task("about", "ghost", "ios")],
            "passed",
            "is ghost",
        )

        write_run("20260925T011900-aaa0019", "passed", [task("about", "passed")])
        record_confirmation("about", "20260925T011900-aaa0019", edges=synth)
        write_run(
            "20260925T012000-aaa0020",
            "stopped",
            [task("about", "stopped")],
        )
        views = confirmation_views(synth)
        about = views.get("about") or {}
        if about.get("stale"):
            errors.append("never-started stopped task must not stale confirmation")
        if about.get("latest_run") != "20260925T011900-aaa0019":
            errors.append(f"never-started stopped became latest_run {about.get('latest_run')}")
        if edge_result_runs(synth).get("about") == "20260925T012000-aaa0020":
            errors.append("never-started stopped appeared in result_runs")
        write_run(
            "20260925T012100-aaa0021",
            "stopped",
            [task("about", "stopped", duration_s=0.4)],
        )
        if edge_result_in_run(load_run("20260925T012100-aaa0021"), "about") != "stopped":
            errors.append("started-then-stopped task should count as stopped")

        expect_reject(
            "20260925T012200-aaa0022",
            [task("about", "passed"), task("about", "running", "ios", duration_s=0.3)],
            "passed",
            "is running",
        )
        expect_reject(
            "20260925T012300-aaa0023",
            [task("about", "passed"), task("about", "paused", "ios", duration_s=0.3)],
            "passed",
            "is paused",
        )

        old_exec = _execute_task

        def boom(active, rec, task, logf):
            raise KeyError("lane-boom")

        _execute_task = boom
        try:
            class _Active:
                stop_requested = False
                proc = object()
                procs = {"android": object()}
                lock = threading.Lock()
                flushed = False
                finished = None

                def flush(self):
                    self.flushed = True

                def finish_task_rows(self, task, ok):
                    self.finished = (task, ok)

            boom_task = {
                "id": "edge:about@android#agent",
                "edge": "about",
                "state": "running",
                "platform": "android",
            }
            holder = _Active()
            _safe_execute_task(holder, {"id": "x", "state": "running"}, boom_task, io.StringIO())
            if boom_task.get("state") != "failed":
                errors.append("lane exception should mark task failed")
            if boom_task.get("duration_s") is None:
                errors.append("lane exception should set duration_s")
            if not holder.flushed:
                errors.append("lane exception should flush")
            if holder.finished != (boom_task, False):
                errors.append("lane exception should finish_task_rows(task, False)")
            if "android" in holder.procs:
                errors.append("lane exception should unbind active.procs")
        finally:
            _execute_task = old_exec

        dead_id = "20260925T012400-aaa0024"
        write_run(dead_id, "running", [task("about", "running", duration_s=0.2)], pid=999999999)
        old_load = load_status_record

        def fake_load(run_id=None):
            path = RUNS_DIR / f"{dead_id}.json"
            return json.loads(path.read_text(encoding="utf-8"))

        load_status_record = fake_load
        try:
            out = stop_recorded_run()
        finally:
            load_status_record = old_load
        if out.get("state") != "interrupted":
            errors.append(f"dead-pid stop_recorded_run state is {out.get('state')}")
        dead_rec = json.loads((RUNS_DIR / f"{dead_id}.json").read_text(encoding="utf-8"))
        if dead_rec.get("state") != "interrupted":
            errors.append(f"dead-pid record state is {dead_rec.get('state')}")

        live_id = "20260925T012500-aaa0025"
        write_run(
            live_id,
            "running",
            [task("about", "running", duration_s=0.2), task("about", "passed", "ios")],
            pid=os.getpid(),
        )
        old_kill = os.kill

        def deny_kill(pid, sig):
            raise PermissionError("Operation not permitted")

        def fake_live(run_id=None):
            path = RUNS_DIR / f"{live_id}.json"
            return json.loads(path.read_text(encoding="utf-8"))

        load_status_record = fake_live
        os.kill = deny_kill
        try:
            stop_recorded_run()
            errors.append("PermissionError on os.kill should raise StopForbiddenError")
        except StopForbiddenError as exc:
            if "cannot signal pid" not in str(exc):
                errors.append(f"StopForbiddenError message is {exc}")
        except Exception as exc:  # noqa: BLE001
            errors.append(f"PermissionError path raised {type(exc).__name__}: {exc}")
        finally:
            os.kill = old_kill
            load_status_record = old_load

        rec = {
            "id": "20260925T012600-aaa0026",
            "state": "running",
            "started": "2026-09-25T00:00:00Z",
            "tasks": [
                task("about", "running", duration_s=0.2),
                task("about", "passed", "ios"),
            ],
        }
        finalize_record(rec)
        code = cli_exit_code(rec, 0)
        if rec["tasks"][0]["state"] != "interrupted":
            errors.append("finalize should mark leftover running as interrupted")
        if rec.get("state") != "failed":
            errors.append(f"finalize state is {rec.get('state')}")
        if code == 0:
            errors.append("interrupted/failed record must have non-zero CLI exit")

        if tmp.resolve() != Path(CONFIRMATIONS_PATH).resolve().parent:
            errors.append("confirmations escaped the temp dir")
        if "docs/testmap/runs/confirmations.json" in str(CONFIRMATIONS_PATH):
            errors.append("CONFIRMATIONS_PATH still points at the real file")
    except Exception as exc:  # noqa: BLE001
        errors.append(f"confirm self-check crashed: {exc}")
    finally:
        RUNS_DIR = old_runs
        CONFIRMATIONS_PATH = old_conf
        _edge_result_cache = old_cache
        write_historical_projection = old_hist
        shutil.rmtree(tmp, ignore_errors=True)
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Foreground testmap runner (informational; not a CI gate)."
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="Print runnable task ids/labels and manual copy-only commands.",
    )
    parser.add_argument(
        "--self-check",
        action="store_true",
        help="Validate every suite and edge spawn command against task CLI rules.",
    )
    parser.add_argument(
        "--device",
        default="auto",
        help="Device id from `python3 scripts/testmap_devices.py`, or auto.",
    )
    parser.add_argument(
        "--source",
        default="manual",
        choices=("manual", "select", "verify"),
        help="Where the task list came from.",
    )
    parser.add_argument(
        "--repeat",
        type=int,
        default=1,
        help="Run each task this many times as separate k/N rows.",
    )
    parser.add_argument(
        "tasks",
        nargs="*",
        help="Runnable task ids (see --list).",
    )
    args = parser.parse_args(argv)
    if args.self_check:
        errors = validate_all_console_cmds()
        if artifact_png("live", "../preview.png") is not None:
            errors.append("artifact_png leaked live traversal")
        if artifact_png("keyframes", "../secret.png") is not None:
            errors.append("artifact_png leaked keyframe traversal")
        errors.extend(_self_check_confirm_cover())
        if errors:
            print("spawn command errors:", file=sys.stderr)
            for item in errors:
                print(f"  {item}", file=sys.stderr)
            return 1
        print("ok")
        return 0
    if args.list:
        print_task_list()
        return 0
    if not args.tasks:
        parser.print_help()
        return 2
    try:
        return run_foreground(args.tasks, args.device, source=args.source, repeats=args.repeat)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
