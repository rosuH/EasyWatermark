#!/usr/bin/env python3
"""Fill eval/templates/verify.md from a change + select report + run records.

Expected effect = this change's description + copy.yaml titles. Not method names.
Does not write expected: into map.yaml. Does not auto-promote agent success to L2.

L3 rows bind to select_report()['l3'] (edge/ref/platform), not `'L3' in str(report)`.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from e2e_select import select_report
from generate_testmap import load_copy
from testmap_run import load_run

REPO_ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = REPO_ROOT / "eval" / "templates" / "verify.md"
RUNS = REPO_ROOT / "docs" / "testmap" / "runs"


def expected_effect(change: str, edge_ids: list[str], lang: str = "zh") -> str:
    copy = load_copy()
    titles = []
    for eid in edge_ids:
        row = (copy.get("edges") or {}).get(eid) or {}
        title = row.get(lang) or row.get("en") or eid
        titles.append(f"{eid} — {title}")
    body = change.strip()
    if titles:
        body = body + "\n" + "\n".join(f"- {t}" for t in titles)
    return body


OK_AGENT_STATES = {"review_required", "executed"}


def summarize_stability(cmd: str, results: list[dict], repeats: int) -> dict:
    """results: [{run_id, state, evidence_dir, flake_class}]."""
    states = [str(r.get("state") or "") for r in results]
    ok = sum(1 for s in states if s in OK_AGENT_STATES)
    n = max(repeats, len(results) or repeats)
    flakes = [str(r.get("flake_class") or "") for r in results if r.get("flake_class")]
    if not results:
        verdict = "not_run"
        flake = ""
    elif ok == 0:
        verdict = "block"
        flake = flakes[0] if flakes else "all_failed"
    elif ok < n:
        verdict = "flake"
        flake = ",".join(flakes) if flakes else "mixed"
    else:
        verdict = "stable"
        flake = ""
    return {
        "cmd": cmd,
        "ok": ok,
        "n": n,
        "verdict": verdict,
        "flake_class": flake,
        "run_ids": [str(r.get("run_id") or "") for r in results if r.get("run_id")],
        "evidence": [str(r.get("evidence_dir") or "") for r in results if r.get("evidence_dir")],
    }


def _stability_table(agent: list[dict], attempts: list[dict] | None, repeats: int) -> str:
    header = (
        "| Agent task | n/N review_required or executed | Flake class | Run ids | Evidence dirs |\n"
        "|---|---|---|---|---|"
    )
    by_cmd: dict[str, dict] = {}
    for item in attempts or []:
        cmd = str(item.get("cmd") or "")
        if cmd:
            by_cmd[cmd] = item
    lines = [header]
    cmds = [str(a.get("cmd") or "") for a in agent if a.get("cmd")]
    if not cmds:
        lines.append("| (none selected) | | | | |")
        return "\n".join(lines)
    for cmd in cmds:
        row = by_cmd.get(cmd)
        if row is None:
            lines.append(f"| `{cmd}` | 0/{repeats} | not_run | | |")
            continue
        lines.append(
            f"| `{cmd}` | {row.get('ok', 0)}/{row.get('n', repeats)} "
            f"{row.get('verdict') or ''} | {row.get('flake_class') or ''} | "
            f"{', '.join(row.get('run_ids') or [])} | "
            f"{', '.join(row.get('evidence') or [])} |"
        )
    return "\n".join(lines)


def selected_l3(report: dict) -> list[dict]:
    rows = report.get("l3")
    if isinstance(rows, list):
        return [row for row in rows if isinstance(row, dict) and row.get("ref")]
    return []


def _l3_table(l3: list[dict], l3_runs: list[dict] | None) -> str:
    header = (
        "| L3 ref | Ran? | Numbers or “no numeric emission” | Artifact path | vs previous local run (or “no baseline”) |\n"
        "|---|---|---|---|---|"
    )
    by_ref: dict[str, dict] = {}
    for item in l3_runs or []:
        ref = str(item.get("ref") or "")
        if ref:
            by_ref[ref] = item
    if not l3:
        return header + "\n| (none selected) | no | | | |"
    lines = [header]
    for row in l3:
        ref = row["ref"]
        plat = row.get("platform") or ""
        label = f"`{ref}`" + (f" ({plat})" if plat else "")
        run = by_ref.get(ref)
        if run is None:
            lines.append(f"| {label} | no | not run | | |")
            continue
        ran = "yes" if run.get("ran") else "no"
        numbers = run.get("numbers") or "no numeric emission"
        artifact = run.get("artifact") or ""
        baseline = run.get("baseline") or "no baseline"
        lines.append(f"| {label} | {ran} | {numbers} | {artifact} | {baseline} |")
    return "\n".join(lines)


def render_report(
    *,
    change: str,
    git_sha: str,
    range_arg: str | None,
    run_id: str | None,
    observed: str,
    limitations: str,
    l3_runs: list[dict] | None = None,
    stability: list[dict] | None = None,
    repeats: int = 3,
    confirm: str = "",
) -> str:
    report = select_report(range_arg)
    edges = report.get("edges") or []
    expected = expected_effect(change, edges)
    l3 = selected_l3(report)
    select_blob = [
        f"range: {report.get('label')}",
        f"owners: {', '.join(report.get('owners') or [])}",
        f"edges: {', '.join(edges) or '(none)'}",
        f"docs_only: {report.get('docs_only')}",
        f"no_hit: {report.get('no_hit')}",
        f"platform_change: {', '.join(report.get('platform_change') or []) or '(none)'}",
        f"agent: {', '.join(item.get('cmd') or '' for item in report.get('agent') or []) or '(none)'}",
        f"artemis: {', '.join(item['id'] for item in report.get('artemis') or []) or '(none)'}",
        f"l3_count: {len(l3)}",
        "l3_refs: "
        + (
            ", ".join(
                f"{item['ref']}" + (f"@{item['platform']}" if item.get("platform") else "")
                for item in l3
            )
            or "(none)"
        ),
    ]
    rec = load_run(run_id) if run_id else None
    rows = []
    if rec:
        for task in rec.get("tasks") or []:
            layers = task.get("layers") or {}
            rows.append(
                f"| `{task.get('id')}` | {rec.get('id')} | "
                f"{task.get('state')} / business={layers.get('business') or task.get('state')} "
                f"/ human_confirm={task.get('human_confirmation', False)} |"
            )
    if not rows:
        rows.append("| | | |")
    template = TEMPLATE.read_text(encoding="utf-8")
    filled = template
    filled = filled.replace("- Date:", f"- Date: {datetime.now(timezone.utc).date().isoformat()}", 1)
    filled = filled.replace("- Git SHA:", f"- Git SHA: {git_sha}", 1)
    filled = filled.replace("- What changed (paths or gist):", f"- What changed (paths or gist): {change}", 1)
    filled = filled.replace("-\n\n## Select", expected + "\n\n## Select", 1)
    filled = filled.replace("```\n```", "```\n" + "\n".join(select_blob) + "\n```", 1)
    table = (
        "| Task / command | Run id (if any) | Pass / fail |\n"
        "|---|---|---|\n" + "\n".join(rows)
    )
    filled = filled.replace(
        "| Task / command | Run id (if any) | Pass / fail |\n|---|---|---|\n| | | |",
        table,
        1,
    )
    heuristic = (
        f"- What I looked at (witness / preview / device / walk): {observed or '(not filled)'}\n"
        f"- What I saw vs expected: {observed or '(not filled)'}\n"
        f"- Human Confirm (edge → run id, stale?): {confirm or '(not filled)'}\n"
        "- Promote a repeatable path to L2? no\n"
        "- If yes, which edge / ref:\n"
        f"- Limitations: {limitations or '(none)'}\n"
        "- Independent review is not human confirmation.\n"
    )
    filled = filled.replace(
        "- What I looked at (witness / preview / device / walk):\n"
        "- What I saw vs expected:\n"
        "- Human Confirm (edge → run id, stale?):\n"
        "- Promote a repeatable path to L2? yes / no\n"
        "- If yes, which edge / ref:\n",
        heuristic,
        1,
    )
    filled = filled.replace(
        "| Agent task | n/N review_required or executed | Flake class | Run ids | Evidence dirs |\n"
        "|---|---|---|---|---|\n"
        "| | | | | |",
        _stability_table(report.get("agent") or [], stability, repeats),
        1,
    )
    filled = filled.replace(
        "| L3 ref | Ran? | Numbers or “no numeric emission” | Artifact path | vs previous local run (or “no baseline”) |\n|---|---|---|---|---|\n| | | | | |",
        _l3_table(l3, l3_runs),
        1,
    )
    return filled


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Write a local verify.md (gitignored).")
    parser.add_argument("--change", required=True)
    parser.add_argument("--sha", default="HEAD")
    parser.add_argument("--range")
    parser.add_argument("--run-id")
    parser.add_argument("--observed", default="")
    parser.add_argument("--limitations", default="")
    parser.add_argument("--l3-json", help="JSON list of {ref,ran,numbers,artifact,baseline}")
    parser.add_argument(
        "--stability-json",
        help="JSON list of {cmd,ok,n,verdict,flake_class,run_ids,evidence}",
    )
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--confirm", default="")
    parser.add_argument("--out")
    args = parser.parse_args(argv)
    l3_runs = None
    if args.l3_json:
        raw = Path(args.l3_json).read_text(encoding="utf-8")
        loaded = json.loads(raw)
        if not isinstance(loaded, list):
            raise SystemExit("--l3-json must be a JSON list")
        l3_runs = loaded
    stability = None
    if args.stability_json:
        raw = Path(args.stability_json).read_text(encoding="utf-8")
        loaded = json.loads(raw)
        if not isinstance(loaded, list):
            raise SystemExit("--stability-json must be a JSON list")
        stability = loaded
    text = render_report(
        change=args.change,
        git_sha=args.sha,
        range_arg=args.range,
        run_id=args.run_id,
        observed=args.observed,
        limitations=args.limitations,
        l3_runs=l3_runs,
        stability=stability,
        repeats=max(1, args.repeats),
        confirm=args.confirm,
    )
    out = Path(args.out) if args.out else RUNS / f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')}-{args.sha}-verify.md"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
