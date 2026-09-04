# Isolated Projects / Gradle 9.7 plugin compatibility (ROS-100)

Audit of every **Gradle plugin** and every **CMP/KMP version pin** against Isolated Projects, Configuration Cache, and the official Kotlin/AGP matrices. Runtime-only AndroidX libraries (Coil, Koin, MaterialKolor, DataStore, …) are not Isolated Projects actors.

## Stack

| Piece | Pin | Official “fully supported” | Verdict |
|---|---|---|---|
| Gradle | 9.7.0 | Kotlin 2.4.10 max **9.5.0** | Beyond the Kotlin table. Kotlin docs allow newer Gradle with possible deprecation warnings. AGP 9.3 **requires** Gradle ≥ 9.5; Isolated Projects incubating is 9.7. Keep 9.7. |
| AGP | 9.3.1 | Kotlin 2.4.10 max **9.1.0**; AGP 9.3 needs Gradle 9.5 | Beyond the Kotlin table, owner-authorized. Isolated Projects is part of the AGP 9.3 / Gradle 9.7 collaboration. Keep. |
| Kotlin / KMP / Compose compiler / serialization | 2.4.10 | Current stable. Do **not** take 2.4.20-Beta (no supported KSP pair). | KGP Isolated Projects declared since 2.1.20. `kotlin-multiplatform` is listed “Broken” only for **WASM/JS** ([KT-80311](https://youtrack.jetbrains.com/issue/KT-80311)). This repo targets Android / Desktop JVM / iOS only. |
| KSP | 2.3.11 | Isolated Projects Ready since 2.3.4 | 2.3.11 adds `org.gradle.isolated-projects` support. `ksp.project.isolation.enabled=true` is set explicitly. |
| CMP Gradle plugin | **1.12.0** (was 1.12.0-rc01) | Latest Maven Central stable (2026-08-25) | Aligns UI/foundation/runtime with Compose BOM `2026.08.00` (1.12.0). Material3 stays `1.12.0-alpha03`. Configuration Cache fixes are already in this line. |
| Room Gradle plugin | 2.8.4 | Isolated Projects fix landed in 2.7.0-rc02 | Applied on `:shared` only. Compatible. |
| compose-stability-analyzer | 0.13.0 | Isolated Projects since 0.13.0 | 0.12.0 failed ROS-100 (`:app` walked the foreign task graph). |
| Foojay toolchain resolver | 1.0.0 | Latest; required for Gradle 9 | Settings plugin. Compatible. |
| AndroidX Compose BOM | 2026.08.00 | UI 1.12.0 | Matches CMP 1.12.0. |
| JetBrains / androidx Material3 | 1.12.0-alpha03 / 1.5.0-alpha22 | CMP 1.12.0 published pair | Do not float to BOM stable 1.4.0. |
| Lifecycle (KMP) | 2.11.0 | CMP 1.12.0 published pair | Compatible. |
| Navigation Compose (Android) | 2.10.0-beta01 | Product minSdk 23; 2.10.0-rc01 is minSdk 24 | Library, not a Gradle plugin. Keep the beta pin. |
| Coil 3 | **3.6.0** (was 3.5.0) | CMP 1.12.0 / Kotlin 2.4.10 / Skiko 0.150.1 | 3.5.0 pulled Skiko 0.144.6 and tripped CMP's desktop compatibility check. |
| Koin BOM | 4.2.2 | KMP runtime | No Gradle plugin. |
| MaterialKolor | 5.0.0 | CMP Material3 consumer | No Gradle plugin. |
| DataStore | 1.3.0-alpha10 | KMP Preferences | No Gradle plugin. |
| androidx.sqlite bundled | 2.7.0 | Room off-Android driver | Desktop/iOS only. |
| Robolectric | 4.16.1 | Android host tests | No Gradle plugin. |
| Benchmark / ProfileInstaller | 1.5.0-alpha07 / 1.4.1 | AGP 9 test modules | Baseline Profile Isolated Projects work landed with Room 2.7. |

Unused catalog leftovers `toolsGradle=7.4.2` and `ktlintGradle=11.3.1` were removed. Neither was applied; both predate Gradle 9 / Isolated Projects.

## Not in this tree

- No Hilt / kapt (commented out).
- No Spotless / ktlint plugin (commented out; Spotless Isolated Projects is only a partial fix at 8.3.0).
- No wasm/js Kotlin targets (the published KMP Isolated Projects hole).

## Known hole: Windows MSI / WiX

CMP 1.12.0 `configureWix()` (Windows-only) writes the downloaded WiX toolset into `rootProject.layout.buildDirectory`. Isolated Projects forbids that cross-project `Project.layout` access, so `:desktopApp:packageDistributionForCurrentOS` fails at configuration on Windows while Linux DEB and macOS DMG stay green (master run [33823548228](https://github.com/rosuH/EasyWatermark/actions/runs/33823548228)).

The plugin returns early when `WIX_PATH` is a real directory. Desktop packaging CI already installs WiX via Chocolatey; it must export that bin dir as `WIX_PATH` (not only `PATH`). Local Windows packaging under Isolated Projects needs the same env var, or `--no-isolated-projects`.

Do not turn Isolated Projects off in `gradle.properties` for this. The hole is CMP's, not a reason to drop parallel configuration.

## Proof

Green under Isolated Projects on this agent (JDK 17, SDK 37):

```
./gradlew :shared:compileAndroidMain :shared:compileKotlinDesktop \
  :cmonet:compileDebugKotlin :app:assembleDebug :desktopApp:classes \
  :app:testDebugUnitTest :shared:desktopTest --isolated-projects
```

`BUILD SUCCESSFUL`, configuration cache stored. iOS compile stays macOS-only.
