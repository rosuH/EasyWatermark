# 10 — Z final §9 DoD audit + PR #358 graduation proposal

**What to build:** Checklist audit against `codex-goal-v2.md` §9 (builds, shared tests, Desktop packaging, iOS XCUITest, route-of-record, data layer, Android owner sign-off, docs/ADRs/local tickets). Assemble an owner-facing **graduation proposal** for Draft PR #358 (merge plan options + risks)—**no auto-merge**. Confirm process artifacts live in local `.scratch/...` tickets + process docs, not public GitHub issue ops or reactivated planning-with-files.

**Blocked by:** 05 A5e; 06 B0; 07 B1; 08 B2; 09 B3 — **all complete**.

**Status:** **complete — audit + residual automated re-runs delivered; §9 still NOT MET; awaiting owner graduation decision** (2026-07-12)

## Acceptance checklist

- [x] §9 checklist with evidence links/paths
- [x] Graduation proposal ready for owner decision
- [x] Residual automated gates re-run on lab host (strict goldens + iosSimulatorArm64Test)
- [x] No force-push/merge without owner; no redefining DoD as “almost done”

## Deliverables

| Artifact | Role |
|----------|------|
| `docs/parity/v2.10.0/dod/s9-dod-audit-2026-07-12.md` | Full §9 checklist PASS/PARTIAL/FAIL + residuals (updated after r2) |
| `docs/parity/v2.10.0/dod/graduation-proposal-pr358.md` | Options A/B/C for owner; recommended A (or B if archive debt accepted) |
| `build/s4d383-dod-audit/` | Final gates: app debug+release+unit, shared compile+desktopTest, headless, createDistributable |
| `build/s4d383-dod-audit-r2/` | Residual re-runs: iosSimulatorArm64Test **101/0**; strict goldens **53/0** (`strict=true`) |
| `codex-goal-v2.md` §11 | Refreshed to post-audit + r2 truth |

## §9 one-line verdict

**NOT MET.** Automated residual PARTIALs from the initial audit are **closed** on this lab host. Remaining open residual is primarily **parity archive breadth** (plus intentional packaging/renderer exceptions). Program ready for **owner graduation decision**, not unattended merge or “migration complete” claim.

## Final gates (this ticket)

| Gate | Result |
|------|--------|
| assembleDebug + assembleRelease + testDebugUnitTest + shared multi-target compile + desktopTest | **EXIT 0** · unit **53/0** · desktopTest **132/0** |
| desktop headless | **EXIT 0** |
| createDistributable (Corretto 17) | **EXIT 0** · `EasyWatermark.app` |
| `iosSimulatorArm64Test` RUN (r2) | **EXIT 0** · **101/0** |
| strict goldens r2 (`WATERMARK_GOLDEN_STRICT=true --rerun-tasks`) | **EXIT 0** · **53/0** · stdout `strict=true` |
| iosAppUITests | Cite prior **19/0** (not re-run) |
| Packaging CI | Historical green on #358 |

## Owner decision needed

Reply using the format in `graduation-proposal-pr358.md`:

```text
GRADUATION: A | B | C
PUSH: yes | no
MERGE: no | (only if B and explicit)
STRICT_GOLDENS: done (r2)
```
