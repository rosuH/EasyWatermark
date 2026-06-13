# ADR-0001: Platform targets and order — Android → Desktop (validation) → iOS

**Status:** Accepted (2026-06-13) · **Plan ref:** D1

## Context
CMP iOS has been stable since 1.8.0, but every hard migration risk found in the readiness audit (photo-library stack, memory limits, share-in) is iOS-specific. Desktop (JVM) arrives nearly free with the KMP restructure and has no permission model.

## Decision
Target Android + Desktop + iOS. Bring-up order: Desktop first as the architecture-validation target (plan C4), iOS second with its own dedicated phase (C5). Web/Wasm is out of scope until after iOS ships.

## Consequences
- The shared engine is proven on two Skia platforms before App Store work begins.
- Whether desktop ships publicly (packaging/signing/notarization) is a separate decision: ADR-0013.
