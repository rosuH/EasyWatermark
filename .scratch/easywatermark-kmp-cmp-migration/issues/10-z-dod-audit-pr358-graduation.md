# 10 — Z final §9 DoD audit + PR #358 graduation proposal

**What to build:** Checklist audit against `codex-goal-v2.md` §9 (builds, shared tests, Desktop packaging, iOS XCUITest, route-of-record, data layer, Android owner sign-off, docs/ADRs/local tickets). Assemble an owner-facing **graduation proposal** for Draft PR #358 (merge plan options + risks)—**no auto-merge**. Confirm process artifacts live in local `.scratch/...` tickets + process docs, not public GitHub issue ops or reactivated planning-with-files.

**Blocked by:** 05 A5e; 06 B0; 07 B1; 08 B2; 09 B3 — **all complete**.

**Status:** **complete — audit delivered; §9 NOT MET; awaiting owner graduation decision** (2026-07-12)

## Acceptance checklist

- [x] §9 checklist with evidence links/paths
- [x] Graduation proposal ready for owner decision
- [x] No force-push/merge without owner; no redefining DoD as “almost done”

## Deliverables

| Artifact | Role |
|----------|------|
| `docs/parity/v2.10.0/dod/s9-dod-audit-2026-07-12.md` | Full §9 checklist PASS/PARTIAL/FAIL + residuals |
| `docs/parity/v2.10.0/dod/graduation-proposal-pr358.md` | Options A/B/C for owner; recommended A |
| `build/s4d383-dod-audit/` | Final gates: app debug+release+unit, shared compile+desktopTest, headless, createDistributable |
| `codex-goal-v2.md` §11 | Refreshed to post-audit truth |

## §9 one-line verdict

**NOT MET.** Program ready for **owner graduation decision**, not unattended merge or “migration complete” claim.

## Final gates (this ticket)

| Gate | Result |
|------|--------|
| assembleDebug + assembleRelease + testDebugUnitTest + shared multi-target compile + desktopTest | **EXIT 0** · unit **53/0** · desktopTest **132/0** |
| desktop headless | **EXIT 0** |
| createDistributable (Corretto 17) | **EXIT 0** · `EasyWatermark.app` |
| iosAppUITests | Cite prior **19/0** (not re-run) |
| Packaging CI | Historical green on #358 |

## Owner decision needed

Reply using the format in `graduation-proposal-pr358.md` (`GRADUATION: A|B|C`, `PUSH`, `MERGE`, `STRICT_GOLDENS`).
