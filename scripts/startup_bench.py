#!/usr/bin/env python3
"""Cold-start harness for EasyWatermark (Android / Desktop / iOS).

Collects OS-level display time plus in-app EWM_STARTUP marks. Writes JSON + a
markdown summary. Does not start or stop an already-running Android emulator.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MARK_RE = re.compile(r"EWM_STARTUP mark=(\S+) t_ms=(\d+)")
ANDROID_PKG_DEBUG = "me.rosuh.easywatermark.debug"
ANDROID_PKG_RELEASE = "me.rosuh.easywatermark"
ANDROID_ACTIVITY = "me.rosuh.easywatermark.ui.MainActivity"
IOS_BUNDLE = "me.rosuh.easywatermark.ios"


def run(cmd: list[str], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, text=True, **kwargs)


def pct(values: list[float], p: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    if len(s) == 1:
        return s[0]
    k = (len(s) - 1) * (p / 100.0)
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def summarize(values: list[float]) -> dict:
    clean = [v for v in values if v == v]
    if not clean:
        return {}
    return {
        "n": len(clean),
        "min": min(clean),
        "p50": statistics.median(clean),
        "mean": statistics.mean(clean),
        "p90": pct(clean, 90),
        "max": max(clean),
        "stdev": statistics.pstdev(clean) if len(clean) > 1 else 0.0,
    }


def parse_marks(text: str) -> dict[str, int]:
    out: dict[str, int] = {}
    for name, ms in MARK_RE.findall(text):
        out.setdefault(name, int(ms))
    return out


def parse_am_start_w(text: str) -> dict[str, int]:
    out = {}
    for key in ("ThisTime", "TotalTime", "WaitTime"):
        m = re.search(rf"{key}:\s*(\d+)", text)
        if m:
            out[key] = int(m.group(1))
    return out


def android_iter(pkg: str, out_dir: Path, i: int) -> dict:
    run(["adb", "shell", "setprop", "log.tag.EwmStartup", "DEBUG"], check=False)
    run(["adb", "shell", "setprop", "debug.ewm.startup_trace", "1"], check=False)
    run(["adb", "shell", "am", "force-stop", pkg], check=False)
    time.sleep(2.0)
    run(["adb", "logcat", "-c"], check=False)
    started = time.monotonic()
    proc = run(
        [
            "adb",
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            f"{pkg}/{ANDROID_ACTIVITY}",
        ],
        capture_output=True,
    )
    am = parse_am_start_w(proc.stdout + proc.stderr)
    # Wait for in-app fully_drawn (or timeout).
    marks: dict[str, int] = {}
    deadline = time.monotonic() + 20.0
    while time.monotonic() < deadline:
        log = run(
            ["adb", "logcat", "-d", "-s", "EwmStartup:D", "EwmStartup:I"],
            capture_output=True,
        ).stdout
        marks = parse_marks(log)
        if "fully_drawn" in marks:
            break
        time.sleep(0.25)
    wall = (time.monotonic() - started) * 1000.0
    (out_dir / f"android_{pkg.split('.')[-1]}_{i:02d}.log").write_text(
        proc.stdout + "\n" + proc.stderr + "\n" + json.dumps(marks, indent=2),
        encoding="utf-8",
    )
    return {"os": am, "marks": marks, "runner_wall_ms": wall, "pkg": pkg}


def desktop_iter(out_dir: Path, i: int) -> dict:
    env = os.environ.copy()
    env["EWM_STARTUP_TRACE"] = "1"
    env["EWM_STARTUP_TRACE_EXIT_MS"] = "5000"
    started = time.monotonic()
    proc = run(
        [
            str(ROOT / "gradlew"),
            ":desktopApp:run",
            "--max-workers=8",
            "-q",
        ],
        cwd=ROOT,
        env=env,
        capture_output=True,
        timeout=180,
    )
    wall = (time.monotonic() - started) * 1000.0
    text = proc.stdout + proc.stderr
    (out_dir / f"desktop_{i:02d}.log").write_text(text, encoding="utf-8")
    return {"marks": parse_marks(text), "runner_wall_ms": wall, "returncode": proc.returncode}


def ios_sim_iter(udid: str, out_dir: Path, i: int) -> dict:
    run(["xcrun", "simctl", "terminate", udid, IOS_BUNDLE], check=False)
    time.sleep(1.5)
    log_path = out_dir / f"ios_sim_{i:02d}.log"
    stream = subprocess.Popen(
        [
            "xcrun",
            "simctl",
            "spawn",
            udid,
            "log",
            "stream",
            "--style",
            "compact",
            "--predicate",
            'eventMessage CONTAINS "EWM_STARTUP"',
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    started = time.monotonic()
    run(
        ["xcrun", "simctl", "launch", udid, IOS_BUNDLE, "-ewmStartupTrace"],
        capture_output=True,
        timeout=20,
    )
    buf: list[str] = []
    marks: dict[str, int] = {}
    deadline = time.monotonic() + 15.0
    assert stream.stdout is not None
    while time.monotonic() < deadline:
        line = stream.stdout.readline()
        if not line:
            time.sleep(0.05)
            continue
        buf.append(line)
        marks = parse_marks("".join(buf))
        if "fully_drawn" in marks:
            break
    stream.terminate()
    wall = (time.monotonic() - started) * 1000.0
    log_path.write_text("".join(buf), encoding="utf-8")
    return {"marks": marks, "runner_wall_ms": wall}


def ios_device_iter(device: str, out_dir: Path, i: int) -> dict:
    run(
        [
            "xcrun",
            "devicectl",
            "device",
            "process",
            "terminate",
            "--device",
            device,
            "--pid",
            "0",
        ],
        check=False,
        capture_output=True,
    )
    # terminate by bundle
    run(
        [
            "xcrun",
            "devicectl",
            "device",
            "process",
            "terminate",
            "--device",
            device,
            IOS_BUNDLE,
        ],
        check=False,
        capture_output=True,
    )
    time.sleep(1.5)
    log_path = out_dir / f"ios_device_{i:02d}.log"
    started = time.monotonic()
    proc = run(
        [
            "xcrun",
            "devicectl",
            "device",
            "process",
            "launch",
            "--device",
            device,
            "--console",
            "--environment-variables",
            "{}",
            IOS_BUNDLE,
            "--",
            "-ewmStartupTrace",
        ],
        capture_output=True,
        timeout=25,
    )
    wall = (time.monotonic() - started) * 1000.0
    text = proc.stdout + proc.stderr
    log_path.write_text(text, encoding="utf-8")
    return {"marks": parse_marks(text), "runner_wall_ms": wall, "returncode": proc.returncode}


def metric_table(runs: list[dict], mark: str) -> list[float]:
    vals = []
    for r in runs:
        marks = r.get("marks") or {}
        if mark in marks:
            vals.append(float(marks[mark]))
    return vals


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--platform", choices=("android", "desktop", "ios", "all"), default="all")
    p.add_argument("--iters", type=int, default=8)
    p.add_argument("--drop-first", type=int, default=1)
    p.add_argument("--out", type=Path, default=ROOT / "build" / "startup_bench")
    p.add_argument("--ios-udid", default="")
    p.add_argument("--ios-device", default="")
    p.add_argument("--android-pkg", default=ANDROID_PKG_DEBUG)
    p.add_argument("--skip-android-release-ttid", action="store_true")
    args = p.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "host": {
            "os": os.uname().sysname,
            "machine": os.uname().machine,
            "release": os.uname().release,
        },
        "method": {
            "iterations": args.iters,
            "drop_first": args.drop_first,
            "clock": "TimeSource.Monotonic in-process; Android also am start -W",
        },
        "platforms": {},
    }

    if args.platform in ("android", "all"):
        android_runs = []
        for i in range(args.iters):
            print(f"android debug {i+1}/{args.iters}", flush=True)
            android_runs.append(android_iter(args.android_pkg, args.out, i))
        kept = android_runs[args.drop_first :]
        android_block = {
            "device": run(["adb", "shell", "getprop", "ro.product.model"], capture_output=True).stdout.strip(),
            "sdk": run(["adb", "shell", "getprop", "ro.build.version.sdk"], capture_output=True).stdout.strip(),
            "pkg": args.android_pkg,
            "runs": android_runs,
            "summary": {
                "ThisTime": summarize([float(r["os"]["ThisTime"]) for r in kept if r.get("os", {}).get("ThisTime") is not None]),
                "TotalTime": summarize([float(r["os"]["TotalTime"]) for r in kept if r.get("os", {}).get("TotalTime") is not None]),
                "first_compose_frame": summarize(metric_table(kept, "first_compose_frame")),
                "first_screen": summarize(metric_table(kept, "first_screen")),
                "fully_drawn": summarize(metric_table(kept, "fully_drawn")),
                "cold_reveal_done": summarize(metric_table(kept, "cold_reveal_done")),
            },
        }
        if not args.skip_android_release_ttid:
            rel = []
            for i in range(min(args.iters, 5)):
                print(f"android release TTID {i+1}", flush=True)
                run(["adb", "shell", "am", "force-stop", ANDROID_PKG_RELEASE], check=False)
                time.sleep(2.0)
                proc = run(
                    [
                        "adb",
                        "shell",
                        "am",
                        "start",
                        "-W",
                        "-n",
                        f"{ANDROID_PKG_RELEASE}/{ANDROID_ACTIVITY}",
                    ],
                    capture_output=True,
                )
                rel.append(parse_am_start_w(proc.stdout + proc.stderr))
                run(["adb", "shell", "am", "force-stop", ANDROID_PKG_RELEASE], check=False)
            android_block["release_ttid"] = {
                "ThisTime": summarize([float(r["ThisTime"]) for r in rel[1:] if "ThisTime" in r]),
                "TotalTime": summarize([float(r["TotalTime"]) for r in rel[1:] if "TotalTime" in r]),
                "runs": rel,
            }
        report["platforms"]["android"] = android_block

    if args.platform in ("desktop", "all"):
        desk = []
        for i in range(args.iters):
            print(f"desktop {i+1}/{args.iters}", flush=True)
            desk.append(desktop_iter(args.out, i))
        kept = desk[args.drop_first :]
        report["platforms"]["desktop"] = {
            "runs": desk,
            "summary": {
                "app_create_end": summarize(metric_table(kept, "app_create_end")),
                "first_compose_frame": summarize(metric_table(kept, "first_compose_frame")),
                "first_screen": summarize(metric_table(kept, "first_screen")),
                "fully_drawn": summarize(metric_table(kept, "fully_drawn")),
            },
        }

    if args.platform in ("ios", "all"):
        ios_runs = []
        if args.ios_device:
            for i in range(args.iters):
                print(f"ios device {i+1}/{args.iters}", flush=True)
                ios_runs.append(ios_device_iter(args.ios_device, args.out, i))
            kind = "device"
        elif args.ios_udid:
            for i in range(args.iters):
                print(f"ios sim {i+1}/{args.iters}", flush=True)
                ios_runs.append(ios_sim_iter(args.ios_udid, args.out, i))
            kind = "simulator"
        else:
            print("ios skipped: pass --ios-udid or --ios-device", file=sys.stderr)
            kind = "skipped"
        if ios_runs:
            kept = ios_runs[args.drop_first :]
            report["platforms"]["ios"] = {
                "kind": kind,
                "runs": ios_runs,
                "summary": {
                    "swift_app_init": summarize(metric_table(kept, "swift_app_init")),
                    "swift_services_end": summarize(metric_table(kept, "swift_services_end")),
                    "first_compose_frame": summarize(metric_table(kept, "first_compose_frame")),
                    "first_screen": summarize(metric_table(kept, "first_screen")),
                    "fully_drawn": summarize(metric_table(kept, "fully_drawn")),
                },
            }

    platform_json = args.out / f"{args.platform}.json"
    platform_json.write_text(json.dumps(report, indent=2), encoding="utf-8")
    merged_path = args.out / "startup_bench.json"
    merged = {
        "generated_at": report["generated_at"],
        "host": report["host"],
        "method": report["method"],
        "platforms": {},
    }
    for name in ("android", "desktop", "ios"):
        pth = args.out / f"{name}.json"
        if pth.exists():
            try:
                part = json.loads(pth.read_text(encoding="utf-8"))
                merged["platforms"].update(part.get("platforms") or {})
            except json.JSONDecodeError:
                pass
    merged["platforms"].update(report.get("platforms") or {})
    merged_path.write_text(json.dumps(merged, indent=2), encoding="utf-8")
    print(f"wrote {platform_json} and {merged_path}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
