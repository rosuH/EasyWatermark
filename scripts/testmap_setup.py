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

import argparse
import base64
import contextvars
import hashlib
import json
import os
import plistlib
import re
import shlex
import shutil
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zlib
import uuid
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

from testmap_stop import CLEANUP_CMD_TIMEOUT_S, RESTORE_BUDGET_S, terminate_process_group

_CLEANUP_DEADLINE = contextvars.ContextVar("setup_cleanup_deadline", default=None)
_SETUP_STOP = contextvars.ContextVar("setup_stop", default=None)

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
SETUP_BACKUPS = REPO_ROOT / "build" / "testmap" / "setup-backups"
ANDROID_CONFIG = (
    "files/datastore/sp_water_mark_config.preferences_pb",
    "files/datastore/sp_water_mark_user_config.preferences_pb",
)
EXPORT_CONTROL = "files/testmap-export-control.json"
EXPORT_EVENTS = "files/testmap-export-events.jsonl"
EXPORT_CONTROL_PATHS = (EXPORT_CONTROL, EXPORT_EVENTS)
EXPORT_RUN_ID = r"[A-Za-z0-9][A-Za-z0-9_-]{0,95}"

IOS_CONFIG = tuple("Documents/" + Path(path).name for path in ANDROID_CONFIG)


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
    deadline = _CLEANUP_DEADLINE.get()
    if deadline is not None:
        timeout = min(timeout, CLEANUP_CMD_TIMEOUT_S, deadline - time.monotonic())
        if timeout <= 0:
            raise TimeoutError("Setup restore deadline exceeded; retained backup requires recovery")
    should_stop = _SETUP_STOP.get() if deadline is None else None
    if should_stop and should_stop():
        raise InterruptedError("Setup stopped; restoring saved preferences")
    if should_stop:
        proc = subprocess.Popen(argv, stdin=subprocess.PIPE if input_data is not None else None,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        expires = time.monotonic() + timeout
        pending_input = input_data
        try:
            while True:
                if should_stop():
                    raise InterruptedError("Setup stopped; restoring saved preferences")
                remaining = expires - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(argv, timeout)
                try:
                    stdout, stderr = proc.communicate(input=pending_input, timeout=min(.25, remaining))
                    result = subprocess.CompletedProcess(argv, proc.returncode, stdout, stderr)
                    break
                except subprocess.TimeoutExpired:
                    pending_input = None
        except BaseException:
            terminate_process_group(proc, term_s=.2, kill_s=.2)
            for stream in (proc.stdin, proc.stdout, proc.stderr):
                if stream is not None:
                    stream.close()
            raise
    else:
        result = subprocess.run(argv, capture_output=True, timeout=timeout, input=input_data)
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
    width, height = (2400, 2400) if str(marker).startswith("exportcancel") else PNG_SIZE
    paths = {key: folder / f"ewm-suite-{marker}-{key}.png" for key in ("A", "B", "C", "icon")}
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
    write_png(
        paths["C"],
        width,
        height,
        lambda y: _band_row(width, y, height, (16, 122, 109), (255, 255, 255), (214, 90, 36)),
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


def android_reset_saved_config(serial: str) -> None:
    """Only called after apply_setup has durably backed up both files."""
    for name in ANDROID_CONFIG:
        adb_shell(serial, "run-as", ANDROID_PACKAGE, "rm", "-f", name)


def android_leave_system_picker(serial: str) -> None:
    """Leave a system photo picker so the next case does not open behind it."""
    try:
        adb_shell(serial, "input", "keyevent", "KEYCODE_HOME")
    except ValueError:
        pass
    for package in (
        "com.google.android.apps.photos",
        "com.google.android.photopicker",
        "com.android.photopicker",
        "com.android.providers.media.module",
    ):
        try:
            adb_shell(serial, "am", "force-stop", package)
        except ValueError:
            continue


def android_start_home(serial: str) -> None:
    """Open the launcher task. A previous share-in task must not come back as the editor."""
    # NEW_TASK | CLEAR_TASK | CLEAR_TOP. Force-stop alone still lets the next
    # plain start reuse the last SEND task on some images.
    adb_shell(
        serial,
        "am",
        "start",
        "-W",
        "-n",
        ACTIVITY,
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.LAUNCHER",
        "-f",
        "0x14008000",
    )


def android_frame_hash(serial: str) -> bytes:
    """Hash the framebuffer below the status bar so the clock does not count as motion."""
    raw = adb(serial, ["exec-out", "screencap"], binary=True, timeout=20)
    if len(raw) < 16:
        return hashlib.sha256(raw).digest()
    width, height = struct.unpack_from("<II", raw, 0)
    stride = width * 4
    header = 16 if len(raw) >= 16 + height * stride else 12
    skip_rows = min(120, height)
    body = raw[header + skip_rows * stride :]
    return hashlib.sha256(body).digest()


def android_ui_xml(serial: str) -> str:
    remote = "/sdcard/ewm-ready.xml"
    adb_shell(serial, "uiautomator", "dump", remote)
    xml = adb(serial, ["exec-out", "cat", remote], timeout=20)
    return xml if isinstance(xml, str) else ""


def android_ui_contains(serial: str, text: str) -> bool:
    try:
        xml = android_ui_xml(serial)
    except (ValueError, OSError, subprocess.TimeoutExpired):
        return False
    return f'text="{text}"' in xml or f'content-desc="{text}"' in xml


def _ui_has(xml: str, text: str) -> bool:
    return f'text="{text}"' in xml or f'content-desc="{text}"' in xml


def android_wait_editor_ready(serial: str) -> None:
    """One editor gate for every Android editor case.

    Save must be present, and the launch and About markers must be absent.
    A Save node left under another page is not the editor.
    """
    previous = None
    deadline = time.time() + 12
    while time.time() < deadline:
        try:
            current = android_frame_hash(serial)
        except InterruptedError:
            raise
        except (ValueError, OSError, subprocess.TimeoutExpired):
            time.sleep(0.7)
            continue
        if previous is not None and current == previous:
            break
        previous = current
        time.sleep(0.7)
    # The preview can keep animating, so a stable frame is not required.
    ready = time.time() + 30
    last = ""
    while time.time() < ready:
        try:
            xml = android_ui_xml(serial)
        except InterruptedError:
            raise
        except (ValueError, OSError, subprocess.TimeoutExpired):
            time.sleep(1)
            continue
        last = xml
        if (
            _ui_has(xml, "Save")
            and not _ui_has(xml, "Version")
            and not _ui_has(xml, "Choose Images")
        ):
            return
        time.sleep(1)
    raise ValueError(
        "Android editor gate failed: need Save, with Version and Choose Images absent. "
        f"dump has Save={_ui_has(last, 'Save')} "
        f"Version={_ui_has(last, 'Version')} "
        f"Choose Images={_ui_has(last, 'Choose Images')}"
    )


def product_version() -> tuple[str, int]:
    """NAME and CODE from ProductVersion.kt. The installed package must match both."""
    path = REPO_ROOT / "shared/src/commonMain/kotlin/me/rosuh/easywatermark/ProductVersion.kt"
    text = path.read_text(encoding="utf-8")
    name = re.search(r'NAME: String = "([^"]+)"', text)
    code = re.search(r"CODE: Int = (\d+)", text)
    if not name or not code:
        raise ValueError(f"ProductVersion.kt has no NAME/CODE ({path})")
    return name.group(1), int(code.group(1))


def android_installed(serial: str) -> dict:
    """Package name, versionName, versionCode, and sha256 of the installed base APK."""
    dump = adb_shell(serial, "dumpsys", "package", ANDROID_PACKAGE)
    name_m = re.search(r"versionName=(\S+)", dump)
    code_m = re.search(r"versionCode=(\d+)", dump)
    path_line = adb_shell(serial, "pm", "path", ANDROID_PACKAGE)
    apk = ""
    for line in path_line.splitlines():
        if line.startswith("package:"):
            apk = line.split(":", 1)[1].strip()
            break
    if not apk:
        raise ValueError(f"pm path did not return an APK for {ANDROID_PACKAGE}")
    raw = adb(serial, ["exec-out", "cat", apk], binary=True, timeout=180)
    if not isinstance(raw, (bytes, bytearray)) or not raw:
        raise ValueError(f"could not read installed APK {apk}")
    return {
        "platform": "android",
        "package": ANDROID_PACKAGE,
        "version": name_m.group(1) if name_m else "",
        "version_code": int(code_m.group(1)) if code_m else None,
        "sha256": hashlib.sha256(raw).hexdigest(),
        "artifact": apk,
    }


def ios_installed(udid: str) -> dict:
    """Bundle id, short version, build number, and sha256 of the installed executable."""
    app = Path(simctl(udid, "get_app_container", udid, IOS_BUNDLE, "app").strip())
    info = plistlib.loads((app / "Info.plist").read_bytes())
    shared = app / "Frameworks" / "Shared.framework" / "Shared"
    exe_name = str(info.get("CFBundleExecutable") or "")
    exe = shared if shared.is_file() else (app / exe_name if exe_name else None)
    if exe is None or not exe.is_file():
        raise ValueError(f"iOS app executable missing under {app}")
    code_raw = str(info.get("CFBundleVersion") or "")
    version_code = int(code_raw) if code_raw.isdigit() else None
    return {
        "platform": "ios",
        "package": IOS_BUNDLE,
        "version": str(info.get("CFBundleShortVersionString") or ""),
        "version_code": version_code,
        "sha256": hashlib.sha256(exe.read_bytes()).hexdigest(),
        "artifact": str(exe),
    }


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
        # am start bypasses Instrumentation's EXTRA_STREAM-to-ClipData migration.
        # Put the same URI in data so Android grants read access to the receiver.
        "-d",
        f"{MEDIA}/{media_id}",
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
        f"if test -L {path}; then echo unsafe; elif test -f {path}; then echo yes; elif test -e {path}; then echo unsafe; else echo no; fi",
    )
    if out not in {"yes", "no"}:
        raise ValueError(f"Cannot establish private file state: {path}: {out!r}")
    return out == "yes"


def read_private(serial: str, path: str) -> bytes | None:
    if not _run_as_file(serial, path):
        return None
    return adb(serial, ["exec-out", "run-as", ANDROID_PACKAGE, "cat", path], binary=True)


def write_private(serial: str, path: str, data: bytes, timeout: int = 45) -> None:
    # Claim before streaming. Never overwrite a previous unfinished write.
    temp = path + ".testmap-restore"
    if _run_as_file(serial, temp):
        raise SetupRestoreError(f"Unfinished private write requires reviewed recovery: {temp}")
    claim = (f"mkdir -p {shlex.quote(str(Path(path).parent))} && "
             f"(set -C; : > {shlex.quote(temp)})")
    adb_shell(serial, "run-as", ANDROID_PACKAGE, "sh", "-c", claim, timeout=timeout)
    published = False
    try:
        script = f"cat > {shlex.quote(temp)} && mv {shlex.quote(temp)} {shlex.quote(path)}"
        adb(serial, ["shell", f"run-as {ANDROID_PACKAGE} sh -c {shlex.quote(script)}"],
            timeout=timeout, input_data=data)
        published = True
    finally:
        if not published:
            # A setup Stop must not suppress cleanup of the file we just claimed.
            deadline = _CLEANUP_DEADLINE.get()
            token = _CLEANUP_DEADLINE.set(deadline if deadline is not None else time.monotonic() + CLEANUP_CMD_TIMEOUT_S)
            try:
                adb_shell(serial, "run-as", ANDROID_PACKAGE, "rm", "-f", temp)
                if _run_as_file(serial, temp):
                    raise ValueError("Temporary private write still exists")
            except Exception as exc:
                raise SetupRestoreError(f"Private write temporary cleanup failed: {temp}") from exc
            finally:
                _CLEANUP_DEADLINE.reset(token)


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
    for path in backup:
        # Also reject residue when the original destination was absent. Its
        # ownership cannot be guessed during recovery, so retain journal/lock.
        if _run_as_file(serial, path + ".testmap-restore"):
            raise SetupRestoreError(f"Unfinished private write requires reviewed recovery: {path}")
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


def ios_grant_library_read(udid: str) -> None:
    simctl(udid, "privacy", udid, "grant", "photos", IOS_BUNDLE)
    simctl(udid, "privacy", udid, "grant", "photos-add", IOS_BUNDLE)


def ios_terminate(udid: str) -> None:
    """Stop the app if it is running. A missing process is already a clean start."""
    try:
        simctl(udid, "terminate", udid, IOS_BUNDLE)
    except ValueError as exc:
        if "No such process" not in str(exc) and "found nothing to terminate" not in str(exc):
            raise


class SetupRestoreError(RuntimeError):
    """App data was not fully restored; preserve evidence and fail the task."""


def _save_setup(state: dict) -> None:
    """Atomic, private journal; written before any app data or fixtures change."""
    data = dict(state)
    data["private_backup"] = {
        path: base64.b64encode(value).decode() if value is not None else None
        for path, value in state.get("private_backup", {}).items()
    }
    path = Path(state["journal"])
    tmp = path.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8") as stream:
        os.chmod(tmp, 0o600)
        json.dump(data, stream, indent=2)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(tmp, path)


def load_setup_backup(path: Path) -> dict:
    path = Path(path).resolve()
    state = json.loads(path.read_text())
    if state.get("journal") != str(path):
        raise ValueError("Backup journal path does not match the reviewed file")
    _validate_setup(state)
    state["private_backup"] = {
        name: base64.b64decode(value, validate=True) if value is not None else None
        for name, value in state.get("private_backup", {}).items()
    }
    return state


def _setup_lock_path(platform: str, identity: str) -> Path:
    return SETUP_BACKUPS.resolve() / (platform + "-" + hashlib.sha256(identity.encode()).hexdigest()[:24] + ".json")


def _device_clock(serial: str) -> dict:
    before = time.time_ns() // 1_000_000
    mono_before = time.monotonic_ns() // 1_000_000
    raw = adb_shell(serial, "date", "+%s%N").strip()
    after = time.time_ns() // 1_000_000
    mono_after = time.monotonic_ns() // 1_000_000
    if (not re.fullmatch(r"[0-9]{19}", raw) or after < before or after - before > 2000
            or mono_after < mono_before or abs((after - before) - (mono_after - mono_before)) > 50):
        raise ValueError("Cannot establish bounded device clock for export control")
    device_ms = int(raw) // 1_000_000
    return {"device_ms": device_ms, "offset_min_ms": device_ms - after,
            "offset_max_ms": device_ms + 1 - before,
            "host_wall_ms": before, "host_monotonic_ms": mono_before}


def _protect_previous_export_control(serial: str, original: bytes | None) -> None:
    if original is None:
        return
    # Never overwrite an active or unrecognised control belonging to another run.
    try:
        value = json.loads(original)
        valid = (len(original) <= 4096 and isinstance(value, dict)
                 and set(value) == {"mode", "run_id", "fixture_uri", "expires_at_ms"}
                 and value["mode"] == "hold-next"
                 and isinstance(value["run_id"], str) and re.fullmatch(EXPORT_RUN_ID, value["run_id"])
                 and isinstance(value["fixture_uri"], str)
                 and re.fullmatch(r"content://media/(external|external_primary)/images/media/[0-9]+", value["fixture_uri"])
                 and type(value["expires_at_ms"]) is int)
    except (ValueError, UnicodeError):
        valid = False
    if not valid or value["expires_at_ms"] > _device_clock(serial)["device_ms"]:
        raise ValueError("Existing active or unrecognised export control requires reviewed recovery")


def _arm_export_control(state: dict) -> None:
    control = state["export_control"]
    uri = MEDIA + "/" + str(state["source_id"])
    if not re.fullmatch(r"content://media/(external|external_primary)/images/media/[0-9]+", uri):
        raise ValueError("Export control requires this setup's exact MediaStore fixture")
    clock = _device_clock(state["serial"])
    marker = {"mode": "hold-next", "run_id": control["run_id"], "fixture_uri": uri,
              "expires_at_ms": clock["device_ms"] + 110000}
    control.update(fixture_uri=uri, clock=clock, expires_at_ms=marker["expires_at_ms"])
    _save_setup(state)  # Original bytes/absence must be durable before either write.
    write_private(state["serial"], EXPORT_EVENTS, b"")
    payload = json.dumps(marker, separators=(",", ":")).encode()
    write_private(state["serial"], EXPORT_CONTROL, payload)
    if read_private(state["serial"], EXPORT_CONTROL) != payload:
        raise ValueError("Export control write verification failed")
    control["armed"] = True
    _save_setup(state)


def _capture_export_control(state: dict) -> None:
    """Only this run's three-field events leave the private backup domain."""
    control = state.get("export_control")
    if not control or not control.get("armed"):
        return
    public = {key: control[key] for key in ("mode", "run_id", "fixture_uri", "clock", "expires_at_ms")}
    public["events"] = []
    try:
        raw = read_private(state["serial"], EXPORT_EVENTS) or b""
        if len(raw) > 16384:
            raise ValueError("Export event journal exceeds bound")
        for line in raw.splitlines():
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError("Invalid export event shape")
            if row.get("run_id") != control["run_id"]:
                continue
            if (set(row) != {"run_id", "event", "timestamp_ms"}
                    or row["event"] not in {"ready", "entered", "cancelled", "watchdog", "cleared"}
                    or type(row["timestamp_ms"]) is not int):
                raise ValueError("Invalid current-run export event")
            public["events"].append(row)
        public["clock_end"] = _device_clock(state["serial"])
    except (ValueError, OSError, UnicodeError, subprocess.TimeoutExpired):
        public["capture_error"] = "Export event capture failed"
    destination = Path(state["journal"]).parent / "export-control-events.json"
    destination.write_text(json.dumps(public, indent=2) + "\n", encoding="utf-8")


def _validate_setup(state: dict) -> None:
    platform = state.get("platform")
    identity = state.get("serial") if platform == "android" else state.get("udid")
    pattern = r"[A-Za-z0-9._:\[\]-]+" if platform == "android" else r"[0-9A-Fa-f-]{25,}"
    if platform not in {"android", "ios"} or not isinstance(identity, str) or not re.fullmatch(pattern, identity):
        raise ValueError("Invalid backup platform/device identity")
    if state.get("setup") not in (ANDROID_SETUPS if platform == "android" else IOS_SETUPS):
        raise ValueError("Invalid backup setup")
    control = state.get("export_control")
    if control is not None and (platform != "android" or state["setup"] != "editor"
            or not isinstance(control, dict) or control.get("mode") != "hold-next"
            or not isinstance(control.get("run_id"), str)
            or not re.fullmatch(EXPORT_RUN_ID, control["run_id"])):
        raise ValueError("Invalid export control recovery scope")
    allowed = set(ANDROID_CONFIG + (CRASH_PREF, CRASH_PREF + ".bak") + (EXPORT_CONTROL_PATHS if control else ())) if platform == "android" else set(IOS_CONFIG)
    private = state.get("private_backup")
    if not isinstance(private, dict) or not set(private).issubset(allowed):
        raise ValueError("Backup contains unsupported private paths")
    expected = set(ANDROID_CONFIG if platform == "android" else IOS_CONFIG)
    if platform == "android" and state["setup"] == "crash":
        expected.update((CRASH_PREF, CRASH_PREF + ".bak"))
    if control:
        expected.update(EXPORT_CONTROL_PATHS)
    if not isinstance(state.get("mutated"), bool) or (state["mutated"] and set(private) != expected):
        raise ValueError("Backup is missing preferences required for recovery")
    journal = Path(str(state.get("journal") or ""))
    if not journal.is_absolute() or not re.fullmatch(r"setup-backup-[a-f0-9]{32}\.json", journal.name):
        raise ValueError("Invalid backup journal path")
    if state.get("lock") != str(_setup_lock_path(platform, identity)):
        raise ValueError("Backup lock is not this repository's lock for this device")
    marker = state.get("marker") or ""
    if not re.fullmatch(r"[A-Za-z0-9]{0,24}-[a-f0-9]{32}", marker):
        raise ValueError("Invalid fixture marker in backup")
    fixtures = state.get("fixtures")
    if not isinstance(fixtures, dict) or not set(fixtures).issubset({"A", "B", "C", "icon"}):
        raise ValueError("Invalid backup fixtures")
    for key, value in fixtures.items():
        path = Path(value).resolve()
        if path.parent != journal.parent or path.name != f"ewm-suite-{marker}-{key}.png":
            raise ValueError("Backup fixture is outside this setup's unique files")
    if not isinstance(state.get("display_backup"), dict) or not set(state["display_backup"]).issubset({"size", "density"}):
        raise ValueError("Invalid backup display settings")
    if state.get("phase") not in {"backing_up", "prepared", "active", "restoring", "restored"}:
        raise ValueError("Invalid backup phase")


def _claim_setup(state: dict, folder: Path) -> None:
    SETUP_BACKUPS.mkdir(parents=True, exist_ok=True, mode=0o700)
    identity = state["serial"] or state["udid"]
    lock = _setup_lock_path(state["platform"], identity)
    journal = folder / ("setup-backup-" + uuid.uuid4().hex + ".json")
    state.update(journal=str(journal.resolve()), lock=str(lock.resolve()), phase="backing_up", mutated=False)
    try:
        with lock.open("x", encoding="utf-8") as stream:
            os.chmod(lock, 0o600)
            json.dump({"journal": state["journal"]}, stream)
            stream.flush()
            os.fsync(stream.fileno())
    except FileExistsError:
        raise ValueError(f"Unfinished setup for this device. Review and explicitly restore the backup referenced by {lock}; refusing to overwrite newer device data")
    _save_setup(state)


def apply_setup(setup: str, *, platform: str, folder: Path, marker: str,
                serial: str | None = None, udid: str | None = None, should_stop=None,
                export_cancel_run_id: str | None = None) -> dict:
    token = _SETUP_STOP.set(should_stop)
    try:
        return _apply_setup(setup, platform=platform, folder=folder, marker=marker, serial=serial, udid=udid,
                            export_cancel_run_id=export_cancel_run_id)
    finally:
        _SETUP_STOP.reset(token)


def _apply_setup(
    setup: str,
    *,
    platform: str,
    folder: Path,
    marker: str,
    serial: str | None = None,
    udid: str | None = None,
    export_cancel_run_id: str | None = None,
) -> dict:
    """Back up before mutation; restore even when preparation itself fails."""
    platform = platform.lower()
    valid = ANDROID_SETUPS if platform == "android" else IOS_SETUPS if platform == "ios" else ()
    if setup not in valid:
        raise ValueError(f"Unsupported setup {setup!r} for {platform}")
    if not (serial if platform == "android" else udid):
        raise ValueError(f"{platform} setup requires an explicit device")
    # simctl privacy cannot reliably read and restore all authorization states.
    # Never turn an existing user's grant into a denial (or vice versa).
    if platform == "ios" and setup == "ios":
        raise ValueError("iOS permission precondition is unmet: this setup requires changing Photos authorization, which cannot be safely restored; use an explicitly prepared dedicated test device")
    if export_cancel_run_id is not None and (platform != "android" or setup != "editor"
            or marker != "exportcancel" or not isinstance(export_cancel_run_id, str)
            or not re.fullmatch(EXPORT_RUN_ID, export_cancel_run_id)):
        raise ValueError("Export hold is restricted to the Android cancel case")
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    marker = re.sub(r"[^A-Za-z0-9]", "", marker)[:24] + "-" + uuid.uuid4().hex
    state = dict(setup=setup, platform=platform, serial=serial, udid=udid,
                 marker=marker, fixtures={}, display_backup={}, private_backup={},
                 source_id=None, damaged_remote=None)
    if export_cancel_run_id is not None:
        state["export_control"] = {"mode": "hold-next", "run_id": export_cancel_run_id}
    _claim_setup(state, folder)
    try:
        if platform == "android":
            require_android_ready(serial)
            if state.get("export_control"):
                _protect_previous_export_control(serial, read_private(serial, EXPORT_CONTROL))
            android_force_stop(serial)
            paths = ANDROID_CONFIG + ((CRASH_PREF, CRASH_PREF + ".bak") if setup == "crash" else ())
            if state.get("export_control"):
                paths += EXPORT_CONTROL_PATHS
            state["private_backup"] = {path: read_private(serial, path) for path in paths}
            if setup == "wide":
                state["display_backup"] = record_display(serial)
            fixtures = make_fixtures(folder, marker)
            state["fixtures"] = {key: str(path.resolve()) for key, path in fixtures.items()}
            state.update(phase="prepared", mutated=True)
            _save_setup(state)
            push_android_fixtures(serial, fixtures)
            android_leave_system_picker(serial)
            android_reset_saved_config(serial)
            if setup == "wide":
                set_wide(serial)
            if setup == "crash":
                inject_crash_gate(serial, installed_version_code(serial))
            if setup in {"home", "crash"}:
                android_start_home(serial)
            else:
                source_id = wait_media_id(serial, fixtures["A"].name)
                state["source_id"] = source_id
                android_share_in(serial, source_id)
            if setup == "failure":
                state["damaged_remote"] = damage_source(serial, fixtures["A"].name, marker, folder)
            if setup not in {"home", "crash"}:
                android_wait_editor_ready(serial)
            if state.get("export_control"):
                _arm_export_control(state)
        else:
            # A failed termination is unsafe: the process may flush old prefs
            # over a restored file. Do not suppress simctl failures here.
            ios_terminate(udid)
            root = Path(simctl(udid, "get_app_container", udid, IOS_BUNDLE, "data").strip())
            if not root.is_absolute() or not root.is_dir():
                raise ValueError("iOS data container is unavailable")
            state["container"] = str(root.resolve())
            for name in IOS_CONFIG:
                path = root / name
                if path.is_symlink() or (path.exists() and not path.is_file()):
                    raise ValueError(f"Unsafe preference path: {path}")
                state["private_backup"][name] = path.read_bytes() if path.exists() else None
            state.update(phase="prepared", mutated=True)
            _save_setup(state)
            for name in IOS_CONFIG:
                (root / name).unlink(missing_ok=True)
            # tmp, Saved Application State, Photos permissions and the library
            # are not needed for this setup and are deliberately preserved.
        state["phase"] = "active"
        _save_setup(state)
        return state
    except BaseException as exc:
        try:
            restore_setup(state)
        except Exception as cleanup:
            raise SetupRestoreError(f"Setup failed ({exc}); restore failed ({cleanup}); backup: {state['journal']}") from exc
        raise


def restore_setup(state: dict) -> None:
    token = _CLEANUP_DEADLINE.set(time.monotonic() + RESTORE_BUDGET_S)
    try:
        _restore_setup(state)
    finally:
        _CLEANUP_DEADLINE.reset(token)


def _restore_setup(state: dict) -> None:
    """Restore only captured data; retain the journal/lock on any failure.

    A hard-killed run cannot execute finally. Its device lock then prevents
    another setup from silently replacing the surviving recovery evidence.
    """
    _validate_setup(state)
    if state.get("phase") == "restored":
        return
    lock = Path(state["lock"])
    if lock.is_symlink() or not lock.is_file() or json.loads(lock.read_text()).get("journal") != state["journal"]:
        raise SetupRestoreError("Recovery lock does not match this backup; refusing to overwrite a newer setup")
    errors = []
    if state.get("mutated"):
        state["phase"] = "restoring"
        _save_setup(state)
        if state["platform"] == "android":
            serial = state["serial"]
            capture_deadline = _CLEANUP_DEADLINE.set(min(_CLEANUP_DEADLINE.get(), time.monotonic() + CLEANUP_CMD_TIMEOUT_S))
            try:
                _capture_export_control(state)
            except Exception:
                # Evidence failure must never bypass restoration. Missing public
                # evidence is rejected by the runner's cancel verdict.
                pass
            finally:
                _CLEANUP_DEADLINE.reset(capture_deadline)
            try:
                restore_private(serial, state["private_backup"])
            except Exception as exc:
                errors.append(str(exc))
            if state["display_backup"]:
                try:
                    restore_display(serial, state["display_backup"])
                except Exception as exc:
                    errors.append(str(exc))
            names = [Path(path).name for path in state["fixtures"].values()]
            if names:
                try:
                    where = "relative_path='" + FIXTURE_FOLDER + "/' AND _display_name IN (" + ",".join("'" + name + "'" for name in names) + ")"
                    adb_shell(serial, "content", "delete", "--uri", MEDIA, "--where", where)
                    adb_shell(serial, "rm", "-f", *(f"/sdcard/{FIXTURE_FOLDER}/{name}" for name in names))
                except Exception as exc:
                    errors.append(str(exc))
        else:
            try:
                ios_terminate(state["udid"])
                root = Path(simctl(state["udid"], "get_app_container", state["udid"], IOS_BUNDLE, "data").strip()).resolve()
                if str(root) != state["container"]:
                    raise ValueError("iOS data container changed; refusing to restore into a different install")
                for name, data in state["private_backup"].items():
                    path = root / name
                    if path.is_symlink():
                        raise ValueError(f"Unsafe restore path: {path}")
                    if data is None:
                        path.unlink(missing_ok=True)
                    else:
                        path.parent.mkdir(parents=True, exist_ok=True)
                        tmp = path.with_name(path.name + ".testmap-restore")
                        tmp.write_bytes(data)
                        os.replace(tmp, path)
                    if (path.read_bytes() if path.exists() else None) != data:
                        raise ValueError(f"Preference restore byte mismatch: {path}")
            except Exception as exc:
                errors.append(str(exc))
    if errors:
        state["restore_errors"] = errors
        _save_setup(state)
        raise SetupRestoreError(f"{'; '.join(errors)}; recovery backup: {state['journal']}")
    state["phase"] = "restored"
    _save_setup(state)
    Path(state["lock"]).unlink(missing_ok=True)


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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--restore-reviewed-backup", type=Path,
                        help="Restore an interrupted setup ONLY after checking that its saved data should replace current test-device preferences")
    args = parser.parse_args()
    if args.restore_reviewed_backup:
        state = load_setup_backup(args.restore_reviewed_backup)
        restore_setup(state)
        print(f"restored setup backup: {args.restore_reviewed_backup}")
        return 0
    ids = map_edge_ids()
    validate_agent_device_binding(ids)
    print(
        f"ok: {len(ids)} edges, setup keys {sorted(SETUPS)}, "
        f"cli {PINNED_CLI_VERSION}, payload {CASES_PATH.relative_to(REPO_ROOT)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
