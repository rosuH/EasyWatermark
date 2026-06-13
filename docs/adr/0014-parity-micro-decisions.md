# ADR-0014: Parity micro-decisions — palette kept, pinch stays off, quality snapping kept

**Status:** Accepted (2026-06-13), derived from ADR-0011

## Context
Several small open calls resolve automatically once production parity (ADR-0011) is the rule.

## Decision
- **Palette background color:** production has it → keep it. CMP path: kmpalette (`com.kmpalette:kmpalette-core:3.1.0`, direct androidx.palette port).
- **Pinch-to-scale of the watermark:** disabled in production (commented out in the View) → stays off. Re-enabling later is a feature ADR.
- **JPEG quality snapping** (min 20, multiples of 20): production behavior → keep through the rewrite.
- **Icon scaling filter:** production uses nearest-neighbor (`filter=false`) → pin `FilterQuality.None` in C2a for parity; any softening is a C2b re-baseline decision.

## Consequences
- Fewer open questions before C2a; deviations from any of these require updating this ADR.
