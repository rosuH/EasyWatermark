# EasyWatermark Compose Migration Handoff

## Goal

Incrementally migrate EasyWatermark from legacy Views/Fragments to a Compose-first UI while preserving current behavior and using the work to build real Compose skill.

## Decisions

- Use incremental, state-first migration, not a big-bang rewrite.
- Keep `WaterMarkImageView` bridged through `AndroidView` until entry, navigation, and editor state are stable.
- Keep `MainActivity` alive for `ACTION_SEND image/*` compatibility until Compose reaches parity.
- Treat the current Compose `add more images` action as replace semantics because that matches legacy behavior.
- Do not directly reuse `SaveImageBSDialogFragment` from `ComposeMainActivity`; it is coupled to `MainActivity`.
- Use a chat-driven workflow: assign one small task, implement, review, update records, then move on.

## Files

- [task_plan.md](/Users/rosu/Coding/EasyWatermark/task_plan.md)
- [findings.md](/Users/rosu/Coding/EasyWatermark/findings.md)
- [progress.md](/Users/rosu/Coding/EasyWatermark/progress.md)
- [2026-04-18-compose-migration-design.md](/Users/rosu/Coding/EasyWatermark/docs/superpowers/specs/2026-04-18-compose-migration-design.md)
- [2026-04-18-compose-migration-plan.md](/Users/rosu/Coding/EasyWatermark/docs/superpowers/plans/2026-04-18-compose-migration-plan.md)
- [2026-04-18-compose-smoke-checklist.md](/Users/rosu/Coding/EasyWatermark/docs/superpowers/plans/2026-04-18-compose-smoke-checklist.md)
- [ComposeMainActivity.kt](/Users/rosu/Coding/EasyWatermark/app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt)
- [SaveImageBSDialogFragment.kt](/Users/rosu/Coding/EasyWatermark/app/src/main/java/me/rosuh/easywatermark/ui/dialog/SaveImageBSDialogFragment.kt)
- [EditorScreen.kt](/Users/rosu/Coding/EasyWatermark/app/src/main/java/me/rosuh/easywatermark/ui/EditorScreen.kt)

## Current State

- `ComposeMainActivity` is already the launcher activity and hosts `LaunchScreen`, `GalleryDialog`, and `EditorScreen`.
- `MainActivity` still owns the legacy `ACTION_SEND image/*` flow.
- The Compose editor top-bar `about` action has been wired and verified with `./gradlew app:assembleDebug`.
- The Compose editor top-bar `add more images` action is wired to the system picker and currently replaces the selected image set, matching legacy behavior.
- The smoke checklist has been rewritten and accepted as the current regression baseline.
- `SaveImageBSDialogFragment` cannot be dropped into `ComposeMainActivity` because:
  - it expects AndroidX `FragmentManager`
  - it casts `requireContext()` / `activity` to `MainActivity`
  - it relies on `MainActivity` helpers for image list, view info, and permission flow

## Risks

- Entry-path drift between `ComposeMainActivity` and `MainActivity`
- Duplicated navigation ownership between Navigation Compose and `LaunchScreenUiState`
- Mixed `LiveData` / `StateFlow` / mutable field ownership in `MainViewModel`
- Save/export still coupled to legacy `MainActivity` dialog APIs
- Rewriting `WaterMarkImageView` too early would create unnecessary regression risk

## Next

Continue save/export migration by introducing a Compose-owned `SaveExportSheet` shell:

1. Add a `showSaveSheet` state in `ComposeMainActivity`
2. Replace the dead save callback with `showSaveSheet = true`
3. Render a Compose `ModalBottomSheet` instead of calling `SaveImageBSDialogFragment`
4. Keep `SaveExportSheet` dumb:
   - UI only
   - receives props and callbacks
   - no direct `viewModel()`
   - no `MainActivity` coupling
5. First version should only include:
   - title
   - helper text
   - image count
   - close action
6. Later phases can add:
   - format selector
   - quality slider
   - export status
   - share / open gallery actions
   - real `MainViewModel.saveImage(...)` integration

## Note

I attempted to save this handoff through `nmem` as requested by the `save-handoff` skill, but `nmem` is not installed in this environment, so this local handoff file is the fallback checkpoint.
