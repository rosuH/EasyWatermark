# ADR-0016: MainActivity integration — ACTION_SEND, crash recovery, legacy Activity retirement

**Status:** Implemented (PR #377, 2026-06-13) · **Date:** 2026-06-13 · **Plan ref:** M1 (entry consolidation), C1.6 (crash recovery), M5 (legacy cleanup)

> **Implementation note (2026-06-13):** Executed and real-device-verified on Galaxy S22+. ACTION_SEND/SEND_MULTIPLE consolidated onto `ComposeMainActivity`; crash recovery ported to `RecoveryScreen.kt`; watermark-template surface ported to `TemplateListSheet.kt` (the last live Compose→legacy bridge). Then the legacy stack was deleted: 3 Activities + `ui/dialog/*` (7) + `ui/panel/*` (9) + `ui/adapter/*` (9) + `ui/base/*` (7) + `LaunchView`/`LaunchViewListener` + `SimpleOverScrollEdgeEffect`/`BounceEdgeEffectFactory` (39 .kt) + 3 manifest `<activity>` entries + the dead `ContextExtension` import. Build green, APK −330KB, full smoke test (launch/editor tabs/template/save/about/recovery) passed with no crashes. Orphaned layout XMLs remain for a follow-up hygiene pass.
>
> **Amendment (2026-08-16):** The sole Activity was renamed back to `MainActivity` so the exported component matches Play v2.10.0 (`.ui.MainActivity`). No trampoline or `activity-alias`. Historical notes below still say `ComposeMainActivity`.

## Context

After the About/OpenSource migrations, the Compose path covers Launch/Editor/Gallery/Save/TextEdit/About/OpenSource. The last legacy holdout is `MainActivity` (820 lines), which still owns three production-critical behaviors:

1. **ACTION_SEND share-in** (`onStart:282`): `intent.action == ACTION_SEND && intent.data != null → dealWithImage(listOf(intent.data))`. Manifest gives `MainActivity` the `ACTION_SEND` + `image/*` intent-filter (ComposeMainActivity has MAIN/LAUNCHER). Note: only single-image `intent.data`; `ACTION_SEND_MULTIPLE` and `EXTRA_STREAM` are NOT handled (existing gap, backlog R11).
2. **Crash-recovery screen** (`onCreate:179`): `if (MyApp.recoveryMode) setContentView(R.layout.activity_recovery)`; `checkHadCrash()` reads SP crash flag → `showCrashDialog`; `launchSuccess()` clears recovery state after 1s.
3. The remaining legacy editor UI (`launchView`), now fully superseded by Compose.

Retiring `MainActivity` also unblocks deleting the legacy chain MainActivity → AboutActivity → OpenSourceActivity.

## Why this needs design-first (not an inline edit)

Unlike the previous parity fixes, this touches **app entry + crash self-heal** — a regression silently breaks share-in or the crash loop recovery for all users. It also introduces a genuinely new pattern (Activity-level intent → Compose NavHost navigation) absent elsewhere in the codebase. High blast radius ⇒ design + careful verification before code.

## Decisions

### Entry strategy → Option A: ComposeMainActivity owns ACTION_SEND (single entry)
Move the `ACTION_SEND`/`image/*` intent-filter from `MainActivity` to `ComposeMainActivity` (already `singleTask`). Rejected Option B (MainActivity as thin trampoline) because the end goal is deleting MainActivity entirely; a trampoline keeps a second entry Activity alive indefinitely.

### Share-intent → Compose navigation bridge
**Use `viewModel.updateImageList(uris)` — NOT `SystemPickerImageSelected`.** Implementation finding (2026-06-13): `Action.SystemPickerImageSelected` calls `ContentUris.parseId(uri)` + a MediaStore `_ID IN (...)` query (MainViewModel:1006-1015), i.e. it assumes the uri is a MediaStore content uri. ACTION_SEND uris come from arbitrary apps (FileProvider, other content providers) with no MediaStore `_ID` → that path would silently fail. The legacy MainActivity share path correctly uses `mainViewModel.updateImageList(uri)` (MainActivity.dealWithImage:726), which does not assume MediaStore. So: parse share intent → `FileUtils.isImage` guard → `viewModel.updateImageList(uris)` → set a one-shot `pendingNavToEditor` Compose `mutableStateOf` flag on the Activity. In `setContent`, a `LaunchedEffect` observing that flag calls `navController.navigate("EditorScreen")` then clears it. Also fix the long-standing gap: handle `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` (clipData) while here. Copy share uris to app cache on receipt (read-permission scope, R7).

### Crash recovery → Compose
`MyApp.recoveryMode` is read in `ComposeMainActivity`; when true, `setContent` shows a Compose recovery screen (port of `activity_recovery`) instead of the NavHost; `checkHadCrash()`/`launchSuccess()` logic moves over. The uncaught-exception handler in `MyApp` is unchanged (Android-only; ADR-0007 capability is future CMP work).

### Retirement
Once the above is verified, delete `MainActivity`, `activity_recovery` consumers tied to it, `AboutActivity`, `OpenSourceActivity`, `activity_about.xml`, `activity_open_source.xml`, and their manifest entries (drop `AboutActivity` parent refs). Verify no dangling references.

## Verification plan (must pass before retirement)

- **Share-in:** `adb shell am start -a android.intent.action.SEND -t image/* --eu android.intent.extra.STREAM <content-uri> -n me.rosuh.easywatermark.debug/.ui.ComposeMainActivity` → lands in editor with the shared image. Test single + multiple. Verify via andromeld on the real device too (true cross-app share).
- **Crash recovery:** force `recoveryMode` (set the SP crash flag) → launch → Compose recovery screen shows → `launchSuccess` clears it.
- **Regression:** normal launch unaffected; back behavior intact.

## Risks

- Lost/empty share intent if EXTRA_STREAM vs data handling is wrong → test both.
- Crash-loop recovery failing silently → explicit recoveryMode test mandatory.
- `singleTask` re-entry delivering to `onNewIntent` not `onCreate` → handle both.
- Cross-app share Uris are read-permission-scoped to the receiving Activity → copy to cache on receipt (same as ADR backlog R7).

## Status

Proposed. Implement as a dedicated focused change (not a tail-end edit), with the verification plan above green before deleting any legacy Activity.
