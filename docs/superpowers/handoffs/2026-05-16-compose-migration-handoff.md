# Session Handoff - EasyWatermark Compose Migration

## Goal

Guide and review an incremental migration of EasyWatermark UI to Jetpack Compose, with the user hand-writing code while I act as PM/code coach. Current focus is the Compose-owned save/export bottom sheet replacing direct reuse of the legacy `SaveImageBSDialogFragment`.

## Decisions

- Use incremental, state-first migration.
- Preserve legacy behavior unless explicitly changing product behavior.
- Do not rewrite `WaterMarkImageView` or the export engine early.
- Do not reuse `SaveImageBSDialogFragment` from `ComposeMainActivity`; it depends on `MainActivity`.
- `SaveExportSheet` should be dumb UI controlled by `ComposeMainActivity` state.
- The add-more-images action currently replaces the image set, matching legacy behavior.
- Continue using a chat-driven code coach loop: assign one small task, user implements, review, then move on.

## Files

- `task_plan.md`
- `findings.md`
- `progress.md`
- `docs/superpowers/specs/2026-04-18-compose-migration-design.md`
- `docs/superpowers/plans/2026-04-18-compose-migration-plan.md`
- `docs/superpowers/plans/2026-04-18-compose-smoke-checklist.md`
- `app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt`
- `app/src/main/java/me/rosuh/easywatermark/ui/save/SaveExportSheet.kt`
- `/Users/rosu/.agent/diagrams/save-export-sheet-coach.html`

## Current State

- `ComposeMainActivity` is the launcher path.
- Legacy `MainActivity` still owns `ACTION_SEND image/*`.
- `SaveExportSheet` shell is rendered from `ComposeMainActivity`.
- The latest verified completed step was the Compose save/export sheet shell.
- `./gradlew app:assembleDebug` passed after the shell was rendered.
- The current next task is to make temporary format and quality values real Compose state.

## Risks

- Normal launch and shared-image entry still diverge.
- `MainViewModel` still mixes `LiveData`, `StateFlow`, navigation state, and business state.
- `SaveExportSheet` is a UI shell only; it is not connected to real export yet.
- `ComposeMainActivity` currently uses hardcoded sheet values.
- Some unused imports/params may remain.
- The worktree has unrelated dirty files; do not revert them unless explicitly requested.

## Next

Review the user's latest changes if needed. Then continue the save/export migration by replacing temporary fixed values with Compose state:

1. In `ComposeMainActivity`, add:

```kotlin
var selectedOutputFormat by remember { mutableStateOf("JPEG") }
var outputQuality by remember { mutableStateOf(80f) }
```

2. Change `SaveExportSheet` API:

```kotlin
onFormatClick: () -> Unit
```

to:

```kotlin
onFormatSelected: (String) -> Unit
```

3. In dropdown items, call:

```kotlin
onFormatSelected("JPEG")
onFormatSelected("PNG")
```

4. In `ComposeMainActivity`, pass:

```kotlin
selectedFormatLabel = selectedOutputFormat
quality = outputQuality.toInt()
isQualityVisible = selectedOutputFormat == "JPEG"
onQualityChange = { outputQuality = it }
onFormatSelected = { selectedOutputFormat = it }
```

5. Verify:

- Default format is `JPEG`.
- Selecting `PNG` hides the quality row and slider.
- Selecting `JPEG` shows the quality row and slider.
- Dragging the slider changes the quality number.
- `./gradlew app:assembleDebug` passes.

## Save Note

Attempted to create a Nowledge handoff with `nmem --json t create`, but `nmem` was not installed or not available in `PATH` in this environment. This file is a local markdown fallback, not a real Nowledge thread.
