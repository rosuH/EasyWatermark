# ADR-0018: Option C2 — commonMain raster for Android production export (and unified preview)

**Status:** Accepted (owner verbal sign-off **「c2！」** 2026-07-12)  
**Supersedes / reopens (in part):** ADR-0004 addenda that kept **Android production** text/icon/**composition** on native `WatermarkRenderer` indefinitely (S4d-8 Option A, S4d-17 Option C, S4d-190 No-Go). Geometry-sharing and Desktop/iOS commonMain paths remain in force.  
**Related:** U0 / product UI strategy Option **C** in `docs/parity/v2.10.0/alignment/u0-cmp-product-ui-decisions.md`; golden policy ADR-0010 (will need rebaseline / perceptual tier).

## Context

Option **C** unifies product UI in commonMain and aims for one watermark **look**. Sub-path:

| | Preview | Android **export** |
|--|---------|---------------------|
| C1 | common raster | stays native |
| **C2** | common raster | **also common raster** |

C2 was previously blocked by byte-parity failures (rotated non-uniform icons; CJK StaticLayout vs MultiParagraph) and composition API mismatch. Owner now accepts moving Android **production export** onto the commonMain Compose/Skiko-style pipeline used by Desktop/iOS (`WatermarkCellComposer` + `composeOverBackground`), knowing this is **not** byte-identical to v2.10.0 native export.

## Decision

1. **Target renderer for all three platforms (preview + export):** commonMain cell raster + `composeOverBackground` (platform only supplies decode, `TextRasterEnv`/fonts, encode, and system I/O).  
2. **Android native `WatermarkRenderer.build*Shader` / `compose` production path is retired on a gated schedule**, not deleted in one unmeasured PR.  
3. **Goldens:** strict FNV byte goldens that encode native Android raster **must be rebaselined or replaced** with a signed perceptual/structural policy (ADR-0010 follow-up). CJK visual change is **accepted** as a product consequence of C2.  
4. **Still permanent platform edges:** system pick/share/save/permissions; decode/encode/EXIF; app entry. **Not** “zero platform code.”  
5. **ProductApp / UI (Option C shell)** continues in parallel: one commonMain product UI; preview slot calls the **same** common raster path export uses (WYSIWYG under C2).

## Non-goals (this ADR)

- Byte-exact match to historical Android `StaticLayout` / `drawBitmap` goldens  
- Restoring S4d-8 “try again until 0 delta” without perceptual policy  
- In-app gallery on iOS/Desktop (U0 E02 unchanged)

## Consequences

- **Positive:** one paint path to maintain; preview ≡ export algorithm; iOS/Desktop/Android align for Option C.  
- **Negative / work:** multi-slice migration; Android export visual change (esp. CJK); golden rebaseline; ship risk if rolled without device + locale QA.  
- **Process:** every production routing slice needs measurement (perceptual/dims + critical locale smoke) before merge; no silent swap.

## Execution outline (implementers)

1. **Gate 0 — policy:** this ADR + golden rebaseline plan (ADR-0010 delta).  
2. **Gate 1 — test-only:** Android instrumented/Robolectric dual-path (native vs common) measurement pack; no production flip.  
3. **Gate 2 — preview:** Android editor preview uses common raster (feature flag OK).  
4. **Gate 3 — export:** `generateImage` / export port routes through common compose; delete or quarantine native builders.  
5. **Gate 4 — cleanup:** goldens, docs, AGENTS “do not route Android through composeIconCell” rules inverted under this ADR.

### Status update (2026-07-13 P3.5)

- `CommonRasterFlags.useCommonRasterPreview` / `useCommonRasterExport` default **`true`** for **debug and release**.  
- Smoke pack: `docs/parity/v2.10.0/captures/c2-p35-smoke-2026-07-13/`.  
- Export **panel** is shared `SaveExportSheetShell` on Android/Desktop/iOS; Photos/MediaStore/FS/share remain platform edges.  
- Gate 4 native-builder delete still owner-gated.

## Owner acceptance (recorded)

- **C2** selected explicitly after product discussion of common 光栅 and C1/C2 tradeoff.  
- Accepts Android export may change vs Play v2.10.0; requires rebaseline, not silent claim of byte parity.
