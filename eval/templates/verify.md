# Verification report

Local only. Copy to `docs/testmap/runs/<timestamp>-<sha>-verify.md`. Do not commit the filled copy.

Authority: historical ADR-0032 operating contract. Loop: `eval/README.md`. Pre-merge quality ring, **not** a GitHub required check.

Agent may not recommend merge or ship without this file filled, human Confirm where required, and no blocking stability failure.

## Change

- Date:
- Git SHA:
- What changed (paths or gist):

## Expected effect (natural language)

This change’s description (PR / session / commit), plus the `copy.yaml` title of the edges you walked. Not a method name.

-

## Select

Paste or summarize `scripts/e2e-select.sh` output: range, owners, suggested layers, agent/manual edges.

```
```

## Scripts

| Task / command | Run id (if any) | Pass / fail |
|---|---|---|
| | | |

Path works = script column. Not the heuristic.

## Stability

Agent Device edges default **3** repeats (`EWM_AGENT_REPEATS`, min 1). Script layer is one red/green. `REPLAY_DIVERGENCE` / timeout / setup fail = flake class, not a product-UI fail.

| Agent task | n/N review_required or executed | Flake class | Run ids | Evidence dirs |
|---|---|---|---|---|
| | | | | |

- 3/3 → stable enough to ask Confirm.
- 1–2/3 → flake; record class; do not treat as product pass.
- 0/3 → **block merge** unless the edge is a known product failure (e.g. Add More).

## Heuristic / UI

Required unless select suggested only L0 / guard.

Expected effect = this change’s description + `copy.yaml` titles. Do not add `expected:` to `map.yaml`.

- What I looked at (witness / preview / device / walk):
- What I saw vs expected:
- Human Confirm (edge → run id, stale?):
- Promote a repeatable path to L2? yes / no
- If yes, which edge / ref:

Independent review is not human confirmation. Do not mint Confirm. Pixel golden / `WATERMARK_GOLDEN_STRICT` is not this column.

## Perf (optional)

Fill only when select suggested L3, or this change is a performance change.

| L3 ref | Ran? | Numbers or “no numeric emission” | Artifact path | vs previous local run (or “no baseline”) |
|---|---|---|---|---|
| | | | | |

Numbers do not own pass/fail. No SLO.
