# EasyWatermark Compose Migration Design

## Goal

Refactor EasyWatermark's UI into a Compose-first architecture in a way that is safe, teachable, and incremental, so the developer can follow a clear roadmap and use the project to build real Compose skill.

## Product Framing

This is not a greenfield app and not a visual redesign project. The real product goal is to reduce UI complexity and maintenance cost without breaking critical user flows such as image picking, image sharing from other apps, editor interaction, and save/export.

## Success Criteria

- Compose becomes the primary UI path for all normal user journeys.
- Legacy View/Fragment code is reduced to compatibility edges and the rendering core during the migration.
- The developer can work milestone by milestone without needing to understand the whole codebase at once.
- The app keeps feature parity through migration.
- There is a stable path to eventually retire `MainActivity` and the legacy dialog/panel stack.

## Non-Goals

- Do not do a full visual redesign.
- Do not rewrite bitmap rendering and export logic in the early phases.
- Do not replace `WaterMarkImageView` in the first half of the migration.
- Do not convert every legacy file just because it exists.

## Current-State Summary

### Entry and Navigation

- `ComposeMainActivity` is the launcher activity.
- `MainActivity` still handles `ACTION_SEND image/*`.
- Compose navigation is active, but screen flow is also duplicated in `LaunchScreenState` / `LaunchScreenUiState`.

### State Management

- `MainViewModel` currently mixes:
  - `LiveData`
  - `StateFlow`
  - mutable fields
  - screen state transitions
  - business state updates

This makes Compose harder to scale because the UI does not have one authoritative state model.

### UI Surface Area

- `LaunchScreen`, `GalleryDialog`, and `EditorScreen` already exist in Compose.
- Legacy `Fragment` and bottom-sheet flows still handle text editing, template selection, save/export, and several editor option panels.
- The editor rendering surface still depends on `WaterMarkImageView` through `AndroidView`.

## Migration Principles

### 1. Parity Before Purity

The right short-term goal is a stable Compose-first product, not a fully pure Compose codebase.

### 2. State Before Renderer

The migration should first consolidate entry flow, navigation ownership, and state modeling. Rewriting rendering before this would add risk without solving the main architectural problem.

### 3. Learn Compose on the Shell

The developer should practice:

- screen structure
- state hoisting
- lifecycle-aware state collection
- Compose navigation
- dialogs and sheets
- View interoperability

before attempting advanced custom rendering work.

### 4. Only Migrate What Earns Its Keep

If a legacy component is stable and low-value to rewrite, it should stay until it becomes the next bottleneck.

## Target Architecture

### Entry

- One future owner for all app entry paths.
- Legacy `MainActivity` may temporarily remain as a thin compatibility trampoline for shared-image entry.

### State

- Screen composables consume immutable `UiState`.
- Screen composables emit typed intents/events.
- `ViewModel` owns business state and exposes lifecycle-safe flows.
- Navigation state is owned by Navigation Compose, not duplicated in screen state.

### Editor

- Editor shell is Compose.
- Rendering engine remains `WaterMarkImageView` behind `AndroidView` until later.
- Save/export flow becomes Compose UI over existing business logic before any deep rendering changes.

## Milestones

### Milestone 0 - Regression Baseline

Build a smoke matrix for the flows that cannot break:

- cold launch
- permission flow
- gallery selection
- system picker
- shared image entry
- multi-image editing
- back handling
- save/export/share

### Milestone 1 - Entry Consolidation

Choose and implement the long-term entry strategy so that normal launch and shared-image launch no longer diverge into separate product experiences.

### Milestone 2 - State Consolidation

Refactor `MainViewModel` and screen contracts so Compose screens operate through one clear `UiState + intent` model.

### Milestone 3 - Compose Shell Completion

Migrate low-risk screens, dialogs, and sheets that are primarily UI orchestration.

### Milestone 4 - Editor Chrome Migration

Migrate the editor's surrounding controls, keeping the rendering view bridged.

### Milestone 5 - Legacy Cleanup

Retire obsolete dialogs, panels, adapters, and activity responsibilities as Compose gains parity.

### Milestone 6 - Optional Rendering Rewrite

Only after the rest is stable, evaluate whether replacing `WaterMarkImageView` is worth the complexity.

## Major Risks and Mitigations

### Risk: Entry Drift

Mitigation:

- Keep one compatibility path only.
- Route all entry scenarios into the same Compose editor journey as early as practical.

### Risk: Navigation Drift

Mitigation:

- Remove duplicated screen-state ownership from `LaunchScreenUiState` once Navigation Compose becomes authoritative.

### Risk: State Drift

Mitigation:

- Move toward immutable screen state and typed events.
- Avoid adding more `Any`-typed or ad hoc mutable UI state.

### Risk: Export Regression

Mitigation:

- Leave bitmap generation and export logic intact until the UI shell is stable.
- Test export at every milestone that touches editor flow.

## Recommended Learning Track

### First

- `collectAsStateWithLifecycle`
- state hoisting
- `rememberLauncherForActivityResult`
- Navigation Compose
- `ModalBottomSheet`

### Then

- screen state modeling
- typed intents
- `AndroidView` interoperability
- side-effect handling

### Later

- custom drawing
- Compose performance tuning
- advanced state optimization

## Decision Log

- Chosen strategy: incremental, state-first migration.
- Deferred strategy: big-bang full Compose rewrite.
- Deferred strategy: early renderer replacement.
