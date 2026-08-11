# ADR-0014: Parity micro-decisions — palette kept, pinch stays off, quality snapping kept

**Status:** Accepted (2026-06-13), derived from ADR-0011 — palette bullet superseded by S4d-41 addendum (2026-06-22)

## Context
Several small open calls resolve automatically once production parity (ADR-0011) is the rule.

## Decision
- **Palette background color:** ~~production has it → keep it. CMP path: kmpalette (`com.kmpalette:kmpalette-core:3.1.0`, direct androidx.palette port).~~ **SUPERSEDED by S4d-41 — see addendum below.**
- **Pinch-to-scale of the watermark:** disabled in production (commented out in the View) → stays off. Re-enabling later is a feature ADR.
- **JPEG quality snapping** (min 20, multiples of 20): production behavior → keep through the rewrite.
- **Icon scaling filter:** production uses nearest-neighbor (`filter=false`) → pin `FilterQuality.None` in C2a for parity; any softening is a C2b re-baseline decision.

## Accepted (S3b / D1)

- **CJK text-cell height — `TextMeasurer` vs `StaticLayout`:** the commonMain `TextMeasurer` seam is
  byte-exact vs legacy `StaticLayout` for the covered Latin/emoji/bold/italic rows, but **CJK cell height
  grows** (width exact). Deterministic deltas (device-independent; held on SM-S906E and emulator-5554,
  both API 36): single-line +1/+2/+5px at 12/24/48f; two-line +4/+9/+18px. **Decision (ACCEPTED, S3b):**
  the Compose CJK line-height is the renderer baseline (Option 1 of the session-223154 product-decision
  matrix), under the ADR-0004 `textSize` re-spec + golden re-baseline — not fragile per-script compat
  tuning. **Wired into the product renderer** in S3b: `WatermarkRenderer.buildTextShader` measures the
  text-cell box via the `TextMeasureEnv`/`WatermarkTextMeasurer` seam (drawing stays legacy
  `StaticLayout`). Gated green by `WatermarkCellParityGateTest`: non-CJK strict legacy==seam; CJK exact
  width + exact signed delta + signed absolute baseline (no tolerance widening; delta logged, not
  hidden). Per the updated device policy (any available adb target), the gate's device-pinned absolute
  baselines were re-pinned to the S3b acceptance target **emulator-5554 / API 36** (CJK heights matched
  the earlier SM-S906E baseline exactly; only CJK/emoji glyph widths differed by a few px). S0 export
  golden: the two CJK entries (`cjk`, `cjk_multiline_315`) were re-baselined; non-CJK unchanged.

## Addendum (S4d-41, 2026-06-22) — palette dropped, not ported

The original "keep palette + port to kmpalette" decision was written against the production
release (v2.10.0), where the View stack generated a `Palette` from the source image and tinted
the editor background (`bgColor`/`titleTextColor`). The C3 readiness scan (S4d-40, Option B) found
that the **current Compose build never re-wired that feature**: there is no `Palette.from/.generate`
call, no `updateColorPalette` caller, and no `colorPalette`/`paletteFlow` consumer — the plumbing
(`PaletteKtx`, `MemorySettingRepo._palette/paletteFlow/updatePalette`, `MainViewModel.colorPalette`,
`EditorScreen.onBgReady`, the `androidx.palette:palette-ktx` dependency) was dead wiring.

**Decision (ACCEPTED, S4d-41, owner-approved):** drop the dormant `androidx.palette` feature rather
than port it. Removing dead, non-generating, non-consumed plumbing is **parity-neutral today** (no
visible behavior change — the tint already does not render in the Compose build), and it removes one
Android-only dependency from the C3 de-Androidization surface. **kmpalette is NOT added.** If the
image-tinted background is wanted back, it returns as a new feature ADR (re-introduce generation +
consumer + a KMP palette source), not as a silent restore.

This supersedes only the palette bullet of the original Decision; pinch-to-scale, JPEG quality
snapping, and the icon scaling filter decisions are unchanged.

> Related but separate: `:cmonet` Material You dynamic-color is **live** and is NOT touched here — it
> migrates later as the ADR-0007 `isDynamicColorAvailable()` capability (a device-gated slice).

## Addendum (2026-08-11) — content theme reopened under ADR-0027

S4d-41 correctly dropped **dormant** Palette plumbing. Product later reopened **photo-driven Editor
theming** as a deliberate feature: not a silent restore of `androidx.palette` bg-only chrome, but a
full **Content editor theme** (seed → M3 `ColorScheme` for the Editor session). See **ADR-0027**.
The “do not silent-restore Palette” rule still holds; implementation must follow 0027 (MaterialKolor /
MCU path, default on, photo priority in Editor).

## Consequences
- Fewer open questions before C2a; deviations from any of these require updating this ADR.
- The palette background-color feature is removed from the Compose build (S4d-41); reintroducing it is a new feature ADR.
