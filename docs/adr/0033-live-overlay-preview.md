# ADR-0033: Editor preview is a live two-layer overlay; export still bakes

**Status:** Proposed — Implemented, pending device sign-off  
**Amends:** ADR-0018 §5 (preview slot no longer paints the export `composeOverBackground` bitmap)  
**Related:** ADR-0029 (Library derivative stays photo-layer chrome), ADR-0030 (working set: Source stays; Watermarked is no longer the editor paint), ADR-0028 (filmstrip thumbs stay Coil)  
**Hub:** `docs/superpowers/research/2026-08-26-watermark-background-platform-docs.md`

## Context

Preview and export both flatten photo + cell into one `ImageBitmap` (`CommonWatermarkPipeline` → `composeOverBackground`). The UI holds no peelable watermark layer. A far filmstrip jump can therefore paint an unwatermarked `SourcePlaceholder` while a new Watermarked frame composes (`PreviewPaintPolicy.showSourceWhileComposing` is true on path change).

Platforms already document display-time layering (Compose custom `Painter`, Android `LayerDrawable`). The gap is the product contract, not a missing API.

Owner chose the sticker route over “hold the last baked frame.” Three forks were locked the same day (see Decision).

## Decision

1. **Editor main preview is two layers.** Photo layer = decoded Source (or iOS Library derivative). Overlay = the current cell tiled with the same REPEAT / CLAMP rule as `composeOverBackground`, in the same fit-center rect as the photo. No settle-to-bake: the preview stays live-stacked. GPU overlay vs offscreen bake filter differences are accepted.

2. **Export, share, and save-sheet baked thumbs stay `composeOverBackground`.** One flattened bitmap. Preview aligns tiling math, not the export output allocation.

3. **Cell sizing stays image-space.** Text `fontPx = textSize × displayedPreviewWidth / 1000`. Hide the overlay until a cell exists for the **currently displayed preview-resolution** bitmap’s width. Do not stretch the previous text cell. Icon cells (`textSize / 14`) may be reused across widths.

4. **Waiting chrome is not the previous photo.** Prefer the Coil filmstrip thumb as the photo layer; if none, an empty slot. That chrome may be unwatermarked. **Never** paint a preview-resolution Source or Library frame without a matching overlay.

5. **Atomic first real paint.** Source / Library and the matching overlay appear together. If the overlay is late, keep thumb / empty / last valid two-layer frame — not a bare preview-resolution photo.

6. **Out of scope for the first cut:** drawing the overlay on the filmstrip; putting PhotoKit pixels into the pipeline; changing EXIF bake or decode edges.

## Consequences

- **Positive:** far jump no longer flashes a full unwatermarked preview photo; slider / CLAMP drag can move the overlay without rebaking the photo; iOS Library can be a photo layer under a real overlay once the cell matches that width.
- **Trade-off:** ADR-0018 “preview ≡ export bitmap” is withdrawn for the editor slot. A short unwatermarked thumb / empty gap is accepted while the new cell (and/or Source) is not ready.
- **ADR-0029:** first paint is no longer “unwatermarked Library, then fade to Watermarked.” Library is photo-layer only; overlay waits on cell width. Amend 0029 in the same implementation PR.
- **ADR-0030:** editor paint no longer requires a Watermarked working-set hit. Source residency stays. Dropping the ~5 Watermarked frames is a later slice, not the first cut.
- **Revert path:** paint one baked `ImageBitmap` again; restore `showSourceWhileComposing` as the wait policy.

## Execution outline

- [x] Common overlay painter (`drawWatermarkTiles` + `LiveOverlayPreview`) using existing `composeTextCell` / `composeIconCell`; no new output `ImageBitmap` for the editor slot.
- [x] Android / Desktop / iOS editor slots only. Export line untouched.
- [x] Tests: path change never publishes Source/Library without overlay; text width change hides overlay; filmstrip thumb / empty is the wait chrome.
- [x] CLAMP overlay-only drag; ADR-0029 / 0018 / 0030 sentences amended.
- [ ] Owner device sign-off (then this ADR can leave Proposed).
- [ ] Later slice: shrink `PreviewPurpose.Watermarked` residency.
