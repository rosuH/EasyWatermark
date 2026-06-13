# ADR-0009: EXIF stripping on export is a privacy feature

**Status:** Accepted (2026-06-13) · **Plan ref:** D9

## Context
The export path bakes orientation into pixels at decode and writes no metadata — GPS, timestamps, camera info are all stripped. This matches the app's privacy positioning and current production behavior (parity, ADR-0011).

## Decision
Strip-by-default is the documented contract on every platform. The iOS `PhotoLibraryStore` actual must verify PHPhotoLibrary doesn't silently re-attach metadata. Document the behavior user-facing (README/FAQ).

## Consequences
- Any future "preserve EXIF" option is a feature request with its own ADR, not a bug fix.
- Cross-platform golden/behavior tests assert absence of metadata in outputs.
