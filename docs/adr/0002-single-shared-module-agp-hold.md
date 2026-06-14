# ADR-0002: Single `:shared` module; hold AGP 8.x with a C4-gate re-check

**Status:** Accepted (2026-06-13) · **Plan ref:** D2

## Context
JetBrains recommends starting existing apps with one shared KMP module + thin platform entry points. AGP 9 forces `com.android.kotlin.multiplatform.library`, which is currently bitten by CMP-9547 (compose resources not packaged into the APK); AGP 10 (~H2 2026+) removes the legacy path entirely.

## Decision
One `:shared` module (commonMain/androidMain/iosMain/desktopMain) consumed by thin `:app`, `:desktopApp`, `iosApp/`. Stay on AGP 8.13.2 for now. **Immediately before the restructure (plan C4), re-check CMP-9547 and pick the module plugin ONCE** — AGP 9 stack if fixed, otherwise `com.android.library` + `kotlin-multiplatform` with a scheduled plugin migration.

## Consequences
- Avoids a double module-plugin migration if the bug is fixed in time (Risk R8/R17).
- KSP config names depend on this choice: plain `ksp` for Android on AGP 8.x, `kspAndroid` under the AGP 9 KMP plugin.
