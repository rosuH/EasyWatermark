#!/usr/bin/env python3
"""Watch step rows. Marks come only from runner events, never from logs."""

from __future__ import annotations

import json
from pathlib import Path

_SKIP = {"context", "env"}
_POINT_CMDS = {"press", "click", "tap", "longpress", "swipe", "gesture"}


def parse_script(path: Path, platform: str | None = None) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".json":
        return parse_steps_json(text, platform)
    return parse_ad(text, platform)


def parse_ad(text: str, platform: str | None = None) -> list[dict]:
    rows = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        cmd, _, rest = line.partition(" ")
        if cmd in _SKIP:
            continue
        rest = rest.strip()
        rows.append(_row(len(rows) + 1, cmd, rest, _ad_point(cmd, rest), platform))
    return rows


def parse_steps_json(text: str, platform: str | None = None) -> list[dict]:
    data = json.loads(text)
    if not isinstance(data, list):
        raise ValueError("steps file must be a JSON array")
    rows = []
    for item in data:
        if not isinstance(item, dict):
            continue
        cmd = str(item.get("command") or "").strip()
        if not cmd:
            continue
        inp = item.get("input") if isinstance(item.get("input"), dict) else {}
        rows.append(
            _row(len(rows) + 1, cmd, _json_args(inp), _json_point(inp), platform, inp)
        )
    return rows


def public_steps(rows: list[dict]) -> list[dict]:
    out = []
    for row in rows:
        item = {
            "n": row["n"],
            "command": row["command"],
            "args": row.get("args") or "",
            "state": row.get("state") or "pending",
            "platform": row.get("platform") or "",
        }
        if row.get("point"):
            item["point"] = row["point"]
        out.append(item)
    return out


def apply_event(rows: list[dict], event: dict) -> bool:
    """Apply one structured runner event. Unknown lines are not events."""
    kind = event.get("type")
    if kind not in {"replay_action_start", "replay_action_stop"}:
        return False
    idx = _row_index(rows, event)
    if idx is None:
        return False
    row = rows[idx]
    point = point_from_event(event)
    if point:
        row["point"] = point
    if kind == "replay_action_start":
        row["state"] = "current"
        return True
    if event.get("ok") is True:
        row["state"] = "done"
        return True
    if event.get("ok") is False:
        row["state"] = "failed"
        return True
    return False


def apply_timing_line(rows: list[dict], line: str) -> bool:
    line = line.strip()
    if not line.startswith("{"):
        return False
    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        return False
    if not isinstance(event, dict):
        return False
    return apply_event(rows, event)


def point_from_event(event: dict) -> dict | None:
    point = _xy(event.get("x"), event.get("y"))
    if point:
        return point
    start = event.get("start")
    if isinstance(start, dict):
        point = _xy(start.get("x"), start.get("y"))
        if point:
            return point
    timing = event.get("resultTiming")
    if isinstance(timing, dict) and timing is not event:
        return point_from_event(timing)
    return None


def _row(n: int, cmd: str, args: str, point: dict | None, platform: str | None, inp: dict | None = None) -> dict:
    row = {
        "n": n,
        "command": cmd,
        "args": args,
        "state": "pending",
        "platform": platform or "",
        "point": point,
    }
    if inp is not None:
        row["input"] = inp
    return row


def _row_index(rows: list[dict], event: dict) -> int | None:
    raw = event.get("step")
    if not isinstance(raw, int):
        raw = event.get("stepIndex")
    if not isinstance(raw, int) or raw < 1 or raw > len(rows):
        return None
    return raw - 1


def _ad_point(cmd: str, rest: str) -> dict | None:
    if cmd not in _POINT_CMDS:
        return None
    nums = []
    for tok in rest.split():
        if tok[:1] in {'"', "'"}:
            break
        try:
            nums.append(float(tok))
        except ValueError:
            if nums:
                break
            return None
    if cmd == "swipe" and len(nums) >= 2:
        return {"x": nums[0], "y": nums[1]}
    if len(nums) >= 2:
        return {"x": nums[0], "y": nums[1]}
    return None


def _json_args(inp: dict) -> str:
    parts = []
    if inp.get("app"):
        parts.append(str(inp["app"]))
    target = inp.get("target") if isinstance(inp.get("target"), dict) else {}
    selector = target.get("selector") or inp.get("selector")
    if selector:
        parts.append(str(selector))
    if inp.get("query"):
        parts.append(str(inp["query"]))
    if inp.get("text"):
        parts.append(str(inp["text"]))
    return " ".join(parts)


def _json_point(inp: dict) -> dict | None:
    point = _xy(inp.get("x"), inp.get("y"))
    if point:
        return point
    start = inp.get("start") if isinstance(inp.get("start"), dict) else None
    if start:
        point = _xy(start.get("x"), start.get("y"))
        if point:
            return point
    target = inp.get("target") if isinstance(inp.get("target"), dict) else None
    if target:
        return _xy(target.get("x"), target.get("y"))
    return None


def _xy(x: object, y: object) -> dict | None:
    if isinstance(x, bool) or isinstance(y, bool):
        return None
    if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
        return None
    return {"x": float(x), "y": float(y)}


def _self_check() -> None:
    ad = """context platform=android
env WATERMARK="EWM COMBO"
open me.rosuh.easywatermark.debug
wait label="Save"
press label="Content"
press 12 34
swipe 1 2 9 8
"""
    rows = parse_ad(ad, "android")
    assert [r["command"] for r in rows] == ["open", "wait", "press", "press", "swipe"]
    assert all(r["state"] == "pending" for r in rows)
    assert rows[2]["point"] is None
    assert rows[3]["point"] == {"x": 12.0, "y": 34.0}
    assert rows[4]["point"] == {"x": 1.0, "y": 2.0}
    assert apply_timing_line(rows, "press label=Save done") is False
    assert all(r["state"] == "pending" for r in rows)
    assert apply_event(rows, {"type": "replay_action_start", "step": 3})
    assert rows[2]["state"] == "current"
    assert rows[0]["state"] == "pending"
    assert apply_event(rows, {"type": "replay_action_stop", "step": 3, "ok": True})
    assert rows[2]["state"] == "done"
    assert apply_event(rows, {"type": "replay_action_stop", "step": 4, "ok": False})
    assert rows[3]["state"] == "failed"
    assert rows[4]["state"] == "pending"
    js = parse_steps_json(
        '[{"command":"press","input":{"target":{"kind":"selector","selector":"label=\\"Save\\""}}}]',
        "android",
    )
    assert js[0]["point"] is None
    assert "Save" in js[0]["args"]
    pointed = parse_steps_json(
        '[{"command":"swipe","input":{"start":{"x":3,"y":4},"end":{"x":9,"y":8}}}]'
    )
    assert pointed[0]["point"] == {"x": 3.0, "y": 4.0}
    repo = Path(__file__).resolve().parents[1] / "docs/testing/agent-device/scripts/editor-style-then-export@android.ad"
    if repo.is_file():
        live = parse_script(repo, "android")
        assert live[0]["command"] == "open"
        assert all(r["point"] is None for r in live)
        assert all(r["state"] == "pending" for r in live)
    print("testmap_steps ok")


if __name__ == "__main__":
    _self_check()
