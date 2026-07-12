# Codex Goal v2 — EasyWatermark Full KMP/CMP Migration

Standing mission/process contract. **Read this file every session before work.**

| | |
|---|---|
| Owner | rosuH |
| Created | 2026-07-11 |
| Branch | `feat/migrate_to_compose` |
| Supersedes | `codex-goal.md` (v1) — **sole process/mission contract** |
| Product | EasyWatermark (`me.rosuh.easywatermark`) |

---

## 0. Precedence

1. **This file is the sole process/mission contract.** Supersedes `codex-goal.md` and the retired ACSP/cowork loop. Historical ACSP under `~/.agent-cowork/sessions/EasyWatermark/` is **read-only** — never create/move/publish sessions there.
2. **Technical constraints** in `AGENTS.md`, `docs/CONTEXT.md`, `docs/adr/*` remain binding. Process conflict → **this file wins**. Technical conflict → **`AGENTS.md` / ADRs win** until owner says otherwise.
3. **Operational source of truth:** this file, the **local** Matt Pocock program under `.scratch/easywatermark-kmp-cmp-migration/` (`spec.md` + `issues/*.md`; see `docs/agents/issue-tracker.md`), the accepted worker brief, and verified artifacts (diffs, test outputs, screenshots). Public GitHub Issues are **not** the migration ops backend. `task_plan.md`, `progress.md`, and `findings.md` are **historical evidence only** — do not read them at session start, do not update them after slices, and do not use them to choose work.
4. **Slice done ≠ mission done.** Overall completion requires **every** §9 DoD item. Never redefine goal as “almost done” or “PR merge-ready.”

---

## 1. Mission

Release-grade EasyWatermark on Android, Desktop, and iOS:

1. **Maximize KMP + CMP sharing.** Models, repos, DataStore, Room, use-cases, and UI live in `:shared` `commonMain` unless a platform edge is strictly required. Prefer official Android / Compose / JetBrains KMP-CMP docs (`android docs search`) over training data.
2. **1:1 industrial-grade pixel restoration.** Android debug must match production **v2.10.0** (`me.rosuh.easywatermark` from `master`) screen × state × gesture. That release is the **only** visual/behavioral source of truth. After Android sign-off, iOS/Desktop align to that Android baseline with **explicit** exceptions.
3. **Native UI only as narrow edges:** app/window entry; picker/share/save/permission system UI; capability glue; renderer surfaces where Android native raster/composition is required (§6).

PR #358 stays **Draft**. Offer graduation only when §9 is fully met.

---

## 2. Operating model

### 2.1 Codex = commander / advisor / architect / reviewer

**Precise, concise, token-efficient.** Codex recovers truth, decomposes, routes Matt Pocock skills, writes briefs, monitors Herdr (no busy-loop), reviews diffs/tests/screenshots, requests revisions or accepts, **authorizes** docs + local commits. **Does not** implement/detail-execute unless owner explicitly overrides that slice.

### 2.2 Herdr CLI agents = sole detail executors

All implementation, verification, docs updates, and local commits go through Herdr, priority:

```
  (1) Grok Agent   PRIMARY — default implementer;
                   UI / device / screenshot comparison ALWAYS prefer Grok
  (2) Kimi         SMALL / FAST only — tiny diffs, pure docs/status, short checks
  (3) OpenCode     BACKUP — only if Grok unavailable / limited / blocked

  No suitable CLI → record blocker, ask owner.
  Do NOT silently self-implement under Codex.
```

### 2.3 Concurrency, commit, roles

- **One primary editor per slice**; no concurrent writes to overlapping files. Others may **read-only** review.
- Worker edits+verifies; **no commit** until Codex accepts. After accept, authorize CLI (normally **Grok**) to update durable docs if needed and record verified artifacts / local ticket status under `.scratch/.../issues/`. **Do not** use `gh issue` for migration ops. **Do not update `task_plan.md` / `progress.md` / `findings.md` as execution workflow.** Local commit only.
- **Never** push/merge/reset/rebase/stash/clean without owner.

```
  Owner → Codex (command/review/authorize; no default impl)
            → Herdr: Grok > Kimi > OpenCode
              → worktree feat/migrate_to_compose + local .scratch tickets + verified artifacts
```

---

## 3. Matt Pocock skill routing (preferred)

Skills guide decomposition/review; **CLI agents execute**. Not commit subjects.

| Skill | When | Codex output |
|---|---|---|
| **wayfinder** | Multi-session roadmap uncertainty | Ordered options + critical path |
| **to-spec** | Scope agreed, not ticketized | In/out, constraints, acceptance |
| **to-tickets** | Spec → tracer slices + deps | Ordered `S4d-NNN`; one concern each |
| **implement** | One bounded ticket | §5 brief → primary Herdr editor |
| **code-review** | Required before accepting any worker report | Standards/spec vs brief + §6 |

Flow: `wayfinder` (only when roadmap uncertainty exists) → `to-spec` (agreed scope) → `to-tickets` (approved multi-slice work) → loop(`implement` per frontier ticket → worker → `code-review` before acceptance → accept/revise).

---

## 4. Herdr lifecycle + Codex slice loop

```
  1 INSPECT   git status -uall; HEAD; current local ticket under .scratch/.../issues/ + accepted brief; AGENTS; CLI availability
  2 BRIEF     one primary editor; §5 template; exact allowed/forbidden paths
  3 SEND      Herdr dispatch; record agent id / start
  4 WAIT      no busy-loop; backoff/event wait; stall → one status check → escalate/requeue
  5 REPORT    worker format §5; claims untrusted
  6 EVIDENCE  real diff; cited commands exist; VIEW screenshots (not file size)
  7 DECIDE    revise (concrete gaps) or accept (lock file set; no drive-bys)
  8 AUTHORIZE docs + local commit (normally Grok); record pre/post HEAD
  9 VERIFY    only intentional leftovers; never clean tree; never touch unbriefed WIP
```

Per slice: skill-route (§3) → smallest `S4d-NNN` → brief → lifecycle → accept → authorize docs/commit. Owner-blocked → document + §7.4 lane-switch; never self-implement.

---

## 5. Worker brief template

```markdown
# Worker brief — S4d-NNN — <title>

## Objective
<what ships / what does not>

## Why now
<critical-path reason; blocked alternatives>

## Exact allowed files
- path/...

## Forbidden files / actions
- Do not edit: <paths>  # always exclude unbriefed paths + parked research (§11)
- Do not: push/merge/reset/rebase/stash/clean, install deps, shut down Android/iOS
  simulators, change Compose/Skiko versions, add deps, edit Weblate locales, reopen §6
- Default: NO commit (Codex reviews first)

## Acceptance criteria
- [ ] behavior-preserving unless stated
- [ ] <functional/UI checks>
- [ ] git diff --check clean on touched files
- [ ] Gradle gates below green

## Exact commands
./gradlew <slice tasks> --max-workers=8 --console=plain
./gradlew --stop --max-workers=8 --console=plain
# :app:assembleDebug :app:testDebugUnitTest minimum
# + :shared all-target compile + desktopTest + iosSimulatorArm64Test if shared touched
# + desktop headless / iOS xcodebuild as needed

## Required screenshots / artifacts
- <paths or none>; VIEW if UI

## Stop / escalation
Owner decision; new dep; golden rebaseline; persisted-byte change; §6 conflict;
CLI down; scope past allowed files; >N revision loops.

## Report
Summary; files; commands+results; artifacts; residual;
READY FOR CODEX REVIEW | BLOCKED | NEEDS REVISION
```

---

## 6. Hard guardrails (do not reopen silently)

Any reopen needs an **explicit owner decision**. Record **durable architecture/policy** changes in an ADR; record **active toolchain/runtime blockers** on the local ticket file / in the accepted brief, not in `findings.md`. “Share more” does not override.

1. **Android production raster/composition stays native:** text (`buildTextShader`/StaticLayout — S4d-17); icon (`buildIconShader` — S4d-8/ADR-0004); composition (`WatermarkRenderer.compose` — S4d-190 No-Go). `WatermarkCellComposer` / `composeTextCell` / `composeIconCell` / `composeOverBackground` = **Desktop/iOS only**. Shared geometry/constants (`WatermarkGeometry`, `ICON_SCALE_REFERENCE_TEXT_SIZE`) are the single sizing source for all platforms.
2. **No `ViewInfo` / `AndroidView`-bridged renderer.** Preview = Compose `Canvas` over `WatermarkRenderer` (S3c-3).
3. **No `commonMain expect/actual createDataStore`.** Plain per-platform store factories; Android creation byte-faithful (S4d-74/78/120).
4. **No compose-resources / compose.components in `:shared`** (CMP-9547). Fonts via byte-array / NSBundle / classpath as built.
5. **Persisted bytes sacred.** DataStore keys/values; Room `version=1`; seeds `ewm-db-ch.db`/`ewm-db-eng.db`; storage ids (tile ordinals, typeface/style keys, Android DECAL id 3 → REPEAT). No silent migrations.
6. **Deliberate Android `Uri` edges stay** (gallery/picker contracts, `SaveExportSheet.imageUris`, `BitmapUtils`/`BitmapCache`/`FileUtils`). Do not “fix” incidentally.
7. **Dynamic color** via `DynamicColorCapability`; only Android actual touches `:cmonet`. Absorbing `:cmonet` owner-gated.
8. **Weblate owns non-default `strings.xml`.** Never hand-edit the 13 locales.
9. **Privacy:** offline; zero tracking/stats/crash SDKs; no new permissions; export strips EXIF (ADR-0009).
10. **Strict FNV goldens out of GitHub CI** (S4d-171/172). PR CI = `:app:assembleDebug` + `:shared:desktopTest` + non-strict unit tests. Local strict only; no rebaseline without owner.
11. **New dependencies owner-gated.** Prefer stdlib/JDK/system frameworks.
12. **Consumer-first pure state/use-case extraction (≥2 platforms).** Extract a pure state transition or use-case into commonMain **only when**:
    - the **same** rule/state transition has **named production consumers on ≥2 platforms**, and
    - inputs/outputs contain **no platform types** (`Context`, `Uri`, `Bitmap`, SwiftUI types, AWT, etc.).
    - **Tests, DEBUG witnesses, and theoretical callers do not count.**
    - Otherwise keep platform-side or document as a **platform edge**.
    - Do **not** require (or claim) that whole `MainViewModel` must move; extract only dual-consumed pure slices. No speculative shared ViewModel / nav reducer / IO `expect` (S4d-191).
13. **Do not reopen focused-text dependency troubleshooting or change Compose/Skiko versions without an owner dependency-alignment decision.** The shared CMP `TextContentOption` host is runtime-proven (S4d-378, `bf9a3825`, full iOS XCUITest 19/0); it is not blocked.

---

## 7. Order of work, architecture, critical path

### 7.1 Phases (hard order)

**Phase A (current):** release-grade KMP/CMP code migration; shared CMP as product UI route of record; platform edges only where required; behavior-preserving; persisted bytes stable.

**Phase B (only after A5 gate):** screenshot/recording 1:1 vs v2.10.0 on **Android first**, then iOS/Desktop align to signed Android baseline. Do **not** mix Phase B polish into Phase A slices.

### 7.2 Target architecture (ASCII)

```
  +--------------------- :shared / commonMain ---------------------+
  |  models · repos · DataStore consumers · Room · pure use-cases |
  |  WatermarkGeometry + Desktop/iOS cell/compose primitives        |
  |  shared CMP screens/shells/options/theme (product UI route)   |
  +--------------------------+------------------------------------+
                             |
         platform edges only |  (not product UI growth)
                             v
  +------------+   +--------------+   +---------------------------+
  | :app       |   | :desktopApp  |   | iosApp (SwiftUI entry)    |
  | Activity   |   | window entry |   | PhotosPicker/Share/Save   |
  | permissions|   | AWT file IO  |   | bridges / NSBundle fonts  |
  | MediaStore |   | user dirs    |   | ComposeUIViewController   |
  | Coil/Uri   |   | packager     |   | hosts for shared CMP      |
  | native     |   |              |   |                           |
  | Watermark- |   | Skiko path   |   | Skiko path                |
  | Renderer   |   | uses shared  |   | uses shared composer      |
  | (text/icon |   | composer     |   |                           |
  |  /compose) |   |              |   |                           |
  +------------+   +--------------+   +---------------------------+
```

### 7.3 Explicit dependency chain (local ticket order)

    [01 A5a]      [02 A5b]      [03 A5c]      [04 A5d]
        |             |             |             |
        +-------------+-------------+-------------+
                      v
              [05 A5 closeout]
       NOT READY → return to 01–04
                      |
                    PASS
                      |
                      v
            [06 B0 Android v2.10.0
                baseline inventory]
                      |
                      v
            +---------+---------+
            v                   v
    [07 B1 launch/gallery    [08 B2 editor/export
        owner sign-off]          owner sign-off]
            |                   |
            +---------+---------+
                      v
        [09 B3 iOS/Desktop alignment
                + exception registry]
                      |
                      v
    [10 final DoD audit + PR #358 graduation proposal]

**S4d-338 (historical):** the former focused-text dependency investigation is runtime-resolved by S4d-378; it does **not** block shared text or A5. Do not reopen that troubleshooting or change Compose/Skiko versions without an owner dependency-alignment decision.

### 7.4 Lane-switching on owner blocks

Owner wall (golden rebaseline, new dep, §6 reopen, visible behavior):

1. Record question (authorized docs after Codex accepts block note).
2. Park ticket; do not idle; do not decide unilaterally.
3. Switch to another ready local ticket; current frontier is 01 through 04, and later work follows the unblocked ticket chain in §7.3.

---

## 8. Verification playbook

### 8.1 Gradle

- Always `--max-workers=8`; after heavy work `./gradlew --stop --max-workers=8`.
- Minimum: `:app:assembleDebug`, non-strict `:app:testDebugUnitTest`, `git diff --check`.
- If `:shared` touched: compile android+desktop+both iOS; `:shared:desktopTest`; `:shared:iosSimulatorArm64Test`.
- Milestones: `:app:assembleRelease`; `:desktopApp:run --args='--headless'`; `:desktopApp:createDistributable` on supported packaging JDK (Corretto 17/Zulu). Never permanent `checkJdkVendor=false`.
- Strict goldens local/pinned only: `WATERMARK_GOLDEN_STRICT=true ./gradlew :app:testDebugUnitTest`.

### 8.2 Android UI — AndroMeld MCP

Prefer AndroMeld over raw adb/shell: `devices.list` → session → step actions → metadata-first observe → `includeImage` when pixels matter. **VIEW** screenshots. Confirm `READ_MEDIA_IMAGES` before share-in/editor. `android` CLI OK for docs/emulator/layout.

### 8.3 iOS

`:shared:iosSimulatorArm64Test` (iOS 27.0). `iosApp` + `iosAppUITests` (`-uiTestFixtureImage` DEBUG seam for real render/save/share). PHPicker grid-cell automation residual (S4d-57) is toolchain, not product — do not re-prove endlessly.

### 8.4 Desktop

`:desktopApp:run` / `--args='--headless'`; `:shared:desktopTest` for Skiko.

### 8.5 Phase B parity protocol

Same device: production v2.10.0 then debug; control `en`/`zh`, font scale, light/dark, images. Screenshot pairs + recordings; archive under `docs/parity/` (or successor); one-line why per exception. iOS/Desktop align to **signed Android baseline**.

### 8.6 Host resources

Cap `--max-workers=8`; stop Gradle after heavy work; warn before long automation; headless emulators when no interaction. **Owner standing order (2026-07-11): do not shut down already-live Android or iOS simulators** used for migration unless owner explicitly orders it.

---

## 9. Definition of Done (overall only)

Slice finish is **never** overall completion. All must be true:

- [ ] `:app` assembleDebug + assembleRelease green; non-strict unit tests green; strict goldens green locally (or owner-signed rebaseline).
- [ ] `:shared` compiles all 4 targets; commonTest / desktopTest / iosSimulatorArm64Test green.
- [ ] `:desktopApp` runs; headless witness passes; `createDistributable` app image; desktop packaging CI green.
- [ ] `iosApp` builds/installs/runs on simulator; XCUITest suite green (documented PHPicker residual excepted).
- [ ] Shared CMP UI is route of record on all three platforms; platform-native UI only at allowed edges, each listed with a reason.
- [ ] Data layer (models, repos, DataStore, Room, use-cases) is commonMain **except documented platform-edge implementations** (Android / Desktop / iOS factories, migrations, native IO, and other listed edges); persisted bytes unchanged end-to-end.
- [ ] Android 1:1 parity archive complete (screens × states × locale × theme + recordings), **owner signed off** screen by screen; iOS/Desktop alignment documented with explicit exceptions.
- [ ] `AGENTS.md`, `docs/CONTEXT.md`, ADRs, local `.scratch` program tickets, and verified evidence reflect final architecture (process contract = this file; ACSP retired).
- [ ] Graduation proposal for PR #358 presented to owner (merge plan, not auto-merge).

---

## 10. Git discipline

- Branch `feat/migrate_to_compose`. Record HEAD before staging; stage only intended files; re-check before commit.
- Local commit only after Codex accept + authorize (normally Grok). Short imperative subjects; code/docs may be separate commits.
- Never push/merge/rebase/reset/stash/clean without owner. Never scoop unbriefed dirty/untracked files (§11).
- Local may lead `origin` substantially; no unsolicited remote catch-up.

---

## 11. Current truth (2026-07-12, post ticket 10 residual re-run)

Baseline only — always re-check `git status`. Full checklist: `docs/parity/v2.10.0/dod/s9-dod-audit-2026-07-12.md`.

| Item | Value |
|---|---|
| Local tickets **01–10** | **01–09 complete**; **10** §9 audit + graduation proposal + **residual automated re-runs** delivered |
| Phase A | **A5 PASS** (ticket 05) |
| Phase B | Core Android launch/gallery/editor/export **owner-signed** (07/08); iOS/Desktop exceptions **registered** (09) |
| Residual r2 gates | **strict goldens 53/0** (`strict=true`); **iosSimulatorArm64Test 101/0** — `build/s4d383-dod-audit-r2/` |
| **§9 overall** | **NOT MET** (parity archive not exhaustive locale/theme/recording matrix; Desktop unsigned; ADR renderer split intentional). Automated residual gates closed on lab host. **Not** “migration complete.” |
| Graduation | **Owner chose A** (2026-07-12): keep Draft; **no push**; **no merge**. Proposal: `docs/parity/v2.10.0/dod/graduation-proposal-pr358.md` |
| Local process backend | `.scratch/easywatermark-kmp-cmp-migration/` |
| Protect | User research doc `docs/superpowers/research/2026-07-11-project-branch-goals-progress.md` stays uncommitted unless owner allows |
| Remote | Local may lead `origin/feat/migrate_to_compose`; PR #358 **Draft** (stay Draft under graduation A) |
| Simulators | Do **not** shut down live Android + iOS simulators unless owner orders |
| Process | Commander (Grok authorized); Herdr Grok > Kimi > OpenCode |

### 11.1 Next on chain

1. ~~Owner graduation decision~~ → **A** recorded (push/merge not authorized).  
2. Under A: optional polish residual = **parity archive expansion** (zh / theme / recordings) if owner asks to continue polish.  
3. Do **not** push/merge/claim three-platform 1:1 or PR merge-ready without a new owner command.

---

## 12. Overclaim guard

Do not claim: migration complete; three-platform 1:1; PR merge-ready; Android pixel parity from smoke alone; Desktop packaging = public ship (ADR-0013 Proposed); focused XCUITest = full suite; A5 pass without ticket `05`. Prefer the current **local** ticket state under `.scratch/easywatermark-kmp-cmp-migration/` + verified artifacts + git HEAD over stale planning-file text or revoked public GitHub migration issues.

---

## 13. Source map

| Source | Role |
|---|---|
| **This file** | Sole mission/process contract |
| `codex-goal.md` | Historical process (superseded) |
| `AGENTS.md` | Technical truth, closed decisions, commands |
| `task_plan.md` / `progress.md` / `findings.md` | **Historical evidence only** — do not use for routing or session start |
| Local tickets (`.scratch/easywatermark-kmp-cmp-migration/`; `docs/agents/issue-tracker.md`) | Operational ticket / task source of truth (not public GitHub issues) |
| Verified artifacts (diffs, test outputs, screenshots) | Acceptance evidence |
| `docs/CONTEXT.md` / `docs/adr/*` | Domain + architecture |
| CMP plan under `docs/superpowers/plans/` | C1–C6 blueprint context, not process |

---

*End of codex-goal-v2.md*
