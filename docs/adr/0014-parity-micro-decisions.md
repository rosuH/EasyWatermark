# ADR-0014: Parity micro-decisions — palette kept, pinch stays off, quality snapping kept

**Status:** Accepted (2026-06-13), derived from ADR-0011

## Context
Several small open calls resolve automatically once production parity (ADR-0011) is the rule.

## Decision
- **Palette background color:** production has it → keep it. CMP path: kmpalette (`com.kmpalette:kmpalette-core:3.1.0`, direct androidx.palette port).
- **Pinch-to-scale of the watermark:** disabled in production (commented out in the View) → stays off. Re-enabling later is a feature ADR.
- **JPEG quality snapping** (min 20, multiples of 20): production behavior → keep through the rewrite.
- **Icon scaling filter:** production uses nearest-neighbor (`filter=false`) → pin `FilterQuality.None` in C2a for parity; any softening is a C2b re-baseline decision.

## Candidate (pending final C2b wiring sign-off)

- **CJK text-cell height — `TextMeasurer` vs `StaticLayout`:** the C2 commonMain `TextMeasurer` seam is
  byte-exact vs legacy `StaticLayout` for the covered Latin/emoji/bold/italic rows, but **CJK cell height
  grows** (width exact). Measured deterministically on device (SM-S906E / API 36): single-line +1/+2/+5px
  at 12/24/48f; two-line +4/+9/+18px. **Candidate decision:** accept the Compose CJK line-height as the
  C2b future-renderer baseline (Option 1 of the session-223154 product-decision matrix), under the
  ADR-0004 `textSize` re-spec + golden re-baseline — rather than fragile per-script line-metric compat
  tuning. Encoded as a green **signed-baseline** gate (`WatermarkCellParityGateTest`): non-CJK strict
  legacy parity; CJK exact width + exact signed delta/baseline (no tolerance widening; delta logged, not
  hidden). **Status: candidate pending final C2b wiring sign-off — not wired into the product
  renderer/export.** If the developer prefers legacy-exact CJK cells, the alternative is compat tuning
  (then the gate's CJK deltas become 0); record the choice here at C2b.

## Consequences
- Fewer open questions before C2a; deviations from any of these require updating this ADR.
