#!/usr/bin/env python3
"""Builder-agnostic testmap device setup (Python 3 stdlib only).

map.yaml remains the only topology. Artemis and Agent Device share these
preconditions so ACTION_SEND, crash XML, density, and synthetic fixtures
are not copied into either runner.

Setup keys: home | editor | wide | crash | failure | ios

Does not replay .ad scripts, ingest layers, or mint human confirmation.
Does not reinstall, clear-app-state, uninstall production, or shut down
live emulators/simulators.
"""

from __future__ import annotations

import os
import re
import shlex
import shutil
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = Path(__file__).resolve().parent
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from testmap_agent_device import (  # noqa: E402
    AGENT_DEVICE_CASES_PATH as CASES_PATH,
    PINNED_VERSION as PINNED_CLI_VERSION,
    validate_agent_device_binding,
)

MAP_PATH = REPO_ROOT / "docs" / "testmap" / "map.yaml"
ANDROID_PACKAGE = "me.rosuh.easywatermark.debug"
IOS_BUNDLE = "me.rosuh.easywatermark.ios"
ACTIVITY = f"{ANDROID_PACKAGE}/me.rosuh.easywatermark.ui.MainActivity"
MEDIA = "content://media/external_primary/images/media"
FIXTURE_FOLDER = "Pictures/EwmArtemis"
CRASH_PREF = "shared_prefs/sp_water_mark_crash_info.xml"
SETUPS = frozenset({"home", "editor", "wide", "crash", "failure", "ios"})
ANDROID_SETUPS = frozenset({"home", "editor", "wide", "crash", "failure"})
IOS_SETUPS = frozenset({"home", "editor", "ios", "wide"})
PNG_SIZE = (960, 640)


def pinned_cli() -> str:
    """Resolve the pinned agent-device binary. Never npx @latest."""
    path = shutil.which("agent-device")
    if not path:
        raise ValueError("agent-device is not on PATH; pin 0.21.2 and install locally")
    return path


def require_pinned_cli() -> str:
    exe = pinned_cli()
    result = subprocess.run([exe, "--version"], capture_output=True, text=True, timeout=15)
    version = (result.stdout or result.stderr or "").strip().splitlines()[0].strip()
    if PINNED_CLI_VERSION not in version:
        raise ValueError(
            f"agent-device must be {PINNED_CLI_VERSION}, got {version!r} from {exe}"
        )
    return exe


def adb_bin() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidate = Path(sdk) / "platform-tools" / "adb"
        if candidate.is_file():
            return str(candidate)
    home = Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb"
    if home.is_file():
        return str(home)
    found = shutil.which("adb")
    if not found:
        raise ValueError("adb is not on PATH")
    return found


def _run(argv, *, timeout=45, input_data=None, binary=False):
    result = subprocess.run(
        argv,
        capture_output=True,
        timeout=timeout,
        input=input_data,
    )
    if result.returncode != 0:
        err = (result.stderr or result.stdout or b"").decode(errors="replace")
        raise ValueError(f"{shlex.join(argv)} failed ({result.returncode}): {err.strip()}")
    if binary:
        return result.stdout
    return (result.stdout or b"").decode().strip()


def adb(serial, args, *, timeout=45, binary=False, input_data=None):
    if not serial or not re.fullmatch(r"[A-Za-z0-9._:\[\]-]+", serial):
        raise ValueError("--serial is required and must be a valid adb serial")
    return _run([adb_bin(), "-s", serial, *args], timeout=timeout, binary=binary, input_data=input_data)


def adb_shell(serial, *parts, timeout=45):
    return adb(serial, ["shell", shlex.join(parts)], timeout=timeout)


def _png_chunk(tag: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png(path: Path, width: int, height: int, row_rgb) -> Path:
    """Write an RGB PNG. row_rgb(y) -> bytes of length width*3."""
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw.extend(row_rgb(y))
    payload = (
        b"\x89PNG\r\n\x1a\n"
        + _png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + _png_chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + _png_chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return path


def _band_row(width, y, height, top, mid, bottom, band=56):
    if y < band:
        rgb = top
    elif y >= height - band:
        rgb = bottom
    else:
        rgb = mid
    return bytes(rgb) * width


def make_fixtures(folder: Path, marker: str) -> dict[str, Path]:
    """Stdlib A/B/icon PNGs. A = blue/yellow, B = purple/green, icon = red."""
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    width, height = PNG_SIZE
    paths = {key: folder / f"ewm-suite-{marker}-{key}.png" for key in ("A", "B", "icon")}
    write_png(
        paths["A"],
        width,
        height,
        lambda y: _band_row(width, y, height, (25, 77, 128), (255, 255, 255), (232, 183, 70)),
    )
    write_png(
        paths["B"],
        width,
        height,
        lambda y: _band_row(width, y, height, (119, 51, 153), (255, 255, 255), (34, 136, 102)),
    )
    write_png(paths["icon"], 128, 128, lambda y: bytes((223, 48, 48)) * 128)
    return paths


def push_android_fixtures(serial: str, fixtures: dict[str, Path]) -> None:
    adb_shell(serial, "mkdir", "-p", f"/sdcard/{FIXTURE_FOLDER}")
    for path in fixtures.values():
        remote = f"/sdcard/{FIXTURE_FOLDER}/{path.name}"
        adb(serial, ["push", str(path), remote])
        adb_shell(
            serial,
            "am",
            "broadcast",
            "-a",
            "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d",
            f"file://{remote}",
        )


def wait_media_id(serial: str, display_name: str, timeout_s: float = 15) -> str:
    deadline = time.monotonic() + timeout_s
    where = f"_display_name='{display_name}' AND relative_path='{FIXTURE_FOLDER}/'"
    while time.monotonic() < deadline:
        # adb shell argv must be one quoted command: an unquoted --where
        # with spaces is parsed as content(1) usage, so the fixture never
        # looks uniquely indexed.
        raw = adb_shell(
            serial,
            "content",
            "query",
            "--uri",
            MEDIA,
            "--projection",
            "_id",
            "--where",
            where,
        )
        ids = re.findall(r"Row: \d+ _id=(\d+)", raw)
        if len(ids) == 1:
            return ids[0]
        time.sleep(0.5)
    raise ValueError(f"Synthetic image {display_name} was not uniquely indexed in MediaStore")


def android_force_stop(serial: str) -> None:
    adb_shell(serial, "am", "force-stop", ANDROID_PACKAGE)


def android_start_home(serial: str) -> None:
    adb_shell(serial, "am", "start", "-W", "-n", ACTIVITY)


def android_share_in(serial: str, media_id: str) -> None:
    adb_shell(
        serial,
        "am",
        "start",
        "-W",
        "-n",
        ACTIVITY,
        "-a",
        "android.intent.action.SEND",
        "-t",
        "image/png",
        "--eu",
        "android.intent.extra.STREAM",
        f"{MEDIA}/{media_id}",
        "--grant-read-uri-permission",
        "-f",
        "1",
    )


def record_display(serial: str) -> dict[str, str]:
    return {key: adb_shell(serial, "wm", key) for key in ("size", "density")}


def restore_display(serial: str, backup: dict[str, str]) -> None:
    for key, raw in backup.items():
        match = re.search(rf"Override {key}: (\S+)", raw)
        adb_shell(serial, "wm", key, match[1] if match else "reset")


def set_wide(serial: str) -> None:
    adb_shell(serial, "wm", "size", "1600x1200")
    adb_shell(serial, "wm", "density", "160")


def set_compact(serial: str) -> None:
    adb_shell(serial, "wm", "size", "1080x1920")
    adb_shell(serial, "wm", "density", "420")


def _run_as_file(serial: str, path: str) -> bool:
    out = adb_shell(
        serial,
        "run-as",
        ANDROID_PACKAGE,
        "sh",
        "-c",
        f"if test -f {path}; then echo yes; else echo no; fi",
    )
    return out == "yes"


def read_private(serial: str, path: str) -> bytes | None:
    if not _run_as_file(serial, path):
        return None
    return adb(serial, ["exec-out", "run-as", ANDROID_PACKAGE, "cat", path], binary=True)


def write_private(serial: str, path: str, data: bytes, timeout: int = 45) -> None:
    argv = [
        adb_bin(),
        "-s",
        serial,
        "shell",
        "run-as",
        ANDROID_PACKAGE,
        "sh",
        "-c",
        f"cat > {shlex.quote(path)}",
    ]
    result = subprocess.run(argv, capture_output=True, timeout=timeout, input=data)
    if result.returncode != 0:
        err = (result.stderr or result.stdout or b"").decode(errors="replace")
        raise ValueError(f"write_private {path} failed: {err.strip()}")


def inject_crash_gate(serial: str, version_code: str) -> dict[str, bytes | None]:
    backup: dict[str, bytes | None] = {}
    adb_shell(serial, "run-as", ANDROID_PACKAGE, "mkdir", "-p", "shared_prefs")
    for path in (CRASH_PREF, CRASH_PREF + ".bak"):
        backup[path] = read_private(serial, path)
    tree = ET.fromstring(backup[CRASH_PREF] or b"<map />")
    for key, value in (("crash_count", "2"), ("recovery_version", version_code)):
        name = f"sp_water_mark_crash_info_key_{key}"
        element = tree.find(f"int[@name='{name}']")
        if element is None:
            element = ET.SubElement(tree, "int", name=name)
        element.set("value", value)
    adb_shell(serial, "run-as", ANDROID_PACKAGE, "rm", "-f", CRASH_PREF + ".bak")
    write_private(
        serial,
        CRASH_PREF,
        ET.tostring(tree, encoding="utf-8", xml_declaration=True),
    )
    return backup


def restore_private(serial: str, backup: dict[str, bytes | None]) -> None:
    android_force_stop(serial)
    for path, data in backup.items():
        if data is None:
            adb_shell(serial, "run-as", ANDROID_PACKAGE, "rm", "-f", path)
            if _run_as_file(serial, path):
                raise ValueError(f"Unexpected restored preference file: {path}")
        else:
            write_private(serial, path, data)
            restored = read_private(serial, path)
            if restored != data:
                raise ValueError(f"Preference restore byte mismatch: {path}")


def installed_version_code(serial: str) -> str:
    dump = adb_shell(serial, "dumpsys", "package", ANDROID_PACKAGE)
    match = re.search(r"versionCode=(\d+)", dump)
    if not match:
        raise ValueError("Could not establish installed APK version for recovery gate")
    return match[1]


def damage_source(serial: str, remote_name: str, marker: str, folder: Path) -> str:
    remote = f"/sdcard/{FIXTURE_FOLDER}/{remote_name}"
    invalid = Path(folder) / f"ewm-invalid-{marker}.bin"
    invalid.write_bytes(f"EWM deliberate source decode failure {marker}".encode())
    adb(serial, ["push", str(invalid), remote])
    return remote


def restore_source(serial: str, local: Path, remote: str) -> None:
    adb(serial, ["push", str(local), remote])


def require_android_ready(serial: str) -> None:
    if adb(serial, ["get-state"]) != "device":
        raise ValueError("An online Android device is required")
    sdk = int(adb_shell(serial, "getprop", "ro.build.version.sdk"))
    if sdk < 29:
        raise ValueError("An online Android API 29+ device is required")
    if not adb_shell(serial, "pm", "path", ANDROID_PACKAGE).startswith("package:"):
        raise ValueError("Install the debug APK before running the suite")


def simctl(udid: str, *args, timeout=45):
    if not udid or not re.fullmatch(r"[0-9A-Fa-f-]{25,}", udid):
        raise ValueError("--udid is required and must be a simulator UDID")
    return _run(["xcrun", "simctl", *args], timeout=timeout)


def ios_revoke_library_read(udid: str) -> None:
    simctl(udid, "privacy", udid, "revoke", "photos", IOS_BUNDLE)


def apply_setup(
    setup: str,
    *,
    platform: str,
    folder: Path,
    marker: str,
    serial: str | None = None,
    udid: str | None = None,
) -> dict:
    """Apply a named setup. Returns state for restore_setup()."""
    if setup not in SETUPS:
        raise ValueError(f"Unknown setup {setup!r}; expected one of {sorted(SETUPS)}")
    platform = platform.lower()
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    state = {
        "setup": setup,
        "platform": platform,
        "serial": serial,
        "udid": udid,
        "marker": marker,
        "fixtures": {},
        "display_backup": {},
        "private_backup": {},
        "source_id": None,
        "damaged_remote": None,
    }
    if platform == "android":
        if setup not in ANDROID_SETUPS:
            raise ValueError(f"Setup {setup} is not an Android harness key")
        if not serial:
            raise ValueError("Android setup requires --serial")
        require_android_ready(serial)
        fixtures = make_fixtures(folder, marker)
        state["fixtures"] = {key: str(path) for key, path in fixtures.items()}
        push_android_fixtures(serial, fixtures)
        android_force_stop(serial)
        if setup == "wide":
            state["display_backup"] = record_display(serial)
            set_wide(serial)
        if setup == "crash":
            version = installed_version_code(serial)
            state["private_backup"] = inject_crash_gate(serial, version)
        if setup in {"home", "crash"}:
            android_start_home(serial)
        else:
            source_id = wait_media_id(serial, Path(state["fixtures"]["A"]).name)
            state["source_id"] = source_id
            android_share_in(serial, source_id)
        if setup == "failure":
            state["damaged_remote"] = damage_source(
                serial, Path(state["fixtures"]["A"]).name, marker, folder
            )
        return state
    if platform == "ios":
        if setup not in IOS_SETUPS:
            raise ValueError(f"Setup {setup} is not an iOS harness key")
        if not udid:
            raise ValueError("iOS setup requires --udid")
        if setup == "ios":
            ios_revoke_library_read(udid)
        # editor/home/wide: scripts attach or relaunch. Width change is a
        # simulator device choice (iPad), not a runtime morph on iPhone.
        return state
    raise ValueError(f"Unsupported setup platform {platform}")


def restore_setup(state: dict) -> None:
    platform = state.get("platform")
    serial = state.get("serial")
    if platform != "android" or not serial:
        return
    damaged = state.get("damaged_remote")
    fixtures = state.get("fixtures") or {}
    if damaged and fixtures.get("A"):
        restore_source(serial, Path(fixtures["A"]), damaged)
    backup = state.get("display_backup") or {}
    if backup:
        restore_display(serial, backup)
    private = state.get("private_backup") or {}
    if private:
        restore_private(serial, private)


def map_edge_ids(map_text: str | None = None) -> list[str]:
    text = map_text if map_text is not None else MAP_PATH.read_text(encoding="utf-8")
    # Only collect edge ids from the edges: block, not node ids.
    _, _, rest = text.partition("\nedges:")
    if not rest:
        raise ValueError("map.yaml has no edges block")
    ids = re.findall(r"(?m)^  - id: ([a-z0-9][a-z0-9-]*)\s*$", rest)
    if len(ids) != 29:
        raise ValueError(f"map.yaml must have 29 edges, got {len(ids)}: {ids}")
    return ids


def main() -> int:
    ids = map_edge_ids()
    validate_agent_device_binding(ids)
    print(
        f"ok: {len(ids)} edges, setup keys {sorted(SETUPS)}, "
        f"cli {PINNED_CLI_VERSION}, payload {CASES_PATH.relative_to(REPO_ROOT)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
