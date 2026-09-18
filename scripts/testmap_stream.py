#!/usr/bin/env python3
"""Watch stream for the testmap console.

Android emulator/device: `adb exec-out screenrecord --output-format=h264`
piped through ffmpeg to a FIFO (binary-safe; a PTY cooks 0x0A and
corrupts JPEG). Fallback: screencap / simctl JPEG stills.

GET /api/device-stream is multipart/x-mixed-replace. Never boots or shuts
down emulators. Do not use `adb emu` (webrtc can tear down the AVD).
"""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
import tempfile
import threading
import time
from collections.abc import Iterator

from testmap_devices import (
    JPEG_MAGIC,
    adb_bin,
    capture_android_frame,
    capture_desktop_frame,
    capture_ios_jpeg,
    resolve_watch_target,
)

BOUNDARY = "ewmframe"
IDLE_STOP_S = 3.0
FFMPEG = shutil.which("ffmpeg") or "ffmpeg"
JPEG_EOI = b"\xff\xd9"


def png_to_jpeg(png: bytes, *, timeout: float = 5) -> bytes | None:
    if not png:
        return None
    try:
        proc = subprocess.run(
            [
                FFMPEG,
                "-hide_banner",
                "-loglevel",
                "error",
                "-i",
                "pipe:0",
                "-frames:v",
                "1",
                "-an",
                "-pix_fmt",
                "yuvj420p",
                "-q:v",
                "5",
                "-f",
                "image2",
                "-c:v",
                "mjpeg",
                "pipe:1",
            ],
            input=png,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    data = proc.stdout or b""
    return data if data.startswith(JPEG_MAGIC) else None


def split_jpegs(buf: bytearray) -> tuple[list[bytes], bytearray]:
    frames: list[bytes] = []
    data = bytes(buf)
    start = data.find(JPEG_MAGIC)
    while start >= 0:
        end = data.find(JPEG_EOI, start + 2)
        if end < 0:
            return frames, bytearray(data[start:])
        frames.append(data[start : end + 2])
        data = data[end + 2 :]
        start = data.find(JPEG_MAGIC)
    return frames, bytearray(data)


def capture_watch_jpeg(target: dict) -> bytes | None:
    """One framebuffer JPEG. None if the device is gone or capture failed."""
    if not target or target.get("state") != "ready":
        return None
    platform = target.get("platform")
    device_id = str(target.get("id") or "")
    if not device_id:
        return None
    if platform == "android":
        png = capture_android_frame(device_id)
        return png_to_jpeg(png) if png else None
    if platform == "ios":
        if target.get("kind") == "physical":
            return None
        return capture_ios_jpeg(device_id)
    if platform == "desktop":
        png = capture_desktop_frame()
        return png_to_jpeg(png) if png else None
    return None


def multipart_part(frame: bytes, mime: str = "image/jpeg") -> bytes:
    return (
        b"--" + BOUNDARY.encode("ascii") + b"\r\n"
        b"Content-Type: " + mime.encode("ascii") + b"\r\n"
        b"Content-Length: " + str(len(frame)).encode("ascii") + b"\r\n"
        b"\r\n" + frame + b"\r\n"
    )


def _terminate(proc: subprocess.Popen | None) -> None:
    if proc is None or proc.poll() is not None:
        return
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=2)
    except Exception:
        proc.kill()
        try:
            proc.wait(timeout=1)
        except Exception:
            pass


class _Producer:
    def __init__(self, key: str, target: dict) -> None:
        self.key = key
        self.target = dict(target)
        self._cv = threading.Condition()
        self._frame: bytes | None = None
        self._seq = 0
        self._subs = 0
        self._stop = threading.Event()
        self._thread = threading.Thread(
            target=self._run, name=f"ewm-stream-{key}", daemon=True
        )
        self._thread.start()

    def _publish(self, frame: bytes) -> None:
        with self._cv:
            self._frame = frame
            self._seq += 1
            self._cv.notify_all()

    def _run_android_h264(self, serial: str) -> bool:
        """Live H264 screenrecord → MJPEG. False if no frame arrived."""
        rec = subprocess.Popen(
            [
                adb_bin(),
                "-s",
                serial,
                "exec-out",
                "screenrecord",
                "--output-format=h264",
                "--time-limit",
                "0",
                "--size",
                "720x1280",
                "--bit-rate",
                "3000000",
                "-",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        tmp = tempfile.mkdtemp(prefix="ewm-mjpeg-")
        fifo = os.path.join(tmp, "out.mjpg")
        os.mkfifo(fifo)
        rfd = os.open(fifo, os.O_RDONLY | os.O_NONBLOCK)
        wfile = open(fifo, "wb", buffering=0)
        ff = subprocess.Popen(
            [
                FFMPEG,
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "h264",
                "-framerate",
                "30",
                "-i",
                "pipe:0",
                "-an",
                "-r",
                "30",
                "-flush_packets",
                "1",
                "-strict",
                "unofficial",
                "-pix_fmt",
                "yuvj420p",
                "-q:v",
                "5",
                "-vf",
                "scale=-2:720",
                "-f",
                "image2pipe",
                "-c:v",
                "mjpeg",
                "pipe:1",
            ],
            stdin=subprocess.PIPE,
            stdout=wfile,
            stderr=subprocess.DEVNULL,
        )
        wfile.close()

        def copy_h264() -> None:
            try:
                while not self._stop.is_set():
                    chunk = rec.stdout.read(65536) if rec.stdout else b""
                    if not chunk:
                        break
                    if ff.stdin:
                        ff.stdin.write(chunk)
                        ff.stdin.flush()
            except Exception:
                pass
            finally:
                try:
                    if ff.stdin:
                        ff.stdin.close()
                except Exception:
                    pass

        threading.Thread(target=copy_h264, daemon=True).start()
        buf = bytearray()
        got = False
        try:
            while not self._stop.is_set():
                try:
                    chunk = os.read(rfd, 65536)
                except BlockingIOError:
                    chunk = b""
                except OSError:
                    break
                if chunk:
                    buf.extend(chunk)
                    frames, buf = split_jpegs(buf)
                    for frame in frames:
                        got = True
                        self._publish(frame)
                elif rec.poll() is not None and ff.poll() is not None:
                    break
                else:
                    self._stop.wait(0.005)
            return got
        finally:
            _terminate(rec)
            _terminate(ff)
            try:
                os.close(rfd)
            except OSError:
                pass
            shutil.rmtree(tmp, ignore_errors=True)

    def _run_stills(self) -> None:
        while not self._stop.is_set():
            frame = capture_watch_jpeg(self.target)
            if frame:
                self._publish(frame)
            else:
                self._stop.wait(0.2)

    def _run(self) -> None:
        platform = self.target.get("platform")
        device_id = str(self.target.get("id") or "")
        if platform == "android" and device_id:
            while not self._stop.is_set():
                if not self._run_android_h264(device_id):
                    break
            if self._stop.is_set():
                return
        self._run_stills()

    def subscribe(self) -> Iterator[bytes]:
        last = -1
        with self._cv:
            self._subs += 1
        try:
            while not self._stop.is_set():
                with self._cv:
                    if self._seq == last:
                        self._cv.wait(timeout=1.0)
                    if self._seq == last:
                        continue
                    last = self._seq
                    frame = self._frame
                if frame:
                    yield frame
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


class StreamHub:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._producers: dict[str, _Producer] = {}

    def _key(self, target: dict) -> str:
        return f"{target.get('platform')}:{target.get('id')}"

    def iter_frames(
        self,
        platform: str | None,
        device_id: str | None,
        preferred: dict | None = None,
    ) -> Iterator[bytes]:
        target = resolve_watch_target(platform, device_id, preferred)
        if not target:
            return
        key = self._key(target)
        with self._lock:
            prod = self._producers.get(key)
            if prod is None or not prod._thread.is_alive():
                prod = _Producer(key, target)
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


HUB = StreamHub()

SCRCPY = shutil.which("scrcpy")
_scrcpy_lock = threading.Lock()
_scrcpy_proc: subprocess.Popen | None = None
_scrcpy_serial: str | None = None


def scrcpy_cmd(serial: str) -> list[str]:
    bin_path = SCRCPY or "scrcpy"
    return [
        bin_path,
        "-s",
        serial,
        "--max-fps=60",
        "--video-bit-rate=8M",
        "--no-audio",
        "--always-on-top",
        "--window-title=EasyWatermark watch",
        "--window-width=360",
        "-V",
        "error",
    ]


def stop_scrcpy() -> None:
    global _scrcpy_proc, _scrcpy_serial
    with _scrcpy_lock:
        proc = _scrcpy_proc
        _scrcpy_proc = None
        _scrcpy_serial = None
    _terminate(proc)


def ensure_scrcpy(serial: str) -> dict:
    """Open the real scrcpy window for this serial. One instance."""
    global _scrcpy_proc, _scrcpy_serial
    if not serial:
        return {"ok": False, "error": "no serial"}
    if not SCRCPY:
        return {"ok": False, "error": "scrcpy not on PATH"}
    with _scrcpy_lock:
        if (
            _scrcpy_proc is not None
            and _scrcpy_proc.poll() is None
            and _scrcpy_serial == serial
        ):
            return {"ok": True, "serial": serial, "pid": _scrcpy_proc.pid, "reused": True}
        old = _scrcpy_proc
        _scrcpy_proc = None
        _scrcpy_serial = None
    _terminate(old)
    proc = subprocess.Popen(
        scrcpy_cmd(serial),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    with _scrcpy_lock:
        _scrcpy_proc = proc
        _scrcpy_serial = serial
    return {"ok": True, "serial": serial, "pid": proc.pid, "reused": False}


def _idle_reaper() -> None:
    while True:
        time.sleep(IDLE_STOP_S)
        try:
            HUB.reap_idle()
        except Exception:
            pass


threading.Thread(target=_idle_reaper, name="ewm-stream-reaper", daemon=True).start()
