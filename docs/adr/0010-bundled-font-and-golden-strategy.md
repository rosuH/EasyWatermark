# ADR-0010: Bundled watermark font; two-tier golden strategy; sRGB pin

**Status:** Accepted (2026-06-13) · **Plan ref:** D10, D4

> **Production font scope superseded by [ADR-0025](0025-system-default-watermark-fonts.md) (2026-08-09):** product Text mode uses system-default fonts; Noto is test-only. Golden two-tier + sRGB pin below still apply.

## Context
Text rendering is not pixel-identical across platforms (Android Minikin vs Skia Paragraph; no Roboto on iOS). The default watermark starts with an emoji and the app ships 13 locales. iPhone photos are Display-P3 while Android decodes to sRGB.

## Decision
- Bundle one font via compose resources for watermark text (typeface styles map onto it).
- Goldens are two-tier: (1) **strict** same-platform old↔new parity (the C2 gate, near-pixel-exact); (2) **perceptual** cross-platform diffs (SSIM-style, looser in text-glyph regions) with per-platform baseline sets. "Green on JVM" never proxies for Android correctness.
- Decode-to-sRGB is pinned in the `ImageCodec` contract and asserted in goldens.

## Consequences
- Visual change risk when the bundled font replaces system-default rendering — covered by the C2 re-baseline + parity sign-off.
- Baseline sets are per-platform artifacts in the repo/CI cache.
- **CJK dimension gating is instrumented/device-only (candidate, C2b):** Robolectric is NOT a CJK dimension oracle (its CJK metrics differ from device — e.g. the `你好世界 watermark` cell measures differently under Robolectric vs SM-S906E/API 36), so the strict same-platform CJK old↔new cell-dimension check runs on a pinned device, not on the JVM. The candidate `WatermarkCellParityGateTest` records **signed** CJK baselines (exact absolute values + exact legacy→seam height deltas, no tolerance widening) per pinned device/API; the JVM tier keeps Latin/structural coverage only. Deltas were deterministic across repeated device runs.

## Amendment — S4d-15 (2026-06-18): implementation-ready text-parity framework + accepted font scope (Option A)

This amends (does not supersede) the Accepted decision above with concrete evidence and acceptance
gates gathered after S4d-14C. **Status of the font scope: ACCEPTED — Option A (Latin + CJK bundle),
emoji delegated to platform fallback** (owner decision, S4d-15 round 2, 2026-06-18; see "Selected font
scope" below).

### What changed since ADR-0010 was accepted
- S4d-12 fixed commonMain multiline horizontal alignment (`composeTextCell` centres each line).
- S4d-14C fixed Android native multiline vertical centring (full-block `StaticLayout.height`); Android
  text stays native `StaticLayout`. No text draw-swap occurred.
- S4d-8 (icons) established the durable caution: commonMain Compose cannot byte-match the Android
  raster for every case; gate on the correct platform, do not swap before proof.

### Post-S4d-14C on-device parity gap map (Android `StaticLayout` vs commonMain `MultiParagraph`)
Measured on emulator-5554 / `sdk_gphone64_arm64` / API 36, **current system font, no bundle yet**
(`WatermarkTextRasterParityInstrumentedTest`; full numbers in the S4d-15 `on-device-parity-output.md`):

| corpus | IoU | colorDiff | category |
| --- | --- | --- | --- |
| ascii_0 / bold / italic / bold_italic (single-line Latin) | 1.000 | 0 | **byte-identical** |
| multiline (Latin, 0°) | 0.984 | 0.8% | near (S4d-14C) |
| ascii_315 (Latin rotated) | 0.929 | 2.1% | near (rotation edge AA) |
| emoji_default_315 | 0.877 | 1.6% | near (colour-glyph + rotation) |
| cjk_0 / cjk_315 | 0.70 / 0.61 | 28% / 13% | **large** |
| cjk_multiline_0 | 0.390 | 46% | **largest** |

Cell **dimensions match exactly for every row** (both size from the same `TextMeasurer`/`WatermarkTextMeasurer`
seam). The divergence is glyph raster, concentrated in CJK.

### Clarification: what a bundled font does and does NOT fix
On Android both paths already resolve to the system font (`FontFamily.Default` for measure /
`Typeface.create(..., obtainSysTypeface())` for the legacy `StaticLayout` draw, `PainKtx.applyConfig`).
So the large CJK gap is a **layout-engine difference** (`StaticLayout`/Minikin draw vs Compose
`MultiParagraph`/`AndroidParagraph`), NOT a missing-font difference. Therefore:
- A bundled font's PRIMARY value is **cross-platform** parity (Android Minikin vs Desktop/iOS Skia
  `Paragraph`; iOS has no Roboto) — ADR-0010's original cross-platform motivation.
- A bundled font may *reduce* the Android `StaticLayout`↔`MultiParagraph` CJK gap (consistent metrics)
  but is **not guaranteed** to make it byte-exact. The Android text draw-swap therefore additionally
  depends on a tolerance/perceptual policy for CJK (and emoji), not on the font alone.

### Typeface-style mapping (already in code; bundled font must preserve)
`WaterMark.textTypeface` ∈ {Normal, Italic, Bold, BoldItalic} → Android `Typeface` style ints
(`obtainSysTypeface`) on the draw path, and `FontWeight.Bold`/`FontStyle.Italic` on the measure path
(`toWatermarkTextStyle`). Today bold/italic/bold-italic are **synthesized** by the platform and are
**byte-identical** across both paths (gap map above). A bundled family should provide real
Regular/Bold/Italic/BoldItalic faces (or accept synthesis) and must keep these four byte-identical.

### Acceptance gates before ANY Android text draw-swap (concrete)
1. STRICT (byte-exact) for single-line Latin incl. all 4 styles — already met.
2. PERCEPTUAL (tolerance) for rotated Latin / multiline / emoji — IoU ≥ 0.85 AND colorDiff ≤ 2.5%
   AND dims exact (all currently pass). Edge-AA class.
3. CJK: LOG-ONLY until a bundled-font measurement shows convergence; explicit owner-approved tolerance
   (perceptual) policy required before CJK is in scope for a swap. Emoji: perceptual or excluded.
4. No silent golden rebaseline; production-first paired screenshots for any UI-affecting swap (ADR-0004 S4d-8 rule).
(Full proposal + rationale: S4d-15 `parity-threshold-proposal.md`.)

### Selected font scope (OWNER decision, S4d-15 round 2, 2026-06-18) — Option A
**Bundle a Latin + CJK family** for watermark text across Android/Desktop/iOS: a **Noto Sans + Noto Sans
CJK SC** class family (OFL-1.1 / OFL-compatible). **Emoji is NOT bundled** — it is delegated to the
platform's emoji fallback and treated as perceptual/excluded from strict draw-swap thresholds (bundling
Noto Color Emoji ~9–24 MB is rejected for a privacy/offline/F-Droid app).

Rationale (owner): the target is a production-grade cross-platform renderer (not just Android-local
parity); the app has real CJK watermark usage + zh-locale distribution, so leaving CJK as system/log-only
would keep the main future parity risk unresolved; the +~8–10 MB CJK cost is accepted for this migration
direction, while emoji bundling is not.

Scope notes / still-open implementation details (resolved in S4d-16, not here):
- Exact binary artifacts (filenames, weights, variable-vs-static, region subsetting) and confirmed
  current sizes/licenses are verified when the font is actually added (S4d-16) — do NOT over-specify here.
- Integration: inject the bundled `FontFamily` via `TextRasterEnv.fontFamilyResolver` per platform to
  avoid the excluded CMP-9547 / `compose.components.resources` dependency (S4d-9 F1). See
  `font-candidate-matrix.md`.
- **Standing caveat (unchanged by the decision):** a bundled CJK font is the right CROSS-PLATFORM
  baseline but may NOT make the Android `StaticLayout`↔`MultiParagraph` CJK raster byte-exact. Any Android
  text draw-swap therefore stays gated on a measured, owner-approved **perceptual** CJK threshold (this
  decision selects the font scope; it does NOT authorize an Android text draw-swap).

## Amendment — S4d-18 (2026-06-18): Desktop production font location + Desktop perceptual gate
- **Desktop production font location:** the bundled Latin+CJK fonts now ship as **`shared/src/desktopMain/resources/fonts/`** (the desktop production resource location), loaded via the Skiko byte-`Font` factory in `DesktopWatermarkTextRenderer` — still **no compose-resources / CMP-9547**. The S4d-16 desktop *test* copies were de-duplicated into this one location (the desktopTest helper loads `fonts/` from the classpath, which resolves to the main resources). The Android test copies (`app/src/androidTest/assets/fonts/`) are unchanged. A cross-platform shared/iOS font location + `expect/actual` loader remains deferred (S4d-16 note).
- **Desktop golden tier (first instance of ADR-0010's cross-platform perceptual tier):** `DesktopTextRendererGoldenTest` gates the Desktop renderer with a perceptual/stability signature (positive dims, visible ink, deterministic 8×8 quantized-ink signature across two renders, CJK ink density + differs-from-Latin), NOT exact host-font pixels — host Skia/Noto rasterization is not byte-portable. This is per-platform/Desktop-only and does not touch the Android strict tier.
- **Skiko fallback note:** on the Skiko desktop host, latin-first `FontFamily(latin, cjk)` per-glyph falls back to the CJK face (CJK renders real glyphs), so the Desktop renderer defaults latin-first. Does not change the Android decision (Android text stays native, S4d-17 Option C).

## Amendment — S4d-192 (2026-06-28): iOS renderer perceptual/stability gate
- **iOS golden tier (cell-level, font-robust):** `IosWatermarkRendererGoldenTest` gates the iOS Skiko renderer under `:shared:iosSimulatorArm64Test` with the same coarse 8x8 quantized-ink perceptual/stability signature shape used by the Desktop gate: positive dimensions, visible ink, at least one inked bucket, and deterministic signature across two independent renders. Coverage is Latin text, CJK text, multiline text, rotated-315 text, and a rotated non-uniform icon cell.
- **Different claim from Desktop:** the iOS test uses `FontFamily.Default` via the existing iOS renderer path, so it deliberately does **not** assert Desktop's bundled-font-specific properties (`CJK differs from Latin`, absolute CJK density). It catches blank/collapsed/nondeterministic iOS renderer regressions; it is not a byte-exact golden, not bundled-font packaging proof, and not Android renderer parity.

## Amendment — S4d-193 (2026-06-28): iOS composition perceptual/stability gate
- **iOS golden tier (composition-level, background-diff based):** the same `IosWatermarkRendererGoldenTest` now also gates `IosWatermarkRenderer.composeOverImage` and `composeIconOverImage` with a coarse 8x8 changed-pixel signature against a deterministic decoded background. This is necessary because composition output is opaque; a non-blank assertion cannot prove the watermark changed anything.
- **Coverage and claim:** tests cover text REPEAT, text CLAMP, REPEAT-vs-CLAMP changed-region difference, and icon composition. They assert background-sized output, nonzero changed regions, and deterministic signatures. This catches no-op/nondeterministic/collapsed composition, but remains a perceptual iOS Skiko guard: no byte-exact PNG baseline, no bundled-font assertion, no Android native composition parity claim, and no 1:1 UI/UX acceptance.
