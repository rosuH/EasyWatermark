#!/usr/bin/env python3
"""Suggest e2e layers/tasks from a git range (ADR-0032 P2).

Informational only. Never a CI gate (ADR-0031 / ADR-0032 §4). Always exits 0.
Stdlib only. Reuses the map parser in generate_testmap.py.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS.parent
sys.path.insert(0, str(SCRIPTS))
sys.dont_write_bytecode = True

from generate_testmap import MAP_PATH, PLATFORMS, load_map_yaml, validate_map  # noqa: E402
from testmap_agent_device import (  # noqa: E402
    agent_device_cases_by_id,
    case_supports_platform,
)

PRIORITY_ORDER = {"core": 0, "normal": 1, "edge": 2}

# Extra buckets (not in the ADR list) so uiTest / guard / desktopTest land somewhere honest.
BUCKET_COMMON_UI = "shared/src/commonMain ui"
BUCKET_COMMON = "shared/src/commonMain"
BUCKET_ANDROID = "shared/src/androidMain"
BUCKET_DESKTOP = "shared/src/desktopMain"
BUCKET_IOS = "shared/src/iosMain"
BUCKET_APP = "app/"
BUCKET_DESKTOP_APP = "desktopApp/"
BUCKET_IOS_APP = "iosApp/"
BUCKET_MACRO = "macrobenchmark/"
BUCKET_TESTMAP = "docs/testmap/"
BUCKET_UITEST = "shared/src/uiTest"
BUCKET_SHARED_TEST = "shared/src/*Test"
BUCKET_OTHER = "other"


def _git(args: list[str]) -> list[str]:
    try:
        out = subprocess.check_output(
            ["git", *args],
            cwd=REPO_ROOT,
            text=True,
            stderr=subprocess.DEVNULL,
        )
    except subprocess.CalledProcessError:
        return []
    return [
        line.strip()
        for line in out.splitlines()
        if line.strip() and "__pycache__" not in line and not line.endswith(".pyc")
    ]


def changed_paths(range_arg: str | None) -> tuple[str, list[str]]:
    if range_arg:
        label = range_arg
        paths = _git(["diff", "--name-only", range_arg])
        return label, paths
    label = "working-tree vs HEAD (staged + unstaged + untracked)"
    paths = set(_git(["diff", "--name-only", "HEAD"]))
    paths.update(_git(["ls-files", "--others", "--exclude-standard"]))
    return label, sorted(paths)


def classify(path: str) -> str:
    p = path.replace("\\", "/")
    if p.startswith("docs/testmap/"):
        return BUCKET_TESTMAP
    if p.startswith("shared/src/uiTest/"):
        return BUCKET_UITEST
    if p.startswith("shared/src/commonMain/") and "/ui/" in p:
        return BUCKET_COMMON_UI
    if p.startswith("shared/src/commonMain/"):
        return BUCKET_COMMON
    if p.startswith("shared/src/androidMain/"):
        return BUCKET_ANDROID
    if p.startswith("shared/src/desktopMain/"):
        return BUCKET_DESKTOP
    if p.startswith("shared/src/iosMain/"):
        return BUCKET_IOS
    if (
        p.startswith("shared/src/desktopTest/")
        or p.startswith("shared/src/iosTest/")
        or p.startswith("shared/src/commonTest/")
        or p.startswith("shared/src/skikoTest/")
    ):
        return BUCKET_SHARED_TEST
    if p.startswith("app/"):
        return BUCKET_APP
    if p.startswith("desktopApp/"):
        return BUCKET_DESKTOP_APP
    if p.startswith("iosApp/"):
        return BUCKET_IOS_APP
    if p.startswith("macrobenchmark/"):
        return BUCKET_MACRO
    return BUCKET_OTHER


def owners_for_buckets(buckets: set[str], all_owners: set[str]) -> set[str]:
    wanted: set[str] = set()
    if BUCKET_COMMON_UI in buckets:
        wanted.add("shared/ui")
    # uiTest is the L1 harness, not product UI — do not explode every shared/ui edge.
    if BUCKET_COMMON in buckets:
        wanted.update(
            o for o in all_owners if o.startswith("shared/") and o != "shared/ui"
        )
    if BUCKET_ANDROID in buckets or BUCKET_APP in buckets:
        wanted.update(o for o in all_owners if o == "app" or o.startswith("app/"))
    if BUCKET_DESKTOP in buckets or BUCKET_DESKTOP_APP in buckets:
        wanted.add("desktopApp")
    if BUCKET_IOS in buckets or BUCKET_IOS_APP in buckets:
        wanted.add("iosApp")
    return wanted


def drive_all_none(edge: dict) -> bool:
    plats = edge.get("platforms") or {}
    return all(
        isinstance(plats.get(p), dict) and plats[p].get("drive") == "none"
        for p in PLATFORMS
    )


def ios_drive(edge: dict) -> str:
    plats = edge.get("platforms") or {}
    ios = plats.get("ios")
    if isinstance(ios, dict):
        return str(ios.get("drive") or "none")
    return "none"


def agent_cmds_for_edge(edge: dict) -> list[dict]:
    """Default agent tasks from cases.json supported flags, never desktop."""
    eid = edge["id"]
    try:
        case = agent_device_cases_by_id().get(eid)
    except (OSError, ValueError):
        case = None
    rows: list[dict] = []
    android_ok = True
    ios_ok = ios_drive(edge) != "none"
    if case:
        android_ok = case_supports_platform(case, "android")
        ios_ok = case_supports_platform(case, "ios")
    if android_ok:
        rows.append(
            {
                "id": eid,
                "platform": "android",
                "cmd": f"edge:{eid}@android#agent",
            }
        )
    if ios_ok:
        rows.append(
            {
                "id": eid,
                "platform": "ios",
                "cmd": f"edge:{eid}@ios#agent",
            }
        )
    return rows


def _deprecated_artemis_cmd(edge_id: str) -> str:
    """Historical CLI id. Select/console default is #agent; do not emit this."""
    return f"edge:{edge_id}@android#artemis"


def ref_token(ref: str) -> str:
    cut = min((i for i in (ref.find("."), ref.find(" ")) if i >= 0), default=-1)
    return ref if cut < 0 else ref[:cut]


def select_report(range_arg: str | None) -> dict:
    """Structured change→owners→edges plan. Informational; never a CI gate."""
    empty = {
        "ok": False,
        "error": None,
        "label": None,
        "paths": [],
        "buckets": {},
        "owners": [],
        "edges": [],
        "docs_only": False,
        "no_hit": False,
        "platform_change": [],
        "agent": [],
        "artemis": [],
        "none_edges": [],
        "suggest_l1": False,
        "suggest_guard": False,
        "shared_ui_all_l1": False,
        "l3": [],
    }
    if not MAP_PATH.is_file():
        empty["error"] = f"missing {MAP_PATH}"
        return empty
    try:
        _nodes, edges = validate_map(load_map_yaml(MAP_PATH.read_text(encoding="utf-8")))
    except ValueError as exc:
        empty["error"] = f"map parse failed: {exc}"
        return empty
    all_owners = {o for e in edges for o in (e.get("owners") or [])}
    label, paths = changed_paths(range_arg)
    by_bucket: dict[str, list[str]] = defaultdict(list)
    for path in paths:
        by_bucket[classify(path)].append(path)
    buckets = set(by_bucket)
    wanted_owners = owners_for_buckets(buckets, all_owners)
    matched = [
        e for e in edges if wanted_owners.intersection(e.get("owners") or [])
    ]
    matched.sort(key=lambda e: (PRIORITY_ORDER.get(e.get("priority"), 9), e["id"]))
    product_buckets = {
        BUCKET_COMMON_UI,
        BUCKET_COMMON,
        BUCKET_ANDROID,
        BUCKET_DESKTOP,
        BUCKET_IOS,
        BUCKET_APP,
        BUCKET_DESKTOP_APP,
        BUCKET_IOS_APP,
        BUCKET_MACRO,
        BUCKET_UITEST,
        BUCKET_SHARED_TEST,
    }
    docs_only = bool(paths) and not (buckets & product_buckets) and (
        BUCKET_TESTMAP in buckets or all(
            classify(p) == BUCKET_OTHER and (p.startswith("docs/") or p.startswith("eval/"))
            for p in paths
        )
    )
    if not paths:
        no_hit = False
        empty_reason = "no_changes"
    elif not matched and not (buckets & {BUCKET_UITEST, BUCKET_TESTMAP, BUCKET_SHARED_TEST, BUCKET_OTHER}):
        no_hit = True
        empty_reason = "no_owner_match"
    else:
        no_hit = not matched and not (buckets & {BUCKET_UITEST, BUCKET_TESTMAP, BUCKET_MACRO})
        empty_reason = None
    platform_change = []
    if buckets & {BUCKET_ANDROID, BUCKET_APP}:
        platform_change.append("android")
    if buckets & {BUCKET_IOS, BUCKET_IOS_APP}:
        platform_change.append("ios")
    if buckets & {BUCKET_DESKTOP, BUCKET_DESKTOP_APP}:
        platform_change.append("desktop")
    agent: list[dict] = []
    if (
        "android" in platform_change
        or "ios" in platform_change
        or BUCKET_COMMON_UI in buckets
        or any(
            "app/session" in (e.get("owners") or [])
            or "shared/ui" in (e.get("owners") or [])
            for e in matched
        )
    ):
        for edge in matched:
            agent.extend(agent_cmds_for_edge(edge))
    # Eval still reads report["artemis"] ids. Cmds live on agent (#agent).
    artemis = [{"id": eid} for eid in dict.fromkeys(row["id"] for row in agent)]
    shared_ui_all_l1 = bool(buckets & {BUCKET_UITEST, BUCKET_COMMON_UI})
    l3: list[dict] = []
    seen_l3: set[tuple[str, str | None]] = set()
    l3_edges = list(matched)
    if BUCKET_MACRO in buckets:
        l3_edges = list(edges)
    for edge in l3_edges:
        for case in edge.get("cases") or []:
            if case.get("layer") != "L3" and case.get("dimension") != "perf":
                continue
            key = (str(case.get("ref") or ""), case.get("platform"))
            if key in seen_l3:
                continue
            seen_l3.add(key)
            l3.append(
                {
                    "edge_id": edge["id"],
                    "ref": key[0],
                    "platform": case.get("platform"),
                }
            )
    return {
        "ok": True,
        "error": None,
        "label": label,
        "paths": paths,
        "buckets": {k: v for k, v in by_bucket.items() if v},
        "owners": sorted(wanted_owners),
        "edges": [e["id"] for e in matched],
        "matched": matched,
        "docs_only": docs_only,
        "no_hit": no_hit,
        "empty_reason": empty_reason,
        "platform_change": platform_change,
        "agent": agent,
        "artemis": artemis,
        "none_edges": [e["id"] for e in matched if drive_all_none(e)],
        "suggest_l1": bool(
            matched
            or (buckets & {BUCKET_COMMON_UI, BUCKET_UITEST, BUCKET_SHARED_TEST, BUCKET_TESTMAP, BUCKET_COMMON})
        ),
        "suggest_guard": bool(buckets & {BUCKET_TESTMAP, BUCKET_SHARED_TEST, BUCKET_UITEST}) or bool(matched),
        "shared_ui_all_l1": shared_ui_all_l1,
        "l3": l3,
        "edges_full": edges,
        "bucket_set": buckets,
    }


def print_help() -> None:
    text = """e2e-select.sh — suggest e2e layers from a git range (ADR-0032 P2)

Informational only. Never a CI gate (ADR-0031 / ADR-0032 section 4). Always exits 0.

Usage:
  scripts/e2e-select.sh              working tree vs HEAD (staged, unstaged, untracked)
  scripts/e2e-select.sh master...HEAD
  scripts/e2e-select.sh --help

Looks up docs/testmap/map.yaml: classify paths into coarse buckets, match edge
owners[], print a suggested run list by layer.
"""
    print(text.rstrip())


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("-h", "--help", action="store_true")
    parser.add_argument("range", nargs="?", default=None)
    args = parser.parse_args(argv)
    if args.help:
        print_help()
        return 0

    print("e2e-select (informational; not a CI gate -- testmap / ADR-0031)")

    report = select_report(args.range)
    if report.get("error"):
        print(f"error: {report['error']} (still exiting 0)")
        return 0

    edges = report["edges_full"]
    paths = report["paths"]
    label = report["label"]
    by_bucket = defaultdict(list, report["buckets"])
    buckets = report["bucket_set"]
    wanted_owners = set(report["owners"])
    matched = report["matched"]
    print(f"range: {label}")
    print(f"changed: {len(paths)} files")

    by_bucket: dict[str, list[str]] = defaultdict(list)
    for path in paths:
        by_bucket[classify(path)].append(path)
    buckets = set(by_bucket)
    for name in (
        BUCKET_COMMON_UI,
        BUCKET_COMMON,
        BUCKET_ANDROID,
        BUCKET_DESKTOP,
        BUCKET_IOS,
        BUCKET_APP,
        BUCKET_DESKTOP_APP,
        BUCKET_IOS_APP,
        BUCKET_MACRO,
        BUCKET_TESTMAP,
        BUCKET_UITEST,
        BUCKET_SHARED_TEST,
        BUCKET_OTHER,
    ):
        files = by_bucket.get(name)
        if not files:
            continue
        print(f"  {name}: {len(files)}")
        for f in files[:8]:
            print(f"    {f}")
        if len(files) > 8:
            print(f"    ... +{len(files) - 8} more")

    if not paths:
        print("no changes; nothing to suggest")
        return 0

    print()
    if matched:
        print(f"affected edges ({len(matched)}) via owners {', '.join(sorted(wanted_owners))}:")
        for edge in matched:
            owners = ",".join(edge.get("owners") or [])
            print(f"  [{edge.get('priority')}] {edge['id']}  ({owners})")
    else:
        print("affected edges: none (no owner match)")
        if BUCKET_UITEST in buckets:
            print("  shared/src/uiTest changed — suggest L1/guard, not every shared/ui edge")
        if BUCKET_TESTMAP in buckets:
            print("  docs/testmap/ changed — run the generator and the guard")
        if BUCKET_OTHER in buckets or BUCKET_SHARED_TEST in buckets:
            print("  other / shared tests still suggest the PR L0/L1/guard commands below")

    cases_by_layer: dict[str, list[tuple[str, str, str | None]]] = defaultdict(list)
    for edge in matched:
        for case in edge.get("cases") or []:
            cases_by_layer[case["layer"]].append(
                (edge["id"], case["ref"], case.get("platform"))
            )
    if buckets & {BUCKET_UITEST, BUCKET_COMMON_UI}:
        for edge in edges:
            for case in edge.get("cases") or []:
                if case.get("layer") == "L1":
                    cases_by_layer["L1"].append(
                        (edge["id"], case["ref"], case.get("platform"))
                    )
    if BUCKET_MACRO in buckets:
        for edge in edges:
            for case in edge.get("cases") or []:
                if case.get("layer") == "L3" or case.get("dimension") == "perf":
                    cases_by_layer["L3"].append(
                        (edge["id"], case["ref"], case.get("platform"))
                    )

    none_edges = [e["id"] for e in matched if drive_all_none(e)]
    suggest_l1 = bool(
        matched
        or (buckets & {BUCKET_COMMON_UI, BUCKET_UITEST, BUCKET_SHARED_TEST, BUCKET_TESTMAP, BUCKET_COMMON})
    )
    suggest_guard = bool(buckets & {BUCKET_TESTMAP, BUCKET_SHARED_TEST, BUCKET_UITEST}) or bool(matched)

    print()
    print("suggested")
    print("  L0 / L1 / guard  (PR gates)")
    if suggest_l1 or suggest_guard:
        print("    ./gradlew :shared:desktopTest --max-workers=8")
        print("    ./gradlew :shared:iosSimulatorArm64Test --max-workers=8")
        if BUCKET_TESTMAP in buckets:
            print("    python3 scripts/generate_testmap.py   # if map.yaml changed")
        l0 = cases_by_layer.get("L0") or []
        l1 = cases_by_layer.get("L1") or []
        if l1:
            print("    L1 refs:")
            seen_l1: list[str] = []
            for _eid, ref, plat in l1:
                key = f"{ref}|{plat or ''}"
                if key in seen_l1:
                    continue
                seen_l1.append(key)
                suffix = f" ({plat})" if plat else ""
                print(f"      {ref}{suffix}")
        if l0:
            print(f"    L0 refs: {len(l0)} contract cases on matched edges (ride desktopTest)")
    else:
        print("    (none from this range)")

    print("  L2  (local / not a PR gate)")
    l2 = cases_by_layer.get("L2") or []
    if l2:
        ios_refs = [c for c in l2 if ref_token(c[1]) == "PickerFlowUITests" or c[2] == "ios"]
        and_refs = [
            c
            for c in l2
            if ref_token(c[1])
            in {"ProductJourneys", "ProductBaselineProfileGenerator"}
            or c[2] == "android"
        ]
        desk_refs = [
            c
            for c in l2
            if ref_token(c[1]) == "DesktopWatermarkFlow" or c[2] == "desktop"
        ]
        if ios_refs:
            print("    iOS XCUITest (booted simulator):")
            print(
                "      xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp "
                "-sdk iphonesimulator \\"
            )
            print(
                "        -destination 'platform=iOS Simulator,id=<udid>' "
                "-only-testing:iosAppUITests/PickerFlowUITests test"
            )
            print(
                f"        {len(ios_refs)} mapped "
                f"{'ref' if len(ios_refs) == 1 else 'refs'} (suite PickerFlowUITests)"
            )
        if and_refs:
            print("    Android instrumented / journeys:")
            print("      ./gradlew :app:connectedDebugAndroidTest")
            print("      ProductJourneys lives in :macrobenchmark (UI Automator)")
            tokens = sorted({ref_token(ref) for _e, ref, _p in and_refs})
            print(f"        suites: {', '.join(tokens)} ({len(and_refs)} refs)")
        if desk_refs:
            print("    Desktop headless spine:")
            print("      ./gradlew :desktopApp:run --args='--headless'")
            print(
                f"        {len(desk_refs)} mapped "
                f"{'ref' if len(desk_refs) == 1 else 'refs'} (DesktopWatermarkFlow)"
            )
        leftover = [
            c
            for c in l2
            if c not in ios_refs and c not in and_refs and c not in desk_refs
        ]
        for _e, ref, plat in leftover:
            print(f"    {ref}" + (f" ({plat})" if plat else ""))
    else:
        print("    (none on matched edges)")

    print("  L3  (observational; no SLO)")
    l3 = cases_by_layer.get("L3") or []
    if l3 or BUCKET_MACRO in buckets:
        tokens = sorted({ref_token(ref) for _e, ref, _p in l3})
        if tokens:
            print(f"    {', '.join(tokens)} ({len(l3)} mapped refs)")
        else:
            print("    macrobenchmark / HundredImageSeedTest -e ewmPerfHold / DEVICE_PERF_*")
    else:
        print("    (none on matched edges)")

    print("  agent / manual")
    if none_edges:
        print("    drive:none on all platforms:")
        for eid in none_edges:
            print(f"      {eid}")
    print("    walk protocol: docs/agents/e2e-walk.md")

    print("  Agent Device (not a second map; drive labels unchanged)")
    if report["agent"]:
        for item in report["agent"]:
            print(f"    {item['cmd']}")
        print("    Replay/SDK completed is not a product pass")
        print("    CLI still accepts deprecated #artemis")
    else:
        print("    (none on matched edges)")

    if report["docs_only"]:
        print("  docs-only: generator + guard; no silent full map run")
    if report["no_hit"]:
        print("  no owner hit: nothing to run silently; see buckets above")
    if report["platform_change"]:
        print("  platform change: " + ", ".join(report["platform_change"]))
    if report["shared_ui_all_l1"]:
        print("  shared UI owner: every L1 ref is in-scope (allowed over-run)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
