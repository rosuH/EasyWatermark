# ADR-0040: Preview gesture targets and watermark editing

Status: Proposed (interaction demo approved by the owner on 2026-10-18; implementation acceptance pending).

## Context

The owner accepted the point-select demo including double-tap text editing and requested the same interaction in the App. The existing preview has matching Source + cell live layers (ADR-0033), per-photo CLAMP offsets in Session, and shared text editing UI. Separate platform drag handlers cannot arbitrate photo viewing and watermark manipulation consistently.

Research: [official product interaction evidence](https://github.com/rosuH/EasyWatermark/blob/2ccbeca4/docs/superpowers/research/2026-10-17-font-preview-interactions.md). Photoshop iPad distinguishes transforms inside a selection from canvas gestures outside. Target locking and repeated-watermark group semantics are project decisions, not claims about competitors.

## Decision

- Revision approved by the owner on 2026-10-18 after physical iPhone review: a completed tap selects the watermark group; a blank tap or Done clears selection. Each gesture captures the selection that existed before pointer down. Unselected gestures always pan/zoom the photo, even over watermark pixels. Selected gestures anywhere transform the watermark/group. Adding fingers, landing in gaps, and crossing cells never retarget. This replaces pointer-down glyph arbitration, which was ambiguous for dense patterns.
- Reset view and Done sit in a fixed, opaque 48 dp toolbar outside the photo; remove the Edit watermark shortcut. Double-tap retains the existing text sheet. Selection outlines identify the repeated group.
- Tap watermark pixels to select. One-finger drag moves the selected watermark; two fingers resize and rotate it. REPEAT changes the whole pattern, never an individual copy. CLAMP changes the single cell.
- With no selection, photo gestures pan/zoom only the viewing surface (1–4×); Reset view restores Fit. View transform and selection chrome do not enter Session, caches, export, or preferences. Photo change resets the view and selection. Changing tile mode or watermark content type clears the previous selection.
- Double tap a text watermark opens the existing WatermarkTextEditSheet. Its Confirm/Cancel/template behavior is shared with the Content editor. Image watermarks retain their image-content controls.
- Gesture drafts render cells only, using the same font resolution and raster pipeline as committed preview/export. No photo re-decode or persistent edit during a gesture. A successful end submits one atomic config change for size/rotation/repeated origin; CLAMP offset uses the existing Session commit and one non-draft overlay paint.
- A gesture draft owns its source pixel dimensions. Normalization uses that coordinate frame; pending rasters from an obsolete resolution cannot commit. The final normalized offset is retained until matching host layers arrive.
- Repeat origin is global config, normalized to photo dimensions. New preference keys default to zero, preserving existing repeated output. CLAMP offsets remain per-photo Session state. The two are not interchangeable.
- Fit + viewing transform is inverted before hit-testing. Transparent inter-cell gap belongs to the photo; small text gets a bounded touch margin. Selection chrome is not rastered into watermark cells.

## Acceptance

Verify Android/iOS and Desktop shared behavior, target lock through pointer-count changes, rotated/scaled hits, view reset and photo switch, existing text-sheet Confirm/Cancel, REPEAT group transforms, CLAMP persistence/cancellation, current-font identity, real exported pixels and unchanged exports after view-only gestures. Run affected host checks plus Mode B from the final change range and provide device recordings. Owner Human Confirm is a separate final acceptance step; no automatic merge.
