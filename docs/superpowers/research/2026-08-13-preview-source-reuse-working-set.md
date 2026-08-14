# Preview source reuse + long-edge working set (2026-08-13)

## What changed

- **Source = expensive decode.** One ImageIO thumbnail per `(path, preview long-edge)`. Host stores it under Source purpose and passes it as `background` into `IosPreviewRaster.renderWatermarked`.
- **Watermarked = cheap compose.** Config change clears only Watermarked purpose and recomposes. Draft still uses the shorter long-edge and does not write committed Source keys.
  **Superseded 2026-08-14:** the draft now deliberately *shares* the Source entry (offset-independent, so sharing is sound and often a hit). Only *Watermarked* draft entries are still forbidden. See `2026-08-14-ios-preview-perf-leftovers.md`.
- **Budgets follow the preview long-edge.** `bytesPerFrame ≈ longEdge × ceil(longEdge × 3/4) × 4`; Source / Watermarked = max(floor, 5 × frame); joint = sum. Floors stay 12 / 48 MiB. Device memory `< 3.5 GiB` compresses joint to 64 MiB; otherwise joint ceiling is 128 MiB.
  **Superseded 2026-08-14:** this 4:3 frame model under-counted any source at or above 1:1, so byte eviction silently held 3 frames instead of 5. `bytesPerFrame` is now the worst-case square fence and **entry counts** control residency.
- **Phone idle preview long-edge stays 1920.** Export is still full-res.
- **HEIF Coil decode** opens one `CGImageSource` for size + thumbnail and gives Coil that bitmap (no second `allocPixels`/`readPixels`).

## What did not change

R2 draft-first paint, Coil `beyondBounds` / full-strip prefetch, Coil as watermark/export engine.

## Numbers (high-memory, 4:3)

| Preview long-edge | Frame | 5 frames | Source | Watermarked | Joint |
|---|---:|---:|---:|---:|---:|
| 720 | 1.48 MiB | 7.42 MiB | 12 MiB floor | 48 MiB floor | 60 MiB |
| 1920 | 10.55 MiB | 52.73 MiB | 52.73 MiB | 52.73 MiB | 105.47 MiB |
