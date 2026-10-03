#!/usr/bin/env python3
"""ADR-0032 testmap run engine (stdlib only). Never a CI gate.

Importable by the local console, and runnable as a foreground CLI:

    python3 scripts/testmap_run.py --list
    python3 scripts/testmap_run.py guard
    python3 scripts/testmap_run.py guard l1-desktop
"""

from __future__ import annotations

import argparse
import codecs
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
    ingest_agent_device_result,
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
    public_steps,
    materialize_evidence_script,
    EvidenceEvents,
    record_sdk_plan_digest,
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
    state = apply_setup(
        str(setup),
        platform=str(platform),
        folder=folder,
        marker=marker,
        serial=serial,
        udid=udid,
        should_stop=should_stop,
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
    surface = _cancel_surface_text(task)
    if surface and any(token in surface for token in _CANCEL_COMPLETION):
        task["state"] = "uncovered"
        task["note"] = "未覆盖取消"


_IOS_EXPORT_UNCOVERED = {
    "export-failure-recovery": "iOS 上没有可以触发导出失败的接缝",
    "export-cancel": "iOS 上没有可以触发慢速导出或取消的接缝",
}


def _mark_ios_export_uncovered(task: dict) -> None:
    """These iOS edges cannot be forced. They are gaps, not failures."""
    if task.get("platform") != "ios":
        return
    reason = _IOS_EXPORT_UNCOVERED.get(str(task.get("edge_id") or ""))
    if not reason:
        return
    if task.get("state") not in {"failed", "review_required", "passed", "uncovered"}:
        return
    task["state"] = "uncovered"
    task["note"] = reason


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
        if tok == "--json":
            flags.append(tok)
        i += 1
    payload = json.dumps([step.get("batch_step") or {"command": step["command"], "input": step.get("input") or {}}])
    return [cmd[0], "batch", "--steps", payload, *flags]


def _batch_ok(text: str, code: int) -> bool:
    raw = text.strip()
    if raw.startswith("{"):
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError:
            return code == 0
        if isinstance(obj, dict) and "success" in obj:
            return obj.get("success") is True and code == 0
    return code == 0


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
            code = _tee_child(proc, _DupWrite(logf, buf), tee_stdout, deadline_s=420)
        finally:
            if on_proc:
                on_proc(None)
        ok = _batch_ok(buf.getvalue(), code)
        if on_step_event:
            event = {"type": "replay_action_stop", "step": row["n"], "ok": ok}
            if row.get("point"):
                event["x"] = row["point"]["x"]
                event["y"] = row["point"]["y"]
            on_step_event(platform, event)
        if not ok:
            return code or 1
    return 0


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
            code = _run_batched_steps(
                cmd, parse_script(Path(evidence_manifest["script"]), watch_plat) if evidence_manifest else rows,
                logf, tee_stdout, on_proc,
                (lambda _plat, event: evidence_events(event)) if evidence_events else on_step_event,
                should_stop, watch_plat, env
            )
        finally:
            try:
                if not (should_stop and should_stop()) and spec.get("edge_id") == "export-cancel":
                    _capture_cancel_surface(cmd, spec, logf, should_stop=should_stop)
            finally:
                try:
                    _restore_agent_setup(setup_state, logf)
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
