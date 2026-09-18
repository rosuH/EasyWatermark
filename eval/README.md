# Change-to-verify loop

After a product change, do this. Authority: the test-map operating contract in [`docs/testmap/historical-adr-0032-e2e-test-map-and-harness.md`](../docs/testmap/historical-adr-0032-e2e-test-map-and-harness.md) (2026-08-23 amendment). Current `docs/adr/0032` is splash fade and is not this harness. Vocabulary: [CONTEXT.md — Verification](../docs/CONTEXT.md).

This is not a GitHub required check. Do not invent a click engine. Do not add `--heuristic`. Do not add `WATERMARK_GOLDEN_STRICT` or Agent Device to PR CI.

## Pre-merge (Mode B)

After a **product** code change or new requirement, before asking to merge or ship:

1. `scripts/e2e-select.sh` (or a git range).
2. Run suggested host scripts when they are not docs-only.
3. Run selected `#agent` edges **3 times** (`EWM_AGENT_REPEATS`, min 1) via `scripts/e2e-verify.sh --change '…' --run`.
4. Fill stability / UI / optional perf in the generated `docs/testmap/runs/<ts>-<sha>-verify.md`.
5. Human Confirm on the console. Agent must not mint it.
6. Only then ask the owner to merge.

`scripts/e2e-verify.sh --change '…'` writes the report stub without executing devices. `--run` executes agent repeats and fills the stability table (exit 2 if any agent task is 0/N, unless you later mark a known product failure in the report).

Single-edge debug (`跑 pick-to-editor`) is Mode A in `e2e-testmap` and is **not** a merge gate.

Vocabulary: expected effect = change description + [`docs/testmap/copy.yaml`](../docs/testmap/copy.yaml) titles. See [CONTEXT.md — Verification](../docs/CONTEXT.md#verification).

Explore → promote (unscripted / `drive: none` edges) is [e2e-walk.md](../docs/agents/e2e-walk.md), not this file.

## 1. Bind

```
scripts/e2e-select.sh
```

(or `scripts/e2e-select.sh <git-range>`). Coarse buckets → `owners[]` → edges. Running every L1 for a shared-UI owner **is** 1-to-1. That over-run is allowed.

## 2. Run the suggested scripts

Use the commands select printed, or the same task ids via `scripts/e2e-run.sh` / the testmap console. Script red/green is whether **the path works**.

- Warn before sustained emulator + build (this machine has frozen under that load).
- Do not shut down already-live Android or iOS simulators.

## 3. Heuristic — when select said L1, L2, L3, or agent/manual

Skip this step only when select suggested **L0 / guard** and nothing else.

Expected effect = **this change’s description** (PR / session / commit) + the edge’s human title in [`docs/testmap/copy.yaml`](../docs/testmap/copy.yaml). Do not treat method names as the expected effect. Do not add `expected:` to `map.yaml`.

Look at what already exists: console live/keyframe PNGs, `shared/build/l1-witness/`, `android layout` / screenshot, Desktop window, iOS fixture seam. Record what you saw versus the expected effect.

If select listed agent/manual, walk those edges with [e2e-walk.md](../docs/agents/e2e-walk.md). Honor `drive: real | seam | none`.

## 4. Performance — when select suggested L3, or this change is a perf change

Run the L3 refs hanging on the matched edges (`owners[]` + `cases[]`). No `l3-*` console task: use the Gradle / suite commands select printed (or the ride-along suites).

If a matched edge has **no** L3 dimension, add an observational case to `map.yaml` first, regenerate, then run. Unmapped benches (e.g. `DEVICE_PERF_*`) are not 1-to-1 until they hang on an edge. Do not run every L3 in the map.

Numbers do not own pass/fail. No SLO.

## 5. Report

Prefer `scripts/e2e-verify.sh --change '…'` which copies [`templates/verify.md`](templates/verify.md) to `docs/testmap/runs/<timestamp>-<sha>-verify.md` (gitignored) and fills select + stability stubs. Fill UI heuristic and Confirm by hand. Fill perf only when step 4 applied.

Do not commit the filled report. Without this file, agents must not recommend merge or ship.

## 6. Promote

If the heuristic found a **repeatable** path that should be a scripted L2, follow the promotion steps in [e2e-walk.md](../docs/agents/e2e-walk.md).
