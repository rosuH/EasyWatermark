# ADR-0021: iOS progressive photo import (path-first slots)

**Status:** Accepted (2026-08-07)
**Context slice:** iOS PhotosPicker latency / progressive editor fill
**Related:** ADR-0017 (Session owner), ADR-0020 (single-scene), ADR-0009 (EXIF strip on export)

## Context

The iOS editor previously waited for a whole-batch `Data` transfer and Kotlin
`ByteArray` staging before Session held any images. That leaves an empty preview
and filmstrip while iCloud/HEIC transfers run, duplicates decode work, and
retains N full payloads at once.

## Decision

1. **Path-first transfer:** Swift `FileRepresentation` copies each provider temp
   file into an app-owned `ewm_import_provisional_*` path before the transfer
   closure returns. Transfer concurrency is capped at **2**, with first-item
   priority.
2. **Presentation slots outside Session:** commonMain `EditorMediaSlot`
   (`Pending` / `Ready(ImageInfo)` / `Failed`) is UI-only. Session publishes
   **only** Ready `ImageInfo` paths (`ewm_src_*`) after adoption.
3. **Zero public Shared.framework growth:** progressive begin/ready/fail/
   finish/cancel/retry/remove/prioritize use `NotificationCenter` names shared
   with Swift. No new public `IosProductRootHost` progressive methods.
4. **Single-flight pixels:** `IosPreviewImageRepository` keys
   `(ownedPath, pixelBucket, purpose)`; joint budgets **40 MiB**
   source/preview and **8 MiB** filmstrip; waiter cancellation propagates.
5. **ImageIO path thumbnails:** metadata + orientation-aware native thumbnails
   for picker previews; final export remains full-resolution, sRGB, EXIF-stripped.
6. **Ownership:** Swift deletes provisional paths only after Host acknowledges
   successful Session publication. Superseded generations clean only their own
   provisional files. Host dispose tears down NC observers and cancels in-flight
   progressive work.

## Consequences

- **Positive:** stable editor geometry immediately; progressive fill; bounded
  concurrency/memory; no full-batch `List<ByteArray>` retention on the photo path.
- **Positive:** framework ABI stays stable (owner zero-public-growth decision).
- **Negative:** control plane is stringly NotificationCenter (documented names;
  behavioral tests required).
- **Residual:** physical iPhone Release + iCloud 1/8/50 witness is
  `DEVICE_GATE_PENDING` until owner authorizes device interaction.
- **Landed with:** filmstrip parity (single `EditorFilmstripScaffold`, Ready image-only,
  Pending loading animation, prefetch without global `filmstripThumbEpoch` bump) —
  ACSP `20260806-084724--ios-preview-list-parity` owner retest 2026-08-07.
- **Future:** multi-scene still requires scene-scoped Session (ADR-0020); do not
  process-share progressive Host observers across windows without a new design.
