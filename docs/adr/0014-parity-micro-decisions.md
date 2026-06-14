# ADR-0014: Parity micro-decisions — palette kept, pinch stays off, quality snapping kept

**Status:** Accepted (2026-06-13), derived from ADR-0011

## Context
Several small open calls resolve automatically once production parity (ADR-0011) is the rule.

## Decision
- **Palette background color:** production has it → keep it. CMP path: kmpalette (`com.kmpalette:kmpalette-core:3.1.0`, direct androidx.palette port).
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

## Consequences
- Fewer open questions before C2a; deviations from any of these require updating this ADR.
