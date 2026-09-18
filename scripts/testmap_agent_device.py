#!/usr/bin/env python3
"""Bind Agent Device replay to the testmap (stdlib only).

docs/testmap/map.yaml is the topology. docs/testing/agent-device-cases.json is
the execution payload, keyed 1:1 by original edge id. It is not a second map.

Drive labels in map.yaml stay real|seam|none. Walking a system picker, PHPicker,
share sheet, or FileDialog does not rewrite drive from seam/none to real.

Pinned CLI: agent-device 0.21.2 via AGENT_DEVICE_BIN or PATH. Never npx @latest.
Android: --serial. iOS: --udid. Session: testmap-<edge>. Desktop is out of scope.

Status layers (never collapse to replay 0):
  execution          child process / timeout / interrupt
  script_checks      export / file checks from result.json
  agent_observation  replay / REPLAY_DIVERGENCE (not a product pass)
  independent_review review.json (not human confirmation)
  human_confirmation always false here; console POST /api/confirm only
  business           product verdict; Add More stays failed on the historical run
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from testmap_artemis import (
    load_result_json,
    newest_png,
    recording_index,
    task_state_from_layers,
    verdict_layers,
)

REPO_ROOT = Path(__file__).resolve().parents[1]
AGENT_DEVICE_CASES_PATH = REPO_ROOT / "docs" / "testing" / "agent-device-cases.json"
DEFAULT_EVIDENCE_ROOT = REPO_ROOT / "build" / "agent-device"
SCRIPT_ROOT = REPO_ROOT / "docs" / "testing" / "agent-device" / "scripts"
PINNED_VERSION = "0.21.2"
ANDROID_PACKAGE = "me.rosuh.easywatermark.debug"
IOS_PACKAGE = "me.rosuh.easywatermark.ios"
AGENT_PLATFORMS = ("android", "ios")
SETUP_KEYS = frozenset({"home", "editor", "wide", "crash", "failure", "ios"})
INGEST_LAYERS = (
    "execution",
    "script_checks",
    "agent_observation",
    "independent_review",
    "human_confirmation",
    "business",
)

_prepared_udids: set[str] = set()


def session_name(edge_id: str, platform: str | None = None) -> str:
    if platform:
        return f"testmap-{edge_id}-{platform}"
    return f"testmap-{edge_id}"


def package_for(platform: str) -> str:
    if platform == "android":
        return ANDROID_PACKAGE
    if platform == "ios":
        return IOS_PACKAGE
    raise ValueError(f"Agent Device package is android/ios only, got {platform}")


def load_agent_device_manifest(path: Path | None = None) -> dict:
    target = path or AGENT_DEVICE_CASES_PATH
    if not target.is_file():
        raise ValueError(f"missing Agent Device payload {target}")
    data = json.loads(target.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("cases"), list):
        raise ValueError("agent-device-cases.json must be an object with a cases list")
    return data


def agent_device_cases_by_id(manifest: dict | None = None) -> dict[str, dict]:
    data = manifest if manifest is not None else load_agent_device_manifest()
    out: dict[str, dict] = {}
    for case in data.get("cases") or []:
        if not isinstance(case, dict) or not case.get("id"):
            raise ValueError("each Agent Device case needs an id")
        cid = str(case["id"])
        if cid in out:
            raise ValueError(f"duplicate Agent Device case id {cid}")
        out[cid] = case
    return out


def case_platforms(case: dict) -> list[str]:
    raw = case.get("platforms")
    plats: list[str] = []
    if isinstance(raw, list) and raw:
        plats = [str(p) for p in raw]
    else:
        single = case.get("platform")
        if isinstance(single, str) and single.strip():
            plats = [p.strip() for p in single.split(",") if p.strip()]
    out: list[str] = []
    seen: set[str] = set()
    cid = case.get("id")
    for plat in plats:
        if plat == "desktop":
            raise ValueError(
                f"Agent Device case {cid!r} must not include desktop"
            )
        if plat not in AGENT_PLATFORMS:
            raise ValueError(
                f"Agent Device case {cid!r} unknown platform {plat!r}"
            )
        if plat not in seen:
            seen.add(plat)
            out.append(plat)
    return out


def case_supports_platform(case: dict, platform: str) -> bool:
    if platform not in case_platforms(case):
        return False
    flags = case.get("supported")
    if isinstance(flags, dict):
        return bool(flags.get(platform))
    if isinstance(flags, bool):
        return flags
    return False


def agent_platforms_for_edge(
    edge_id: str, manifest: dict | None = None
) -> list[str]:
    cases = agent_device_cases_by_id(manifest)
    case = cases.get(edge_id)
    if not case:
        raise ValueError(f"unknown Agent Device case: {edge_id}")
    return case_platforms(case)


def default_script_path(edge_id: str, platform: str) -> Path:
    return (
        REPO_ROOT
        / "docs"
        / "testing"
        / "agent-device"
        / "scripts"
        / f"{edge_id}@{platform}.ad"
    )


def case_script_path(case: dict, platform: str) -> Path:
    scripts = case.get("scripts")
    script = case.get("script")
    rel = None
    if isinstance(scripts, dict):
        rel = scripts.get(platform)
    elif isinstance(script, dict):
        rel = script.get(platform)
    elif isinstance(script, str) and script.strip():
        rel = script.strip()
    if not rel:
        return default_script_path(str(case["id"]), platform)
    path = Path(rel)
    return path if path.is_absolute() else REPO_ROOT / path


def _edge_id_list(edge_ids: list) -> list[str]:
    out: list[str] = []
    for item in edge_ids:
        if isinstance(item, dict):
            cid = item.get("id")
            if not cid:
                raise ValueError("map edge is missing id")
            out.append(str(cid))
        else:
            out.append(str(item))
    return out


def validate_agent_device_binding(
    edge_ids: list, manifest: dict | None = None
) -> None:
    """Fail if cases.json is a second topology, misses a map edge, or lacks .ad files."""
    data = manifest if manifest is not None else load_agent_device_manifest()
    source_path = data.get("source_path")
    if source_path != "docs/testmap/map.yaml":
        raise ValueError(
            "agent-device-cases.json source_path must be docs/testmap/map.yaml, "
            f"got {source_path!r}"
        )
    if data.get("cli_pin") != PINNED_VERSION:
        raise ValueError(
            f"agent-device-cases.json cli_pin must be {PINNED_VERSION}, "
            f"got {data.get('cli_pin')!r}"
        )
    layers = data.get("ingest_layers")
    if layers is not None and list(layers) != list(INGEST_LAYERS):
        raise ValueError(
            "agent-device-cases.json ingest_layers must stay "
            f"{list(INGEST_LAYERS)}, got {layers!r}"
        )
    cases = agent_device_cases_by_id(data)
    wanted = _edge_id_list(edge_ids)
    got = list(cases)
    missing = [eid for eid in wanted if eid not in cases]
    extra = [cid for cid in got if cid not in set(wanted)]
    if missing or extra:
        raise ValueError(
            "Agent Device payload is not 1:1 with map edges; "
            f"missing={missing} extra={extra}"
        )
    script_root = SCRIPT_ROOT.resolve()
    for cid, case in cases.items():
        edges = case.get("edges")
        if edges != [cid]:
            raise ValueError(
                f"Agent Device case {cid} edges must be [{cid!r}] "
                f"(traceability only), got {edges!r}"
            )
        plats = case_platforms(case)
        if not plats:
            raise ValueError(f"Agent Device case {cid} needs platforms")
        setup = case.get("setup")
        if setup not in SETUP_KEYS:
            raise ValueError(f"Agent Device case {cid} has unknown setup {setup!r}")
        supported = case.get("supported")
        if not isinstance(supported, dict):
            raise ValueError(f"Agent Device case {cid} supported must be per-platform")
        for plat in plats:
            if plat not in supported or not isinstance(supported[plat], bool):
                raise ValueError(f"Agent Device case {cid} missing supported.{plat}")
            if not supported[plat]:
                reason = case.get("reason") or {}
                if not (isinstance(reason, dict) and reason.get(plat)):
                    raise ValueError(
                        f"Agent Device case {cid} unsupported {plat} needs reason"
                    )
            path = case_script_path(case, plat)
            if not path.is_file():
                raise ValueError(f"Agent Device case {cid} missing script {path}")
            try:
                path.resolve().relative_to(script_root)
            except ValueError as exc:
                raise ValueError(
                    f"Agent Device case {cid} script must live under {SCRIPT_ROOT}"
                ) from exc
            allowed = (f"@{plat}.ad", f"@{plat}.json")
            if not path.name.endswith(allowed):
                raise ValueError(
                    f"Agent Device case {cid} script.{plat} must be "
                    f"<edge>@{plat}.ad or <edge>@{plat}.json"
                )


def evidence_dir_for(edge_id: str, *, stamp: str | None = None) -> Path:
    stamp = stamp or datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")
    root = DEFAULT_EVIDENCE_ROOT
    base = root / stamp / edge_id
    if not base.exists():
        return base
    n = 2
    while True:
        candidate = root / f"{stamp}-{n}" / edge_id
        if not candidate.exists():
            return candidate
        n += 1


def agent_device_enabled() -> bool:
    return os.environ.get("TESTMAP_AGENT_DEVICE") != "0"


def agent_device_bin() -> str:
    env = (os.environ.get("AGENT_DEVICE_BIN") or "").strip()
    if env:
        lowered = env.lower()
        if "npx" in lowered.split() or "@latest" in lowered:
            raise ValueError(
                "AGENT_DEVICE_BIN must be the agent-device binary, not npx @latest"
            )
        return env
    found = shutil.which("agent-device")
    if found:
        return found
    return "agent-device"


def is_agent_device_cmd(cmd: list[str]) -> bool:
    if not cmd:
        return False
    joined = " ".join(cmd)
    if "npx" in cmd[0] or "npx " in joined or "@latest" in joined:
        return False
    return Path(cmd[0]).name == "agent-device"


def agent_device_version(bin_path: str | None = None) -> str:
    binary = bin_path or agent_device_bin()
    try:
        proc = subprocess.run(
            [binary, "--version"],
            text=True,
            capture_output=True,
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    text = (proc.stdout or proc.stderr or "").strip()
    if not text:
        return ""
    return text.split()[-1]


def ensure_pinned_agent_device(bin_path: str | None = None) -> str:
    binary = bin_path or agent_device_bin()
    if os.environ.get("TESTMAP_AGENT_DEVICE_ALLOW_VERSION") == "1":
        return binary
    ver = agent_device_version(binary)
    if ver != PINNED_VERSION:
        raise ValueError(
            f"agent-device pinned to {PINNED_VERSION}, got {ver or 'unknown'} "
            f"from {binary}"
        )
    return binary


def prepare_ios_runner_cmd(
    udid: str,
    *,
    session: str | None = None,
    bin_path: str | None = None,
) -> list[str]:
    if not udid or udid == "<device>":
        raise ValueError("iOS prepare ios-runner requires --udid")
    cmd = [
        bin_path or agent_device_bin(),
        "prepare",
        "ios-runner",
        "--platform",
        "ios",
        "--udid",
        udid,
    ]
    if session:
        cmd.extend(["--session", session])
    return cmd


def ios_prepare_needed(udid: str) -> bool:
    if not udid or udid == "<device>":
        return False
    if os.environ.get("TESTMAP_AGENT_DEVICE_DRY") == "1":
        return False
    if os.environ.get("TESTMAP_AGENT_DEVICE_SKIP_PREPARE") == "1":
        return False
    if os.environ.get("TESTMAP_AGENT_DEVICE_PREPARE") == "0":
        return False
    if udid in _prepared_udids:
        return False
    return True


def maybe_prepare_ios_runner(spec: dict, logf) -> None:
    """Run `prepare ios-runner` on the same --udid before iOS replay when needed."""
    platform = spec.get("agent_platform") or spec.get("needs_device")
    if platform != "ios":
        return
    device = spec.get("device") if isinstance(spec.get("device"), dict) else {}
    udid = str(device.get("id") or spec.get("udid") or "")
    if not ios_prepare_needed(udid):
        return
    edge_id = str(spec.get("edge_id") or "")
    cmd = prepare_ios_runner_cmd(
        udid, session=session_name(edge_id, "ios") if edge_id else None
    )
    evidence = Path(spec.get("agent_device_output") or "")
    if str(evidence):
        evidence.mkdir(parents=True, exist_ok=True)
    if logf is not None:
        logf.write(f"\n## prepare: {' '.join(cmd)}\n")
        logf.flush()
    proc = subprocess.run(
        cmd,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        timeout=300,
        check=False,
    )
    blob = (proc.stdout or "") + (proc.stderr or "")
    if logf is not None and blob:
        logf.write(blob)
        if not blob.endswith("\n"):
            logf.write("\n")
        logf.flush()
    if str(evidence):
        try:
            (evidence / "prepare.log").write_text(blob, encoding="utf-8")
        except OSError:
            pass
    if proc.returncode != 0:
        raise RuntimeError(
            f"prepare ios-runner failed (exit {proc.returncode}) for --udid {udid}"
        )
    _prepared_udids.add(udid)


def agent_device_cmd(
    edge_id: str,
    *,
    platform: str,
    output_dir: Path,
    serial: str | None = None,
    udid: str | None = None,
    script: Path | None = None,
    timeout_ms: int | None = None,
) -> list[str]:
    """Build `agent-device replay` argv. Android always --serial; iOS always --udid."""
    if platform == "desktop":
        raise ValueError("Agent Device is not used for Compose Desktop")
    if platform not in AGENT_PLATFORMS:
        raise ValueError(f"Agent Device platform must be android or ios, got {platform}")
    if platform == "android":
        if not serial:
            raise ValueError("Android Agent Device replay requires --serial")
    elif not udid:
        raise ValueError("iOS Agent Device replay requires --udid")
    if script is None:
        cases = agent_device_cases_by_id()
        case = cases.get(edge_id)
        if not case:
            raise ValueError(f"unknown Agent Device case: {edge_id}")
        if not case_supports_platform(case, platform):
            raise ValueError(
                f"Agent Device case {edge_id} does not support {platform}"
            )
        script = case_script_path(case, platform)
    binary = agent_device_bin()
    if script.suffix == ".json":
        cmd = [
            binary,
            "batch",
            "--steps-file",
            str(script),
            "--on-error",
            "stop",
            "--platform",
            platform,
            "--session",
            session_name(edge_id, platform),
            "--json",
        ]
    else:
        cmd = [
            binary,
            "replay",
            str(script),
            "--platform",
            platform,
            "--session",
            session_name(edge_id, platform),
            "--json",
            "-e",
            f"PACKAGE={package_for(platform)}",
        ]
    if platform == "android":
        cmd.extend(["--serial", serial or ""])
    else:
        cmd.extend(["--udid", udid or ""])
    ms = timeout_ms
    if ms is None:
        raw = os.environ.get("TESTMAP_AGENT_DEVICE_TIMEOUT_MS")
        if raw:
            try:
                ms = int(raw)
            except ValueError:
                ms = None
        else:
            ms = 360000
    if ms and script.suffix != ".json":
        cmd.extend(["--timeout", str(ms)])
    _ = output_dir
    return cmd


def _read_text(path: Path) -> str:
    if not path.is_file():
        return ""
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _coerced_replay_result(
    result: dict | None, exit_code: int | None, evidence: Path
) -> dict:
    """Replay 0 is observation complete, never a product pass."""
    log_text = _read_text(evidence / "replay.log") + _read_text(evidence / "prepare.log")
    if result is None:
        result = {}
    else:
        result = dict(result)
    blob = log_text + json.dumps(result, default=str)
    if "REPLAY_DIVERGENCE" in blob:
        result.setdefault("status", "failed")
        result["agent_status"] = "replay_divergence"
        result.setdefault("error", "REPLAY_DIVERGENCE")
        result.setdefault("timed_out", False)
        return result
    if result.get("timed_out") or result.get("status") == "timed_out":
        result["status"] = "timed_out"
        result["timed_out"] = True
        return result
    if exit_code in {130, -15, -9} or result.get("status") == "interrupted":
        result["status"] = "interrupted"
        result.setdefault("timed_out", False)
        return result
    if result.get("status") == "blocked":
        return result
    raw_status = str(result.get("status") or "")
    if raw_status in {"passed", "ok", "completed", "success"}:
        result["status"] = "executed_review_required"
        result.setdefault("agent_status", "completed")
        return result
    if exit_code not in (0, None):
        result.setdefault("status", "failed")
        result.setdefault("agent_status", "failed")
        result.setdefault("error", f"replay exit {exit_code}")
        result.setdefault("timed_out", False)
        return result
    if not raw_status:
        result["status"] = "executed_review_required"
        result.setdefault("agent_status", "completed")
        result.setdefault("timed_out", False)
    return result


def _resolve_agent_evidence(spec: dict) -> Path:
    """Prefer agent_device_output; accept agent_output from parallel-edit tests."""
    edge_id = str(spec.get("edge_id") or "")
    raw = spec.get("agent_device_output") or spec.get("agent_output") or ""
    output = Path(raw) if raw else Path()
    if not str(output):
        return output
    if (output / "result.json").is_file():
        return output
    nested_edge = output / edge_id / "result.json" if edge_id else None
    if nested_edge is not None and nested_edge.is_file():
        return nested_edge.parent
    if output.is_dir():
        found = list(output.glob("*/result.json"))
        if found:
            return found[0].parent
    return output


def ingest_agent_device_result(spec: dict, exit_code: int | None) -> dict:
    edge_id = spec.get("edge_id") or ""
    evidence = _resolve_agent_evidence(spec)
    result = load_result_json(evidence / "result.json") if str(evidence) else None
    result = _coerced_replay_result(result, exit_code, evidence)
    review_file = evidence / "review.json"
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
    if layers.get("business") == "passed":
        layers["business"] = "review_required"
    layers["human_confirmation"] = False
    layers["green_from_process_zero"] = False
    state = task_state_from_layers(layers)
    if state in {"executed", "passed"}:
        state = "review_required"
    recs = recording_index(evidence) if str(evidence) else []
    if str(evidence) and not (evidence / "result.json").is_file():
        try:
            evidence.mkdir(parents=True, exist_ok=True)
            (evidence / "result.json").write_text(
                json.dumps(result, indent=2) + "\n", encoding="utf-8"
            )
        except OSError:
            pass
    case = {
        "ref": f"AgentDevice.{edge_id}",
        "name": edge_id,
        "status": layers["business"],
        "raw_status": result.get("status"),
        "message": result.get("error") or "",
        "layers": layers,
    }
    return {
        "cases": [case],
        "layers": layers,
        "agent_device_state": state,
        "agent_device_result": result,
        "recordings": recs,
        "evidence_dir": str(evidence),
        "human_confirmation": False,
        "independent_review": independent,
        "preview_png": str(newest_png(evidence) or ""),
    }
