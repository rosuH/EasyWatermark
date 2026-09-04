# Compose Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate EasyWatermark to a stable Compose-first UI through incremental milestones that preserve behavior, improve architecture, and help the developer practice Compose safely.

**Architecture:** Keep the existing rendering/export engine and migrate the app around it. Unify entry flow, make Navigation Compose the navigation owner, convert screens to `UiState + typed intent`, then replace legacy dialogs/panels in descending order of learning value and regression risk.

**Tech Stack:** Kotlin, Android SDK, Jetpack Compose, Navigation Compose, Activity Result APIs, Koin, legacy View interoperability via `AndroidView`

---

## Priority Model

- `P0` Prevent regressions in critical flows.
- `P1` Eliminate duplicated entry and state ownership.
- `P2` Move more UI surface to Compose.
- `P3` Retire legacy infrastructure after parity.
- `P4` Explore pure Compose rendering only if the earlier milestones land cleanly.

## Milestone Board

| Milestone | Priority | Outcome |
|----------|----------|---------|
| M0 Regression Baseline | P0 | The team can change UI without flying blind |
| M1 Entry Consolidation | P1 | All launch paths converge on one Compose-led experience |
| M2 State Consolidation | P1 | Compose screens read one authoritative state model |
| M3 Shell Completion | P2 | Launch, gallery, and light-weight sheets are Compose-first |
| M4 Editor Chrome Migration | P2 | Editor controls are Compose-first while renderer stays bridged |
| M5 Legacy Cleanup | P3 | Obsolete Fragment/panel paths can be removed |
| M6 Rendering Evaluation | P4 | Team decides whether pure Compose rendering is justified |

### Task 1: Establish Regression Baseline

**Why now:** Every later migration step risks breaking hidden behavior, especially image entry and export.

**Files:**
- Create: `app/src/androidTest/java/me/rosuh/easywatermark/ComposeMigrationSmokeTest.kt`
- Modify: `app/src/androidTest/java/me/rosuh/easywatermark/ExampleInstrumentedTest.kt` or replace it with real smoke coverage
- Reference: `app/src/main/AndroidManifest.xml`
- Reference: `app/src/main/java/me/rosuh/easywatermark/ui/MainActivity.kt`
- Reference: `app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt`

**Tasks:**
- [ ] Write a manual smoke matrix covering:
  - cold launch
  - permission request
  - gallery dialog open/dismiss
  - system photo picker
  - shared image from another app
  - multi-image preview selection
  - back behavior from editor
  - save/export/share
- [ ] Add at least 2 automated instrumentation smoke tests for the safest critical paths.
- [ ] Decide what must stay manual because it depends on system UI or storage integration.
- [ ] Record the baseline behavior before changing architecture.

**Exit Criteria:**
- The app has a repeatable checklist before and after each migration milestone.
- At least a minimal test harness exists for launcher flow and editor entry.

**Validation:**
- Run: `./gradlew app:connectedDebugAndroidTest`
- Manually verify the smoke matrix on a device or emulator.

### Task 2: Consolidate Entry Strategy

**Why now:** The app currently has two product paths: launcher goes to Compose, while `ACTION_SEND` still goes to legacy `MainActivity`.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/MainActivity.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/utils/ktx/ContextExtension.kt` if intent helpers need to change

**Tasks:**
- [ ] Choose one long-term owner for all entry flows:
  - Option A: move share-intent handling into `ComposeMainActivity`
  - Option B: keep `MainActivity` as a thin trampoline into Compose
- [ ] Remove any product logic from the losing entry path.
- [ ] Ensure both normal launch and shared-image launch land in the same editor journey.
- [ ] Keep legacy compatibility only where it is still required.

**Exit Criteria:**
- There is one authoritative user journey into the editor.
- `MainActivity` no longer owns a separate UI experience.

**Validation:**
- Launch the app normally.
- Share an image into the app from another app.
- Verify both paths reach the Compose-led flow.

### Task 3: Consolidate Screen State and Events

**Why now:** Compose scales badly when navigation state, business state, and mutable UI state are all mixed.

**Files:**
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/MainViewModel.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/LaunchScreen.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/UiState.kt`
- Create: `app/src/main/java/me/rosuh/easywatermark/ui/state/` package if needed
- Create: `app/src/main/java/me/rosuh/easywatermark/ui/intent/` package if needed

**Tasks:**
- [ ] Define screen-level immutable state for launch/gallery/editor.
- [ ] Replace `Action.WaterMarkChange(FuncTitleModel, Any)` with typed editor intents.
- [ ] Stop duplicating navigation ownership in both `NavHost` and `LaunchScreenUiState`.
- [ ] Standardize on `StateFlow` for Compose-facing state.
- [ ] Leave repository/business internals unchanged unless required for state cleanup.

**Exit Criteria:**
- Each screen reads a clear `UiState`.
- Each user action is expressed as a typed intent/event.
- `MainViewModel` no longer expands legacy state patterns while migration continues.

**Validation:**
- Re-run smoke tests.
- Verify screen transitions still work after state cleanup.
- Verify configuration changes do not reset critical editor state unexpectedly.

### Task 4: Finish the Compose Shell

**Why now:** This is the best learning zone for a developer who is still building Compose fluency.

**Files:**
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/LaunchScreen.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/compose/GalleryDialog.kt`
- Migrate from: `app/src/main/java/me/rosuh/easywatermark/ui/dialog/GalleryFragment.kt`
- Migrate from: legacy simple sheets/dialogs that are mostly UI orchestration

**Tasks:**
- [ ] Make launch, permission, gallery, and picker flows fully Compose-led.
- [ ] Replace remaining simple legacy dialog/sheet surfaces with Compose equivalents.
- [ ] Keep business logic in the `ViewModel`; keep Compose focused on state and event wiring.
- [ ] Avoid touching save/export and renderer internals in this milestone.

**Exit Criteria:**
- The user can launch, pick images, and reach the editor without depending on legacy UI containers.
- Low-risk dialogs are Compose-first.

**Validation:**
- Manual smoke pass for launch and image selection.
- Verify permission denial and retry flows.

### Task 5: Migrate Editor Chrome Around the Existing Renderer

**Why now:** This grows real Compose skill without taking on the hardest rendering risk.

**Files:**
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/EditorScreen.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/compose/*.kt`
- Migrate from: `app/src/main/java/me/rosuh/easywatermark/ui/panel/*.kt`
- Migrate from: `app/src/main/java/me/rosuh/easywatermark/ui/dialog/TextWatermarkBSDFragment.kt`
- Migrate from: `app/src/main/java/me/rosuh/easywatermark/ui/dialog/TextContentTemplateListFragment.kt`
- Migrate from: `app/src/main/java/me/rosuh/easywatermark/ui/dialog/EditTextContentFragment.kt`

**Tasks:**
- [ ] Move editor top bar, bottom control groups, and text/template flows into Compose.
- [ ] Keep `WaterMarkImageView` inside `AndroidView`.
- [ ] Replace legacy panel fragments with Compose option surfaces one group at a time:
  - content
  - style
  - layout
- [ ] Keep each sub-flow shippable before moving to the next one.

**Exit Criteria:**
- Most editor interactions happen in Compose.
- Legacy panel fragments are no longer part of the main editing path.

**Validation:**
- Verify changing watermark content, style, and layout still updates the preview.
- Verify multi-image selection still switches the preview correctly.

### Task 6: Compose Save/Export UI and Remove Legacy Orchestration

**Why now:** This is high-value but high-risk, so it should come after the editor shell is stable.

**Files:**
- Migrate from: `app/src/main/java/me/rosuh/easywatermark/ui/dialog/SaveImageBSDialogFragment.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/MainViewModel.kt`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/EditorScreen.kt`
- Create: Compose save/export sheet files under `app/src/main/java/me/rosuh/easywatermark/ui/compose/`

**Tasks:**
- [ ] Build Compose save/export UI on top of existing save business logic.
- [ ] Preserve format selection, quality control, progress display, and share/gallery actions.
- [ ] Move permission prompting out of the legacy activity/dialog coupling.
- [ ] Do not rewrite bitmap generation or compression internals in this milestone.

**Exit Criteria:**
- Save/export is Compose-led.
- Users can still export, open gallery, and share results successfully.

**Validation:**
- Export JPEG and PNG.
- Share exported output to another app.
- Open exported output in gallery.

### Task 7: Cleanup and Decide What Not to Rewrite

**Why now:** Cleanup should happen only after product parity is visible.

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/me/rosuh/easywatermark/ui/MainActivity.kt`
- Remove or archive: unused files under `ui/dialog/`, `ui/panel/`, and adapters only after replacement is complete

**Tasks:**
- [ ] Identify which legacy classes are truly dead after Compose parity.
- [ ] Remove legacy UI files only after they are no longer on any user path.
- [ ] Keep renderer/export code that still earns its keep.
- [ ] Write down the final decision on whether `WaterMarkImageView` should stay bridged or be replaced later.

**Exit Criteria:**
- There is no longer a confusing dual-track UI stack.
- The remaining legacy code is intentional and documented.

**Validation:**
- Run the full smoke matrix.
- Confirm there are no dead entry paths in the manifest.

## Do Not Do Early

- Do not rewrite `WaterMarkImageView` first.
- Do not rewrite save/export/compression internals first.
- Do not rewrite MediaStore query logic first.
- Do not remove `MainActivity` share support before Compose reaches parity.
- Do not collapse the migration into one giant PR.

## Recommended Developer Learning Order

1. Learn Compose shell patterns in `LaunchScreen` and `ComposeMainActivity`.
2. Learn state modeling in `MainViewModel`.
3. Learn editor UI composition in `EditorScreen` and the option surfaces.
4. Learn interoperability discipline through `AndroidView`.
5. Only later evaluate custom drawing or renderer replacement.

## Suggested Working Style

- Ship one milestone at a time.
- Keep each milestone reviewable in isolation.
- Run the smoke checklist after every milestone.
- When uncertain, prefer lower-risk UI migration over deeper engine rewrites.
