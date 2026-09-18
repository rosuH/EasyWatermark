#!/usr/bin/env python3
"""Bind Artemis Android agent runs to the testmap (stdlib only).

docs/testmap/map.yaml is the topology. docs/testing/artemis-cases.json is the
Android agent execution payload, keyed 1:1 by original edge id. It is not a
second map.

Drive labels in map.yaml stay real|seam|none. An Artemis run that walked the
system picker does not rewrite pick-to-editor android.drive from seam to real.

Status layers (never collapse to process 0 or SDK completed):
  execution          child process / timeout / interrupt
  script_checks      MediaStore / export file checks from result.json
  agent_observation  Artemis SDK agent_status / agent_reported_success
  independent_review reviewed-results.json (not human confirmation)
  human_confirmation console POST /api/confirm only
  business           product verdict; Add More stays failed even if files passed
"""

from __future__ import annotations

import json
import os
import re
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ARTEMIS_CASES_PATH = REPO_ROOT / "docs" / "testing" / "artemis-cases.json"
REVIEWED_RESULTS_PATH = REPO_ROOT / "build" / "artemis-suite" / "20260912-reviewed-results.json"
DEFAULT_ARTEMIS_DIR = Path(os.environ.get("ARTEMIS_DIR") or "/Users/rosu/Coding/artemis")
DEFAULT_ARTEMIS_PYTHON = DEFAULT_ARTEMIS_DIR / ".venv" / "bin" / "python"

# Independent review is not a human operator confirmation.
REVIEW_STATUSES = {
    "accepted",
    "accepted_with_recovery",
    "failed",
    "blocked",
}

# File-check statuses that still require visual / human follow-up.
REVIEW_REQUIRED_PREFIXES = (
    "passed_file_checks_visual_review_required",
    "executed_review_required",
)

# Historical independent reviews bound to a specific run + evidence directory.
# They must not paint every future run of the same edge as failed.
HISTORICAL_RUN_ID = "20260912T140000-000c3f44"
HISTORICAL_BUSINESS_REVIEWS = {
    (
        "editor-to-add-more-picker",
        HISTORICAL_RUN_ID,
    ): {
        "business": "failed",
        "evidence": "build/artemis-suite/20260912-round1/editor-to-add-more-picker",
        "reason": (
            "2026-09-12 review: appending B replaced A. Later file-pass after "
            "the model re-selected A+B is not append success. Bound to this "
            "run only; a new run stays pending until its own review."
        ),
    },
}


def iso_utc(dt: datetime | None = None) -> str:
    value = dt or datetime.now(timezone.utc)
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def load_artemis_manifest(path: Path | None = None) -> dict:
    target = path or ARTEMIS_CASES_PATH
    if not target.is_file():
        raise ValueError(f"missing Artemis payload {target}")
    data = json.loads(target.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or not isinstance(data.get("cases"), list):
        raise ValueError("artemis-cases.json must be an object with a cases list")
    return data


def artemis_cases_by_id(manifest: dict | None = None) -> dict[str, dict]:
    data = manifest if manifest is not None else load_artemis_manifest()
    out: dict[str, dict] = {}
    for case in data.get("cases") or []:
        if not isinstance(case, dict) or not case.get("id"):
            raise ValueError("each Artemis case needs an id")
        cid = str(case["id"])
        if cid in out:
            raise ValueError(f"duplicate Artemis case id {cid}")
        out[cid] = case
    return out


def validate_artemis_binding(edge_ids: list[str], manifest: dict | None = None) -> None:
    """Fail if cases.json is a second topology or misses a map edge."""
    data = manifest if manifest is not None else load_artemis_manifest()
    source_path = data.get("source_path")
    if source_path != "docs/testmap/map.yaml":
        raise ValueError(
            f"artemis-cases.json source_path must be docs/testmap/map.yaml, got {source_path!r}"
        )
    cases = artemis_cases_by_id(data)
    wanted = list(edge_ids)
    got = list(cases)
    missing = [eid for eid in wanted if eid not in cases]
    extra = [cid for cid in got if cid not in set(wanted)]
    if missing or extra:
        raise ValueError(
            "Artemis payload is not 1:1 with map edges; "
            f"missing={missing} extra={extra}"
        )
    for cid, case in cases.items():
        edges = case.get("edges")
        if edges != [cid]:
            raise ValueError(
                f"Artemis case {cid} edges must be [{cid!r}] (traceability only), got {edges!r}"
            )


def uitest_on_desktop_classpath(gradle_text: str | None = None) -> bool:
    if gradle_text is None:
        gradle = REPO_ROOT / "shared" / "build.gradle.kts"
        gradle_text = gradle.read_text(encoding="utf-8") if gradle.is_file() else ""
    return "src/uiTest" in gradle_text


def artemis_python(artemis_dir: Path | None = None) -> Path:
    root = Path(artemis_dir or DEFAULT_ARTEMIS_DIR)
    return root / ".venv" / "bin" / "python"


def artemis_cmd(
    edge_id: str,
    *,
    serial: str,
    output_dir: Path,
    artemis_dir: Path | None = None,
    timeout: int = 360,
    dry_run: bool = False,
) -> list[str]:
    py = artemis_python(artemis_dir)
    cmd = [
        str(py),
        str(REPO_ROOT / "scripts" / "artemis-suite.py"),
        "--cases",
        edge_id,
        "--output-dir",
        str(output_dir),
        "--timeout",
        str(timeout),
        "--manifest",
        str(ARTEMIS_CASES_PATH),
    ]
    if dry_run:
        cmd.append("--dry-run")
    else:
        cmd.extend(
            [
                "--serial",
                serial,
                "--artemis-dir",
                str(artemis_dir or DEFAULT_ARTEMIS_DIR),
            ]
        )
    return cmd


def is_artemis_python(cmd: list[str]) -> bool:
    if len(cmd) < 2:
        return False
    joined = " ".join(cmd)
    return "artemis-suite.py" in joined


def load_result_json(path: Path) -> dict | None:
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    return data if isinstance(data, dict) else None


def classify_script_status(raw: str | None) -> str:
    status = raw or ""
    if status in {"blocked", "planned", "timed_out", "interrupted", "failed"}:
        return status
    if status.startswith("passed_") or status in REVIEW_REQUIRED_PREFIXES:
        return status
    if status:
        return status
    return "unknown"


def pending_confirmation(script_status: str) -> bool:
    return script_status.startswith("passed_file_checks") or script_status == "executed_review_required"


def historical_business_for(edge_id: str, run_id: str | None) -> str | None:
    if not run_id:
        return None
    row = HISTORICAL_BUSINESS_REVIEWS.get((edge_id, run_id))
    if not row:
        return None
    return str(row["business"])


def business_status(
    edge_id: str,
    *,
    script_status: str,
    independent_review: str | None,
    run_id: str | None = None,
) -> str:
    bound = historical_business_for(edge_id, run_id)
    if bound is not None:
        return bound
    if independent_review == "failed":
        return "failed"
    if independent_review == "blocked" or script_status == "blocked":
        return "blocked"
    if script_status in {"failed", "timed_out", "interrupted"}:
        return script_status
    if pending_confirmation(script_status):
        return "review_required"
    return script_status or "unknown"


def verdict_layers(
    edge_id: str,
    *,
    exit_code: int | None,
    result: dict | None,
    independent_review: str | None = None,
    human_confirmed: bool = False,
    run_id: str | None = None,
) -> dict:
    result = result or {}
    script = classify_script_status(result.get("status"))
    agent_status = result.get("agent_status")
    if agent_status is None and "agent_reported_success" in result:
        agent_status = "completed" if result.get("agent_reported_success") else result.get("agent_status")
    execution = "ok"
    if result.get("timed_out"):
        execution = "timed_out"
    elif result.get("status") == "interrupted" or exit_code in {130, -15, -9}:
        execution = "interrupted"
    elif result.get("status") == "blocked":
        execution = "blocked"
    elif result.get("status") == "planned":
        execution = "planned"
    elif exit_code not in (0, None) and script not in REVIEW_REQUIRED_PREFIXES:
        execution = f"exit_{exit_code}"
    layers = {
        "execution": execution,
        "script_checks": script,
        "agent_observation": agent_status if agent_status is not None else result.get("agent_reported_success"),
        "independent_review": independent_review,
        "human_confirmation": bool(human_confirmed),
        "business": business_status(
            edge_id,
            script_status=script,
            independent_review=independent_review,
            run_id=run_id,
        ),
        "green_from_process_zero": False,
        "green_from_sdk_completed": False,
    }
    # Process 0 / SDK completed must never be the product pass bit.
    if exit_code == 0 and pending_confirmation(script):
        layers["green_from_process_zero"] = False
    if agent_status == "completed" and layers["business"] != "passed":
        layers["green_from_sdk_completed"] = False
    return layers


def task_state_from_layers(layers: dict) -> str:
    business = layers.get("business")
    if business in {"failed", "timed_out", "interrupted"}:
        return "failed"
    if business == "blocked":
        return "blocked"
    if layers.get("execution") == "planned":
        return "planned"
    if business == "review_required":
        return "review_required"
    if layers.get("execution") in {"ok", "planned"}:
        return "executed"
    return "failed"


def load_reviewed_results(path: Path | None = None) -> dict[str, dict]:
    target = path or REVIEWED_RESULTS_PATH
    if not target.is_file():
        return {}
    try:
        data = json.loads(target.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    cases = data.get("cases") if isinstance(data, dict) else None
    if not isinstance(cases, list):
        return {}
    out: dict[str, dict] = {}
    for item in cases:
        if isinstance(item, dict) and item.get("id"):
            out[str(item["id"])] = item
    return out


def _as_run_iso(value: str | None) -> str | None:
    if not value:
        return None
    raw = str(value).strip()
    try:
        if raw.endswith("Z") and len(raw) >= 20:
            dt = datetime.strptime(raw[:19], "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)
        else:
            dt = datetime.fromisoformat(raw.replace("Z", "+00:00")).astimezone(timezone.utc)
        return dt.strftime("%Y-%m-%dT%H:%M:%SZ")
    except (ValueError, TypeError, OSError):
        return None


def historical_time_range(rows: dict[str, dict] | None = None) -> tuple[str, str]:
    """Truthful window from original evidence, not the synthetic 140000 run id."""
    rows = rows if rows is not None else load_reviewed_results()
    starts: list[str] = []
    ends: list[str] = []
    for row in rows.values():
        if not isinstance(row, dict):
            continue
        evidence = row.get("evidence")
        folder = Path(str(evidence)) if evidence else None
        result = None
        if folder is not None:
            result = load_result_json(folder / "result.json") if folder.is_dir() else load_result_json(folder)
        if result:
            started = _as_run_iso(result.get("started_at"))
            finished = _as_run_iso(result.get("finished_at"))
            if started:
                starts.append(started)
            if finished:
                ends.append(finished)
            elif started and row.get("duration_seconds") is not None:
                try:
                    epoch = datetime.strptime(started, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
                    ends.append(
                        datetime.fromtimestamp(
                            epoch.timestamp() + float(row["duration_seconds"]),
                            timezone.utc,
                        ).strftime("%Y-%m-%dT%H:%M:%SZ")
                    )
                except (TypeError, ValueError, OSError):
                    pass
    started = min(starts) if starts else "2026-09-12T06:00:00Z"
    finished = max(ends) if ends else started
    return started, finished


def evidence_href(path: Path | str) -> str | None:
    try:
        target = Path(path).resolve()
        root = DEFAULT_EVIDENCE_ROOT.resolve()
        rel = target.relative_to(root)
    except (OSError, ValueError):
        return None
    parts = rel.parts
    if len(parts) < 2:
        return None
    if not _safe_evidence_id(parts[0]) or not _safe_evidence_id(parts[1]):
        return None
    if any(part in {".", ".."} for part in parts):
        return None
    return "/artemis-evidence/" + "/".join(parts)


def evidence_links_for(task: dict) -> list[dict]:
    """Run-scoped screenshot/video/log/result links under the existing sandbox."""
    raw_dir = task.get("evidence_dir") or task.get("source_evidence")
    folder = Path(str(raw_dir)) if raw_dir else None
    links: list[dict] = []
    seen: set[str] = set()

    def add(path: Path, kind: str) -> None:
        if not path.is_file():
            return
        href = evidence_href(path)
        if not href or href in seen:
            return
        seen.add(href)
        links.append({"kind": kind, "name": path.name, "href": href})

    if folder is not None and folder.is_dir():
        for name, kind in (
            ("pre.png", "screenshot"),
            ("post.png", "screenshot"),
            ("result.json", "result"),
            ("artemis.log", "log"),
            ("agent-result.json", "log"),
            ("package.txt", "log"),
        ):
            add(folder / name, kind)
        recordings = task.get("recordings") or recording_index(folder)
        for rec in recordings:
            path = Path(rec["path"] if isinstance(rec, dict) else rec)
            suffix = path.suffix.lower()
            kind = "recording" if suffix in {".mp4", ".webm", ".mkv"} else "trace"
            add(path, kind)
    return links


def historical_projection(reviewed: dict[str, dict] | None = None) -> dict:
    """Project 2026-09-12 Artemis results into a testmap run record.

    Original result.json files are not rewritten. Add More stays failed.
    Independent review is recorded separately from human confirmation.
    """
    rows = reviewed if reviewed is not None else load_reviewed_results()
    tasks = []
    fail_n = pass_n = blocked_n = 0
    for edge_id, row in rows.items():
        raw = row.get("raw_status") or row.get("status")
        review = row.get("status")
        result = {
            "status": raw,
            "agent_reported_success": None,
            "timed_out": False,
        }
        layers = verdict_layers(
            edge_id,
            exit_code=0,
            result=result,
            independent_review=review if review in REVIEW_STATUSES else None,
            human_confirmed=False,
            run_id=HISTORICAL_RUN_ID,
        )
        state = task_state_from_layers(layers)
        if layers["business"] == "failed":
            fail_n += 1
            state = "failed"
        elif layers["business"] == "blocked":
            blocked_n += 1
            state = "blocked"
        else:
            # Independent review never becomes a console "passed" / human confirm.
            state = "review_required"
        evidence = row.get("evidence")
        tasks.append(
            {
                "id": f"edge:{edge_id}@android#artemis",
                "label": f"Artemis {edge_id} (historical 2026-09-12)",
                "state": state,
                "exit_code": None,
                "duration_s": row.get("duration_seconds"),
                "parse_xml": False,
                "builder": "artemis",
                "historical": True,
                "source_evidence": evidence,
                "source_review": row.get("review"),
                "layers": layers,
                "independent_review": review,
                "human_confirmation": False,
                "cases": [
                    {
                        "ref": f"Artemis.{edge_id}",
                        "name": edge_id,
                        "status": layers["business"],
                        "raw_status": raw,
                        "message": (
                            "historical projection; originals not rewritten"
                            + (
                                "; product regression: append replaced A"
                                if edge_id == "editor-to-add-more-picker"
                                else ""
                            )
                        ),
                    }
                ],
            }
        )
    # Do not treat independent review as human confirmation, and do not
    # promote file-pass Add More to passed.
    add_more = next((t for t in tasks if t["id"].endswith("editor-to-add-more-picker@android#artemis") or "add-more" in t["id"]), None)
    started, finished = historical_time_range(rows)
    rec = {
        "id": HISTORICAL_RUN_ID,
        "started": started,
        "finished": finished,
        "git": {"sha": "0edf939", "dirty": True},
        "selection": ["artemis-historical-2026-09-12"],
        "device": "emulator-5554",
        "state": "failed" if fail_n else "passed",
        "historical": True,
        "source": str(REVIEWED_RESULTS_PATH.relative_to(REPO_ROOT)),
        "note": (
            "Projection of build/artemis-suite/20260912-reviewed-results.json. "
            "Originals stay in round1/round2. Independent review is not human confirmed. "
            "editor-to-add-more-picker remains business failed."
        ),
        "tasks": tasks,
        "pass_count": pass_n,
        "fail_count": fail_n,
        "skip_count": blocked_n,
        "blocked_count": blocked_n,
        "log": "build/artemis-suite/20260912-reviewed-results.json",
    }
    if add_more is not None:
        rec["add_more_business"] = "failed"
        rec["add_more_script"] = add_more["layers"]["script_checks"]
    return rec


def color_audit_trail() -> dict:
    """Pointer-only audit: original failed result.json plus offline file-recheck."""
    round1 = REPO_ROOT / "build" / "artemis-suite" / "20260912-round1" / "editor-to-custom-color-sheet" / "result.json"
    round2 = REPO_ROOT / "build" / "artemis-suite" / "20260912-round2" / "editor-to-custom-color-sheet" / "result.json"
    recheck = REPO_ROOT / "build" / "artemis-suite" / "20260912-round2" / "editor-to-custom-color-sheet" / "file-recheck.json"
    return {
        "edge_id": "editor-to-custom-color-sheet",
        "original_round2_status": (load_result_json(round2) or {}).get("status"),
        "round2_result": str(round2),
        "file_recheck": str(recheck) if recheck.is_file() else None,
        "round1_result": str(round1) if round1.is_file() else None,
        "note": (
            "Round 1 used a shared HEX field that Android does not show. "
            "Round 2 result.json stays failed. file-recheck.json is an offline "
            "pixel audit of #FF28FFD2, not a rewritten original."
        ),
        "rewritten": False,
    }


def newest_png(folder: Path) -> Path | None:
    if not folder.is_dir():
        return None
    pngs = sorted(folder.rglob("*.png"), key=lambda p: p.stat().st_mtime, reverse=True)
    return pngs[0] if pngs else None


def recording_index(folder: Path) -> list[dict]:
    if not folder.is_dir():
        return []
    out = []
    for path in sorted(folder.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in {".mp4", ".webm", ".mkv"} and "traces" not in path.parts:
            continue
        rel = str(path)
        out.append({"path": rel, "name": path.name, "suffix": path.suffix.lower()})
    return out


_SAFE_EVIDENCE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
DEFAULT_EVIDENCE_ROOT = REPO_ROOT / "build" / "artemis-suite"


def _safe_evidence_id(value: str) -> bool:
    if not value or value in {".", ".."}:
        return False
    if ".." in value:
        return False
    return bool(_SAFE_EVIDENCE_ID.fullmatch(value))


def sandbox_artemis_file(
    round_id: str,
    case_id: str,
    rel: str,
    *,
    evidence_root: Path | None = None,
) -> Path | None:
    """Resolve a file under the Artemis evidence root. Rejects `.` / `..` ids,
    absolute rel paths, and any resolve/symlink escape past the root."""
    if not _safe_evidence_id(round_id) or not _safe_evidence_id(case_id):
        return None
    if not rel or rel.startswith("/") or ".." in Path(rel).parts:
        return None
    root = (evidence_root or DEFAULT_EVIDENCE_ROOT).resolve()
    base = (root / round_id / case_id).resolve()
    target = (base / rel).resolve()
    try:
        base.relative_to(root)
        target.relative_to(root)
        target.relative_to(base)
    except ValueError:
        return None
    return target if target.is_file() else None
