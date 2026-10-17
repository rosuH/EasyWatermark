#!/usr/bin/env python3
"""Audited continuous Android routes. Source cases remain independent."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from testmap_agent_device import agent_device_cases_by_id, case_script_path, case_supports_platform
from testmap_steps import parse_ad

ROOT = Path(__file__).resolve().parents[1]
# Exact payloads establish these boundaries. Changed scripts require a new audit.
_BOUNDARIES = {
    "editor-to-text-edit-sheet": ("editor", "editor", "f923b01db72536c00150e05c7cadedb78e850a5f58586bc01c6554fcff8b5ce5"),
    "editor-typeface-style": ("editor", "editor-style", "cdd9c056b79cbaff16e8638fba8cbd2b980b2f735b8b65577c0afa5fb1bf20c9"),
    "editor-to-about": ("editor", "editor", "228b139b21ffcfcec765b9a313b0c3fe700b8454466e79b50aef41f724549a87"),
    "launch-to-about": ("home", "home", "9f853e725564af1e50b50853fe0114a0d0ee24100b444d0bacd706850f48c242"),
    "about-to-open-source": ("home", "home", "921fd8d3f6cbaaa642e52e5fef06e5aa082a138429c9284d24d973315738a74f"),
    "about-follow-photo": ("home", "home", "0113fe8c6ff139d75d09bbdfc4e7fee13641f8457f7220bf4bb410ee0b5e563a"),
    "about-wallpaper": ("home", "home", "e762d52582a88dff5949ba772b848dcaa28eae0fcd7de4bd41a419e0e690585f"),
}


def route_plan(tasks: list[str], device: str = "auto") -> dict:
    """Read only; reject unsupported transitions before touching any device."""
    segments = []
    try:
        if not isinstance(tasks, list) or len(tasks) < 2 or len(tasks) > 20:
            raise ValueError("A continuous route needs 2 to 20 Device walk cases.")
        if len(set(tasks)) != len(tasks):
            raise ValueError("Choose each case once; Repeat repeats the whole route.")
        if not isinstance(device, str):
            raise ValueError("device must be a string")
        if device.startswith("ios"):
            raise ValueError("These continuous routes support Android only.")
        cases = agent_device_cases_by_id()
        previous = None
        for task in tasks:
            match = re.fullmatch(r"edge:([a-z0-9-]+)@android#agent", task) if isinstance(task, str) else None
            if not match:
                raise ValueError("Continuous routes require Android Device walk only; remove Checks and other platforms.")
            edge = match[1]
            if edge not in _BOUNDARIES:
                raise ValueError(f"{edge} has no audited continuous entry and exit. Run it as an independent case.")
            entry, exit_, expected = _BOUNDARIES[edge]
            if previous and previous != entry:
                raise ValueError(f"{edge} needs {entry}, but the previous case ends at {previous}. Change the order or use independent cases.")
            case = cases.get(edge) or {}
            if case.get("setup") != entry or not case_supports_platform(case, "android"):
                raise ValueError(f"{edge} setup or platform changed; its route boundary needs review.")
            source = case_script_path(case, "android")
            raw = source.read_bytes()
            sha = hashlib.sha256(raw).hexdigest()
            if source.suffix != ".ad" or sha != expected:
                raise ValueError(f"{edge} script changed; its route boundary needs review.")
            rows = parse_ad(raw.decode(), "android")
            if not rows or rows[0]["command"] != "open" or rows[-1]["command"] != "close":
                raise ValueError(f"{edge} has an unsupported session boundary.")
            segments.append({"task_id": task, "edge_id": edge, "source": str(source.relative_to(ROOT)),
                             "source_sha256": sha, "entry": entry, "exit": exit_, "steps": len(rows) - 2})
            previous = exit_
        return {"supported": True, "platform": "android", "setup": segments[0]["entry"], "segments": segments}
    except (ValueError, OSError, TypeError) as exc:
        return {"supported": False, "reason": str(exc), "segments": segments}


def materialize_route(plan: dict, root: Path, repeat: int = 1) -> dict:
    """One initial open, no relaunch between segments; cleanup owns session close."""
    tasks = [segment["task_id"] for segment in plan["segments"]]
    checked = route_plan(tasks)
    if not checked["supported"] or checked != plan:
        raise ValueError(checked.get("reason") or "Route source identity changed after planning.")
    digest = hashlib.sha256(json.dumps(plan, sort_keys=True).encode()).hexdigest()[:12]
    route_id = "route-" + digest
    source = root / "scripts" / f"{route_id}-r{repeat}.ad"
    lines = ["context platform=android"]
    provenance, segments = [], []
    n = 0
    for index, segment in enumerate(plan["segments"]):
        original = ROOT / segment["source"]
        rows = parse_ad(original.read_text(), "android")
        # Only one audited source uses env today; preserve it without interpreting DSL.
        lines.extend(line for line in original.read_text().splitlines() if line.strip().startswith("env "))
        start = n + 1
        for row in rows:
            if row["command"] == "close" or (row["command"] == "open" and index):
                continue
            n += 1
            lines.append((row["command"] + " " + row["args"]).strip())
            provenance.append({"n": n, "source_task_id": segment["task_id"], "source_step": row["n"],
                               "source_sha256": segment["source_sha256"]})
        segments.append({**segment, "start_step": start, "end_step": n})
    source.parent.mkdir(parents=True, exist_ok=True)
    if source.exists():
        raise ValueError("Route script already exists; refusing stale evidence reuse.")
    source.write_text("\n".join(lines) + "\n")
    metadata = {"route_id": route_id, "route_script": str(source),
                "route_script_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "route_segments": segments,
                "route_provenance": provenance, "route_session": f"testmap-{root.name}-{route_id}-r{repeat}"}
    source.with_suffix(".route.json").write_text(json.dumps(metadata, indent=2) + "\n")
    return metadata
