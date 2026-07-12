# Spec — EasyWatermark KMP/CMP release-grade migration + Phase B parity

**Local program slug:** `easywatermark-kmp-cmp-migration`
**Process contract:** `codex-goal-v2.md`
**Tracker backend:** local files under `.scratch/easywatermark-kmp-cmp-migration/` (not GitHub issues)

---

## Problem statement

EasyWatermark must ship as a **release-grade** multiplatform product (Android + Desktop + iOS) with maximized KMP/CMP sharing, while preserving offline privacy, persisted config bytes, and Android production behavior. Public GitHub issues are **revoked** as the operational task backend. Work is tracked only via this local spec + tracer tickets. PR #358 remains a **Draft** integration checkpoint until overall Definition of Done (`codex-goal-v2.md` §9) is met.

## Solution

1. **Phase A** — shared CMP as product UI **route of record** on all three platforms; thin platform edges only; behavior-preserving; green verification gates (§8). Close with an explicit A5 PASS (ticket `05`) — **not claimed yet**.
2. **Phase B** — only after A5 PASS: Android debug **1:1** industrial-grade parity vs production **v2.10.0** (`me.rosuh.easywatermark` from `master`), **owner sign-off per screen**.
3. Align iOS/Desktop to that **signed** Android baseline with an explicit exception registry.
4. Present a **§9 DoD audit + PR #358 graduation proposal** (no auto-merge).

## User stories

- As the owner, I can track migration work as ordered local tracer tickets without public issue noise.
- As an agent, I can pick the frontier ticket under `.scratch/.../issues/` and verify with command outputs + Grok image review.
- As a user of any platform build, I get the same product capabilities with platform-native edges only where required.

## Implementation decisions

- Shared CMP is the long-term product UI route of record; do not grow long-term SwiftUI/Desktop-only product UI when CMP can own the screen.
- **Android production raster/composition stays native** (`WatermarkRenderer` text/icon/compose). Shared cell/compose primitives are **Desktop/iOS only**. Shared geometry/constants are the single sizing source.
- **Persisted bytes sacred** (DataStore keys/values, Room v1 + seeds, storage ids).
- **No compose-resources** in `:shared` (CMP-9547).
- **No new dependencies** without owner gate.
- **No speculative shared ViewModel / nav reducer / IO `expect`** (S4d-191); pure use-cases only under §6.12 (≥2 real production consumers, no platform types).
- **Phase A before Phase B** — no 1:1 pixel polish mixed into Phase A code-migration slices.
- **Parity baseline:** Android production **v2.10.0** is the only visual/behavioral source of truth. Screen sign-off requires **explicit owner approval**.

## Testing decisions

| Seam | What counts |
|------|-------------|
| Verified command outputs | Gradle `--max-workers=8`; assemble; non-strict + local strict unit; shared multi-target tests; Desktop headless + createDistributable |
| Grok image review | VIEW screenshots/recordings (not file size). Codex does not inspect images |
| Android device | Production app vs debug; AndroMeld preferred; media permission when needed |
| iOS XCUITest | Full `iosAppUITests`; fixture seam; PHPicker grid residual is toolchain |
| Desktop witnesses | Headless save/template flows; packaging on supported JDK |

## Guardrails (summary)

See `codex-goal-v2.md` §6 in full. Do not silently reopen Android draw-swap, DataStore expect factories, compose-resources, or CI strict FNV.

## Out of scope

- Unrelated product features (PDF, stickers, etc.).
- Auto-merge of PR #358; permanent packaging JDK vendor bypass; silent golden rebaseline.
- Reactivating root `task_plan.md` / `progress.md` / `findings.md` as execution workflow.
- Public GitHub issue create/comment/edit/close for migration ops.

## Current evidence — S4d-378 (complete; not A5 pass)

**Slice:** Production iOS host for shared CMP `TextContentOption` (replaces SwiftUI TextField+Apply); templates remain SwiftUI with per-row delete ids; simple MainActor workflow bridge.

**Commit:** `bf9a3825` — *Use shared CMP text control on iOS*

**Final gates** (`build/s4d378-final-validation/`):

| Gate | Result |
|------|--------|
| Android assemble + unit | **53/0** non-strict |
| `WATERMARK_GOLDEN_STRICT=true` unit | **53/0** strict |
| Shared multiplatform suites | **548/0** across suite XMLs (desktop + iOS sim + android host pure); `commonPureTest` task green |
| Desktop headless + createDistributable | **green** (Corretto 17) |
| Full iosAppUITests | **19/0**, exit 0, **HANG=0** |

**Grok visual review:** final `05-ios.xcresult` text-content + templates attachments — post-confirm host text + save/apply/delete row lifecycle OK; confirm sheet not photographed; watermarked preview often scrolled off-frame; residual template DB rows from prior runs. **Not** Android v2.10.0 parity.

**A5 status:** Local audit concluded Phase A **NOT READY** (iOS production still multi-host control stack vs `EditorScreenShell` route; gallery/about/desktop scope gaps). Frontier tickets `01`–`05` address that; **do not claim A5 pass**.

## Ticket index

| File | Role |
|------|------|
| `issues/01-a5a-ios-editor-screen-shell.md` | Frontier |
| `issues/02-a5b-ios-gallery-route-or-exception.md` | Parallel |
| `issues/03-a5c-ios-about-scope-or-absence.md` | Parallel |
| `issues/04-a5d-desktop-editor-window-exception.md` | Parallel |
| `issues/05-a5e-phase-a-closeout.md` | Blocked by 01–04 |
| `issues/06-b0-android-v2100-baseline-inventory.md` | Blocked by 05 |
| `issues/07-b1-android-launch-gallery-parity.md` | Blocked by 06 |
| `issues/08-b2-android-editor-export-parity.md` | Blocked by 06 |
| `issues/09-b3-ios-desktop-alignment-exceptions.md` | Blocked by 07+08 |
| `issues/10-z-dod-audit-pr358-graduation.md` | Blocked by 05–09 |
