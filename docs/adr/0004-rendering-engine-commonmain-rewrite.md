# ADR-0004: Rendering engine — single commonMain rewrite with C2a/C2b split

**Status:** Accepted; the image-space sizing portion is **Proposed** until C2b sign-off · **Plan ref:** D4, C2a/C2b

## Context
The engine's portable core is ~400–500 LOC and every drawing primitive maps ~1:1 to `androidx.compose.ui.graphics`/`ui.text` common APIs (StaticLayout→TextMeasurer, BitmapShader(REPEAT)→ImageShader(Repeated), offscreen Bitmap+Canvas→ImageBitmap+CanvasDrawScope). Two design debts: preview/export composition is duplicated, and export scale derives from the preview View's matrix (`1/MSCALE_X`, both axes — bug) via `ViewInfo`, which is window-size-dependent (a correctness bug on Desktop).

## Decision
Rewrite ONCE in commonMain-compatible compose-ui graphics; do NOT keep per-platform engines. Split:
- **C2a:** extract `WatermarkRenderer`; the existing View AND `generateImage` both delegate to it; zero behavior change; strict same-platform goldens gate the swap. Golden harness is built BEFORE C2a against the old engine (C1.7).
- **C2b:** replace the AndroidView preview with a Compose Canvas; move to image-space watermark sizing with a one-time `textSize` config migration; delete `WaterMarkImageView`/`WaterMarkShader`/`ViewInfo`.
Only decode/encode/EXIF-orientation/photo-store are expect/actual (behind interfaces, ADR-0005).

## Consequences
- Completing the Compose migration and creating the CMP engine become the same work.
- The sizing re-spec changes what persisted `textSize` means → product sign-off + config migration + golden re-baseline required at C2b (Risk R4).
- Headless TextMeasurer needs platform bootstrap (`createFontFamilyResolver(context)` on Android).
