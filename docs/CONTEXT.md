# EasyWatermark Domain Context

Domain vocabulary and invariants. Agents and humans: use these words with these meanings; update this file when a concept is added, renamed, or retired (docs-with-code gate).

## Core concepts

| Term | Meaning |
|---|---|
| **Watermark cell** | The small offscreen bitmap containing one rendered instance of the watermark (text or icon, already rotated, with gap padding baked in). Built once, then tiled or placed. Today: `buildTextBitmapShader`/`buildIconBitmapShader`. Target: `WatermarkRenderer.buildCell()` in commonMain. |
| **Tile mode** | How the cell covers the photo. `REPEAT` (UI: "repeat") = shader-tiled across the whole image. `CLAMP` (UI: "decal") = single instance at a draggable fractional offset. Persisted today as android `Shader.TileMode.ordinal` in DataStore (debt — ADR-0007); target is an app-owned enum with ordinal-compatible mapping. `MIRROR`/`DECAL` are representable but unreachable from UI. |
| **Gap (hGap/vGap)** | Spacing between tiles, percent-of-cell: `finalSize = cellSize * (gap/100 + 1)`. |
| **Rotation-AABB** | The axis-aligned bounding box of rotated content: `w' = w·cos+h·sin`, `h' = w·sin+h·cos`, with the degree piecewise-normalized (0–90 → d; 90–270 → |180−d|; else 360−d). Cell dimensions derive from it (text mode). Icon mode uses the icon **diagonal** for both dims instead. |
| **textSize dual duty** | For text: raw px size of the text paint (today view-px at preview scale — density-dependent). For icon: scale ratio = `textSize / 14f`. The CMP plan re-specs sizing to **image-space units** (fraction of image width) — requires a one-time config migration (ADR-0004, Proposed at C2b). |
| **Fractional offset (offsetX/offsetY)** | CLAMP-mode watermark position as 0..1 fractions of the displayed photo bounds. Survives across preview/export because it is resolution-independent. |
| **inSample preview decode** | Preview photos are decoded with `inSampleSize` (power of two, always ≥2) bounded by the view size; export decodes full-res. Skiko has no inSampleSize equivalent — iOS preview decode must be bounded another way (plan C5.2). |
| **ViewInfo (deprecated concept)** | Snapshot of the preview View's measured size/matrix used to derive export scale (`1/MSCALE_X`, both axes — known bug). Export is blocked until the view reports it. Dies with the engine rewrite (plan C2b); do not build against it. |
| **Image-space sizing (target)** | Watermark dimensions defined relative to the source image, independent of any window/view size. Prerequisite for Desktop correctness. |
| **Golden two-tier** | (1) Strict same-platform goldens: old engine vs new engine, near-pixel-exact — the C2 gate. (2) Cross-platform perceptual diffs (SSIM-style, looser in text regions) with per-platform baseline sets — JVM/iOS Skia text ≠ Android Minikin text even with a bundled font. |
| **Parity baseline** | Production release **v2.10.0** (master). All UI migrations are measured against its screenshots/behavior, not against this branch's interim Compose screens. |
| **MediaRef (planned)** | Platform-neutral image identity (replaces `android.net.Uri` in models). |
| **ImageFormat (planned)** | App-owned export format enum (replaces `Bitmap.CompressFormat` in models/UI). JPEG honors quality (snapped to multiples of 20, min 20); PNG ignores it. |
| **Template** | Saved watermark text snippets (Room entity), prepopulated from locale-selected bundled DBs (`ewm-db-ch.db` / `ewm-db-eng.db`). |
| **Recovery mode** | Crash-loop self-heal: uncaught-exception handler counts crashes, relaunches into a recovery screen (today: legacy `MainActivity` + `activity_recovery` layout) offering reset. Must survive `MainActivity` retirement (plan C1.6). |

## User flows

pick image(s) (photo picker / share-in via ACTION_SEND) → edit in Editor (panels: content text/templates, style color+alpha+size, layout gap+rotation+tile mode, icon watermark) → save (format+quality) → share/open.

## Invariants (do not break silently)

- **Privacy:** fully offline; no tracking/crash SDKs; no permissions on API ≥29; export strips ALL EXIF metadata including GPS (a feature, ADR-0009 — orientation is baked into pixels at decode instead).
- **Parity:** visual/behavioral changes vs production require an ADR or explicit product sign-off (Goal 2).
- Pinch-to-scale of the watermark is **disabled in production** (commented out) — keep it disabled for parity unless an ADR changes that.
- Preview background color comes from Palette extraction of the photo (cosmetic; kept for parity — kmpalette on CMP).
