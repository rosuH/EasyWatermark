# ADR-0007: Platform-neutral model layer (TileMode, ImageFormat, MediaRef)

**Status:** Accepted (2026-06-13) · **Plan ref:** D7

## Context
Android types leak into the domain: `WaterMark.tileMode: Shader.TileMode` (persisted as android enum **ordinal** in DataStore), `iconUri`/`ImageInfo.uri: android.net.Uri`, `UserPreferences.outputFormat: Bitmap.CompressFormat`, `ViewInfo: Matrix+ScaleType`. Cross-enum ordinal equality is fragile and blocks commonMain.

## Decision
Introduce app-owned `TileMode` and `ImageFormat` enums with explicit ordinal-compatible mappers (+ DataStore migration for the persisted ordinal), a `MediaRef` value class for image identity, kotlinx-datetime for time. `ViewInfo` is deleted by ADR-0004 C2b, not ported. The recent `Bitmap.CompressFormat` standardization in `SaveExportSheet` was a deliberate stepping stone; it swaps to `ImageFormat` in ONE move (sheet + prefs + repo together, plan C3.5). `:cmonet` is replaced by an `isDynamicColorAvailable()` capability (Android actual keeps the OEM allowlist; iOS/Desktop return false; static color schemes in Theme.kt are the fallback).

## Consequences
- Models become movable to commonMain; persistence stays backward-compatible via the mappers.
- Parcelize survives only in androidMain if needed; nav args use `@Serializable` (ADR-0003).
