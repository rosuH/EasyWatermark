# ADR-0010: Bundled watermark font; two-tier golden strategy; sRGB pin

**Status:** Accepted (2026-06-13) · **Plan ref:** D10, D4

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
