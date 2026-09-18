#!/usr/bin/env python3
"""H.264 watch producers for the testmap console (stdlib only).

Android: official scrcpy-server 4.1 `raw_stream=true` over adb forward.
iOS Simulator: `idb video-stream --format h264` stdout.
Physical iOS is not captured. Never boots or shuts down emulators.
"""

from __future__ import annotations

import json
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import threading
import time
from collections.abc import Iterator
from pathlib import Path

from testmap_devices import adb_bin, resolve_watch_target

IDLE_STOP_S = 3.0
ANNEXB4 = b"\x00\x00\x00\x01"
DEFAULT_CODEC = "avc1.42E01E"
DEVICE_JAR = "/data/local/tmp/ewm-scrcpy-server.jar"
SCRCPY_VERSION = "4.1"


def scrcpy_server_jar() -> Path | None:
    prefix = shutil.which("scrcpy")
    candidates: list[Path] = []
    if prefix:
        bound = Path(prefix).resolve().parent.parent / "share" / "scrcpy" / "scrcpy-server"
        candidates.append(bound)
    brew = shutil.which("brew")
    if brew:
        try:
            out = subprocess.run(
                [brew, "--prefix", "scrcpy"],
                capture_output=True,
                text=True,
                timeout=5,
                check=False,
            )
            if out.returncode == 0 and out.stdout.strip():
                candidates.append(Path(out.stdout.strip()) / "share" / "scrcpy" / "scrcpy-server")
        except (OSError, subprocess.TimeoutExpired):
            pass
    candidates.append(Path("/opt/homebrew/opt/scrcpy/share/scrcpy/scrcpy-server"))
    for path in candidates:
        if path.is_file():
            return path
    return None


def pack_frame(payload: bytes) -> bytes:
    return struct.pack(">I", len(payload)) + payload


def unpack_frames(buf: bytearray) -> tuple[list[bytes], bytearray]:
    frames: list[bytes] = []
    while len(buf) >= 4:
        (length,) = struct.unpack(">I", bytes(buf[:4]))
        if length > 8_000_000:
            return frames, bytearray()
        if len(buf) < 4 + length:
            break
        frames.append(bytes(buf[4 : 4 + length]))
        del buf[: 4 + length]
    return frames, buf


def config_payload(*, width: int = 0, height: int = 0, codec: str = DEFAULT_CODEC) -> bytes:
    return json.dumps(
        {"codec": codec, "width": width, "height": height, "format": "annexb"},
        separators=(",", ":"),
    ).encode("utf-8")


def ensure_annexb(data: bytes) -> bytes:
    if not data:
        return data
    if data.startswith(ANNEXB4) or data.startswith(b"\x00\x00\x01"):
        return data
    return ANNEXB4 + data


def split_annexb(buf: bytearray) -> tuple[list[bytes], bytearray]:
    data = bytes(buf)
    starts: list[int] = []
    i = 0
    n = len(data)
    while i + 3 <= n:
        if i + 4 <= n and data[i : i + 4] == ANNEXB4:
            starts.append(i)
            i += 4
            continue
        if data[i : i + 3] == b"\x00\x00\x01":
            starts.append(i)
            i += 3
            continue
        i += 1
    if len(starts) < 2:
        return [], buf
    nalus = [ensure_annexb(data[a:b]) for a, b in zip(starts, starts[1:])]
    return nalus, bytearray(data[starts[-1] :])


def nalu_type(nalu: bytes) -> int:
    raw = ensure_annexb(nalu)
    off = 4 if raw.startswith(ANNEXB4) else 3
    if off >= len(raw):
        return 0
    return raw[off] & 0x1F


class _VideoProducer:
    def __init__(self, key: str, target: dict) -> None:
        self.key = key
        self.target = dict(target)
        self._cv = threading.Condition()
        self._packet: bytes | None = None
        self._sps: bytes | None = None
        self._pps: bytes | None = None
        self._seq = 0
        self._subs = 0
        self._stop = threading.Event()
        self._kids: list[subprocess.Popen] = []
        self._forwards: list[tuple[str, int]] = []
        self._thread = threading.Thread(
            target=self._run, name=f"ewm-h264-{key}", daemon=True
        )
        self._thread.start()

    def _publish(self, payload: bytes) -> None:
        kind = nalu_type(payload) if payload[:1] != b"{" else 0
        packet = pack_frame(payload)
        with self._cv:
            if kind == 7:
                self._sps = payload
            elif kind == 8:
                self._pps = payload
            self._packet = packet
            self._seq += 1
            self._cv.notify_all()

    def _track(self, proc: subprocess.Popen) -> subprocess.Popen:
        self._kids.append(proc)
        return proc

    def _run(self) -> None:
        self._publish(config_payload())
        platform = self.target.get("platform")
        kind = self.target.get("kind")
        device_id = str(self.target.get("id") or "")
        try:
            if platform == "android" and device_id:
                self._run_android(device_id)
            elif platform == "ios" and kind == "simulator" and device_id:
                self._run_idb(device_id)
        except Exception as exc:
            try:
                Path("/tmp/ewm-h264-producer.log").write_text(f"{type(exc).__name__}: {exc}\n", encoding="utf-8")
            except OSError:
                pass
        finally:
            self._cleanup()

    def _run_android(self, serial: str) -> None:
        jar = scrcpy_server_jar()
        if jar is None:
            return
        adb = adb_bin()
        push = subprocess.run(
            [adb, "-s", serial, "push", str(jar), DEVICE_JAR],
            capture_output=True,
            timeout=20,
            check=False,
        )
        if push.returncode != 0:
            return
        scid = secrets.randbelow(0x7FFFFFFF)
        scid_s = f"{scid:08x}"
        sock = socket.socket()
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        sock.close()
        fwd = subprocess.run(
            [adb, "-s", serial, "forward", f"tcp:{port}", f"localabstract:scrcpy_{scid_s}"],
            capture_output=True,
            timeout=10,
            check=False,
        )
        if fwd.returncode != 0:
            return
        self._forwards.append((serial, port))
        server_cmd = (
            f"CLASSPATH={DEVICE_JAR} app_process / com.genymobile.scrcpy.Server "
            f"{SCRCPY_VERSION} scid={scid_s} log_level=error audio=false "
            f"control=false tunnel_forward=true raw_stream=true cleanup=true "
            f"max_fps=30 video_bit_rate=4000000"
        )
        proc = self._track(
            subprocess.Popen(
                [adb, "-s", serial, "shell", server_cmd],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
            )
        )
        conn: socket.socket | None = None
        deadline = time.monotonic() + 12
        last_err = ""
        time.sleep(1.2)
        while time.monotonic() < deadline and not self._stop.is_set():
            if proc.poll() is not None:
                out = proc.stdout.read() if proc.stdout else b""
                Path("/tmp/ewm-h264-producer.log").write_text(
                    f"server exit {proc.returncode} {out[:500]!r}\n", encoding="utf-8"
                )
                return
            try:
                conn = socket.create_connection(("127.0.0.1", port), timeout=0.4)
                conn.settimeout(1.0)
                break
            except OSError as exc:
                last_err = str(exc)
                time.sleep(0.15)
        if conn is None:
            Path("/tmp/ewm-h264-producer.log").write_text(
                f"connect failed port={port} scid={scid_s} {last_err}\n", encoding="utf-8"
            )
            return

        Path("/tmp/ewm-h264-producer.log").write_text(
            f"connected port={port} scid={scid_s} raw_stream\n", encoding="utf-8"
        )
        buf = bytearray()
        try:
            while not self._stop.is_set():
                try:
                    chunk = conn.recv(65536)
                except socket.timeout:
                    continue
                except OSError:
                    break
                if not chunk:
                    break
                if not buf and not chunk.startswith(b"\x00\x00"):
                    chunk = ensure_annexb(chunk)
                buf.extend(chunk)
                nalus, buf = split_annexb(buf)
                for nalu in nalus:
                    self._publish(nalu)
        finally:
            try:
                conn.close()
            except OSError:
                pass

    def _run_idb(self, udid: str) -> None:
        idb = shutil.which("idb")
        if not idb:
            return
        proc = self._track(
            subprocess.Popen(
                [
                    idb,
                    "video-stream",
                    "--fps",
                    "30",
                    "--format",
                    "h264",
                    "--udid",
                    udid,
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
            )
        )
        if proc.stdout is None:
            return
        buf = bytearray()
        while not self._stop.is_set():
            chunk = proc.stdout.read(65536)
            if not chunk:
                break
            if not buf and not chunk.startswith(b"\x00\x00"):
                chunk = ensure_annexb(chunk)
            buf.extend(chunk)
            nalus, buf = split_annexb(buf)
            for nalu in nalus:
                self._publish(nalu)

    def _cleanup(self) -> None:
        for proc in self._kids:
            if proc.poll() is None:
                proc.send_signal(signal.SIGTERM)
                try:
                    proc.wait(timeout=2)
                except Exception:
                    proc.kill()
        self._kids.clear()
        adb = adb_bin()
        for serial, port in self._forwards:
            subprocess.run(
                [adb, "-s", serial, "forward", "--remove", f"tcp:{port}"],
                capture_output=True,
                timeout=8,
                check=False,
            )
        self._forwards.clear()

    def subscribe(self) -> Iterator[bytes]:
        with self._cv:
            self._subs += 1
            sps = self._sps
            pps = self._pps
            last = self._seq
        yield pack_frame(config_payload())
        if sps:
            yield pack_frame(sps)
        if pps:
            yield pack_frame(pps)
        try:
            while not self._stop.is_set():
                with self._cv:
                    if self._seq == last:
                        self._cv.wait(timeout=1.0)
                    if self._seq == last:
                        continue
                    last = self._seq
                    packet = self._packet
                if packet:
                    yield packet
        finally:
            with self._cv:
                self._subs -= 1

    def subscriber_count(self) -> int:
        with self._cv:
            return self._subs

    def stop(self) -> None:
        self._stop.set()
        with self._cv:
            self._cv.notify_all()
        self._cleanup()


class H264Hub:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._producers: dict[str, _VideoProducer] = {}

    def _key(self, target: dict) -> str:
        return f"{target.get('platform')}:{target.get('id')}"

    def iter_packets(
        self,
        platform: str | None,
        device_id: str | None,
        preferred: dict | None = None,
    ) -> Iterator[bytes]:
        target = resolve_watch_target(platform, device_id, preferred)
        if not target:
            return
        if target.get("platform") == "desktop":
            return
        if target.get("platform") == "ios" and target.get("kind") == "physical":
            return
        key = self._key(target)
        with self._lock:
            prod = self._producers.get(key)
            if prod is None or not prod._thread.is_alive():
                prod = _VideoProducer(key, target)
                self._producers[key] = prod
        yield from prod.subscribe()

    def reap_idle(self) -> None:
        with self._lock:
            dead = [
                key
                for key, prod in self._producers.items()
                if prod.subscriber_count() == 0
            ]
            for key in dead:
                prod = self._producers.pop(key)
                prod.stop()

    def stop_all(self) -> None:
        with self._lock:
            prods = list(self._producers.values())
            self._producers.clear()
        for prod in prods:
            prod.stop()


H264_HUB = H264Hub()


def _idle_reaper() -> None:
    while True:
        time.sleep(IDLE_STOP_S)
        try:
            H264_HUB.reap_idle()
        except Exception:
            pass


threading.Thread(target=_idle_reaper, name="ewm-h264-reaper", daemon=True).start()
