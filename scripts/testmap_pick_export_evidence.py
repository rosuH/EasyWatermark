#!/usr/bin/env python3
"""Owned Android pick/edit/export evidence; no UI/controller or credential handling.

prepare(serial, folder, marker) returns JSON-serializable state. The caller owns UI,
sets state['ui_success'] = True only after observing the requested edit/export UI,
then calls verify(state, evidence) and restore(state, evidence) in finally.
Compare preference_baseline_sha256 across sequential arms; restoration must succeed
before preparing the next arm. Never run concurrent writers on the device.

Run --self-check for a device-free ownership/parser check.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

from testmap_setup import (
    ANDROID_PACKAGE, MEDIA, adb, adb_shell, android_force_stop,
    make_fixtures, push_android_fixtures, read_private, require_android_ready,
    restore_private,
)

ALBUM = "EwmHybridBench"
ALBUM_PATH = f"Pictures/{ALBUM}/"
EXPORT_PATH = "Pictures/EasyWaterMark/"
PREFERENCES = tuple(f"files/datastore/{name}.preferences_pb" for name in (
    "sp_water_mark_config", "sp_water_mark_user_config",
))
COLUMNS = ("_id", "_display_name", "relative_path", "owner_package_name",
           "mime_type", "is_pending", "_size")
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def _hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _save(path: Path, value: dict) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(path)
    return str(path)


def _parse_rows(raw: str, columns: tuple = COLUMNS) -> list[dict]:
    if raw.strip() == "No result found.":
        return []
    rows = []
    for line in raw.strip().splitlines():
        match = re.fullmatch(r"Row: \d+ (.+)", line)
        if not match:
            raise ValueError(f"Unexpected MediaStore response: {line[:200]}")
        fields = re.split(r", (?=[A-Za-z_][A-Za-z0-9_]*=)", match[1])
        pairs = [field.split("=", 1) for field in fields]
        if any(len(pair) != 2 for pair in pairs):
            raise ValueError("Malformed MediaStore fields")
        row = dict(pairs)
        if len(row) != len(pairs) or set(row) != set(columns) or not row["_id"].isdigit():
            raise ValueError("Ambiguous MediaStore columns/identity")
        rows.append(row)
    if not rows or len({row["_id"] for row in rows}) != len(rows):
        raise ValueError("Missing or duplicate MediaStore rows")
    return rows


def _query(serial: str, columns: tuple = COLUMNS, where: str | None = None,
           *, uri: str = MEDIA) -> list[dict]:
    args = ["content", "query", "--uri", uri, "--projection", ":".join(columns)]
    if where:
        args += ["--where", where]
    return _parse_rows(adb_shell(serial, *args), columns)


def _exports(state: dict, *, completed: bool) -> list[dict]:
    rows = _query(state["serial"], where=(
        f"relative_path='{EXPORT_PATH}' AND owner_package_name='{ANDROID_PACKAGE}' "
        "AND _display_name LIKE '%.png'"
    ))
    baseline = set(state["baseline_ids"])
    return [row for row in rows if (
        row["_id"] not in baseline
        and row["owner_package_name"] == ANDROID_PACKAGE
        and row["relative_path"] == EXPORT_PATH
        and row["_display_name"].endswith(".png")
        and "/" not in row["_display_name"]
        and row["mime_type"] == "image/png"
        and (not completed or (row["is_pending"] == "0" and int(row["_size"]) > 0))
    )]


def _album_contents(serial: str) -> tuple[bool, list[str]]:
    remote = f"/sdcard/{ALBUM_PATH.rstrip('/')}"
    raw = adb_shell(serial, "sh", "-c",
                    f"if test -e {remote}; then echo exists; ls -A {remote}; else echo absent; fi")
    lines = raw.splitlines()
    if not lines or lines[0] not in {"exists", "absent"}:
        raise ValueError("Cannot inspect benchmark album")
    return lines[0] == "exists", lines[1:]


def _source_rows(state: dict) -> list[dict]:
    return _query(state["serial"], where=(
        f"relative_path='{ALBUM_PATH}' AND _display_name='{state['source']['display_name']}'"
    ))


def _picker_identity(rows: list[dict], source_id: str, timezone: str) -> dict:
    def epoch(row):
        taken = row["datetaken"]
        value, divisor = (row["date_modified"], 1) if taken.lower() in {"null", ""} else (taken, 1000)
        if value.lower() in {"null", ""}:
            return None
        if not re.fullmatch(r"-?\d+", value):
            raise ValueError("Invalid MediaStore picker timestamp")
        return int(value) // divisor

    source = [row for row in rows if row["_id"] == source_id]
    if len(source) != 1 or not timezone.strip():
        raise ValueError("Missing unique source row or device timezone")
    seconds = epoch(source[0])
    if seconds is None or seconds <= 0 or sum(epoch(row) == seconds for row in rows) != 1:
        raise ValueError("Source picker timestamp is missing or shared by another image")
    return {"date_modified": source[0]["date_modified"], "datetaken": source[0]["datetaken"],
            "picker_epoch_seconds": seconds, "picker_timezone": timezone.strip()}


def prepare(serial: str, folder: Path, marker: str) -> dict:
    """Prepare one source with a globally unique picker timestamp, in an owned album.

    Raises on unsafe/reused names or an occupied album. A failed partial setup is
    journaled and cleaned before re-raising; inspect its cleanup JSON on failure.
    Does not launch the app or change preferences. The UI may select the exact
    timestamp in Photos directly; visiting the album is not a requirement.
    """
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", marker):
        raise ValueError("marker must be 1..64 ASCII letters/digits/underscore/hyphen, alphanumeric first")
    if not shutil.which("magick"):
        raise ValueError("Installed magick is required for real image decoding")
    work = Path(folder).resolve() / "pick-export"
    work.mkdir(parents=True, exist_ok=False)
    require_android_ready(serial)
    android_force_stop(serial)
    existed, contents = _album_contents(serial)
    album_rows = _query(serial, ("_id",), f"relative_path LIKE '{ALBUM_PATH}%'")
    if contents or album_rows:
        raise ValueError("EwmHybridBench contains existing files/rows; refusing to touch them")
    name = f"ewm-suite-{marker}-A.png"
    if _query(serial, ("_id",), f"_display_name='{name}'"):
        raise ValueError("Fixture marker/name already exists in MediaStore")
    state = {
        "serial": serial, "folder": str(work), "album": ALBUM,
        "album_path": ALBUM_PATH, "marker": marker, "marker_text": f"EWM BENCH {marker}",
        "baseline_ids": [row["_id"] for row in _query(serial, ("_id",))],
        "album_existed": existed, "preferences": {}, "ui_success": False,
        "source": {}, "prepared": False,
    }
    _save(work / "prepare.json", state)
    try:
        for remote in PREFERENCES:
            data = read_private(serial, remote)
            backup = work / Path(remote).name
            if data is not None:
                backup.write_bytes(data)
            state["preferences"][remote] = {
                "path": str(backup) if data is not None else None,
                "sha256": _hash(data) if data is not None else None,
            }
            _save(work / "prepare.json", state)
        state["preference_baseline_sha256"] = _hash(json.dumps(
            {key: value["sha256"] for key, value in state["preferences"].items()},
            sort_keys=True,
        ).encode())
        fixtures = make_fixtures(work / "fixtures", marker)
        source = fixtures["A"]
        state["source"] = {
            "path": str(source), "display_name": source.name,
            "remote_path": f"/sdcard/{ALBUM_PATH}{source.name}",
            "sha256": _hash(source.read_bytes()), "media_id": None, "uri": None,
            "width": 960, "height": 640,
        }
        _save(work / "prepare.json", state)
        push_android_fixtures(serial, {"A": source}, ALBUM_PATH.rstrip("/"))
        deadline = time.monotonic() + 15
        while True:
            rows = _source_rows(state)
            if len(rows) == 1 and rows[0]["_id"] not in state["baseline_ids"]:
                state["source"].update(media_id=rows[0]["_id"], uri=f"{MEDIA}/{rows[0]['_id']}")
                break
            if time.monotonic() >= deadline:
                raise ValueError("Fixture was not uniquely indexed")
            time.sleep(0.25)
        _, contents = _album_contents(serial)
        album_rows = _query(serial, ("_id",), f"relative_path LIKE '{ALBUM_PATH}%'")
        if contents != [source.name] or album_rows != [{"_id": state["source"]["media_id"]}]:
            raise ValueError("Benchmark album does not contain exactly the owned A image")
        # Aggregate external volumes, including unrelated albums, to reject duplicate
        # picker labels. Date taken is milliseconds; date modified is seconds.
        timestamp_rows = _query(serial, ("_id", "date_modified", "datetaken"),
                                uri="content://media/external/images/media")
        timezone = adb_shell(serial, "getprop", "persist.sys.timezone")
        state["source"].update(_picker_identity(timestamp_rows, state["source"]["media_id"], timezone))
        state["prepared"] = True
        _save(work / "prepare.json", state)
        return state
    except Exception as exc:
        state["prepare_error"] = str(exc)
        _save(work / "prepare.json", state)
        cleanup = restore(state, work)
        raise RuntimeError(f"prepare failed: {exc}; cleanup: {cleanup['report_path']}") from exc


def _decode_png(path: Path) -> bytes:
    header = path.read_bytes()[:24]
    if len(header) != 24 or header[:8] != PNG_MAGIC or header[12:16] != b"IHDR" or struct.unpack(
        ">II", header[16:24]
    ) != (960, 640):
        raise ValueError("Expected PNG IHDR 960x640")
    dimensions = subprocess.run(["magick", str(path), "-format", "%w %h", "info:"],
                                capture_output=True, check=True, timeout=30).stdout
    if dimensions.strip() != b"960 640":
        raise ValueError("magick dimensions differ from 960x640")
    pixels = subprocess.run(["magick", str(path), "-alpha", "off", "-colorspace", "sRGB",
                             "-depth", "8", "rgb:-"],
                            capture_output=True, check=True, timeout=30).stdout
    if len(pixels) != 960 * 640 * 3:
        raise ValueError("magick did not decode exactly one RGB image")
    return pixels


def _bands(pixels: bytes) -> dict:
    ratios = {}
    for name, start, stop, rgb in (
        ("blue", 0, 56, (25, 77, 128)),
        ("white", 56, 584, (255, 255, 255)),
        ("gold", 584, 640, (232, 183, 70)),
    ):
        begin, end = start * 960 * 3, stop * 960 * 3
        matched = sum(all(abs(pixels[i + channel] - rgb[channel]) <= 12 for channel in range(3))
                      for i in range(begin, end, 3))
        ratios[name] = matched / ((stop - start) * 960)
    return ratios


def verify(state: dict, evidence: Path) -> dict:
    """Capture independent facts. success = explicit caller UI assertion AND file checks.

    Marker byte presence is not protobuf field parsing or proof of rendered text.
    OCR is observational only and never upgrades failed checks to success.
    """
    evidence = Path(evidence).resolve()
    report = {"success": False, "file_checks_passed": False,
              "ui_success": state.get("ui_success") is True, "checks": {}, "outputs": [],
              "ocr": {"available": bool(shutil.which("tesseract")), "used_for_success": False},
              "limitations": ["Caller supplies UI acceptance; file checks do not verify UI.",
                              "DataStore byte presence and image changes do not prove exact rendered text; OCR is supplemental."]}
    report["report_path"] = str(evidence / "pick-export-verification.json")
    try:
        if not state.get("prepared"):
            raise ValueError("Setup did not complete")
        source = Path(state["source"]["path"])
        report["checks"]["source_hash_unchanged"] = _hash(source.read_bytes()) == state["source"]["sha256"]
        deadline = time.monotonic() + 10
        while True:
            config = read_private(state["serial"], PREFERENCES[0])
            rows = _exports(state, completed=True)
            marker_present = config is not None and state["marker_text"].encode() in config
            if (rows and marker_present) or time.monotonic() >= deadline:
                break
            time.sleep(0.25)
        report["checks"]["marker_bytes_persisted"] = marker_present
        if config is not None:
            config_path = evidence / "pick-export-watermark.preferences_pb"
            config_path.parent.mkdir(parents=True, exist_ok=True)
            config_path.write_bytes(config)
            report["datastore"] = {"path": str(config_path), "sha256": _hash(config)}
        all_owned = _exports(state, completed=False)
        report["new_owned_rows"] = all_owned
        report["checks"]["exactly_one_completed_new_export"] = len(rows) == 1 and len(all_owned) == 1
        if len(rows) != 1:
            raise ValueError(f"Expected one completed fresh owned PNG, got {len(rows)}")
        row = rows[0]
        uri = f"{MEDIA}/{row['_id']}"
        payload = adb(state["serial"], ["exec-out", "content", "read", "--uri", uri], binary=True)
        output = evidence / f"pick-export-output-{row['_id']}.png"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(payload)
        report["outputs"].append({"uri": uri, "row": row, "path": str(output),
                                  "sha256": _hash(payload), "size": len(payload)})
        report["checks"]["published_size_matches_file"] = len(payload) == int(row["_size"]) > 0
        pixels, original = _decode_png(output), _decode_png(source)
        report["checks"]["png_decoded_960x640"] = True
        report["checks"]["output_pixels_differ_from_source"] = pixels != original
        report["checks"]["output_hash_differs_from_source"] = _hash(payload) != state["source"]["sha256"]
        report["source_band_ratios"] = _bands(pixels)
        report["checks"]["source_bands_retained"] = all(value >= 0.60 for value in report["source_band_ratios"].values())
        report["file_checks_passed"] = all(report["checks"].values())
        if report["ocr"]["available"]:
            try:
                result = subprocess.run(["tesseract", str(output), "stdout"],
                                        capture_output=True, timeout=30)
                text_path = evidence / "pick-export-ocr.txt"
                text_path.write_bytes(result.stdout)
                report["ocr"].update(path=str(text_path), exit_code=result.returncode,
                                      exact_marker_seen=state["marker_text"] in result.stdout.decode(errors="replace"),
                                      stderr=result.stderr.decode(errors="replace"))
            except (OSError, subprocess.TimeoutExpired) as exc:
                report["ocr"]["error"] = str(exc)
        report["success"] = report["ui_success"] and report["file_checks_passed"]
    except Exception as exc:
        report["error"] = str(exc)
    _save(Path(report["report_path"]), report)
    return report


def restore(state: dict, evidence: Path) -> dict:
    """Delete only new, revalidated owned PNGs/fixture; restore exact preference bytes.

    Preserves baseline IDs and unrelated rows/files. Pending owned PNGs are eligible
    for cleanup too. Leaves the app force-stopped, including after failed cleanup.
    """
    serial = state["serial"]
    report = {"success": False, "deleted_ids": [], "errors": [], "preferences_restored": False,
              "report_path": str(Path(evidence).resolve() / "pick-export-cleanup.json")}
    try:
        android_force_stop(serial)
        candidates = _exports(state, completed=False)
        if state.get("source"):
            candidates += [row for row in _source_rows(state)
                           if row["_id"] not in state["baseline_ids"]
                           and row["relative_path"] == ALBUM_PATH
                           and row["_display_name"] == state["source"]["display_name"]
                           and row["_id"] == (state["source"]["media_id"] or row["_id"])]
        for row in candidates:
            fresh = _query(serial, where=f"_id={row['_id']}")
            if fresh != [row] or row["_id"] in state["baseline_ids"]:
                raise ValueError(f"Refusing changed/baseline row {row['_id']}")
            adb_shell(serial, "content", "delete", "--uri", f"{MEDIA}/{row['_id']}")
            if _query(serial, ("_id",), f"_id={row['_id']}"):
                raise ValueError(f"MediaStore deletion did not persist: {row['_id']}")
            report["deleted_ids"].append(row["_id"])
        if state.get("source"):
            remote = state["source"]["remote_path"]
            exists = adb_shell(serial, "sh", "-c", f"if test -f {remote}; then echo yes; fi")
            if exists == "yes":
                payload = adb(serial, ["exec-out", "cat", remote], binary=True)
                if _hash(payload) != state["source"]["sha256"]:
                    raise ValueError("Refusing changed fixture file")
                adb_shell(serial, "rm", "-f", remote)
        existed, contents = _album_contents(serial)
        report["album_empty"] = not contents and not _query(serial, ("_id",), f"relative_path LIKE '{ALBUM_PATH}%'")
        if existed and not contents and not state["album_existed"]:
            adb_shell(serial, "rmdir", f"/sdcard/{ALBUM_PATH.rstrip('/')}")
        remaining = {row["_id"] for row in _query(serial, ("_id",))}
        report["baseline_ids_preserved"] = set(state["baseline_ids"]).issubset(remaining)
        report["owned_exports_absent"] = not _exports(state, completed=False)
    except Exception as exc:
        report["errors"].append(str(exc))
    finally:
        try:
            backup = {}
            for remote, item in state["preferences"].items():
                data = Path(item["path"]).read_bytes() if item["path"] is not None else None
                if (_hash(data) if data is not None else None) != item["sha256"]:
                    raise ValueError(f"Backup hash mismatch: {remote}")
                backup[remote] = data
            restore_private(serial, backup)
            report["preferences_restored"] = all(read_private(serial, key) == value for key, value in backup.items())
        except Exception as exc:
            report["errors"].append(f"preference restore: {exc}")
        try:
            android_force_stop(serial)
        except Exception as exc:
            report["errors"].append(f"final force-stop: {exc}")
    report["success"] = not report["errors"] and all(report.get(key) is True for key in (
        "album_empty", "baseline_ids_preserved", "owned_exports_absent", "preferences_restored",
    ))
    _save(Path(report["report_path"]), report)
    return report


def _self_check() -> None:
    import tempfile
    from unittest.mock import patch

    timestamps = _parse_rows(
        "Row: 0 _id=10, date_modified=1700000000, datetaken=NULL\n"
        "Row: 1 _id=11, date_modified=1700000000, datetaken=1700000001999",
        ("_id", "date_modified", "datetaken"),
    )
    identity = _picker_identity(timestamps, "10", "Asia/Singapore")
    assert identity["picker_epoch_seconds"] == 1700000000
    assert identity["picker_timezone"] == "Asia/Singapore"
    assert _picker_identity(timestamps, "11", "UTC")["picker_epoch_seconds"] == 1700000001
    timestamps[1]["datetaken"] = "1700000000999"
    try:
        _picker_identity(timestamps, "10", "UTC")
    except ValueError:
        pass
    else:
        raise AssertionError("Duplicate picker seconds must fail, including date-taken/modified collisions")

    def row(identity, owner=ANDROID_PACKAGE, path=EXPORT_PATH, pending="0"):
        return dict(zip(COLUMNS, (str(identity), f"ewm_{identity}.png", path, owner,
                                  "image/png", pending, "100")))

    rows = [row(1), row(2), row(3, owner="other.app"), row(4, path="Pictures/Other/"), row(5, pending="1")]
    deleted = []

    def shell(serial, *args):
        assert serial == "mock-device"
        if args[:2] == ("content", "query"):
            columns = tuple(args[args.index("--projection") + 1].split(":"))
            where = args[args.index("--where") + 1] if "--where" in args else ""
            selected = rows
            if where.startswith("_id="):
                selected = [item for item in rows if item["_id"] == where[4:]]
            elif ALBUM_PATH in where:
                selected = []
            # Deliberately return unrelated rows for the exports query: local guards must reject them.
            return "\n".join(f"Row: {i} " + ", ".join(f"{key}={item[key]}" for key in columns)
                             for i, item in enumerate(selected)) or "No result found."
        if args[:2] == ("content", "delete"):
            identity = args[-1].rsplit("/", 1)[1]
            deleted.append(identity)
            rows[:] = [item for item in rows if item["_id"] != identity]
            return "Deleted 1 rows."
        if args[0] == "sh":
            return "absent"
        raise AssertionError(args)

    with tempfile.TemporaryDirectory() as folder, patch.multiple(
        sys.modules[__name__], adb_shell=shell, android_force_stop=lambda serial: None,
        restore_private=lambda serial, backup: None,
    ):
        state = {"serial": "mock-device", "baseline_ids": ["1"], "preferences": {},
                 "source": {}, "album_existed": True}
        assert [item["_id"] for item in _exports(state, completed=True)] == ["2"]
        assert _parse_rows("No result found.", ("_id",)) == []
        try:
            _parse_rows("Error while accessing provider")
        except ValueError:
            pass
        else:
            raise AssertionError("Provider errors must not become an empty baseline")
        result = restore(state, Path(folder))
        assert result["success"], result
        assert deleted == ["2", "5"], deleted
        assert {item["_id"] for item in rows} == {"1", "3", "4"}
    print("self-check passed: fresh identity, pending cleanup, unrelated/baseline preservation")


if __name__ == "__main__":
    if sys.argv[1:] != ["--self-check"]:
        raise SystemExit("Use --self-check; prepare/verify/restore are importable helpers")
    _self_check()
