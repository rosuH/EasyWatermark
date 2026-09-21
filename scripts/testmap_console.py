#!/usr/bin/env python3
"""Local ADR-0032 testmap console HTTP layer (stdlib only). Never a CI gate.

    scripts/e2e-console.sh
    python3 scripts/testmap_console.py --port 8931

Run engine lives in testmap_run.py (shared with scripts/e2e-run.sh).
"""

from __future__ import annotations

import argparse
import json
import signal
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

sys.dont_write_bytecode = True

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

from testmap_devices import (  # noqa: E402
    capture_device_frame,
    list_devices,
    resolve_watch_target,
)
from testmap_h264 import H264_HUB  # noqa: E402
from testmap_stream import (  # noqa: E402
    BOUNDARY,
    HUB,
    ensure_scrcpy,
    multipart_part,
    stop_scrcpy,
)
from testmap_artemis import (  # noqa: E402
    color_audit_trail,
    historical_projection,
    sandbox_artemis_file,
)
from testmap_run import (  # noqa: E402
    DEFAULT_PORT,
    HOST,
    MANUAL_TASKS,
    MAP_HTML,
    SEMANTICS,
    TASK_SPECS,
    BusyError,
    RunManager,
    confirmation_views,
    edge_badges,
    enrich_run,
    latest_run,
    artifact_png,
    list_witness_files,
    load_map,
    load_run,
    record_confirmation,
    revoke_confirmation,
    run_summaries,
    witness_file,
    write_historical_projection,
)

MANAGER = RunManager()


def _watch_preferred(snap: object, plat: str | None) -> dict | None:
    """Slot-scoped watch only. Never reuse an Android watch for the iOS pane."""
    if not isinstance(snap, dict) or not plat:
        return None
    watches = snap.get("watches") if isinstance(snap.get("watches"), dict) else {}
    preferred = watches.get(plat)
    if isinstance(preferred, dict) and preferred.get("platform") in {None, plat}:
        if preferred.get("device") or plat == "desktop":
            return preferred
    w = snap.get("watch")
    if isinstance(w, dict) and w.get("platform") == plat:
        return w
    return None


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.0: one request per connection. HTTP/1.1 keep-alive plus a full
    # stderr pipe (agent wrapper) produced empty replies after the process sat.
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt: str, *args) -> None:
        try:
            sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))
            sys.stderr.flush()
        except OSError:
            pass

    def _headers(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def _send(self, code: int, body: bytes, content_type: str) -> None:
        self._headers(code, body, content_type)
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code: int, obj: object) -> None:
        self._send(code, json.dumps(obj).encode("utf-8"), "application/json; charset=utf-8")

    def _read_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        raw = self.rfile.read(n)
        if not raw:
            return {}
        data = json.loads(raw.decode("utf-8"))
        if not isinstance(data, dict):
            raise ValueError("JSON body must be an object")
        return data

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        qs = parse_qs(parsed.query)
        if path in {"/", "/map.html"}:
            if not MAP_HTML.is_file():
                self._json(500, {"error": "missing docs/testmap/map.html; run generate_testmap.py"})
                return
            self._send(200, MAP_HTML.read_bytes(), "text/html; charset=utf-8")
            return
        if path == "/api/map":
            try:
                nodes, edges = load_map()
            except ValueError as exc:
                self._json(500, {"error": str(exc)})
                return
            self._json(
                200,
                {
                    "nodes": nodes,
                    "edges": edges,
                    "badges": edge_badges(edges),
                    "confirmations": confirmation_views(edges),
                    "witnesses": list_witness_files(),
                    "latest_run": (latest_run() or {}).get("id"),
                },
            )
            return
        if path == "/api/status":
            try:
                self._json(200, MANAGER.snapshot())
            except Exception as exc:  # noqa: BLE001
                self._json(500, {"error": str(exc), "state": "idle", "active": False})
            return
        if path == "/api/tasks":
            self._json(
                200,
                {
                    "runnable": [
                        {
                            "id": tid,
                            "label": spec["label"],
                            "cmd": spec["cmd"],
                            "heavy": spec["heavy"],
                            "platforms": list(spec.get("platforms") or ["desktop"]),
                            "needs_device": spec.get("needs_device"),
                        }
                        for tid, spec in TASK_SPECS.items()
                    ],
                    "manual": MANUAL_TASKS,
                    "semantics": SEMANTICS,
                    "edge": {
                        "pattern": "edge:<edge-id>@desktop|ios|android",
                        "label": "Run console-runnable cases for one map transition on a chosen OS",
                    },
                },
            )
            return
        if path == "/api/runs":
            self._json(200, {"runs": run_summaries()})
            return
        if path.startswith("/api/runs/"):
            rec = load_run(path[len("/api/runs/") :])
            if not rec:
                self._json(404, {"error": "run not found"})
                return
            self._json(200, enrich_run(rec))
            return
        if path.startswith("/witness/"):
            name = path[len("/witness/") :]
            found = witness_file(name)
            if not found:
                self._json(404, {"error": "witness not found"})
                return
            self._send(200, found.read_bytes(), "image/png")
            return
        if path.startswith("/artifacts/"):
            rest = path[len("/artifacts/") :]
            parts = [p for p in rest.split("/") if p]
            if len(parts) != 2:
                self._json(404, {"error": "artifact not found"})
                return
            found = artifact_png(parts[0], parts[1])
            if not found:
                self._json(404, {"error": "artifact not found"})
                return
            self._send(200, found.read_bytes(), "image/png")
            return
        if path == "/api/devices":
            try:
                self._json(200, list_devices())
            except Exception as exc:  # noqa: BLE001
                self._json(500, {"error": str(exc)})
            return
        if path == "/api/device-frame":
            plat = (qs.get("platform") or [None])[0]
            did = (qs.get("device") or [None])[0]
            snap = MANAGER.snapshot()
            preferred = _watch_preferred(snap, plat)
            target = resolve_watch_target(plat, did, preferred)
            if not target:
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            png = capture_device_frame(target)
            if not png:
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            self._send(200, png, "image/png")
            return
        if path == "/api/device-stream":
            plat = (qs.get("platform") or [None])[0]
            did = (qs.get("device") or [None])[0]
            snap = MANAGER.snapshot()
            preferred = _watch_preferred(snap, plat)
            if not resolve_watch_target(plat, did, preferred):
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header(
                "Content-Type",
                f"multipart/x-mixed-replace; boundary={BOUNDARY}",
            )
            self.send_header("Cache-Control", "no-store, no-cache")
            self.send_header("Pragma", "no-cache")
            self.end_headers()
            try:
                any_frame = False
                for frame in HUB.iter_frames(plat, did, preferred):
                    any_frame = True
                    self.wfile.write(multipart_part(frame))
                    self.wfile.flush()
                if not any_frame:
                    # Generator returned immediately: no ready device.
                    return
            except (BrokenPipeError, ConnectionResetError, OSError):
                return
            return
        if path == "/api/device-video":
            plat = (qs.get("platform") or [None])[0]
            did = (qs.get("device") or [None])[0]
            snap = MANAGER.snapshot()
            preferred = _watch_preferred(snap, plat)
            target = resolve_watch_target(plat, did, preferred)
            if not target or target.get("platform") == "desktop" or (
                target.get("platform") == "ios" and target.get("kind") == "physical"
            ):
                self.send_response(204)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Transfer-Encoding", "chunked")
            self.send_header("Cache-Control", "no-store, no-cache")
            self.end_headers()
            try:
                for packet in H264_HUB.iter_packets(plat, did, preferred):
                    self.wfile.write(f"{len(packet):X}\r\n".encode("ascii") + packet + b"\r\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                return
            return
        if path == "/api/scrcpy":
            plat = (qs.get("platform") or [None])[0]
            did = (qs.get("device") or [None])[0]
            snap = MANAGER.snapshot()
            preferred = _watch_preferred(snap, plat)
            target = resolve_watch_target(plat, did, preferred)
            if not target or target.get("platform") != "android":
                self._json(409, {"ok": False, "error": "scrcpy is Android-only"})
                return
            self._json(200, ensure_scrcpy(str(target.get("id") or "")))
            return
        if path == "/api/artemis/history":
            write_historical_projection()
            rec = historical_projection()
            self._json(
                200,
                {
                    "run": rec,
                    "color_audit": color_audit_trail(),
                    "human_confirmation": "console POST /api/confirm only; independent review is not human confirmed",
                    "add_more": rec.get("add_more_business"),
                },
            )
            return
        if path.startswith("/artemis-evidence/"):
            parts = [p for p in path.split("/") if p]
            # artemis-evidence / <round> / <case> / <rel...>
            if len(parts) < 4:
                self._json(404, {"error": "artifact not found"})
                return
            round_id, case_id = parts[1], parts[2]
            rel = "/".join(parts[3:])
            found = sandbox_artemis_file(round_id, case_id, rel)
            if not found:
                self._json(404, {"error": "artifact not found"})
                return
            ctype = "application/octet-stream"
            if found.suffix.lower() == ".png":
                ctype = "image/png"
            elif found.suffix.lower() in {".json", ".txt", ".log", ".md"}:
                ctype = "text/plain; charset=utf-8"
            elif found.suffix.lower() == ".mp4":
                ctype = "video/mp4"
            self._send(200, found.read_bytes(), ctype)
            return
        self._json(404, {"error": "not found"})

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def do_POST(self) -> None:  # noqa: N802
        path = unquote(self.path.split("?", 1)[0])
        try:
            body = self._read_json()
        except (ValueError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
            return
        try:
            if path == "/api/run":
                tasks = body.get("tasks") or body.get("selection") or []
                if not isinstance(tasks, list) or not all(isinstance(t, str) for t in tasks):
                    raise ValueError("tasks must be a list of strings")
                device = body.get("device") or "auto"
                if device is not None and not isinstance(device, str):
                    raise ValueError("device must be a string")
                result = MANAGER.start(tasks, device)
                self._json(202, result)
                return
            if path == "/api/pause":
                self._json(200, MANAGER.pause())
                return
            if path == "/api/resume":
                self._json(200, MANAGER.resume())
                return
            if path == "/api/stop":
                self._json(200, MANAGER.stop())
                return
            if path == "/api/confirm":
                edge_id = body.get("edge_id")
                if not isinstance(edge_id, str) or not edge_id.strip():
                    raise ValueError("edge_id is required")
                edge_id = edge_id.strip()
                if body.get("revoke"):
                    revoke_confirmation(edge_id)
                    self._json(200, {"ok": True, "revoked": edge_id})
                    return
                run_id = body.get("run_id")
                if not isinstance(run_id, str) or not run_id.strip():
                    raise ValueError("run_id is required")
                rec = record_confirmation(edge_id, run_id.strip())
                self._json(200, rec)
                return
        except BusyError as exc:
            self._json(409, {"error": str(exc), "id": exc.run_id})
            return
        except ValueError as exc:
            self._json(400, {"error": str(exc)})
            return
        self._json(404, {"error": "not found"})

    def do_DELETE(self) -> None:  # noqa: N802
        path = unquote(self.path.split("?", 1)[0])
        try:
            body = self._read_json()
        except (ValueError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
            return
        if path == "/api/confirm":
            edge_id = body.get("edge_id")
            if not isinstance(edge_id, str) or not edge_id.strip():
                self._json(400, {"error": "edge_id is required"})
                return
            revoke_confirmation(edge_id.strip())
            self._json(200, {"ok": True, "revoked": edge_id.strip()})
            return
        self._json(404, {"error": "not found"})


def _reachable_urls(bind_host: str, port: int) -> list[str]:
    urls = [f"http://127.0.0.1:{port}"]
    if bind_host not in {"0.0.0.0", "::", ""}:
        extra = f"http://{bind_host}:{port}"
        if extra not in urls:
            urls.append(extra)
        return urls
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        ip = probe.getsockname()[0]
        probe.close()
    except OSError:
        ip = ""
    if ip and not ip.startswith("127."):
        urls.append(f"http://{ip}:{port}")
    return urls


class ConsoleServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Local testmap console (informational; not a CI gate)."
    )
    parser.add_argument("--host", default=HOST)
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    args = parser.parse_args(argv)
    host = "127.0.0.1" if args.host in {"localhost", "127.0.0.1"} else args.host
    if host not in {"127.0.0.1", "0.0.0.0"}:
        print("error: --host must be 127.0.0.1 or 0.0.0.0", file=sys.stderr)
        return 2
    write_historical_projection()
    httpd = ConsoleServer((host, args.port), Handler)
    urls = _reachable_urls(host, args.port)
    print(
        "testmap console " + "  ".join(urls) + "  (stdlib; not a CI gate)",
        flush=True,
    )
    print("Ctrl-C stops the server and any running child. Gradle daemon is left up.", flush=True)

    def _shutdown(_signum=None, _frame=None) -> None:
        MANAGER.kill_child()
        HUB.stop_all()
        H264_HUB.stop_all()
        stop_scrcpy()
        threading.Thread(target=httpd.shutdown, daemon=True).start()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)
    try:
        httpd.serve_forever(poll_interval=0.5)
    finally:
        MANAGER.kill_child()
        HUB.stop_all()
        H264_HUB.stop_all()
        stop_scrcpy()
        httpd.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
