# 11 — P1 Desktop render/save spine consolidation

**What to build:** Remove the duplicated Desktop Text/Icon render-and-write implementation from
`DesktopWatermarkFlow.runSaveFlow` and `DesktopExportPipelinePort.exportOne`. Both callers must delegate
to one Desktop-only deep module while preserving four distinct destination policies: preview temp,
Save As exact path, Open/Drop unique output, and deterministic headless output.

**Blocked by:** P0 share/CLAMP fixes, `CommonRasterFlags` removal, final verification and push — **complete by owner confirmation (2026-07-18)**.

**Status:** **done (2026-07-18)** — `DesktopRenderSaveSpine` cut over for Flow + ExportPipelinePort; spine tests + headless witness green.

## Why this is the next slice

The real duplication is not the Window UI or the shared Session. It is the same Desktop implementation
knowledge appearing twice:

- choose Text versus Icon with `DesktopSaveDecision.renderPlan`;
- validate and read the icon file;
- map `WaterMark` and `UserPreferences` into `DesktopWatermarkComposer`;
- apply alpha, output format, and quality;
- create the target directory, write bytes, and report final dimensions.

`DesktopWatermarkFlow` additionally reads repositories and can generate a fixture.
`DesktopExportPipelinePort` additionally validates a source `MediaRef`, chooses a collision-free filename,
and adapts the result to the shared export contract. Those are caller policies and must remain outside the
new module.

## Scope boundary

### In scope

- Add one Desktop-only render/save implementation under `shared/src/desktopMain`.
- Redirect `DesktopWatermarkFlow` and `DesktopExportPipelinePort` to it.
- Preserve Text and Image watermarks, JPEG/PNG, quality, alpha, REPEAT/CLAMP, and output dimensions.
- Replace duplicated branch tests with tests against the new deep module's observable result.
- Refresh KDoc and the architecture report after the second caller is cut over.

### Explicit non-goals

- No Android or iOS behavior changes.
- No `expect`/`actual`, new cross-platform port, or commonMain filesystem abstraction.
- No `ExportPipelinePort` outcome redesign; that remains P3.
- No Session/route/presentation ownership cleanup; that remains P4.
- No Desktop UI redesign, FileDialog change, reveal behavior change, or output-directory change.
- No paint/raster rewrite; `DesktopWatermarkComposer` and `CommonWatermarkPipeline` remain the renderer.
- No attempt to make preview a real export or to make fixture output a real selected image.

## Proposed deep-module contract

Recommended location:

`shared/src/desktopMain/kotlin/me/rosuh/easywatermark/render/DesktopRenderSaveSpine.kt`

Minimal shape:

```kotlin
data class DesktopSavedImage(
    val output: MediaRef,
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val outputByteCount: Int,
)

object DesktopRenderSaveSpine {
    fun renderAndSave(
        imageBytes: ByteArray,
        config: WaterMark,
        prefs: UserPreferences,
        target: File,
    ): DesktopSavedImage
}
```

The API accepts an **exact target file**. It does not decide whether that target came from a preview temp,
a Save As dialog, a unique-output policy, or the headless default. This keeps one implementation without
collapsing four different product meanings into one destination abstraction.

The function may stay synchronous: its work is blocking Desktop I/O and rendering, and existing callers
already select `Dispatchers.IO` or `runBlocking`. Do not hide dispatcher ownership inside the module.

## Implementation slices

### P1.0 — Characterize the existing contract

**Change type:** tests plus captured runtime witness only; no production change.

- Extend the existing `DesktopExportPipelinePortTest` / render tests with Text and Image fixtures.
- Cover JPEG and PNG, REPEAT and CLAMP, configured alpha, unique output, and missing icon failure.
- Use the existing `DesktopSaveDecisionTest` to pin exact-target versus unique-target selection rules.
- Run the existing headless path and capture its output path, format, dimensions, and exit result as the
  `DesktopWatermarkFlow` witness; `desktopApp` currently has no test source set, so do not add build plumbing
  merely to test that wrapper.
- Record the observable contract: output exists, extension/format agree, dimensions match input, and each
  caller selects the intended destination policy.
- Keep pixel assertions at the existing Desktop/Skiko policy; do not introduce Android byte-parity claims.

**Stop/accept:** `:shared:desktopTest` and the headless witness are green without production changes.

### P1.1 — Extract the deep module and cut over `DesktopWatermarkFlow`

- Add `DesktopRenderSaveSpine` and `DesktopSavedImage` under `shared/desktopMain`.
- Add `DesktopRenderSaveSpineTest`; this is where exact target-file writing becomes directly testable.
- Move the Text/Icon decision, icon validation/read, composer mapping, directory creation, file write, and
  output metadata into the new module.
- Keep repository reads, fixture creation, input label, and default target selection in
  `DesktopWatermarkFlow.runSaveFlow`.
- Make `runSaveFlow` adapt `DesktopSavedImage` into its existing `SaveOutcome`; do not change callers yet.

**Stop/accept:** Desktop tests and headless flow are green; preview and Save As behavior are unchanged.

### P1.2 — Cut over `DesktopExportPipelinePort`

- Keep source `MediaRef` validation and source-file reading in the adapter.
- Keep `resolveUniqueOutputFile` in the adapter because unique naming is an export destination policy.
- Delegate render/write to `DesktopRenderSaveSpine`.
- Preserve the current `Result<MediaRef>` contract and width/height mutation until P3.
- Delete the duplicated Text/Icon/composer/write branch from the adapter.

**Stop/accept:** both production callers use the same implementation; `DesktopExportPipelinePort` tests,
`:shared:desktopTest`, and Desktop compile are green.

### P1.3 — Caller and documentation cleanup

- Update stale comments in `DesktopWindow`, `Main`, `DesktopWatermarkFlow`, and
  `DesktopExportPipelinePort` to distinguish one implementation from four destination policies.
- Remove now-unused imports/helpers and tests that only assert the deleted duplicate internals.
- Do **not** remove the fixture path's manual presentation marker in this slice; that belongs to typed export
  outcome / Session ownership work, not render/save consolidation.
- Update the architecture HTML and local tracker with actual verification evidence.

**Stop/accept:** no caller chooses a renderer branch; callers only choose input/config snapshots and target.

### P1.4 — Final proof bundle

Run on the exact final worktree:

```text
./gradlew :shared:desktopTest --rerun-tasks --max-workers=8
./gradlew :desktopApp:compileKotlin --max-workers=8
./gradlew :desktopApp:run --args='--headless' --max-workers=8
./gradlew :desktopApp:createDistributable --max-workers=8  # use supported Corretto/Zulu JDK 17
git diff --check
./gradlew --stop
```

Manual Desktop acceptance:

1. Preview refresh writes only the app-private temp file and keeps the last good preview on failure.
2. Save As writes the exact user-selected path.
3. Open and multi-file Drop write collision-free outputs to the configured output directory.
4. Reveal/Open-folder still targets the last real save, never the preview temp.
5. Text and Image mode both save successfully; a missing icon fails loudly.
6. JPEG/PNG and quality settings are honored.

## Expected file ownership

| File | Planned responsibility |
|---|---|
| `shared/.../render/DesktopRenderSaveSpine.kt` | New single Desktop render-and-write implementation |
| `desktopApp/.../DesktopWatermarkFlow.kt` | Repository/fixture/default-target orchestration; thin adapter to spine |
| `shared/.../session/DesktopExportPipelinePort.kt` | Shared export adapter; source validation + unique destination + result mapping |
| `shared/src/desktopTest/.../DesktopRenderSaveSpineTest.kt` | Deep-module contract tests |
| `desktopApp/.../DesktopWindow.kt` | Preview, FileDialog, Open/Drop, reveal and presentation only |

## Risks and stop conditions

Stop the slice and reassess if any of these occurs:

- preview temp becomes `lastSavedFile` or changes export/share state;
- Save As no longer honors the exact chosen path;
- Open/Drop overwrite an existing output;
- alpha is applied twice, CLAMP/REPEAT changes, or output format/quality drifts;
- the extraction requires changing shared `ExportPipelinePort` or Session state semantics;
- a proposed abstraction needs `expect`/`actual` or a second one-method forwarding wrapper;
- headless output, exit behavior, or deterministic fixture changes.

## Definition of done

- [x] One Text/Icon/composer/write implementation exists for Desktop.
- [x] `DesktopWatermarkFlow` and `DesktopExportPipelinePort` both delegate to it.
- [x] Four destination policies retain their distinct semantics.
- [x] Deep-module tests cover Text/Image, JPEG/PNG, REPEAT/CLAMP, alpha, exact target, and missing icon.
- [x] Desktop tests, compile, headless, and `git diff --check` pass (`createDistributable` skipped: Homebrew JDK vendor check).
- [ ] Manual Preview / Save As / Open / Drop / reveal checks pass (needs display session; unit+headless stand in).
- [x] Issue tracker updated with evidence; no P3/P4 work claimed.
