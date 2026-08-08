# Filmstrip jank repro analysis (2026-08-08)

## Capture metadata
| Field | Value |
|-------|-------|
| Device | iPhone 16 Pro, iOS 27.0 (`00008140-000A105601BB001C`) |
| Process | Easy Watermark **pid 22918** |
| Window | 2026-08-08 **17:09:22 → 17:10:23** (~61.2 s) |
| Template | Time Profiler (attach) + Hangs (microhang >250 ms) |
| Artifacts | `docs/superpowers/research/jank-repro-20260808-170839/` |

Files:
- `time-profiler.trace` / `time-profiler-symbolicated.trace`
- `potential-hangs.xml`, `sym-time-profile.xml`
- `03-after-repro.png` (post-window UI)

## Main-thread hang evidence (objective)

Instruments **Hangs** table (all on **Main Thread**):

| Metric | Value |
|--------|-------|
| Hang events | **44** |
| Sum of hang durations | **~20.3 s** in the last ~30 s of the capture |
| Max single hang | **1.10 s** |
| Mean hang | **~461 ms** |
| First hang | t≈**31.5 s** |
| Last hang | t≈**60.6 s** |

Per-second blocked time often **0.5–1.3 s/s** from t=34s onward — enough to destroy scroll frame budget (need ≤16 ms/frame).

This is not a subjective “feel”; the main thread was repeatedly unresponsive for hundreds of ms at a time while the user was reproducing add-more + filmstrip scroll.

## What was on CPU (symbolicated Shared.framework)

Time-profile frames heavily feature **Compose recomposition + Metal redraw**, with product work in:

| Area | Symbols seen in samples |
|------|-------------------------|
| Host preview bind | `IosProductRootHost` (many), `renderPreviewForCurrentSelection`, `bindProgressiveFocus` |
| Raster / WM | `IosPreviewRaster.renderWatermarked`, `CommonWatermarkPipeline.compose`, `WatermarkCellComposer.composeTextCell` |
| Decode | `IosImageIODecoder.decodeThumbnail` / `metadata`, `IosPreviewRaster.decodeSourcePlaceholder` |
| Cache | `IosPreviewImageRepository.load` / `startCompletionLocked` / `enforceBudgetsLocked` |
| Import control | `IosProgressiveImportController` (fileReady observers, `requestFocusReady`) |
| Compose runtime | `Recomposer.runRecomposeAndApplyChanges`, invalidation maps, `SurfaceMetalRedrawer.draw` |

Interpretation: scroll/import churn keeps **invalidating composition** and **rebinding previews** (watermark + filmstrip thumbs), while Main is also spending time in **Compose apply + Metal draw**. Background decode still collides via Main-thread state publication and full recomposes.

## Alignment with known code path (P0)

Filmstrip settle → `requestFocusReady` → `scheduleFocusPreview` → **`bindProgressiveFocus`**:
1. placeholder decode  
2. **await full watermarked preview**  
3. focus filmstrip thumb  
4. **prefetch ALL selected filmstrip thumbs**

Each settle during multi-image scroll can re-trigger that pipeline → Main hangs match the ~0.3–1.1 s cadence.

## Screenshot note
`03-after-repro.png` captured after the 60s window (UI state at end of repro).

## Recommended fix order
1. **Debounce / cancel** watermark bind on focus change (generation cancel; never await on scroll settle path for transfer ACK — only for firstItemAlone import).  
2. **Do not** full-strip `prefetchFilmstripThumbs` on every focus change — only missing visible keys.  
3. Session select immediately; keep presentation bitmap swap cheap (cache hit only on Main).  
4. Optional: freeze filmstrip bucket for editor session to avoid epoch-wide produceState restart.

## Tooling used this session
- `xctrace record --template 'Time Profiler' --attach`
- `xctrace export` (potential-hangs, time-profile)
- `xctrace symbolicate --dsym DerivedData`
- `devicectl` process list + screenshot
