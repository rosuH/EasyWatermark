#!/usr/bin/env python3
"""ADR-0032 testmap run engine (stdlib only). Never a CI gate.

Importable by the local console, and runnable as a foreground CLI:

    python3 scripts/testmap_run.py --list
    python3 scripts/testmap_run.py guard
    python3 scripts/testmap_run.py guard l1-desktop
"""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import signal
import subprocess
import sys
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
    ensure_pinned_agent_device,
    ingest_agent_device_result,
    is_agent_device_cmd,
    maybe_prepare_ios_runner,
    validate_agent_device_binding,
)
from testmap_setup import ANDROID_SETUPS, IOS_SETUPS, apply_setup, restore_setup  # noqa: E402
from testmap_devices import default_watch_slots, ensure_device_ready  # noqa: E402
from testmap_steps import (  # noqa: E402
    apply_timing_line,
    apply_event,
    parse_script,
    public_steps,
)
from testmap_stop import (  # noqa: E402
    RUNNER_KILL_S,
    RUNNER_TERM_S,
    terminate_process_group,
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
    "Pause queue: do not dispatch the next task; the current Gradle process keeps running "
    "(Gradle cannot be safely suspended mid-test). "
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
    return {
        "id": tid,
        "label": spec["label"],
        "state": "pending",
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


def expand_mobile_parallel(task_ids: list[str]) -> list[str]:
    """If a mobile #agent edge is queued, also queue the other OS when supported."""
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


def write_record(rec: dict) -> Path:
    RUNS_DIR.mkdir(parents=True, exist_ok=True)
    path = RUNS_DIR / f"{rec['id']}.json"
    path.write_text(json.dumps(rec, indent=2) + "\n", encoding="utf-8")
    return path


def finalize_record(rec: dict) -> None:
    rec["finished"] = iso(utc_now())
    pass_n = fail_n = skip_n = 0
    for task in rec["tasks"]:
        for case in task.get("cases") or []:
            if case.get("status") == "failed":
                fail_n += 1
            elif case.get("status") == "passed":
                pass_n += 1
            elif case.get("status") == "skipped":
                skip_n += 1
    rec["pass_count"] = pass_n
    rec["fail_count"] = fail_n
    rec["skip_count"] = skip_n
    started_ts = _iso_ts(rec.get("started"))
    finished_ts = _iso_ts(rec.get("finished"))
    if started_ts is not None and finished_ts is not None:
        rec["duration_s"] = round(finished_ts - started_ts, 2)
    if any(t["state"] == "stopped" for t in rec["tasks"]) or rec["state"] == "paused":
        rec["state"] = "stopped"
    elif any(t["state"] == "failed" for t in rec["tasks"]):
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


def write_historical_projection() -> Path:
    rec = historical_projection()
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
        items.append(
            {
                "id": rec.get("id", path.stem),
                "started": rec.get("started"),
                "finished": rec.get("finished"),
                "state": rec.get("state"),
                "git": rec.get("git"),
                "selection": rec.get("selection"),
                "pass_count": rec.get("pass_count", 0),
                "fail_count": rec.get("fail_count", 0),
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
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None


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


def run_covers_edge(rec: dict, edge_id: str, edges: list[dict] | None = None) -> bool:
    if not rec:
        return False
    sel = rec.get("selection") or []
    if edge_id in sel or f"edge:{edge_id}" in sel:
        return True
    suffix = f"{edge_id}@android#artemis"
    if f"edge:{suffix}" in sel or any(str(item).endswith(suffix) for item in sel):
        return True
    for plat in ("android", "ios"):
        agent_suffix = f"{edge_id}@{plat}#agent"
        if f"edge:{agent_suffix}" in sel or any(
            str(item).endswith(agent_suffix) for item in sel
        ):
            return True
    for task in rec.get("tasks") or []:
        tid = str(task.get("id") or "")
        if edge_id in tid or tid.startswith(f"edge:{edge_id}"):
            return True
        for case in task.get("cases") or []:
            if str(case.get("name") or "") == edge_id:
                return True
            ref = str(case.get("ref") or "")
            if ref == f"Artemis.{edge_id}" or ref == f"AgentDevice.{edge_id}":
                return True
    edge = None
    if edges:
        edge = next((e for e in edges if e.get("id") == edge_id), None)
    has_l1 = bool(
        edge and any(c.get("layer") == "L1" for c in (edge.get("cases") or []))
    )
    if has_l1 and ("l1-desktop" in sel or "l0l1-desktop-full" in sel):
        return True
    wanted: set[str] = set()
    if edge:
        for case in edge.get("cases") or []:
            ref = str(case.get("ref") or "")
            if case.get("layer") == "L1" or ref.startswith("TestMapGuardTest"):
                wanted.add(ref)
    if wanted:
        for task in rec.get("tasks") or []:
            for case in task.get("cases") or []:
                if case.get("ref") in wanted:
                    return True
    return False


def latest_covering_run_id(edge_id: str, edges: list[dict] | None = None) -> str | None:
    for item in run_summaries():
        rec = load_run(item["id"])
        if rec and run_covers_edge(rec, edge_id, edges):
            return rec.get("id")
    return None


def confirmation_views(edges: list[dict]) -> dict[str, dict]:
    out: dict[str, dict] = {}
    for eid, rec in load_confirmations().items():
        cover = latest_covering_run_id(eid, edges)
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
    if not run_covers_edge(rec, edge_id, edges):
        raise ValueError(f"run {run_id} does not cover {edge_id}")
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


def _tee_child(proc: subprocess.Popen, logf, tee_stdout: bool) -> int:
    assert proc.stdout is not None
    while True:
        chunk = proc.stdout.read(4096)
        if not chunk:
            break
        logf.write(chunk)
        logf.flush()
        if tee_stdout:
            sys.stdout.write(chunk)
            sys.stdout.flush()
    return proc.wait()


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


def _apply_agent_setup(spec: dict, logf) -> dict | None:
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
    return apply_setup(
        str(setup),
        platform=str(platform),
        folder=folder,
        marker=marker,
        serial=serial,
        udid=udid,
    )


def _restore_agent_setup(state: dict | None, logf) -> None:
    if not state:
        return
    try:
        restore_setup(state)
    except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
        if logf is not None:
            logf.write(f"setup restore failed: {exc}\n")
            logf.flush()


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
    """Extract a watch target from a task cmd / device dict. Ignores placeholders."""
    if not isinstance(task, dict):
        return None
    cmd = list(task.get("cmd") or [])
    platform = None
    device = None
    if "--platform" in cmd:
        i = cmd.index("--platform")
        if i + 1 < len(cmd):
            platform = str(cmd[i + 1])
    if "--serial" in cmd:
        i = cmd.index("--serial")
        if i + 1 < len(cmd):
            device = str(cmd[i + 1])
            platform = platform or "android"
    if "--udid" in cmd:
        i = cmd.index("--udid")
        if i + 1 < len(cmd):
            device = str(cmd[i + 1])
            platform = platform or "ios"
    spec_dev = task.get("device") if isinstance(task.get("device"), dict) else {}
    if not device:
        device = str(spec_dev.get("id") or "")
    if not platform:
        platform = str(spec_dev.get("platform") or "")
    if not device or device in {"auto", "<device>", ""}:
        return None
    if platform not in {"android", "ios"}:
        return None
    builder = task.get("builder")
    if not builder and is_agent_device_cmd(cmd):
        builder = "agent-device"
    return {
        "platform": platform,
        "device": device,
        "task_id": task.get("id"),
        "builder": builder,
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
    payload = json.dumps([{"command": step["command"], "input": step.get("input") or {}}])
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


def _tail_timing(root: Path, on_line, stop: threading.Event) -> None:
    offset = 0
    pending = ""
    path: Path | None = None
    while not stop.is_set():
        if path is None or not path.is_file():
            found = sorted(root.glob("**/replay-timing.ndjson"))
            path = found[-1] if found else None
            offset = 0
            pending = ""
        if path is not None and path.is_file():
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
        stop.wait(0.2)


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
            code = _tee_child(proc, _DupWrite(logf, buf), tee_stdout)
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
            maybe_prepare_ios_runner(spec, logf)
            setup_state = _apply_agent_setup(spec, logf)
        except (ValueError, RuntimeError, OSError, subprocess.TimeoutExpired) as exc:
            logf.write(f"agent-device prepare/setup failed: {exc}\n")
            logf.flush()
            if tee_stdout:
                print(f"agent-device prepare/setup failed: {exc}", file=sys.stderr)
            extra = ingest_agent_device_result(spec, 2)
            _restore_agent_setup(setup_state, logf)
            return 2, extra.get("cases") or [], extra
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
    env = os.environ.copy()
    env.update(spec.get("env") or {})
    if spec.get("builder") == "agent-device" and rows and script and script.suffix == ".json":
        code = _run_batched_steps(
            cmd, rows, logf, tee_stdout, on_proc, on_step_event, should_stop, watch_plat, env
        )
        _restore_agent_setup(setup_state, logf)
        extra = ingest_agent_device_result(spec, code)
        _publish_agent_device_live(spec)
        return code, extra.get("cases") or [], extra
    output = Path(spec.get("agent_device_output") or "")
    if spec.get("builder") == "agent-device" and "replay" in cmd and output.parts:
        output.mkdir(parents=True, exist_ok=True)
        cmd = _replay_as_test(cmd, output)
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
        _restore_agent_setup(setup_state, logf)
        return 127, extra.get("cases") or [], extra
    if on_proc:
        on_proc(proc)
    stop_tail = threading.Event()
    tail = None
    if spec.get("builder") == "agent-device" and rows and output.parts:
        tail = threading.Thread(
            target=_tail_timing,
            args=(output, (lambda line: on_step_event(watch_plat, line)) if on_step_event else None, stop_tail),
            daemon=True,
            name="testmap-steps",
        )
        tail.start()
    try:
        code = _tee_child(proc, sink, tee_stdout)
    finally:
        stop_tail.set()
        if tail is not None:
            tail.join(timeout=1)
        if replay_log is not None:
            replay_log.close()
        if on_proc:
            on_proc(None)
        _restore_agent_setup(setup_state, logf)
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


class BusyError(Exception):
    def __init__(self, run_id: str):
        super().__init__(f"a run is already active ({run_id})")
        self.run_id = run_id


class RunManager:
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
        slots = default_watch_slots()
        out: dict[str, dict | None] = {}
        for plat, item in slots.items():
            overlay = self.last_watches.get(plat)
            if plat == "ios" and overlay and str(overlay.get("device") or "").startswith("emulator-"):
                overlay = None
            if rec:
                for task in rec.get("tasks") or []:
                    w = watch_from_task(task)
                    if w and w.get("platform") == plat:
                        dev = str(w.get("device") or "")
                        if plat == "ios" and (dev.startswith("emulator-") or w.get("kind") == "emulator"):
                            continue
                        overlay = w
                        if task.get("state") == "running":
                            break
            if overlay and overlay.get("device"):
                dev = str(overlay.get("device") or "")
                if plat == "ios" and dev.startswith("emulator-"):
                    out[plat] = None
                    continue
                out[plat] = {
                    "platform": plat,
                    "device": overlay.get("device"),
                    "name": (item or {}).get("name") if item else overlay.get("device"),
                    "kind": overlay.get("kind") or (item or {}).get("kind"),
                }
            elif item:
                out[plat] = {
                    "platform": plat,
                    "device": item.get("id"),
                    "name": item.get("name"),
                    "kind": item.get("kind"),
                }
            else:
                out[plat] = None
        return out

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

    def _should_stop(self) -> bool:
        with self.lock:
            return self.stop_requested

    def snapshot(self) -> dict:
        with self.lock:
            rec = self.active
            watch = self._remember_watch(rec)
            if not rec:
                return {
                    "active": False,
                    "state": "idle",
                    "id": None,
                    "queue": [],
                    "current": None,
                    "elapsed_s": 0,
                    "log_tail": [],
                    "pause_queue": False,
                    "semantics": SEMANTICS,
                    "live": live_snapshot(),
                    "watch": watch,
                    "watches": self._watch_slots(None),
                    "steps": self._public_steps(),
                }
            current = None
            elapsed = 0.0
            for task in rec["tasks"]:
                if task["state"] == "running":
                    started = task.get("_started_mono") or time.monotonic()
                    elapsed = time.monotonic() - started
                    current = {
                        "id": task["id"],
                        "edge_id": task_edge_id(task["id"]),
                        "elapsed_s": round(elapsed, 1),
                    }
                    break
            log_path = Path(rec["log"]) if rec.get("log") else None
            if log_path and not log_path.is_absolute():
                log_path = REPO_ROOT / log_path
            return {
                "active": rec["state"] in {"running", "paused"},
                "state": rec["state"],
                "id": rec["id"],
                "queue": [
                    {
                        "id": t["id"],
                        "edge_id": task_edge_id(t["id"]),
                        "label": t["label"],
                        "state": t["state"],
                        "exit_code": t.get("exit_code"),
                        "duration_s": t.get("duration_s"),
                    }
                    for t in rec["tasks"]
                ],
                "current": current,
                "elapsed_s": round(elapsed, 1) if current else 0,
                "log_tail": log_tail(log_path),
                "pause_queue": self.pause_queue,
                "semantics": SEMANTICS,
                "live": live_snapshot(),
                "watch": watch,
                "watches": self._watch_slots(rec),
                "steps": self._public_steps(),
            }

    def start(self, task_ids: list[str], device: str | None = None) -> dict:
        with self.lock:
            if self.active and self.active.get("state") in {"running", "paused"}:
                raise BusyError(self.active["id"])
            rec = new_record(expand_mobile_parallel(task_ids), device)
            self.active = rec
            self.pause_queue = False
            self.stop_requested = False
            self.step_rows = {}
            self.proc = None
            self.procs = {}
            log_path = REPO_ROOT / rec["log"]
            self.worker = threading.Thread(
                target=self._worker, args=(rec, log_path), daemon=True, name="testmap-run"
            )
            self.worker.start()
            return {"id": rec["id"], "state": "running", "selection": list(rec["selection"])}

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
        with self.lock:
            if not self.active or self.active["state"] not in {"running", "paused"}:
                raise ValueError("no active run")
            self.stop_requested = True
            self.pause_queue = False
            proc = self.proc
            procs = list(self.procs.values())
            self.cv.notify_all()
        terminate_process(proc)
        for item in procs:
            terminate_process(item)
        return {"id": self.active["id"] if self.active else None, "state": "stopping"}

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

    def _run_lane(self, rec: dict, tasks: list[dict], logf) -> None:
        for task in tasks:
            self._execute_one_task(rec, task, logf)

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


def run_foreground(task_ids: list[str], device: str | None = None) -> int:
    """Sequential queue in this process. Ctrl-C writes state=stopped."""
    rec = new_record(task_ids, device)
    log_path = REPO_ROOT / rec["log"]
    log_path.parent.mkdir(parents=True, exist_ok=True)
    current: dict = {"proc": None, "stop": False}

    def _on_proc(proc: subprocess.Popen | None) -> None:
        current["proc"] = proc

    def _interrupt(_signum=None, _frame=None) -> None:
        current["stop"] = True
        terminate_process(current["proc"])

    prev_int = signal.signal(signal.SIGINT, _interrupt)
    prev_term = signal.signal(signal.SIGTERM, _interrupt)
    exit_code = 0
    try:
        with log_path.open("a", encoding="utf-8") as logf:
            logf.write(f"# run {rec['id']} started {rec['started']}\n")
            logf.flush()
            print(f"testmap run {rec['id']}  (not a CI gate)", flush=True)
            for task in rec["tasks"]:
                if current["stop"]:
                    task["state"] = "stopped"
                    continue
                spec = task_run_spec(task)
                rec["state"] = "running"
                task["state"] = "running"
                task["_started_mono"] = time.monotonic()
                t0 = time.monotonic()
                header = f"\n## {task['id']}: {' '.join(spec['cmd'])}\n"
                logf.write(header)
                logf.flush()
                sys.stdout.write(header)
                sys.stdout.flush()
                code, cases, extra = run_task(spec, logf, tee_stdout=True, on_proc=_on_proc)
                current["proc"] = None
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
                if current["stop"]:
                    task["state"] = "stopped"
                    if exit_code == 0:
                        exit_code = code if code not in (0, None) else 130
                elif spec.get("builder") in {"artemis", "agent-device"}:
                    task["state"] = _agent_task_state(spec, extra)
                    if task["state"] in {"failed", "blocked"} and exit_code == 0:
                        exit_code = 1
                elif code == 0:
                    task["state"] = "passed"
                else:
                    task["state"] = "failed"
                    if exit_code == 0:
                        exit_code = code
            for task in rec["tasks"]:
                if task["state"] == "pending":
                    task["state"] = "stopped"
                    if exit_code == 0:
                        exit_code = 130
        finalize_record(rec)
        print(f"wrote {log_path.relative_to(REPO_ROOT)}", flush=True)
        print(f"wrote {(RUNS_DIR / (rec['id'] + '.json')).relative_to(REPO_ROOT)}", flush=True)
        print(f"state={rec['state']}", flush=True)
        return exit_code
    finally:
        signal.signal(signal.SIGINT, prev_int)
        signal.signal(signal.SIGTERM, prev_term)


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
        return run_foreground(args.tasks, args.device)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
