# Compose UI Test (J4 slice)

- **Date:** 2026-09-12
- **Rollback HEAD:** `0edf9396425faebd9143a94168f3073a96ca0497` (recorded before this dep change)
- **CMP pin:** `composeMultiplatform = 1.12.0` (already in `gradle/libs.versions.toml`; not bumped)
- **Accessor:** Compose Multiplatform plugin `compose.uiTest` (rides the existing CMP pin). Requires `@OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)` (CMP marks `uiTest` experimental). No extra catalog library.
- **This is its own J4 slice.** No other catalog promotions.

## What was added

- `shared/src/uiTest` merged into `:shared:desktopTest` via `kotlin.srcDir("src/uiTest/kotlin")` plus `implementation(compose.uiTest)`.
- Same `src/uiTest/kotlin` merge onto `iosArm64Test` / `iosSimulatorArm64Test` (skikoTest `afterEvaluate` pattern) and `implementation(compose.uiTest)` on those test source sets.
- **Not** on Android host / `commonPureTest` (no ImageBitmap/Skiko on host JVM).
- `scripts/generate_testmap.py` treats the literal `"src/uiTest"` in `shared/build.gradle.kts` as desktop-runnable L1.

No okio test dependency was added; Coil 3 is `api` on commonMain and DataStore already uses okio, so okio is transitive for L1 file writes.
