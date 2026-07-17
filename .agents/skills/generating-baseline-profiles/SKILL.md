---
name: generating-baseline-profiles
description: Use this skill to generate and measure Jetpack Compose Baseline Profiles end-to-end with the AGP 8.2+ Baseline Profile Generator module and the Macrobenchmark harness. Covers writing the `BaselineProfileRule` journey for cold startup plus first-scroll, generating `baseline-prof.txt`, verifying it shipped at `assets/dexopt/baseline.prof`, measuring with `MacrobenchmarkRule` under `CompilationMode.Partial(BaselineProfileMode.Require)`, and emitting accurate time-to-fully-drawn via `ReportDrawn` / `ReportDrawnWhen` / `ReportDrawnAfter` from `androidx.activity.compose`. Compose ships unbundled, so every Compose UI app benefits — cited gains around 30% faster startup and 40% smoother first-scroll. Use when the user mentions "baseline profile", "macrobenchmark", "slow cold startup", "first-scroll jank", "StartupTimingMetric", "FrameTimingMetric", "ReportDrawn", "TTFD", or preparing a release build for performance measurement.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - baseline-profiles
  - macrobenchmark
  - startup
  - frame-timing
  - report-drawn
  - ttfd
  - aot-compilation
---

# Generating Baseline Profiles — ship the AOT compilation hint list and prove it moved the needle

Baseline Profiles ship an AOT compilation hint list inside the APK so ART pre-compiles hot Compose code paths on install instead of relying on JIT during the first runs. Cited gains: roughly 30% faster cold startup and 40% smoother first-scroll on the journeys that were profiled. Compose ships **unbundled** from the platform, so every Compose UI app benefits — there is no version of Android where Compose is already AOT-compiled by the system image.

This skill is the measurement spine for the rest of the performance work. Profiles are generated with `BaselineProfileRule` from `androidx.benchmark:benchmark-macro-junit4` and measured with `MacrobenchmarkRule` from the same artifact. Generation and measurement are two distinct `@Test` files in a separate `:baselineprofile` module that AGP 8.2+ scaffolds via **New Module → Baseline Profile Generator**.

## When to use this skill

- Cold startup is slow and the developer wants AOT-compiled hot paths on first launch.
- First-scroll on a `LazyColumn` / `LazyVerticalGrid` is janky on real devices even after stability fixes.
- Preparing a release build and the developer needs a perf baseline before shipping.
- Verifying that a stability or strong-skipping fix actually moved cold-startup or frame-timing numbers — Macrobenchmark is the ground truth.
- The user mentions "baseline profile", "macrobenchmark", "TTFD", "time-to-fully-drawn", "StartupTimingMetric", "FrameTimingMetric", "CompilationMode", `aosp_cf_x86_64_phone-userdebug`, or "ReportDrawn".

## When NOT to use this skill

- Still in early prototyping with no release-ready user journeys yet — Baseline Profiles encode hot paths, and there are no hot paths to encode while screens are still churning.
- The developer wants per-composable recomposition counts — that is `../../recomposition/debugging-recompositions/SKILL.md` (debug Layout Inspector) or `../tracing-recompositions-at-runtime/SKILL.md` (release `@TraceRecomposition`).
- The developer wants a CI gate against stability regressions — that is `../../stability/enforcing-stability-in-ci/SKILL.md`. Baseline Profiles measure runtime; `stabilityCheck` measures compile-time skippability.
- Numbers are being collected from a debug build — debug builds run interpreted with Live Literals and produce non-representative numbers. See `../testing-compose-in-release-mode/SKILL.md`.

## Prerequisites

- **AGP 8.2+** so the **New Module → Baseline Profile Generator** template is available (it scaffolds the `androidx.baselineprofile` Gradle plugin and a `:baselineprofile` module).
- A **physical low-end test device** or a Cuttlefish emulator (`aosp_cf_x86_64_phone-userdebug`). High-end pixel devices mask perf wins; emulators with default API images are not representative.
- A **release** variant configured (`isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt`). Debug builds are not measurable. See `../../build/configuring-r8-for-compose/SKILL.md` for R8 setup if missing.
- `<profileable android:shell="true"/>` in the app `AndroidManifest.xml` under `<application>` so the Macrobenchmark process can attach simpleperf. The `android:` namespace prefix is required — without it, manifest merger fails.
- At least one **scroll journey** identified beyond startup — a feed list, a paginated grid, opening a detail screen. Profiling startup alone leaves first-scroll uncompiled.
- Familiarity with `../../stability/diagnosing-compose-stability/SKILL.md` and `../../recomposition/debugging-recompositions/SKILL.md` if startup is dominated by avoidable recomposition rather than initial composition.

## Workflow

### 1. Add the Baseline Profile Generator module

In Android Studio: **File → New → New Module → Baseline Profile Generator**. Pick the target application module. AGP scaffolds:

- A new `:baselineprofile` module with the `androidx.baselineprofile` Gradle plugin applied.
- A `BaselineProfileGenerator.kt` skeleton with a `BaselineProfileRule` `@Test`.
- A `StartupBenchmarks.kt` skeleton with a `MacrobenchmarkRule` `@Test`.
- The `androidx.baselineprofile` plugin applied in the **app** module too, so `./gradlew :app:generateBaselineProfile` is wired up.

If editing manually instead of using the template, add to `:baselineprofile/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}
android { targetProjectPath = ":app" }
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.0")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
}
```

And to `:app/build.gradle.kts`:

```kotlin
plugins { id("androidx.baselineprofile") }
dependencies { baselineProfile(project(":baselineprofile")) }
```

See `references/macrobenchmark-harness.md` for the full Gradle + manifest setup including `<profileable>`.

### 2. Write the generator with both startup AND scroll

The generator is a `@Test` using `BaselineProfileRule.collect`. **MUST** cover startup plus at least one scroll — startup-only profiles leave first-scroll cold.

```kotlin
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupAndScroll() = rule.collect(packageName = "com.example") {
        startActivityAndWait()
        val feed = device.findObject(By.res("feed"))
        feed.setGestureMargin(device.displayWidth / 5)
        feed.fling(Direction.DOWN)
        feed.fling(Direction.DOWN)
        device.findObject(By.res("feed_item_0"))?.click()
        device.wait(Until.hasObject(By.res("detail")), 3_000)
        device.pressBack()
    }
}
```

Use `Modifier.testTag("feed")` in the Compose source so `By.res("feed")` resolves. A gesture margin of `displayWidth / 5` keeps flings off the system gesture area.

### 3. Generate the profile

```bash
./gradlew :app:generateBaselineProfile
```

For a specific variant: `./gradlew :app:generateReleaseBaselineProfile`.

Output lands at:

```
app/src/<variant>/generated/baselineProfiles/baseline-prof.txt
```

(e.g. `app/src/release/generated/baselineProfiles/baseline-prof.txt`. On older AGP layouts the file may instead land at `app/src/main/generated/baselineProfiles/baseline-prof.txt` — AGP writes the variant-specific path; merge to `main` for shipping if all variants share a profile.)

### 4. Verify the profile shipped in the APK

Build → **Analyze APK** on the release `.apk` / `.aab` and confirm:

```
assets/dexopt/baseline.prof
assets/dexopt/baseline.profm
```

If those files are missing, the profile was generated but not packaged — usually because `baselineProfile(project(":baselineprofile"))` was not added to the app module's `dependencies`. Without these files in the APK, ART has nothing to AOT-compile and the perf gain is zero.

### 5. Measure with Macrobenchmark — startup

A **separate** `@Test`, in the same `:baselineprofile` module, using `MacrobenchmarkRule`. The compilation mode must be `CompilationMode.Partial(BaselineProfileMode.Require)` so the test fails loudly if the profile is missing rather than silently measuring an unprofiled build.

```kotlin
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun startupCompilationBaselineProfiles() = rule.measureRepeated(
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

For an A/B comparison, add a sibling `@Test` with `compilationMode = CompilationMode.None` and compare medians. This is the only way to **prove** the profile moved the number.

### 6. Measure with Macrobenchmark — scroll

```kotlin
@Test
fun feedScrollPerformance() = rule.measureRepeated(
    packageName = "com.example",
    metrics = listOf(FrameTimingMetric()),
    iterations = 5,
    compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
) {
    startActivityAndWait()
    val feed = device.findObject(By.res("feed"))
    feed.setGestureMargin(device.displayWidth / 5)
    feed.fling(Direction.DOWN)
    feed.fling(Direction.DOWN)
}
```

`FrameTimingMetric` reports `frameDurationCpuMs` percentiles (P50 / P90 / P95 / P99) and `frameOverrunMs` (negative = on time, positive = missed deadline). The headline number is **P95 `frameOverrunMs`** — the worst-case missed-deadline frame in the high tail of a scroll.

### 7. Wire ReportDrawn for accurate TTFD

`StartupTimingMetric` reports `timeToInitialDisplay` by default. For `timeToFullDisplay` (TTFD) — the number that actually matches user perception — the app must call `ReportDrawn` once the first meaningful screen state is rendered. Without it, TTFD falls back to `timeToInitialDisplay` and undercounts.

```kotlin
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.ReportDrawnAfter

@Composable
fun Feed(state: FeedState) {
    ReportDrawnWhen { state.items.isNotEmpty() }
    LazyColumn { items(state.items, key = { it.id }) { SnackRow(it) } }
}
```

- `ReportDrawn` — fire immediately on composition (use when the first frame is the meaningful frame).
- `ReportDrawnWhen { predicate }` — fire when the predicate first turns true (typical case: state hydrated).
- `ReportDrawnAfter { suspend block }` — fire after the suspend block completes (typical case: an explicit `awaitFirstFrame()`).

### 8. Run on a real device, compare medians, iterate

Run on a connected physical device or `aosp_cf_x86_64_phone-userdebug` Cuttlefish via `./gradlew :baselineprofile:connectedReleaseAndroidTest`. **Report medians across iterations**, not means — Macrobenchmark logs both, and means are sensitive to a single thermal-throttled run. The IDE displays a result table; the JSON lives at `:baselineprofile/build/outputs/connected_android_test_additional_output/`.

See `references/macrobenchmark-harness.md` for parsing the JSON in CI.

## Patterns

### Pattern: profile startup AND scroll, never startup alone

```kotlin
// WRONG
@Test fun startup() = rule.collect(packageName = "com.example") {
    startActivityAndWait()
}
// WRONG because: only the startup code paths get AOT-compiled. First-scroll, list-item
// composition, and detail-screen entry stay interpreted on first run, which is exactly
// where the user perceives jank. Profile every hot user journey, not just launch.
```

```kotlin
// RIGHT
@Test fun startupAndScroll() = rule.collect(packageName = "com.example") {
    startActivityAndWait()
    val feed = device.findObject(By.res("feed"))
    feed.setGestureMargin(device.displayWidth / 5)
    feed.fling(Direction.DOWN)
    feed.fling(Direction.DOWN)
}
```

### Pattern: assert the profile is applied with BaselineProfileMode.Require

```kotlin
// WRONG
compilationMode = CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)
// WRONG because: UseIfAvailable silently falls back to no profile if assets/dexopt/baseline.prof
// is missing. The Macrobench then measures an unprofiled build and the developer thinks
// the profile is working when it is not. Use Require to fail loudly.
```

```kotlin
// RIGHT
compilationMode = CompilationMode.Partial(BaselineProfileMode.Require)
```

### Pattern: TTFD undercount when ReportDrawn is missing

```kotlin
// WRONG
@Composable
fun Feed(state: FeedState) {
    LazyColumn { items(state.items) { SnackRow(it) } }
}
// WRONG because: TTFD is reported when the first frame draws. An empty LazyColumn draws
// a frame too — TTFD then matches timeToInitialDisplay and undercounts the time the user
// actually waited for content. Add ReportDrawnWhen { state.items.isNotEmpty() }.
```

```kotlin
// RIGHT
@Composable
fun Feed(state: FeedState) {
    ReportDrawnWhen { state.items.isNotEmpty() }
    LazyColumn { items(state.items, key = { it.id }) { SnackRow(it) } }
}
```

### Pattern: never measure Compose perf in a debug build

```text
# WRONG
"./gradlew :app:installDebug && adb shell am start … && record startup with systrace"
# WRONG because: debug builds run Compose interpreted, with Live Literals turning constants
# into getters that defeat compile-time folding. Cold-start numbers are inflated 2–4×;
# scroll FrameTiming numbers are dominated by the interpreter overhead, not by the code.
# Measurement only counts on release + R8 + real device. Cross-link
# ../testing-compose-in-release-mode/SKILL.md.
```

```text
# RIGHT
"./gradlew :baselineprofile:connectedReleaseAndroidTest" on a physical device, with
release variant minified, baseline profile generated, BaselineProfileMode.Require asserting it.
```

### Pattern: report medians, not means, across enough iterations

```text
# WRONG
"Mean cold startup with profile: 412 ms (5 iterations)."
# WRONG because: a single thermal-throttled iteration drags the mean. Macrobenchmark
# reports min / median / max; the median is the resilient summary. 5 iterations is also
# thin for startup variance — use ≥10 for StartupTimingMetric, ≥5 for FrameTimingMetric.
```

```text
# RIGHT
"Median cold startup, 10 iterations: BaselineProfile 318 ms vs None 462 ms (–31%).
 P95 frameOverrunMs scrolling 30 items: BaselineProfile –6 ms vs None +9 ms."
```

### Pattern: gesture-margin so flings are not eaten by the system

```kotlin
// WRONG
val feed = device.findObject(By.res("feed"))
feed.fling(Direction.DOWN)
// WRONG because: on gesture-nav devices, a fling that starts inside the system gesture area
// is intercepted as a back-swipe or recents-swipe and the scroll never happens.
// Macrobenchmark then measures an idle screen and reports artificially-good numbers.
```

```kotlin
// RIGHT
val feed = device.findObject(By.res("feed"))
feed.setGestureMargin(device.displayWidth / 5)
feed.fling(Direction.DOWN)
```

## Mandatory rules

- **MUST** generate Baseline Profiles for both startup AND at least one scroll journey. Startup-only profiles leave first-scroll cold.
- **MUST** measure on a real **low-end physical device** or `aosp_cf_x86_64_phone-userdebug` Cuttlefish. High-end devices mask the perf delta; default API emulator images are not representative.
- **MUST** use `CompilationMode.Partial(BaselineProfileMode.Require)` in the measurement test so a missing or stale profile fails the test loudly. `BaselineProfileMode.UseIfAvailable` silently measures an unprofiled build.
- **MUST** use `ReportDrawn` / `ReportDrawnWhen` / `ReportDrawnAfter` from `androidx.activity.compose` to mark the meaningful first-drawn moment. Without it `timeToFullDisplay` undercounts.
- **MUST NOT** measure Compose perf in debug builds — debug runs interpreted with Live Literals, the numbers are not representative. Cross-link `../testing-compose-in-release-mode/SKILL.md`.
- **MUST** verify the profile shipped in the APK: `assets/dexopt/baseline.prof` must exist after `./gradlew :app:assembleRelease`. If absent, the `baselineProfile(project(":baselineprofile"))` wiring on the app module is missing.
- **MUST** report **medians**, not means, across iterations. Means are sensitive to a single thermal-throttled run.
- **PREFERRED:** ≥10 iterations for `StartupTimingMetric`, ≥5 for `FrameTimingMetric`.
- **PREFERRED:** keep an A/B sibling test pinned to `CompilationMode.None` so every PR can prove the profile is still moving the number.
- **PREFERRED:** add `Modifier.testTag("feed")` (or whatever ID the journey uses) in the Compose source rather than relying on text matchers — text changes with localization, test tags do not.

## Verification

- [ ] `:baselineprofile` module exists with the `androidx.baselineprofile` plugin applied.
- [ ] `:app/build.gradle.kts` has `baselineProfile(project(":baselineprofile"))` in dependencies.
- [ ] `./gradlew :app:generateBaselineProfile` produces `baseline-prof.txt` under `app/src/<variant>/generated/baselineProfiles/` (or `app/src/main/generated/baselineProfiles/`).
- [ ] Build → Analyze APK on the release artifact shows `assets/dexopt/baseline.prof` and `assets/dexopt/baseline.profm`.
- [ ] Generator `@Test` covers cold startup AND at least one scroll fling.
- [ ] Measurement `@Test` uses `CompilationMode.Partial(BaselineProfileMode.Require)`.
- [ ] `ReportDrawn` / `ReportDrawnWhen` / `ReportDrawnAfter` is invoked from a composable that renders only when the screen is meaningfully complete.
- [ ] Macrobench results — median across ≥10 iterations for startup, ≥5 for scroll — show a measurable delta vs `CompilationMode.None`.
- [ ] Measurement run is on a physical low-end device or `aosp_cf_x86_64_phone-userdebug`, not a stock API emulator.
- [ ] Debug-build numbers are not used as evidence anywhere in the report.

## References

- Baseline Profiles overview — https://developer.android.com/topic/performance/baselineprofiles/overview
- Baseline Profiles with Compose — https://developer.android.com/develop/ui/compose/performance/baseline-profiles
- Benchmark Baseline Profiles with Macrobenchmark — https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- Macrobenchmark library — https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview
- `androidx.activity.compose.ReportDrawn` reference — https://developer.android.com/reference/kotlin/androidx/activity/compose/package-summary
- Ben Trengrove, "Why you should always test Compose performance in release" — https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- skydoves, "Improving Android App Performance with Baseline Profiles" (Stream blog) — https://getstream.io/blog/android-baseline-profile/
- Compose performance overview — https://developer.android.com/develop/ui/compose/performance
- `references/macrobenchmark-harness.md` — full Gradle + manifest harness setup, the metric catalogue (`StartupTimingMetric`, `FrameTimingMetric`, `TraceSectionMetric`, `MemoryUsageMetric`, `PowerMetric`), and how to parse the JSON output for CI dashboards.

For confirming that a generated profile actually moved the recomposition count of a specific composable in release, see `../tracing-recompositions-at-runtime/SKILL.md`. For why debug numbers are not measurement evidence, see `../testing-compose-in-release-mode/SKILL.md`. For the build-time R8 setup that release measurement assumes, see `../../build/configuring-r8-for-compose/SKILL.md`. For preventing stability regressions between profile-generation runs, see `../../stability/enforcing-stability-in-ci/SKILL.md`.
