# ADR-0010 delta — golden policy under Option C2 (ADR-0018)

**Status:** Accepted with ADR-0018  
**Date:** 2026-07-12

## Change

Under C2, Android production paint moves toward commonMain `WatermarkCellComposer` /
`composeOverBackground`. Historical **strict FNV** baselines captured against native
`WatermarkRenderer` **must not** block PR CI as a correctness proxy for the new path.

| Suite | Policy after C2 |
|-------|-----------------|
| Structural / dim / non-blank (device-independent) | Keep in CI |
| Strict Robolectric FNV pixel hash (`WATERMARK_GOLDEN_STRICT`) | Local / rebaseline host only; **rebaseline after Gate 3** or retire for C2 path |
| `C2DualPathMeasurementTest` | Measurement pack: dims + opaque IoU + PNG artifacts under `build/c2-dual-path/` — **not** byte-equality |
| Desktop/iOS perceptual goldens | Unchanged (already common path) |

## CJK

CJK StaticLayout vs MultiParagraph delta is **accepted product change** under C2, not a silent bug.
Document in release notes when export flag defaults on for release.
