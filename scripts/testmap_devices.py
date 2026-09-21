#!/usr/bin/env python3
"""List / attach / boot devices for the local testmap console (ADR-0032).

Never a CI gate. Never shut down an already-live emulator or simulator.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
ADB_LINE_RE = re.compile(r"^(\S+)\s+(\S+)(.*)$")
MODEL_RE = re.compile(r"\bmodel:(\S+)")

DEFAULT_IOS_SIM_NAMES = ("iPhone 17 Pro", "iPhone 16 Pro", "iPhone 15 Pro", "iPhone 17")
BOOT_POLL_S = 2.0
ANDROID_BOOT_TIMEOUT_S = 180.0
IOS_BOOT_TIMEOUT_S = 120.0


def _run(
    cmd: list[str],
    *,
    timeout: float = 20,
    check: bool = False,
) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
        timeout=timeout,
        check=check,
    )


def android_sdk() -> Path | None:
    env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if env:
        path = Path(env)
        if path.is_dir():
            return path
    home = Path.home() / "Library" / "Android" / "sdk"
    return home if home.is_dir() else None


def adb_bin() -> str:
    sdk = android_sdk()
    if sdk:
        candidate = sdk / "platform-tools" / "adb"
        if candidate.is_file():
            return str(candidate)
    return shutil.which("adb") or "adb"


def emulator_bin() -> str | None:
    sdk = android_sdk()
    if sdk:
        candidate = sdk / "emulator" / "emulator"
        if candidate.is_file():
            return str(candidate)
    return shutil.which("emulator")


def parse_adb_devices(text: str) -> list[dict]:
    out: list[dict] = []
    started = False
    for raw in text.splitlines():
        line = raw.strip()
        if not line:
            continue
        if line.startswith("List of devices"):
            started = True
            continue
        if not started:
            continue
        m = ADB_LINE_RE.match(line)
        if not m:
            continue
        serial, state, rest = m.group(1), m.group(2), m.group(3) or ""
        model_m = MODEL_RE.search(rest)
        model = (model_m.group(1).replace("_", " ") if model_m else serial)
        kind = "emulator" if serial.startswith("emulator-") else "physical"
        ready = state == "device"
        out.append(
            {
                "id": serial,
                "name": model,
                "platform": "android",
                "kind": kind,
                "state": "ready" if ready else state,
                "bootable": False,
                "avd": None,
            }
        )
    return out


def parse_avd_list(text: str) -> list[str]:
    names: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("List of") or line.startswith("Available"):
            continue
        if line.startswith("-"):
            continue
        names.append(line.split()[0])
    return names


def _adb_avd_name(serial: str) -> str | None:
    try:
        proc = _run([adb_bin(), "-s", serial, "emu", "avd", "name"], timeout=5)
    except (OSError, subprocess.TimeoutExpired):
        return None
    if proc.returncode != 0:
        return None
    line = (proc.stdout or "").strip().splitlines()
    if not line:
        return None
    name = line[0].strip()
    return name or None


def list_android_devices() -> list[dict]:
    attached: list[dict] = []
    try:
        proc = _run([adb_bin(), "devices", "-l"], timeout=8)
        attached = parse_adb_devices(proc.stdout or "")
    except (OSError, subprocess.TimeoutExpired):
        attached = []
    running_avds: set[str] = set()
    for item in attached:
        if item["kind"] == "emulator":
            avd = _adb_avd_name(item["id"])
            item["avd"] = avd
            if avd:
                running_avds.add(avd)
                item["name"] = avd
    avds: list[str] = []
    try:
        proc = _run(["android", "emulator", "list"], timeout=15)
        if proc.returncode == 0:
            avds = parse_avd_list(proc.stdout or "")
    except (OSError, subprocess.TimeoutExpired):
        avds = []
    out = list(attached)
    for name in avds:
        if name in running_avds:
            continue
        out.append(
            {
                "id": f"avd:{name}",
                "name": name,
                "platform": "android",
                "kind": "emulator",
                "state": "shutdown",
                "bootable": True,
                "avd": name,
            }
        )
    return out


def parse_simctl_devices(payload: dict) -> list[dict]:
    out: list[dict] = []
    devices = payload.get("devices") or {}
    if not isinstance(devices, dict):
        return out
    for runtime, rows in devices.items():
        if not isinstance(rows, list):
            continue
        rt = str(runtime)
        if "iOS" not in rt and "iphoneos" not in rt.lower():
            continue
        for row in rows:
            if not isinstance(row, dict) or not row.get("isAvailable", True):
                continue
            udid = str(row.get("udid") or "")
            if not udid:
                continue
            state = str(row.get("state") or "Shutdown")
            ready = state.lower() == "booted"
            out.append(
                {
                    "id": udid,
                    "name": str(row.get("name") or udid),
                    "platform": "ios",
                    "kind": "simulator",
                    "state": "ready" if ready else "shutdown",
                    "bootable": not ready,
                    "runtime": rt,
                }
            )
    return out


def parse_devicectl_physical(payload: dict) -> list[dict]:
    out: list[dict] = []
    devices = ((payload.get("result") or {}).get("devices")) or []
    if not isinstance(devices, list):
        return out
    for row in devices:
        if not isinstance(row, dict):
            continue
        hw = row.get("hardwareProperties") or {}
        if str(hw.get("reality") or "") != "physical":
            continue
        props = row.get("deviceProperties") or {}
        conn = row.get("connectionProperties") or {}
        udid = str(hw.get("udid") or row.get("identifier") or "")
        if not udid:
            continue
        tunnel = str(conn.get("tunnelState") or "").lower()
        pairing = str(conn.get("pairingState") or "").lower()
        if tunnel in {"connected", "connecting"} or pairing == "paired" and tunnel != "unavailable":
            # unavailable physical units stay listed but not ready
            ready = tunnel in {"connected"} or (
                pairing == "paired" and tunnel not in {"disconnected", "unavailable", ""}
            )
        else:
            ready = False
        if pairing == "paired" and tunnel == "disconnected":
            ready = False
        name = str(props.get("name") or hw.get("marketingName") or udid)
        state = "ready" if ready else ("unavailable" if tunnel == "unavailable" else "offline")
        out.append(
            {
                "id": udid,
                "name": name,
                "platform": "ios",
                "kind": "physical",
                "state": state,
                "bootable": False,
            }
        )
    return out


def list_ios_devices() -> list[dict]:
    sims: list[dict] = []
    try:
        proc = _run(["xcrun", "simctl", "list", "devices", "available", "--json"], timeout=20)
        if proc.returncode == 0 and proc.stdout:
            sims = parse_simctl_devices(json.loads(proc.stdout))
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
        sims = []
    physical: list[dict] = []
    try:
        tmp = tempfile.NamedTemporaryFile(prefix="ewm-devicectl-", suffix=".json", delete=False)
    except OSError:
        return physical + sims
    tmp_path = Path(tmp.name)
    tmp.close()
    try:
        proc = _run(
            ["xcrun", "devicectl", "list", "devices", "--json-output", str(tmp_path)],
            timeout=20,
        )
        if proc.returncode == 0 and tmp_path.is_file():
            physical = parse_devicectl_physical(json.loads(tmp_path.read_text(encoding="utf-8")))
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError):
        physical = []
    finally:
        try:
            tmp_path.unlink()
        except OSError:
            pass
    return physical + sims


DESKTOP_WATCH = {
    "id": "desktop",
    "name": "Compose Desktop",
    "kind": "host",
    "platform": "desktop",
    "state": "ready",
}


_DEVICES_CACHE: dict = {"at": 0.0, "value": None}
_DEVICES_TTL_S = 5.0


def list_devices() -> dict[str, list[dict]]:
    now = time.monotonic()
    cached = _DEVICES_CACHE.get("value")
    if cached is not None and now - float(_DEVICES_CACHE["at"] or 0) < _DEVICES_TTL_S:
        return cached
    value = {
        "android": list_android_devices(),
        "ios": list_ios_devices(),
        "desktop": [dict(DESKTOP_WATCH)],
    }
    _DEVICES_CACHE["at"] = now
    _DEVICES_CACHE["value"] = value
    return value


PNG_MAGIC = b"\x89PNG"


def _png_bytes(data: bytes | None) -> bytes | None:
    if not data or not data.startswith(PNG_MAGIC):
        return None
    return data


def ready_listed_device(platform: str, device_id: str) -> dict | None:
    if platform == "desktop" and device_id in {"", "desktop", "auto"}:
        return dict(DESKTOP_WATCH)
    if platform not in {"android", "ios"} or not device_id:
        return None
    for item in list_devices().get(platform) or []:
        if item.get("id") == device_id and item.get("state") == "ready":
            return item
    return None


def default_watch_target() -> dict | None:
    """First ready emulator/simulator. Never boots. Physical is not the default."""
    catalog = list_devices()
    for item in catalog.get("android") or []:
        if item.get("state") == "ready" and item.get("kind") == "emulator":
            return item
    for item in catalog.get("ios") or []:
        if item.get("state") == "ready" and item.get("kind") == "simulator":
            return item
    return None


def resolve_watch_target(
    platform: str | None,
    device_id: str | None,
    preferred: dict | None = None,
) -> dict | None:
    """Pick a ready device to screenshot. Never boots or shuts down.

    A requested platform never falls back to another OS (iOS slot must not
    show the Android emulator).
    """
    plat = (platform or "").strip()
    did = (device_id or "").strip()
    if plat == "desktop":
        return dict(DESKTOP_WATCH)
    if plat not in {"android", "ios"}:
        return None
    if did:
        found = ready_listed_device(plat, did)
        if found:
            return found
    pref_plat = str((preferred or {}).get("platform") or "")
    pref_id = str((preferred or {}).get("device") or "")
    if pref_plat == plat and pref_id:
        found = ready_listed_device(plat, pref_id)
        if found:
            return found
    return None


def capture_android_frame(serial: str) -> bytes | None:
    try:
        proc = subprocess.run(
            [adb_bin(), "-s", serial, "exec-out", "screencap", "-p"],
            capture_output=True,
            timeout=8,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    return _png_bytes(proc.stdout)


def capture_ios_frame(udid: str) -> bytes | None:
    tmp = tempfile.NamedTemporaryFile(prefix="ewm-frame-", suffix=".png", delete=False)
    tmp.close()
    path = Path(tmp.name)
    try:
        proc = _run(
            ["xcrun", "simctl", "io", udid, "screenshot", "--type=png", str(path)],
            timeout=12,
        )
        if proc.returncode != 0 or not path.is_file():
            proc = _run(["xcrun", "simctl", "io", udid, "screenshot", str(path)], timeout=12)
        if proc.returncode != 0 or not path.is_file():
            return None
        return _png_bytes(path.read_bytes())
    except (OSError, subprocess.TimeoutExpired):
        return None
    finally:
        try:
            path.unlink()
        except OSError:
            pass


JPEG_MAGIC = b"\xff\xd8"


def capture_ios_jpeg(udid: str) -> bytes | None:
    tmp = tempfile.NamedTemporaryFile(prefix="ewm-frame-", suffix=".jpg", delete=False)
    tmp.close()
    path = Path(tmp.name)
    try:
        proc = _run(
            ["xcrun", "simctl", "io", udid, "screenshot", "--type=jpeg", str(path)],
            timeout=12,
        )
        if proc.returncode != 0 or not path.is_file():
            return None
        data = path.read_bytes()
        if data.startswith(JPEG_MAGIC):
            return data
        return None
    except (OSError, subprocess.TimeoutExpired):
        return None
    finally:
        try:
            path.unlink()
        except OSError:
            pass


def capture_device_frame(target: dict) -> bytes | None:
    """Screenshot a ready device. Never boots. iOS physical is unsupported."""
    if not target or target.get("state") != "ready":
        return None
    platform = target.get("platform")
    device_id = str(target.get("id") or "")
    if not device_id:
        return None
    if platform == "android":
        return capture_android_frame(device_id)
    if platform == "ios":
        if target.get("kind") == "physical":
            return None
        return capture_ios_frame(device_id)
    if platform == "desktop":
        return capture_desktop_frame()
    return None


def capture_desktop_frame() -> bytes | None:
    """Best-effort PNG of the Compose Desktop window. Never launches the app."""
    jxa = r"""
ObjC.import("CoreGraphics");
var infos = $.CGWindowListCopyWindowInfo($.kCGWindowListOptionOnScreenOnly, 0);
if (!infos) { "" } else {
  var found = "";
  for (var i = 0; i < infos.count; i++) {
    var info = infos.objectAtIndex(i);
    var name = String(info.objectForKey("kCGWindowName") || "");
    var owner = String(info.objectForKey("kCGWindowOwnerName") || "");
    if (/EasyWatermark/i.test(name) || /EasyWatermark/i.test(owner)) {
      found = String(info.objectForKey("kCGWindowNumber"));
      break;
    }
  }
  found;
}
"""
    try:
        probe = subprocess.run(
            ["osascript", "-l", "JavaScript", "-e", jxa],
            capture_output=True,
            text=True,
            timeout=8,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    wid = (probe.stdout or "").strip().strip('"')
    if not wid.isdigit():
        return None
    tmp = tempfile.NamedTemporaryFile(prefix="ewm-desk-", suffix=".png", delete=False)
    tmp.close()
    path = Path(tmp.name)
    try:
        cap = subprocess.run(
            ["screencapture", "-l", wid, "-t", "png", "-x", str(path)],
            capture_output=True,
            timeout=8,
            check=False,
        )
        if cap.returncode != 0 or not path.is_file():
            return None
        return _png_bytes(path.read_bytes())
    except (OSError, subprocess.TimeoutExpired):
        return None
    finally:
        try:
            path.unlink()
        except OSError:
            pass


def default_watch_slots() -> dict[str, dict | None]:
    catalog = list_devices()
    android = None
    for item in catalog.get("android") or []:
        if item.get("state") == "ready" and item.get("kind") == "emulator":
            android = item
            break
    # Idle iOS slot stays empty unless a running agent task binds a physical.
    # Never default to a simulator (and never to emulator-5554).
    return {"android": android, "ios": None, "desktop": dict(DESKTOP_WATCH)}


def _prefer_ready(items: list[dict]) -> dict | None:
    physical = [x for x in items if x.get("kind") == "physical" and x.get("state") == "ready"]
    if physical:
        return physical[0]
    ready = [x for x in items if x.get("state") == "ready"]
    if ready:
        return ready[0]
    return None


def _prefer_bootable_android(items: list[dict]) -> dict | None:
    bootable = [x for x in items if x.get("bootable") and x.get("avd")]
    return bootable[0] if bootable else None


def _prefer_bootable_ios(items: list[dict]) -> dict | None:
    sims = [x for x in items if x.get("kind") == "simulator" and x.get("bootable")]
    for wanted in DEFAULT_IOS_SIM_NAMES:
        for item in sims:
            if item.get("name") == wanted:
                return item
    iphones = [x for x in sims if str(x.get("name") or "").startswith("iPhone")]
    if iphones:
        return iphones[0]
    return sims[0] if sims else None


def resolve_device(platform: str, device_id: str | None) -> dict:
    if platform not in {"android", "ios"}:
        raise ValueError(f"unknown device platform: {platform}")
    catalog = list_devices().get(platform) or []
    wanted = (device_id or "auto").strip() or "auto"
    if wanted != "auto":
        for item in catalog:
            if item["id"] == wanted or item.get("avd") == wanted:
                return dict(item)
        raise ValueError(f"unknown {platform} device: {wanted}")
    ready = _prefer_ready(catalog)
    if ready:
        return dict(ready)
    boot = _prefer_bootable_android(catalog) if platform == "android" else _prefer_bootable_ios(catalog)
    if boot:
        return dict(boot)
    raise ValueError(
        f"no {platform} device is ready and none can be booted — attach a phone or create an AVD / Simulator"
    )


def _wait_android_boot(serial_hint: str | None, avd: str | None, logf, timeout: float) -> dict:
    deadline = time.monotonic() + timeout
    last = ""
    while time.monotonic() < deadline:
        attached = list_android_devices()
        for item in attached:
            if item.get("state") != "ready":
                continue
            if serial_hint and item["id"] == serial_hint:
                return item
            if avd and item.get("avd") == avd:
                return item
            if item.get("kind") == "emulator" and avd:
                continue
        last = ", ".join(f"{x['id']}:{x['state']}" for x in attached) or "none"
        logf.write(f"waiting for Android boot ({last})\n")
        logf.flush()
        time.sleep(BOOT_POLL_S)
    raise TimeoutError(f"Android device did not become ready ({last})")


def _boot_android_avd(avd: str, logf) -> dict:
    binary = emulator_bin()
    if not binary:
        raise RuntimeError("Android emulator binary not found (ANDROID_HOME/emulator/emulator)")
    logf.write(f"## boot android AVD {avd} (-no-window; will not be stopped)\n")
    logf.flush()
    # Detached session: console Stop must not SIGTERM this process group.
    subprocess.Popen(
        [binary, "-avd", avd, "-no-window", "-no-audio", "-no-boot-anim"],
        cwd=REPO_ROOT,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    return _wait_android_boot(None, avd, logf, ANDROID_BOOT_TIMEOUT_S)


def _boot_ios_sim(udid: str, name: str, logf) -> dict:
    logf.write(f"## boot iOS Simulator {name} ({udid}); will not be shut down\n")
    logf.flush()
    boot = _run(["xcrun", "simctl", "boot", udid], timeout=30)
    if boot.returncode != 0:
        err = (boot.stderr or boot.stdout or "").strip()
        if "Unable to boot device in current state: Booted" not in err:
            raise RuntimeError(err or f"simctl boot failed for {udid}")
    status = _run(["xcrun", "simctl", "bootstatus", udid, "-b"], timeout=IOS_BOOT_TIMEOUT_S)
    if status.returncode != 0:
        raise RuntimeError((status.stderr or status.stdout or "simctl bootstatus failed").strip())
    for item in list_ios_devices():
        if item["id"] == udid:
            item["state"] = "ready"
            item["bootable"] = False
            return item
    return {
        "id": udid,
        "name": name,
        "platform": "ios",
        "kind": "simulator",
        "state": "ready",
        "bootable": False,
    }


def ensure_device_ready(platform: str, device_id: str | None, logf) -> dict:
    chosen = resolve_device(platform, device_id)
    if chosen.get("state") == "ready":
        logf.write(f"## device ready: {chosen['kind']} {chosen['name']} ({chosen['id']})\n")
        logf.flush()
        return chosen
    if chosen.get("kind") == "physical":
        raise RuntimeError(
            f"{platform} physical device {chosen['name']} is {chosen.get('state')} — plug it in / trust this computer"
        )
    if platform == "android" and chosen.get("avd"):
        return _boot_android_avd(chosen["avd"], logf)
    if platform == "ios" and chosen.get("kind") == "simulator":
        return _boot_ios_sim(chosen["id"], chosen["name"], logf)
    raise RuntimeError(f"cannot boot {platform} device {chosen.get('id')}")


def _self_check() -> None:
    adb = parse_adb_devices(
        "List of devices attached\n"
        "emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64\n"
        "R5CT10abc              device usb:1-1 product:q4qsqw model:SM_S906E\n"
    )
    assert [x["id"] for x in adb] == ["emulator-5554", "R5CT10abc"]
    assert adb[0]["kind"] == "emulator" and adb[1]["kind"] == "physical"
    avds = parse_avd_list("Pixel_9_Pro_XL\nPixel_9_Pro_Fold\n")
    assert avds == ["Pixel_9_Pro_XL", "Pixel_9_Pro_Fold"]
    sims = parse_simctl_devices(
        {
            "devices": {
                "com.apple.CoreSimulator.SimRuntime.iOS-27-0": [
                    {
                        "udid": "CF9CE125-D6B2-4D40-B634-56C5E5B65CF4",
                        "name": "iPhone 17 Pro",
                        "state": "Shutdown",
                        "isAvailable": True,
                    }
                ]
            }
        }
    )
    assert sims[0]["bootable"] is True and sims[0]["name"] == "iPhone 17 Pro"


def main() -> int:
    if "--self-check" in sys.argv:
        _self_check()
        print("ok")
        return 0
    data = list_devices()
    print(json.dumps(data, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
