#!/usr/bin/env python3
"""Watch step rows. Marks come only from runner events, never from logs."""

from __future__ import annotations

import json
import hashlib
import re
from pathlib import Path


def require_clamp_pixels():
    """Optional leaf dependency; callers check before changing device state."""
    try:
        from PIL import Image
    except ImportError as exc:
        raise ValueError("CLAMP pixels require: python3 -m pip install -r tools/testmap/requirements.txt") from exc
    if Image.__version__ != "12.3.0":
        raise ValueError("CLAMP pixels require Pillow 12.3.0: python3 -m pip install -r tools/testmap/requirements.txt")
    return Image


def clamp_pixels(path: Path, rect: dict, fixture: Path) -> dict:
    """Measure only the owned A fixture's white photo interior, never UI chrome."""
    import math
    Image = require_clamp_pixels()
    if any(type(rect.get(k)) not in (int, float) or not math.isfinite(rect[k]) for k in ("x", "y", "width", "height")):
        raise ValueError("CLAMP preview rectangle is invalid")
    with Image.open(fixture) as source:
        sw, sh = source.size
        if source.format != "PNG" or min(sw, sh) < 128 or sw * sh > 16_000_000:
            raise ValueError("CLAMP owned fixture dimensions/format refused")
        source.load()
        # Exact source bands distinguish this fixture from user pictures and B/C.
        if any(source.convert("RGB").getpixel((sw // 2, y)) != rgb for y, rgb in (
                (20, (25, 77, 128)), (sh // 2, (255, 255, 255)), (sh - 20, (232, 183, 70)))):
            raise ValueError("CLAMP source is not fixture A")
    with Image.open(path) as opened:
        if opened.format != "PNG" or opened.width * opened.height > 16_000_000:
            raise ValueError("CLAMP screenshot format/size refused")
        image = opened.convert("RGB")
    x, y, width, height = (rect[k] for k in ("x", "y", "width", "height"))
    if min(width, height) <= 0 or x < 0 or y < 0 or x + width > image.width or y + height > image.height:
        raise ValueError("CLAMP preview lies outside the screenshot")
    scale = min(width / sw, height / sh)
    fit = [x + (width - sw * scale) / 2, y + (height - sh * scale) / 2, sw * scale, sh * scale]
    left, top, fw, fh = fit
    # Samples stay clear of the center/right watermark and antialiased band edges.
    for sx in (.08, .25, .90):
        for sy, rgb in ((20 / sh, (25, 77, 128)), (.20, (255, 255, 255)), ((sh - 20) / sh, (232, 183, 70))):
            actual = image.getpixel((round(left + sx * fw), round(top + sy * fh)))
            if max(abs(a - b) for a, b in zip(actual, rgb)) > 5:
                raise ValueError("CLAMP screenshot does not register to owned A")
    white = [math.ceil(left + 4), math.ceil(top + 56 * scale + 4),
             math.floor(left + fw - 4), math.floor(top + (sh - 56) * scale - 4)]
    points = set()
    raster = image.load()
    for py in range(white[1], white[3]):
        for px in range(white[0], white[2]):
            r, g, b = raster[px, py]
            if r >= 200 and g >= 90 and r - g >= 12 and g - b >= 25 and r - b >= 45:
                points.add((px, py))
                if len(points) > 100_000:
                    raise ValueError("CLAMP glyph mask exceeds its bound")
    if len(points) < 20:
        raise ValueError("CLAMP has no measurable gold glyph pixels")
    box = [min(p[0] for p in points), min(p[1] for p in points),
           max(p[0] for p in points) + 1, max(p[1] for p in points) + 1]
    bw, bh = box[2] - box[0], box[3] - box[1]
    if (min(bw, bh) < 24 or bw > fw * .45 or bh > fh * .45
            or box[0] <= white[0] + 3 or box[1] <= white[1] + 3
            or box[2] >= white[2] - 3 or box[3] >= white[3] - 3):
        raise ValueError("CLAMP glyph is clipped, too small, or not localized")
    return {"png": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "image_size": list(image.size), "source_size": [sw, sh], "fit": fit, "white": white, "bbox": box,
            "pixel_count": len(points), "_mask": points}


def clamp_pan(before: dict) -> dict:
    """An interior of the visible glyph bbox is strictly inside its raster cell."""
    x0, y0, x1, y1 = before["bbox"]
    left, top, fw, fh = before["fit"]
    if x0 < left + fw * .48 or y0 < top + fh * .48 or min(x1 - x0, y1 - y0) < 48:
        raise ValueError("CLAMP initial cell/drag-start margin is not established")
    dx, dy = round(fw * .12), round(fh * .10)
    if min(dx, dy) < 24 or x1 + dx >= before["white"][2] - 4 or y1 + dy >= before["white"][3] - 4:
        raise ValueError("CLAMP lacks safe room for the whole gesture")
    return {"kind": "pan", "origin": {"x": round((x0 + x1) / 2), "y": round((y0 + y1) / 2)},
            "delta": {"x": dx, "y": dy}, "durationMs": 600, "pointerCount": 1}


def compare_clamp_pixels(before: dict, after: dict, delta: dict | None = None) -> dict:
    if before["image_size"] != after["image_size"] or any(abs(a - b) > 1 for a, b in zip(before["fit"], after["fit"])):
        raise ValueError("CLAMP preview geometry changed")
    a, b = before["bbox"], after["bbox"]
    shift = [round((b[i] + b[i + 2] - a[i] - a[i + 2]) / 2) for i in (0, 1)]
    if delta is None:
        if max(map(abs, shift)) > 2:
            raise ValueError("CLAMP position did not remain stable")
    elif any(not .5 * delta[key] <= shift[i] <= 1.1 * delta[key] for i, key in enumerate(("x", "y"))):
        # Compose consumes touch slop; compare visible motion, not exact SDK delta.
        raise ValueError("CLAMP glyph did not move with the real pan")
    if any(abs((b[i + 2] - b[i]) - (a[i + 2] - a[i])) > max(4, (a[i + 2] - a[i]) * .08) for i in (0, 1)):
        raise ValueError("CLAMP glyph shape dimensions changed")
    moved = {(x + shift[0], y + shift[1]) for x, y in before["_mask"]}
    target = after["_mask"]
    def coverage(source, dest):
        expanded = {(x + dx, y + dy) for x, y in dest for dx in (-1, 0, 1) for dy in (-1, 0, 1)}
        return len(source & expanded) / len(source)
    similarity = min(coverage(moved, target), coverage(target, moved))
    if similarity < .65:
        raise ValueError("CLAMP translated glyph pixels do not match")
    return {"shift_px": shift, "pixel_similarity": round(similarity, 4)}


# Fixed Android CLAMP A/B fixture evidence; these are not general image selectors.
_CLAMP_PALETTE = {"A": (232, 183, 70), "B": (34, 136, 102), "C": (214, 90, 36), "icon": (223, 48, 48)}


def clamp_media_rows(raw: str) -> list[dict]:
    if len(raw) > 65536:
        raise ValueError("CLAMP MediaStore query exceeds bound")
    if raw.strip() == "No result found.":
        return []
    rows = []
    for line in raw.splitlines():
        match = re.fullmatch(r"Row: \d+ (.+)", line)
        if not match:
            raise ValueError("Unknown CLAMP MediaStore query output")
        parts = [part.split("=", 1) for part in match[1].split(", ")]
        if len(parts) != 4 or any(len(part) != 2 for part in parts):
            raise ValueError("CLAMP MediaStore projection changed")
        row = dict(parts)
        if (set(row) != {"_id", "datetaken", "date_added", "date_modified"}
                or not re.fullmatch(r"[1-9][0-9]*", row["_id"])
                or any(value not in ("NULL", "null") and not re.fullmatch(r"[0-9]{1,16}", value)
                       for key, value in row.items() if key != "_id")):
            raise ValueError("Invalid CLAMP MediaStore identity/date")
        rows.append(row)
    if not rows or len(rows) > 64 or len({row["_id"] for row in rows}) != len(rows):
        raise ValueError("Ambiguous CLAMP MediaStore rows")
    return rows


def clamp_photo_second(label: str, zone) -> int:
    from datetime import datetime
    match = re.fullmatch(r"Photo taken on (.+)", label.replace("\u202f", " ").replace("\u00a0", " "))
    if not match:
        raise ValueError("Unknown CLAMP picker photo date")
    return int(datetime.strptime(match[1], "%b %d, %Y, %I:%M:%S %p").replace(tzinfo=zone).timestamp())


def clamp_fixture_identity(image, rect: dict) -> str:
    import math
    if any(type(rect.get(k)) not in (int, float) or not math.isfinite(rect[k]) for k in ("x", "y", "width", "height")):
        raise ValueError("CLAMP fixture rectangle is invalid")
    x, y, w, h = (rect[k] for k in ("x", "y", "width", "height"))
    if min(w, h) < 30 or x < 0 or y < 0 or x + w > image.width or y + h > image.height:
        raise ValueError("CLAMP fixture rectangle clipped or too small")
    samples = [image.getpixel((round(x + w * sx), round(y + h * sy))) for sx in (.35, .5, .65) for sy in (.94, .96)]
    hits = [key for key, rgb in _CLAMP_PALETTE.items()
            if sum(max(abs(a - b) for a, b in zip(pixel, rgb)) <= 18 for pixel in samples) >= 5]
    if len(hits) != 1:
        raise ValueError("CLAMP fixture pixels are absent or ambiguous")
    middle = [image.getpixel((round(x + w * sx), round(y + h * sy))) for sx in (.4, .5, .6) for sy in (.4, .5, .6)]
    expected = _CLAMP_PALETTE["icon"] if hits[0] == "icon" else (255, 255, 255)
    if sum(max(abs(a - b) for a, b in zip(pixel, expected)) <= 18 for pixel in middle) < 7:
        raise ValueError("CLAMP fixture interior does not match")
    return hits[0]


def clamp_editor_binding(data: dict, pixels, fixture: Path) -> tuple[dict, str, dict]:
    package = "me.rosuh.easywatermark.debug"
    thumbs = [n for n in data["nodes"] if n.get("label") == "image" and n.get("type") == "android.widget.ImageView"
              and n.get("bundleId") == package and n.get("hittable") is True and n.get("visibleToUser") is True]
    preview = [n for n in data["nodes"] if n.get("label") == "Watermark preview" and n.get("visibleToUser") is True]
    if len(thumbs) != 2 or len(preview) != 1:
        raise ValueError("CLAMP requires exactly two selectable images and one preview")
    parents = {n.get("parentIndex") for n in thumbs}
    parent = [n for n in data["nodes"] if n.get("index") in parents and n.get("bundleId") == package and n.get("visibleToUser") is True]
    if len(parents) != 1 or len(parent) != 1:
        raise ValueError("CLAMP image parent is not uniquely observed")
    pr, vr = parent[0]["rect"], preview[0]["rect"]
    boxes = sorted((n["rect"] for n in thumbs), key=lambda r: r["x"])
    if (any(r["y"] < vr["y"] + vr["height"] or r["x"] < pr["x"] or r["y"] < pr["y"]
            or r["x"] + r["width"] > pr["x"] + pr["width"] or r["y"] + r["height"] > pr["y"] + pr["height"] for r in boxes)
            or abs(boxes[0]["y"] - boxes[1]["y"]) > 1 or boxes[0]["x"] + boxes[0]["width"] > boxes[1]["x"]):
        raise ValueError("CLAMP images are not a common row below preview")
    bound = {}
    for thumb in thumbs:
        tr = dict(thumb["rect"])
        if abs(tr["width"] - tr["height"]) > 1:
            raise ValueError("CLAMP thumbnail cell is not square")
        # Existing EditorFilmstripMetrics: clickable cell 48dp, centered image 40dp.
        inset = tr["width"] / 12
        tr.update(x=tr["x"] + inset, y=tr["y"] + inset, width=tr["width"] - 2 * inset, height=tr["height"] - 2 * inset)
        key = clamp_fixture_identity(pixels, tr)
        if key in bound:
            raise ValueError("CLAMP editor image pixels are ambiguous")
        bound[key] = thumb
    if set(bound) != {"A", "B"}:
        raise ValueError("CLAMP editor images are not owned A+B")
    with require_clamp_pixels().open(fixture) as source:
        w, h = source.size
    r = dict(vr)
    scale = min(r["width"] / w, r["height"] / h)
    r.update(x=r["x"] + (r["width"] - w * scale) / 2, y=r["y"] + (r["height"] - h * scale) / 2, width=w * scale, height=h * scale)
    focused = clamp_fixture_identity(pixels, r)
    if focused not in bound:
        raise ValueError("CLAMP preview does not match A+B")
    return bound, focused, preview[0]

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
        row = _row(len(rows) + 1, cmd, _json_args(inp), _json_point(inp), platform, inp)
        row["batch_step"] = item
        rows.append(row)
    return rows



# Only flat replay commands have a one-to-one action mapping. Fail closed for
# future include/loop/control syntax rather than assign images to wrong steps.
_FLAT_COMMANDS = {"open", "close", "wait", "press", "click", "tap", "longpress",
                  "swipe", "gesture", "fill", "type", "keyboard", "scroll",
                  "back", "snapshot", "screenshot", "assert", "find", "get",
                  "appstate", "rotate", "pinch", "hover"}


def confined_path(root: Path, path: Path) -> Path:
    root, path = root.resolve(), path.resolve()
    if not path.is_relative_to(root) or path == root:
        raise ValueError("step evidence path must be inside this run directory")
    return path


def _mapping_path(root: Path, path: Path) -> Path:
    if path.is_symlink():
        raise ValueError("step mapping path must not be a symlink")
    return confined_path(root, path)


def materialize_evidence_script(source: Path, root: Path, derived: Path,
                                shot_names: dict[int, str]) -> dict:
    """Insert native, awaited screenshot actions; leave the source untouched.

    The derived replay has a different SDK planDigest and timing. Its SDK
    reports, rather than a locally invented digest, remain authoritative.
    """
    derived = confined_path(root, derived)
    raw = source.read_bytes()
    if source.suffix == ".json":
        return _materialize_batch_evidence(source, root, derived, shot_names, raw)
    lines, mapping = [], []
    n = 0
    for line in raw.decode("utf-8").splitlines():
        stripped = line.strip()
        cmd = stripped.split(maxsplit=1)[0] if stripped else ""
        lines.append(line)
        if not cmd or cmd.startswith("#") or cmd in _SKIP:
            continue
        if cmd not in _FLAT_COMMANDS or stripped.endswith("\\"):
            raise ValueError(f"unsupported replay syntax for step evidence: {cmd}")
        n += 1
        mapping.append({"replay_step": len(mapping) + 1, "step": n,
                        "kind": "action", "command": cmd})
        if cmd == "close":
            continue
        path = confined_path(root, root / "steps" / shot_names[n])
        if path.exists():
            raise ValueError("step evidence target already exists; refusing stale reuse")
        path.parent.mkdir(parents=True, exist_ok=True)
        # SDK parser accepts quoted positional paths; no shell is involved.
        lines.append("screenshot " + json.dumps(str(path)) + " --no-stabilize")
        mapping.append({"replay_step": len(mapping) + 1, "step": n,
                        "kind": "screenshot", "path": str(path)})
    result = ("\n".join(lines) + "\n").encode("utf-8")
    derived.parent.mkdir(parents=True, exist_ok=True)
    derived.write_bytes(result)
    manifest = {"version": 1, "method": "agent-device-inline",
                "source": str(source.resolve()), "source_sha256": hashlib.sha256(raw).hexdigest(),
                "script": str(derived), "script_sha256": hashlib.sha256(result).hexdigest(),
                "timing": "after-step-before-next-action", "mapping": mapping,
                "resume": "Use this derived script and its SDK report planDigest; source digest is not interchangeable."}
    _mapping_path(root, derived.with_suffix(".mapping.json")).write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def _materialize_batch_evidence(source, root, derived, shot_names, raw):
    original = json.loads(raw)
    if not isinstance(original, list) or any(not isinstance(row, dict) or not row.get("command") for row in original):
        raise ValueError("every batch step must have a command")
    steps, mapping = [], []
    for n, action in enumerate(original, 1):
        steps.append(action)
        mapping.append({"replay_step": len(mapping) + 1, "step": n,
                        "kind": "action", "command": action["command"]})
        if action["command"] == "close":
            continue
        path = confined_path(root, root / "steps" / shot_names[n])
        if path.exists():
            raise ValueError("step evidence target already exists; refusing stale reuse")
        path.parent.mkdir(parents=True, exist_ok=True)
        steps.append({"command": "screenshot", "input": {"path": str(path), "stabilize": False}})
        mapping.append({"replay_step": len(mapping) + 1, "step": n,
                        "kind": "screenshot", "path": str(path)})
    result = (json.dumps(steps, indent=2) + "\n").encode()
    derived.parent.mkdir(parents=True, exist_ok=True)
    derived.write_bytes(result)
    manifest = {"version": 1, "method": "agent-device-inline",
                "source": str(source.resolve()), "source_sha256": hashlib.sha256(raw).hexdigest(),
                "script": str(derived), "script_sha256": hashlib.sha256(result).hexdigest(),
                "timing": "after-step-before-next-action", "mapping": mapping,
                "resume": "Existing serial SDK batch dispatch; no replay planDigest exists for this mode."}
    _mapping_path(root, derived.with_suffix(".mapping.json")).write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def record_sdk_plan_digest(manifest_path: Path, artifact_dir: Path, root: Path) -> None:
    """Preserve only SDK-issued digests (0.21.2 emits them on divergence).

    A success report may omit this field; never substitute source SHA or invent
    a digest. Resume remains subject to the SDK's derived-plan digest check.
    """
    manifest_path = _mapping_path(root, manifest_path)
    manifest = json.loads(manifest_path.read_text())
    log = artifact_dir / "replay.log"
    text = log.read_text(errors="replace") if log.is_file() else ""
    digests = sorted(set(re.findall(r'"planDigest"\s*:\s*"([0-9a-f]{64})"', text)))
    manifest["sdk_artifacts"] = str(artifact_dir.resolve())
    manifest["sdk_plan_digest"] = digests[0] if len(digests) == 1 else None
    manifest["sdk_plan_digest_status"] = "reported" if len(digests) == 1 else "not reported" if not digests else "ambiguous"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")


class EvidenceEvents:
    """Translate SDK telemetry only; never issue late device capture commands."""
    def __init__(self, manifest: dict, root: Path, emit):
        self.manifest, self.root, self.emit = manifest, root, emit
        self.mapping = {m["replay_step"]: m for m in manifest["mapping"]}
        self.finished, self.evidenced, self.succeeded = set(), set(), set()

    def __call__(self, payload):
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except (ValueError, TypeError):
                return
        if not isinstance(payload, dict):
            return
        kind = payload.get("type")
        if not isinstance(kind, str) or kind not in {"replay_action_start", "replay_action_stop"}:
            return
        index = payload.get("step", payload.get("stepIndex"))
        if type(index) is not int or index < 1:
            return
        item = self.mapping.get(index)
        if not item:
            return
        n = item["step"]
        replay_path = payload.get("replayPath")
        if replay_path is not None:
            if not isinstance(replay_path, str) or not replay_path:
                return
            try:
                if Path(replay_path).resolve() != Path(self.manifest["script"]).resolve():
                    return
            except (OSError, ValueError, RuntimeError):
                return
        expected = item["command"] if item["kind"] == "action" else "screenshot"
        if payload.get("command") and payload["command"] != expected:
            return
        if item["kind"] == "action":
            self.finished.add(n)
            self.emit({**payload, "step": n})
            if kind == "replay_action_stop":
                self.finished.add(n)
                if payload.get("ok") is False:
                    self._error(n, "Failed action: SDK supplied no attributable step screenshot")
                elif item["command"] == "close":
                    self._error(n, "Session closed; no step screenshot")
                elif payload.get("ok") is True:
                    self.succeeded.add(n)
        elif kind == "replay_action_stop":
            try:
                path = confined_path(self.root, Path(item["path"]))
                valid = n in self.succeeded and payload.get("ok") is True and path.is_file()
                if valid:
                    with path.open("rb") as image:
                        valid = image.read(8) == b"\x89PNG\r\n\x1a\n"
                if not valid:
                    raise ValueError("SDK screenshot failed or PNG artifact is missing")
            except (OSError, ValueError) as exc:
                self._error(n, str(exc))
                return
            self.evidenced.add(n)
            self.emit({"type": "step_evidence", "step": n, "shot": path.name,
                       "shot_capture": {"method": "agent-device-inline",
                                        "timing": "after-step-before-next-action",
                                        "replay_step": item["replay_step"],
                                        "completed_at": payload.get("ts")}})

    def _error(self, n, reason):
        self.evidenced.add(n)
        self.emit({"type": "step_evidence", "step": n, "shot_error": reason})

    def finish(self):
        for n in sorted(self.finished - self.evidenced):
            self._error(n, "Step screenshot did not complete; no later frame substituted")

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
        if isinstance(row.get("duration_ms"), int):
            item["duration_ms"] = row["duration_ms"]
        shot = row.get("shot")
        if isinstance(shot, str) and shot:
            item["shot"] = shot
            if row.get("shot_capture"):
                item["shot_capture"] = row["shot_capture"]
        elif row.get("shot_error"):
            item["shot_error"] = row["shot_error"]
        out.append(item)
    return out


def apply_event(rows: list[dict], event: dict) -> bool:
    """Apply one structured runner event. Unknown lines are not events."""
    kind = event.get("type")
    if kind not in {"replay_action_start", "replay_action_stop", "step_evidence"}:
        return False
    idx = _row_index(rows, event)
    if idx is None:
        return False
    row = rows[idx]
    if kind == "step_evidence":
        for key in ("shot", "shot_capture", "shot_error"):
            if event.get(key):
                row[key] = event[key]
        if row.get("shot"):
            row.pop("shot_error", None)
        return True
    point = point_from_event(event)
    if point:
        row["point"] = point
    if kind == "replay_action_start":
        row["state"] = "current"
        return True
    dur = event.get("durationMs")
    if not isinstance(dur, (int, float)):
        timing = event.get("resultTiming")
        if isinstance(timing, dict):
            dur = timing.get("totalDurationMs")
    if isinstance(dur, (int, float)) and dur >= 0:
        row["duration_ms"] = int(dur)
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
    origin = inp.get("origin") if isinstance(inp.get("origin"), dict) else None
    if origin:
        point = _xy(origin.get("x"), origin.get("y"))
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
    assert apply_event(
        rows, {"type": "replay_action_stop", "step": 3, "ok": True, "durationMs": 480}
    )
    assert rows[2]["state"] == "done"
    assert rows[2]["duration_ms"] == 480
    assert public_steps(rows)[2]["duration_ms"] == 480
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
