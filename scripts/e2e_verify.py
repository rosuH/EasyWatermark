#!/usr/bin/env python3
"""Pre-merge verify helper. Informational; never a GitHub required check.

Writes docs/testmap/runs/<ts>-<sha>-verify.md (gitignored).
With --run, repeats Agent Device tasks N times and fills the stability table.
Does not mint human Confirm. Does not shut down live emulators.
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))
sys.dont_write_bytecode = True

from e2e_select import select_report  # noqa: E402
from testmap_eval import render_report, summarize_stability  # noqa: E402
from testmap_run import load_run  # noqa: E402

RUNS = REPO_ROOT / "docs" / "testmap" / "runs"


def _git_sha() -> str:
    try:
        out = subprocess.check_output(
            ["git", "rev-parse", "--short=8", "HEAD"],
            cwd=REPO_ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        )
        return out.strip() or "HEAD"
    except (OSError, subprocess.CalledProcessError):
        return "HEAD"


def _run_agent_task(cmd: str) -> dict:
    proc = subprocess.run(
        [sys.executable, str(SCRIPTS / "testmap_run.py"), cmd],
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
    )
    run_id = ""
    for line in (proc.stdout or "").splitlines():
        if line.startswith("testmap run "):
            run_id = line.split()[2]
    rec = load_run(run_id) if run_id else None
    task = {}
    if rec:
        for item in rec.get("tasks") or []:
            if item.get("id") == cmd:
                task = item
                break
        if not task and rec.get("tasks"):
            task = rec["tasks"][0]
    state = str(task.get("state") or rec.get("state") or "failed")
    flake = ""
    log = proc.stdout or ""
    if "REPLAY_DIVERGENCE" in log or "REPLAY_DIVERGENCE" in (proc.stderr or ""):
        flake = "REPLAY_DIVERGENCE"
    elif "setup failed" in log:
        flake = "setup"
    elif proc.returncode != 0 and not run_id:
        flake = "timeout_or_spawn"
    return {
        "run_id": run_id or ((rec or {}).get("id") or ""),
        "state": state,
        "evidence_dir": task.get("evidence_dir") or "",
        "flake_class": flake,
    }


def collect_stability(agent: list[dict], repeats: int, do_run: bool) -> list[dict]:
    rows = []
    for item in agent:
        cmd = str(item.get("cmd") or "")
        if not cmd:
            continue
        results = []
        if do_run:
            for _ in range(repeats):
                results.append(_run_agent_task(cmd))
        rows.append(summarize_stability(cmd, results, repeats))
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Write a local pre-merge verify.md (not a CI gate)."
    )
    parser.add_argument("--change", required=True)
    parser.add_argument("--range", help="git range for e2e-select; default working tree vs HEAD")
    parser.add_argument("--sha")
    parser.add_argument("--repeats", type=int, default=int(os.environ.get("EWM_AGENT_REPEATS") or "3"))
    parser.add_argument("--run", action="store_true", help="actually run agent tasks N times")
    parser.add_argument("--observed", default="")
    parser.add_argument("--limitations", default="")
    parser.add_argument("--confirm", default="")
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    repeats = max(1, args.repeats)
    report = select_report(args.range)
    if report.get("docs_only"):
        print("docs-only: generator + guard; no agent repeats", file=sys.stderr)
    sha = args.sha or _git_sha()
    stability = collect_stability(report.get("agent") or [], repeats, args.run)
    blocking = [row for row in stability if row.get("verdict") == "block"]
    text = render_report(
        change=args.change,
        git_sha=sha,
        range_arg=args.range,
        run_id=None,
        observed=args.observed,
        limitations=args.limitations,
        stability=stability,
        repeats=repeats,
        confirm=args.confirm,
    )
    out = (
        Path(args.out)
        if args.out
        else RUNS / f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')}-{sha}-verify.md"
    )
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(out)
    if blocking:
        print(f"stability block: {len(blocking)} agent task(s)", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
