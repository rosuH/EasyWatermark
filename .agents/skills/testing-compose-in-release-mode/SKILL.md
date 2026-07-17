---
name: testing-compose-in-release-mode
description: Use this skill to ensure Jetpack Compose performance numbers reflect production reality by measuring against a release variant with R8 enabled, Live Literals disabled, and Compose Compiler reports read from the release output directory. Covers why debug builds lie (interpreted Compose runtime, JIT warmup, Live Literals constant-getters), how to set up a release-with-symbols measurement build, and how to wire Macrobenchmark, Compose Compiler reports, Layout Inspector, simpleperf, and Android Studio Profiler against it. Cited result is roughly 75 percent startup gain and 60 percent frame-render gain debug to release. Use when the developer reports "slow startup", "jank", "dropped frames", "high recomposition count", or quotes timings from `assembleDebug`, Layout Inspector, or `CompilationMode.None`. Use when reviewing a perf bug, setting up a CI perf gate, or before filing a perf regression.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - release-mode
  - measurement
  - macrobenchmark
  - live-literals
  - r8
  - layout-inspector
  - compose-compiler-reports
  - profiling
---

# Testing Compose in Release Mode — Debug Builds Lie About Performance

Compose ships unbundled — its UI runtime is loaded from app code, runs interpreted in debug, and the Live Literals plugin turns every constant into a getter. Debug recomposition counts, debug startup numbers, and debug compiler reports are not what users experience. This skill teaches Claude how to set up a release-with-symbols build for honest measurement before any perf claim is made.

## When to use this skill

- The developer reports a perf number ("startup is 1.4s", "this composable recomposes 50 times per scroll") that came from a debug build, Layout Inspector against debug, or `CompilationMode.None`.
- The developer is about to file a perf bug, open a regression report, or post benchmark numbers in a PR.
- A CI perf gate is being designed (Macrobenchmark, baseline-profile diff, frame-timing budget).
- The developer asks "why are my benchmark numbers so much worse than what I see on Play Store?".
- Compose Compiler reports are about to be read — they MUST come from the release output directory or every conclusion is suspect.

## When NOT to use this skill

- Feature development before perf is in scope — debug iteration speed is fine while functionality is being built.
- Behavior testing (correctness, integration, instrumentation tests for screens) — that is separate from perf testing and stays in debug.
- Build configuration of R8 itself — see `../../build/configuring-r8-for-compose/SKILL.md`.
- Generating a Baseline Profile from a benchmark run — see `../generating-baseline-profiles/SKILL.md`.
- Reading individual lines of a Compose Compiler report — see `../../stability/diagnosing-compose-stability/SKILL.md`.

## Prerequisites

- A working Android project with a Compose UI.
- A release signing config. A debug-signed release-mode build is acceptable for measurement (use a `signingConfig signingConfigs.debug` on the release type), but unsigned release builds will not install.
- Kotlin 2.0+ with the `org.jetbrains.kotlin.plugin.compose` Gradle plugin.
- A real physical device to run on. Emulator and Cuttlefish numbers are not interchangeable with on-device numbers.
- Familiarity with Macrobenchmark and Baseline Profiles helps but is not required.

## Why debug lies — the four mechanisms

Surface these to the developer when they push back on "but my numbers feel real":

1. **Interpreted Compose runtime.** Compose UI is a regular dependency, not part of the platform. In debug, much of the runtime executes interpreted with JIT warmup happening across the first seconds of the app. R8 in release performs lambda grouping, strips `sourceInformation()` calls, devirtualizes `ComposerImpl`, and constant-folds composable args.
2. **Live Literals.** The Live Literals compiler plugin (on by default in debug for Android Studio's live edit) wraps every literal — `0.dp`, `"Hello"`, `Color.Red` — in a getter. The recomposer treats these as dynamic, so reports flag composables as taking unstable params even when source code only uses constants.
3. **No R8 / no full mode.** Even if R8 were enabled in debug it would be configured weakly. Without R8 full mode, lambda allocations stay, `sourceInformation` strings remain, and the cost-per-frame budget is bigger than what release ships.
4. **Layout Inspector approximations.** Layout Inspector recomposition counts are approximate — they snapshot at intervals and miss intermediate work. Live Literals can also inflate them. Debug-only counts are diagnostic, not authoritative.

Cited measurement: roughly **75 percent startup gain** and **60 percent frame-render gain** when switching debug to release with R8. Source: Ben Trengrove, "Why should you always test Compose performance in release" (Android Developers Medium).

## Workflow

- [ ] **1. Configure R8 on the release variant.** This is the foundation; without it the rest of the workflow is moot. See `../../build/configuring-r8-for-compose/SKILL.md` for the full keep-rule story. Minimum:

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Use debug signing if there is no release keystore yet — the build still installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
```

- [ ] **2. Confirm Live Literals is not enabled for the measured variant.** Live Literals is no longer present in the new Compose Compiler Gradle DSL (Kotlin 2.0+) — the `featureFlags` enum exposes only `IntrinsicRemember`, `OptimizeNonSkippingGroups`, `PausableComposition`, and `StrongSkipping`. On the legacy Compose Compiler 1.5.x extension the property was `liveLiterals`, marked deprecated. For measurement, build the **release** variant — Live Literals is off by default in release. If a separate "benchmark" build type is used (recommended — release minus signing constraints), it inherits the release defaults.

- [ ] **3. Read Compose Compiler reports from the release output directory.** Always. Cross-link `../../stability/diagnosing-compose-stability/SKILL.md` for how to interpret the reports.

```bash
./gradlew :app:assembleRelease -PcomposeCompilerReports=true
ls app/build/compose_compiler/
# app_release-classes.txt, app_release-composables.txt, app_release-composables.csv, app_release-module.json
```

- [ ] **4. For Macrobenchmark, target a release variant of the app under test, with `CompilationMode.Partial(BaselineProfileMode.Require)`.** The benchmark module itself stays its own variant; the **target** app must be release. Cross-link `../generating-baseline-profiles/SKILL.md`.

```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun startupRelease() = rule.measureRepeated(
        packageName = "com.example",
        metrics = listOf(StartupTimingMetric()),
        iterations = 10,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
    ) {
        pressHome()
        startActivityAndWait()
    }
}
```

- [ ] **5. Layout Inspector counts are approximate — confirm in release with `@TraceRecomposition`.** The Layout Inspector recomposition count surface is a debug-only convenience. For an authoritative count, use skydoves' `@TraceRecomposition` from `compose-stability-analyzer` against a release-with-symbols build. Cross-link `../tracing-recompositions-at-runtime/SKILL.md`.

- [ ] **6. For runtime profiling without instrumentation, use a release-with-debug-symbols build.** Add the following so `simpleperf` and the Android Studio Profiler can resolve **native (NDK)** frames in the release variant:

```kotlin
android {
    buildTypes {
        release {
            isMinifyEnabled = true
            // NDK / native symbol level only — controls .so debug symbol packaging.
            ndk { debugSymbolLevel = "FULL" }
        }
    }
}
```

`ndk { debugSymbolLevel = "FULL" }` controls **NDK / native** symbol packaging only; it does **not** affect Kotlin / Java frame readability. Kotlin frame readability comes from `mapping.txt`, which R8 always produces when `isMinifyEnabled = true`. Pair native profiling with R8 retrace (see `../../build/configuring-r8-for-compose/SKILL.md`) to map obfuscated Kotlin stacks back to source lines.

- [ ] **7. Pick the device.** PREFERRED order for benchmark runs:
  1. Physical low-end device (e.g. Pixel 4a, mid-range partner device) — this is what real users feel.
  2. Cuttlefish — predictable but not representative of GPU/thermal behavior.
  3. Emulator — only as a last resort, never for frame-timing budgets.

- [ ] **8. Report the variant + device + R8 status alongside any number.** A perf number without "release / R8 on / Pixel 6 / cold start" is unreviewable. Make this part of the bug-report template.

## Patterns

### Pattern: Reading compiler reports from debug

```bash
# WRONG
./gradlew :app:assembleDebug
cat app/build/compose_compiler/debug/app-composables.txt
# WRONG because: debug enables Live Literals which turns every constant into a getter, so reports show false-positive unstable params and inflate non-skippable counts.
```

```bash
# RIGHT
./gradlew :app:assembleRelease -PcomposeCompilerReports=true
cat app/build/compose_compiler/app_release-composables.txt
```

### Pattern: Macrobench against a debug target

```kotlin
// WRONG
rule.measureRepeated(
    packageName = "com.example",
    metrics = listOf(StartupTimingMetric()),
    iterations = 5,
    startupMode = StartupMode.COLD,
    compilationMode = CompilationMode.None,
)
// WRONG because: CompilationMode.None disables AOT compilation and the target app may also be the debug variant — interpreted Compose stack plus JIT warmup makes startup numbers 3-4x worse than what users see, and any regression diff is dominated by JIT noise.
```

```kotlin
// RIGHT
rule.measureRepeated(
    packageName = "com.example",
    metrics = listOf(StartupTimingMetric()),
    iterations = 10,
    startupMode = StartupMode.COLD,
    compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
)
// And the targetPackage points to the release variant of the app under test.
```

### Pattern: Believing debug Layout Inspector counts

```kotlin
// WRONG
// "Layout Inspector says ProductCard recomposes 50 times per scroll, so the bug is real
// and we need to mark every parameter @Stable."
// WRONG because: Layout Inspector counts are sampled and approximate; Live Literals can inflate them; the count may double or halve in release. Confirm with @TraceRecomposition or Macrobenchmark FrameTimingMetric in a release build before chasing a fix.
```

```kotlin
// RIGHT
// Step 1: Reproduce in release build with @TraceRecomposition on the suspect composable.
// Step 2: If the release-build trace still shows the count, then diagnose with
//         ../../stability/diagnosing-compose-stability/SKILL.md against the release reports.
// Step 3: Fix, then re-measure with FrameTimingMetric in Macrobenchmark.
@TraceRecomposition(traceStates = true)
@Composable
fun ProductCard(product: Product) { /* ... */ }
```

### Pattern: Measure the release variant — Live Literals is off there by default

```text
// WRONG
// "I ran my benchmark against the debug variant — Live Literals was on, so my recomposition
//  counts and frame timings were inflated, and I cannot trust the report."
// WRONG because: Live Literals is on in debug to support Android Studio's live edit; it wraps
// constants in getters that the recomposer treats as dynamic. Stability reports and recomposition
// counts from a debug build are not measurement evidence.
```

```kotlin
// RIGHT — measure release; Live Literals is off there by default in the new (Kotlin 2.0+)
// Compose Compiler Gradle DSL. The featureFlags enum exposes IntrinsicRemember,
// OptimizeNonSkippingGroups, PausableComposition, and StrongSkipping — there is no LiveLiterals
// flag to set. Just build the release variant and read the release reports:
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

### Pattern: Quoting a perf number without provenance

```text
// WRONG
// "Startup is 1420 ms after my change."
// WRONG because: no variant, no device, no compilation mode, no iteration count — unreviewable. The same code measures 460 ms in release with R8 + a baseline profile on a Pixel 6.
```

```text
// RIGHT
// "Startup TimeToInitialDisplay p50 = 460 ms (release, R8 on, baseline profile required,
//  Pixel 6 stock, cold start, 10 iterations, Macrobenchmark)."
```

## Mandatory rules

- **MUST** measure Compose performance in the release variant with R8 enabled on a real physical device. Numbers from debug, from `CompilationMode.None`, or from emulator-only runs are diagnostic at best.
- **MUST** read Compose Compiler reports from the release output directory (`build/compose_compiler/<module>_release-*`). Debug reports are corrupted by Live Literals.
- **MUST NOT** report perf numbers from a debug build without an explicit "debug build, treat as approximate" caveat in the same sentence.
- **MUST NOT** trust Live Literals' constant treatment for any measurement. Measure release — Live Literals is off by default in release in the new (Kotlin 2.0+) Compose Compiler DSL, and the property no longer exists as a feature flag to toggle.
- **MUST** quote variant + device + compilation mode + iteration count alongside any startup or frame-timing number.
- **MUST NOT** equate Layout Inspector recomposition counts (sampled, approximate, debug-only) with `@TraceRecomposition` counts (deterministic, runtime-instrumented). Use the latter for any conclusion.
- **PREFERRED:** physical low-end device > Cuttlefish > emulator for benchmark runs. The thermal and GPU envelope of a low-end device is what surfaces real jank.
- **PREFERRED:** keep `ndk { debugSymbolLevel = "FULL" }` on the release variant so simpleperf, the Android Studio Profiler, and crash retrace work without rebuilding.

## Verification

- [ ] The variant under measurement is `release` (or a release-derived build type), not `debug`.
- [ ] `isMinifyEnabled = true` and `proguard-android-optimize.txt` are present on that variant.
- [ ] The measurement variant is `release` (Live Literals is off by default there in the Kotlin 2.0+ Compose Compiler DSL — there is no `LiveLiterals` feature flag to toggle).
- [ ] Compose Compiler report files have the `_release` suffix in their names (`app_release-composables.txt`, etc.).
- [ ] Macrobenchmark uses `CompilationMode.Partial(BaselineProfileMode.Require)` and the target app installed for the run is the release variant.
- [ ] Reported numbers include variant, device, compilation mode, iteration count.
- [ ] If Layout Inspector was the source of a recomposition claim, the claim has been re-confirmed with `@TraceRecomposition` (or Macrobenchmark `FrameTimingMetric`) on a release build.

## References

- Android Developers — Performance overview: https://developer.android.com/develop/ui/compose/performance
- Android Developers — Why test perf in release (Ben Trengrove): https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- Android Developers — Compose Compiler release notes: https://developer.android.com/jetpack/androidx/releases/compose-compiler
- Android Developers — Baseline Profiles with Compose: https://developer.android.com/develop/ui/compose/performance/baseline-profiles
- Android Developers — Measure a Baseline Profile with Macrobenchmark: https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- Android Developers — Configure and troubleshoot R8 keep rules (2025): https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html
- Chris Banes — Composable Metrics: https://chrisbanes.me/posts/composable-metrics/
- skydoves — compose-stability-analyzer (`@TraceRecomposition`): https://github.com/skydoves/compose-stability-analyzer
- skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
