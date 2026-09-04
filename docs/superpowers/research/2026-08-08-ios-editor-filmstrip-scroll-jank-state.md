# iOS editor filmstrip scroll jank — state capture

**Captured:** 2026-08-08 (session after `febfc5d0`)  
**Branch:** `feat/migrate_to_compose` @ `febfc5d0`  
**Device:** rosu的iPhone offline at capture time (no live Instruments/screenshot)

## User report

After adding more images from the **editor top-right (+)** control, **filmstrip scrolling feels janky**.

## Product path involved

1. Editor `onAddMoreImages` → Swift `isPhotoPickerPresented = true`
2. `ContentView` freezes generation + **append=true** when already in editor
3. `PhotoImportCoordinator.importBatch(..., prioritizeFirst: true, append: true)`
4. Kotlin `IosProgressiveImportController` appends Pending slots; each Ready → Session publish
5. UI: `EditorProgressivePhotoStrip` over `EditorFilmstripScaffold` (snap LazyRow)

## What is already good (not the leak class)

- Path-first import (no N× full-res ByteArray)
- Filmstrip thumb cache budgets (8 MiB / 48 entries) + joint preview 40 MiB
- Leave-editor release (`releaseEditorMediaResources`) after `febfc5d0`
- Recenter effect keyed on **selection only** (list size must not yank scroll on append) — `EditorFilmstripInteraction.recenterEffectKey`
- Prefetch deliberately does **not** bump `filmstripThumbEpoch` (avoids global produceState restart)

## Likely jank drivers (ordered by suspicion)

### P0 — Scroll settle → full watermark bind

On user fling/drag settle, scaffold calls `onItemSelected` → progressive `requestFocusReady` → `scheduleFocusPreview` → **`bindProgressiveFocus`**:

1. decode source placeholder  
2. **await** full watermarked preview  
3. focus filmstrip thumb  
4. background `prefetchFilmstripThumbs(**all** selected paths)`

So mid-scroll browsing of many thumbs pays a **full preview pipeline + full-strip prefetch** per settle. That contends with LazyRow frames on the same process (decode on Default still fights thermal/CPU; Main gets state writes).

### P1 — Every Ready during append invalidates the strip list

Each progressive Ready:

- rewrites `progressiveImport.slots` (`mutableStateOf`) → new `EditorProgressiveSlotPresentation`
- Session `selectedImageList` grows → `EditorScreen(imageList=…)` recomposes
- During import, many Pending cells run `CircularProgressIndicator` (indeterminate)

After import finishes, P0 remains for every later scroll settle.

### P2 — `onCellPxMeasured` → host state / possible epoch bump

Every Ready/Pending cell reports size via `onSizeChanged` → host `measuredFilmstripCellPx`.  
If long-edge **bucket** changes (128/160/192), host bumps **`filmstripThumbEpoch`**, which is a key of every filmstrip `produceState` → all visible thumbs restart load. Even without bucket change, presentation object is rebuilt when measured px updates.

### P3 — Append still uses firstItemAlone + await watermark before ACK

Correct for first-paint priority, but during **add-more while already editing**, user is often already interacting with the strip; long ACK stalls keep Pending chrome longer (more spinners, more slot churn).

### P4 — Snap fling + dual haptics

Settle path performs Compose haptic + `PlatformHaptics.selectionTick()` and may `animateScrollToItem` on tap. Secondary cost vs P0/P1.

## Reproduction recipe (for next online device session)

1. Cold open app → pick ~8–12 photos → wait for watermarked preview  
2. Editor top-right **+** → add another ~8–12  
3. Immediately and after import settles, fling filmstrip left/right  
4. Note: jank during import vs only after Ready; jank on settle vs continuous drag  

## Instruments targets (when device online)

- Time Profiler: `IosPreviewRaster` / Skia decode / `renderPreviewForCurrentSelection` during scroll  
- SwiftUI/Compose: Main thread frame drops on settle  
- Allocations: filmstrip `ImageBitmap` count vs 48-entry cap  

## Fix directions (not applied in this capture)

1. **Scroll settle:** select Session focus cheaply; **debounce / cancel** watermark bind; skip full strip prefetch on focus change (only decode visible/missing)  
2. **Append:** optional lower priority on non-focus Ready chrome; don’t restart full-strip prefetch when only tail grows  
3. **Measurement:** freeze filmstrip bucket once per editor session (or only upgrade once) to avoid epoch storms  
4. **Pending:** cap concurrent spinners / static phase longer under load  

## Code anchors

| Area | Path |
|------|------|
| Bind order / await ACK | `IosProgressiveImportController.launchFileReadyJob`, `onFocusReadyForPreview` |
| Heavy focus bind | `IosProductRootHost.bindProgressiveFocus` |
| Filmstrip UI | `EditorPhotoStrip.kt`, `EditorProgressivePhotoStrip.kt` |
| Thumb produceState | `IosProductRootHost` `thumbnail = { … produceState(path, epoch, bucket) }` |
| Append picker | `ContentView.swift` `onChange(pickedItems)` + `importBatch(append:)` |
| Cache budgets | `IosPreviewImageRepository` |

## Git

```
febfc5d0 fix(ios): prioritize focus watermark bind and close leave-editor media lifecycle
```
