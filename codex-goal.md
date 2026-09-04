# Codex Goal — EasyWatermark Full KMP/CMP Migration

Standing mission file for Codex. Read this file completely before doing any work, every session.
Owner: rosuH. Created: 2026-07-03. Branch of record: `feat/migrate_to_compose`.

## 0. Precedence and mode change (read first)

- **ACSP is retired.** This file supersedes the "Coordinator workflow loop" section of `AGENTS.md` and every ACSP/cowork instruction anywhere in the repo docs. There is no coordinator/worker split, no session queue, no Kimi/Herdr/opencode/tmux routing. **Codex implements directly and alone.**
- The historical ACSP archive at `~/.agent-cowork/sessions/EasyWatermark/` is read-only evidence of past slices. Never create, move, or publish sessions there.
- **Everything else in `AGENTS.md` remains binding**: architecture facts, closed decisions, gotchas, commands, and conventions. Where this file and `AGENTS.md` conflict on *process*, this file wins; where they conflict on *technical constraints*, `AGENTS.md` wins until the owner says otherwise.
- Session memory files at repo root (`task_plan.md`, `progress.md`, `findings.md`): read them at session start, update them as you work.

## 1. Mission

Deliver the complete migration of EasyWatermark, release-grade, on all three platforms:

1. **Maximize KMP + CMP sharing.** Data layer (models, repositories, DataStore, Room, use-cases) and UI layer (screens, components, theme, state) live in `:shared` commonMain wherever a platform edge is not strictly required. Follow official Android, Jetpack Compose, and JetBrains KMP/CMP best practices (verify against current docs via `android docs search` — the offline KB mirrors developer.android.com and the JetBrains KMP docs — rather than training data).
2. **1:1 industrial-grade pixel restoration.** Android debug UI/UX must match production **v2.10.0** (`me.rosuh.easywatermark`, built from `master`) screen by screen, state by state, gesture by gesture. Production v2.10.0 is the only visual/behavioral source of truth — NOT this branch's current screens. After Android parity is signed off, iOS and Desktop align to that Android baseline with explicitly documented platform exceptions.
3. Platform-native UI survives only as narrow edges: app/window entry, picker/share/save/permission system UI, platform capability glue, and renderer surfaces where native rendering is required (see guardrails, §5).

PR #358 stays a Draft integration checkpoint. Do not redefine the goal as "make the PR merge-ready"; offer the graduation decision to the owner only when the Definition of Done (§8) is met.

## 2. Order of work

**Phase A — finish release-grade KMP/CMP code migration (current phase).**
Move remaining data/UI/state code into `:shared` commonMain in small verified slices; wire Android, Desktop, and iOS to consume the shared code; keep persisted bytes and Android runtime behavior identical throughout.

**Phase B — screenshot/recording-driven 1:1 restoration (only after Phase A).**
Per-screen parity protocol (§4.5) against production v2.10.0 on Android first, then iOS/Desktop alignment. Do not start Phase B early; do not mix parity polish into Phase A slices.

## 3. Working loop (per slice)

1. **Recover truth**: read `task_plan.md`, `progress.md`, `findings.md`, `git status --short --branch -uall`, current diff/log. Treat old summaries as leads, not proof.
2. **Pick the smallest shippable slice** that advances §1. Continue the existing `S4d-NNN` slice numbering in `task_plan.md` for traceability (next: S4d-252, see §7).
3. **Implement** with behavior-preserving diffs. One concern per slice. No speculative abstractions, no drive-by refactors.
4. **Verify** with the gates in §4 appropriate to the slice (build/tests always; device/simulator when runtime or UI is touched).
5. **Commit** (see §6), update `task_plan.md`/`progress.md`/`findings.md`, and update `AGENTS.md`/`docs/CONTEXT.md`/ADRs when a durable rule or decision changes (docs-with-code gate: every milestone ships its context delta or states "no doc impact").
6. Pick the next slice. If blocked on a genuine owner decision (anything in §5, golden rebaselines, dependency additions, visible behavior changes), record the question in `findings.md`, park that lane, and continue on another lane — do not idle and do not decide unilaterally.

## 4. Verification playbook

### 4.1 Gradle gates

- Always `--max-workers=8`; after heavy work run `./gradlew --stop --max-workers=8`.
- Per-slice minimum: `:app:assembleDebug`, non-strict `:app:testDebugUnitTest`, `git diff --check`.
- When `:shared` is touched: all-target compile (android + desktop + both iOS), `:shared:desktopTest`, `:shared:iosSimulatorArm64Test` (iOS 27.0 simulator is installed and proven working).
- Release checks at milestones: `:app:assembleRelease` (R8 retention, APK size), `:desktopApp:run --args='--headless'`, `:desktopApp:createDistributable` (use a supported JDK, e.g. Amazon Corretto 17 — never set `checkJdkVendor=false`).
- Strict watermark goldens (`WATERMARK_GOLDEN_STRICT=true ./gradlew :app:testDebugUnitTest`) are a LOCAL pinned-env gate. Run them locally when the render path is touched; never add them to GitHub CI; never rebaseline without owner sign-off.

### 4.2 Android runtime/UI verification — AndroMeld MCP

Use the AndroMeld MCP tools (not raw adb/shell input) for all interactive Android verification, against an **Android emulator** session:

1. `andromeld devices.list` → pick the emulator; `sessions.list` → `session.start` if no Phone Screen session exists.
2. Launch via `app_launch`; act one step at a time; wait for stable UI after navigation.
3. Observe with metadata-first `screen.observe`; set `includeImage=true` only when pixel inspection is needed.
4. **Verify renders by VIEWING screenshots**, never by file size. Confirm `READ_MEDIA_IMAGES` is granted before share-in/editor flows (share-in silently falls back to LaunchScreen without it).
5. The `android` CLI is also available: `android emulator list/start`, `android screenshot`, `android layout` (UI tree as JSON — often faster than screenshots for structural checks), `android run`.

### 4.3 iOS verification

An iOS simulator is installed and activated (iOS 27.0). Use it for:

- `:shared:iosSimulatorArm64Test` (the full shared suite runs and passes there).
- `iosApp` builds via `xcodebuild -sdk iphonesimulator` + install/launch on the simulator; XCUITest target `iosAppUITests` (the `-uiTestFixtureImage` DEBUG seam drives the real render/save/share path). Known limitation: XCUITest cannot address PHPicker grid cells on this toolchain — that residual is documented, not a product failure; do not burn time re-proving it.

### 4.4 Desktop verification

`./gradlew :desktopApp:run` (interactive window) and `--args='--headless'` (bounded automation path); `:shared:desktopTest` goldens gate the Skiko render path.

### 4.5 Visual parity protocol (Phase B)

- Baseline: production v2.10.0 (`me.rosuh.easywatermark`) installed side-by-side with debug (`me.rosuh.easywatermark.debug`) on the SAME emulator/device. Open production FIRST, capture, then debug.
- Control the variables: same locale (test at least `en` and `zh`), same font scale/display size, light AND dark theme, same test images.
- Per screen and per state: capture screenshot pairs, diff them visually (overlay/side-by-side), fix, re-capture until 1:1. For interactions (decal drag, sliders, pickers, share-in, save flow): capture recordings and compare motion/behavior.
- Keep a parity evidence archive per screen under `docs/parity/` (or extend the existing plan docs) and an explicit exceptions list for accepted deviations (e.g. CJK `StaticLayout` vs `MultiParagraph` off-Android). Every exception needs a one-line why.
- iOS/Desktop align to the signed-off Android baseline, not directly to v2.10.0.

### 4.6 Host resource discipline (hard rule)

Sustained emulator+build load has frozen this machine's input devices before. Boot emulators `-no-window` when interaction isn't needed; end sessions with `adb emu kill` and `./gradlew --stop`; cap builds at `--max-workers=8`; warn the owner before long heavy local automation.

## 5. Hard guardrails — closed decisions, do NOT reopen silently

Each of these was decided with evidence; reopening any of them requires an explicit owner decision recorded in an ADR. "KMP/CMP as much as possible" (§1) does NOT override them:

1. **Android production raster/composition stays native**: text (`buildTextShader`/StaticLayout — S4d-17), icon (`buildIconShader` — S4d-8/ADR-0004), composition (`WatermarkRenderer.compose` — S4d-190 No-Go). `WatermarkCellComposer`/`composeTextCell`/`composeIconCell`/`composeOverBackground` are the Desktop/iOS renderers only. Shared constants/geometry (`WatermarkGeometry`, `ICON_SCALE_REFERENCE_TEXT_SIZE`) stay the single source for all three platforms.
2. **No `ViewInfo`/`AndroidView`-bridged renderer contract** — the editor preview is a Compose `Canvas` over `WatermarkRenderer` (S3c-3).
3. **No `commonMain expect/actual createDataStore`** — store creation is plain per-platform functions; Android store creation stays byte-faithful (S4d-74/78/120).
4. **No compose-resources / compose.components** in `:shared` (CMP-9547 isolation). Fonts load via byte-array/NSBundle/classpath boundaries as already built.
5. **Persisted bytes are sacred**: DataStore keys/values, Room schema (`version=1`), seed DBs (`ewm-db-ch.db`/`ewm-db-eng.db`), storage-id mappings (tile mode ordinals, typeface/style serialize keys, SDK-gated DECAL-id-3 migration on Android) must remain byte-identical. No silent migrations.
6. **Deliberate Android `Uri` edges stay** (gallery `Image.uri` — now shared with `MediaRef` where migrated, picker contracts, `SaveExportSheet.imageUris`, `BitmapUtils`/`BitmapCache`/`FileUtils` signatures). Don't "fix" them incidentally.
7. **Dynamic color** goes through `DynamicColorCapability`; only the Android actual touches `:cmonet`. Absorbing `:cmonet` is owner-gated.
8. **Weblate owns non-default `strings.xml`** — never hand-edit the 13 translated locales.
9. **Privacy contract**: fully offline, zero tracking/stats/crash SDKs, no new permissions, export strips EXIF (ADR-0009).
10. **Strict FNV golden gate stays out of GitHub CI** (S4d-171/172); PR CI stays `:app:assembleDebug` + `:shared:desktopTest` + non-strict unit tests.
11. **New dependencies are owner-gated.** Prefer stdlib/JDK/system frameworks (the whole Desktop/iOS pipeline was built without adding any).

## 6. Git discipline

- Work on `feat/migrate_to_compose`. Record `git rev-parse HEAD` before staging; stage only intended files; re-check status before committing.
- **Commit locally per accepted slice**, matching the existing style (short imperative subject; code slice and its doc delta may be separate commits, as in the current log).
- **Never push, merge, rebase, reset, stash, or clean without explicit owner approval.** Never commit unrelated dirty files you did not produce.
- The worktree currently carries ~17 accepted-but-uncommitted slices (S4d-235…251, the CMP UI migration lane: shared Material3 theme, TemplateListSheet/OpenSource/Recovery screens, option components, UiState/Routes/LaunchScreenUiState/Image/LaunchScreenState in `shared/commonMain/ui/`, net −983 lines). **Milestone 0 (first action): checkpoint-commit this backlog** in logically grouped commits (per slice or per lane, following the existing per-slice style) so no further work sits on an unversioned 3-day diff. This file is the owner authorization for those local commits.

## 7. Current state snapshot and backlog (2026-07-03)

Read `AGENTS.md` "Current state" for the full landed-work map. The immediate queue:

1. **Milestone 0** — checkpoint-commit the uncommitted S4d-235…251 lane (§6).
2. **S4d-252** — remove dead `Action.ChooseImage`: delete the `data class ChooseImage(...)` declaration (`app/.../ui/LaunchScreen.kt`) and the `is Action.ChooseImage -> {...}` branch in `MainViewModel.process` (`app/.../ui/MainViewModel.kt`). Zero behavior change (no producer exists; navigation is already `navController.navigate(GalleryDialogRoute)`). Do not move `Action` to shared; do not move `ContentResolver`/`Uri`/`FuncTitleModel`/`Any` payloads into commonMain.
3. **Continue Phase A lanes** (smallest-first, consumer-gated — create a lane plan in `task_plan.md` before starting each):
   - Finish migrating the remaining Android Compose screens/components into shared CMP (`EditorScreen`, `GalleryDialog`, `LaunchScreen` shell) with Android edges (permissions, pickers, share) kept at the activity boundary.
   - Wire Desktop and iOS entry points to consume the shared CMP screens (replacing the minimal `DesktopWindow` controls and SwiftUI bring-up surfaces where CMP can now render them; SwiftUI stays only as app entry/system-UI glue).
   - Shared state layer: extract remaining platform-neutral state/use-cases from `MainViewModel` only when an off-Android consumer actually needs them (S4d-191 finding still holds — no speculative shared ViewModel).
   - Parked residuals to keep on the list: iOS Templates XCUITest proof (S4d-234), RecoveryScreen parity prep (S4d-207 — Phase B), editor baseline delta pack (S4d-208 — Phase B).
4. **Phase B** — per-screen 1:1 restoration per §4.5, Android first, then iOS/Desktop alignment.

## 8. Definition of Done

- [ ] `:app` assembleDebug + assembleRelease green; non-strict unit tests green; strict goldens green locally (or an owner-signed rebaseline).
- [ ] `:shared` compiles for all 4 targets; commonTest/desktopTest/iosSimulatorArm64Test green.
- [ ] `:desktopApp` runs, headless witness passes, `createDistributable` produces the app image; desktop packaging CI workflow green.
- [ ] `iosApp` builds, installs, and runs on the simulator; XCUITest suite green (documented PHPicker residual excepted).
- [ ] Shared CMP UI is the route of record on all three platforms; platform-native UI exists only at the allowed edges, each listed with a reason.
- [ ] Data layer (models, repos, DataStore, Room, use-cases) is commonMain except the documented Android edge injections; persisted bytes unchanged end-to-end.
- [ ] Android 1:1 parity evidence archive complete (screens × states × locale × theme, plus interaction recordings), signed off by the owner screen by screen; iOS/Desktop alignment documented with explicit exceptions.
- [ ] `AGENTS.md`, `docs/CONTEXT.md`, ADRs, and the plan files reflect the final architecture (including the retirement of ACSP).
- [ ] Graduation proposal for PR #358 presented to the owner (merge plan, not an auto-merge).
