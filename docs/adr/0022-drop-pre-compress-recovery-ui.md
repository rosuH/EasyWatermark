# ADR-0022: Drop pre-compress recovery UI

**Status:** Accepted (2026-08-07)  
**Context slice:** Android export OOM recovery / Compose migration dead path  
**Related:** ADR-0011 (parity baseline), ADR-0014 (palette drop pattern)

## Context

Production v2.10.0 (`master`) offered a **pre-compress dialog** when export failed with
`TYPE_ERROR_SAVE_OOM`: toast + `CompressImageDialogFragment` → `MainViewModel.compressImg`
(using `id.zelory:compressor`). On `feat/migrate_to_compose`, the dialog host was never
migrated; only VM plumbing and strings remained. Export failures already surface via
`ExportRecoveryUi` (OutOfMemory / Io user copy) without a compress action.

Owner decision (2026-08-07): **drop** the feature rather than re-port the dialog.

## Decision

1. Remove `compressImg` / `compressedResult` / compress TYPE constants and the Compressor
   dependency from `:app`.
2. Remove EN default strings for compress dialog copy and OSS “Compressor” card when the
   library is gone. Non-default locales left for Weblate cleanup (agents do not hand-edit them).
3. **Keep** JPEG quality `compressLevel` (export preference) and `SAVE_OOM` / OutOfMemory
   recovery messaging — those are not the dialog feature.
4. Treat this as **intentional product shrinkage**, not an open parity P1.

## Consequences

- **Positive:** no dead recovery path; smaller APK surface; OOM UX is honest (retry / fewer images).
- **Trade-off:** users no longer get one-tap compress-after-OOM; must free space or pick smaller sources.
- **Docs:** UI parity matrix X-09 moves from gap → intentional drop.
