# Macrobenchmark harness reference

Full Gradle, manifest, and metric setup for the `:baselineprofile` module. Pulled out of the parent `SKILL.md` so the skill body stays under the 500-line ceiling.

---

## 1. Gradle wiring

### 1.1 `:baselineprofile/build.gradle.kts`

```kotlin
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.example.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    buildTypes {
        // The "benchmark" build type is what AGP runs against. It is a
        // release-equivalent build type with debug-friendly attributes.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.0")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
}

androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)?.applicationId }
        )
    }
}
```

### 1.2 `:app/build.gradle.kts` additions

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

android {
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Mirror the :baselineprofile module's "benchmark" type so AGP can match.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            proguardFiles("benchmark-rules.pro")
        }
    }
}

dependencies {
    baselineProfile(project(":baselineprofile"))
}

baselineProfile {
    // Run only the baseline profile generator instrumentation tests on the
    // benchmark variant; skip them on debug builds.
    automaticGenerationDuringBuild = false
}
```

### 1.3 Settings & version catalog

```kotlin
// gradle/libs.versions.toml
[versions]
benchmarkMacroJunit4 = "1.3.0"
uiautomator = "2.3.0"
baselineprofile = "1.3.0"

[plugins]
androidx-baselineprofile = { id = "androidx.baselineprofile", version.ref = "baselineprofile" }
```

---

## 2. Manifest — `<profileable android:shell="true"/>`

Macrobenchmark attaches `simpleperf` and `perfetto` to the target app process. That requires the app to opt in via `<profileable>` under `<application>`:

```xml
<!-- :app/src/main/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <!-- … -->
        <profileable
            android:shell="true"
            tools:targetApi="29" />
    </application>
</manifest>
```

Required on API 29+ to expose perf counters to non-system processes. Without this the Macrobench JSON is missing trace sections and `FrameTimingMetric` values come back empty.

---

## 3. BenchmarkRule vs MacrobenchmarkRule

`androidx.benchmark:benchmark-junit4` ships **two** rule classes; do not confuse them:

| Rule | Module | Scope | Use it for |
|---|---|---|---|
| `BenchmarkRule` | `benchmark-junit4` (microbenchmark) | In-process, single JVM | Pure-Kotlin / pure-Compose-runtime hot loops (e.g. measuring a `derivedStateOf` calculation) where there is no Activity. |
| `MacrobenchmarkRule` | `benchmark-macro-junit4` | Out-of-process, instruments a real APK | Cold/warm/hot startup, scroll, navigation — anything user-perceived. |

Baseline Profiles only make sense with `MacrobenchmarkRule`. `BenchmarkRule` is for surgical microbenchmarks of an isolated function and never measures startup or AOT compilation effects.

---

## 4. Metric catalogue

`metrics = listOf(...)` accepts any combination of these. Pick the smallest set that answers the question — every extra metric adds trace overhead.

### 4.1 `StartupTimingMetric`

Reports cold/warm/hot startup. Two values per iteration:

- **`timeToInitialDisplay`** — first frame drawn after process start. Always reported.
- **`timeToFullDisplay`** — fires when the app calls `ReportDrawn` / `ReportDrawnWhen` / `ReportDrawnAfter`. Falls back to `timeToInitialDisplay` if the app never calls it.

Pair with `startupMode = StartupMode.COLD` for the cold-start number; `WARM` and `HOT` exist but cold is the public-facing one.

```kotlin
metrics = listOf(StartupTimingMetric())
startupMode = StartupMode.COLD
```

### 4.2 `FrameTimingMetric`

Per-frame stats over the measured block. Reports four values:

- `frameDurationCpuMs` — CPU time spent on the frame.
- `frameOverrunMs` — negative is on time, positive is missed deadline.
- `frameCount` — how many frames the block produced.
- All percentiles: P50 / P90 / P95 / P99.

The headline number is **P95 `frameOverrunMs`**. Use it to track scroll smoothness over time.

```kotlin
metrics = listOf(FrameTimingMetric())
```

### 4.3 `TraceSectionMetric`

Surgical: measures a single named `Trace.beginSection` block in the app code. Use when an animation tick or a specific composable's first composition needs measurement.

```kotlin
metrics = listOf(
    TraceSectionMetric("Feed:firstComposition", mode = TraceSectionMetric.Mode.First)
)
```

In the Compose source:

```kotlin
import androidx.tracing.trace

@Composable
fun Feed(state: FeedState) {
    trace("Feed:firstComposition") {
        LazyColumn { /* ... */ }
    }
}
```

Modes: `First` (first occurrence only; reports nothing if the section is absent), `Sum` (total time across all matches), `Min`, `Max`, `Count` (number of times the section ran), `Average`.

### 4.4 `MemoryUsageMetric`

Heap and native memory snapshots at end-of-iteration. Useful for catching leak regressions but noisy iteration-to-iteration; report only over many iterations.

```kotlin
metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Last))
```

Modes: `Last` (snapshot after the block), `Max` (max during the block).

### 4.5 `PowerMetric`

CPU / GPU / display power draw. Requires a Pixel 6 or later for the on-device power monitor; emulators report nothing. Use for battery-regression dashboards, not for one-off measurement.

```kotlin
metrics = listOf(
    PowerMetric(PowerMetric.Type.Battery())
)
```

---

## 5. CompilationMode matrix

| Mode | What it does | When |
|---|---|---|
| `CompilationMode.None` | Wipes any AOT/JIT compiled code; runs interpreted. | A/B sibling against a profiled run, to prove the profile moved the number. |
| `CompilationMode.Partial(BaselineProfileMode.Require)` | Installs the profile from `assets/dexopt/baseline.prof` and **fails the test** if the profile is missing. | Default for measurement. |
| `CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)` | Installs the profile if present, falls back to none silently. | Avoid — silent fallback hides bugs. |
| `CompilationMode.Partial(warmupIterations = N)` | Runs the test `N` times to let JIT warm up before measuring. | Approximating "what does this look like after the user has used the app for a while". |
| `CompilationMode.Full` | Compiles every method. | Upper-bound reference number. Not what the user actually experiences. |
| `CompilationMode.Ignore` | Don't touch compilation state between iterations. | Almost never. |

For Baseline-Profile work, only `Partial(BaselineProfileMode.Require)` and `None` matter. The first measures the shipped behaviour; the second proves the delta.

---

## 6. Output JSON — parsing for CI

Macrobench writes per-test JSON to:

```
:baselineprofile/build/outputs/connected_android_test_additional_output/<variant>/<deviceSerial>/
    <ClassName>_<methodName>-benchmarkData.json
```

Sample shape (truncated):

```json
{
  "context": {
    "build": { "device": "redfin", "fingerprint": "google/redfin/redfin:14/UD1A.230803.041/…" },
    "compilationMode": "Partial(baselineProfile=Require, warmupIterations=0)"
  },
  "benchmarks": [
    {
      "name": "startupCompilationBaselineProfiles",
      "className": "com.example.baselineprofile.StartupBenchmarks",
      "totalRunTimeNs": 12_345_678_900,
      "metrics": {
        "timeToInitialDisplayMs": {
          "minimum": 312.4,
          "maximum": 387.1,
          "median": 318.7,
          "runs": [318.7, 312.4, 320.1, 325.3, 387.1, 318.5, 319.0, 318.8, 321.2, 318.9]
        },
        "timeToFullDisplayMs": {
          "minimum": 412.0,
          "maximum": 512.3,
          "median": 421.6,
          "runs": [...]
        }
      },
      "warmupIterations": 0,
      "repeatIterations": 10
    }
  ]
}
```

Minimal CI parser — fail the build if `median` exceeds a threshold:

```bash
#!/usr/bin/env bash
set -euo pipefail

JSON="$1"
THRESHOLD_MS="${2:-400}"

median=$(jq '.benchmarks[0].metrics.timeToInitialDisplayMs.median' "$JSON")
echo "median timeToInitialDisplayMs = ${median}"
awk -v m="$median" -v t="$THRESHOLD_MS" 'BEGIN { exit (m <= t) ? 0 : 1 }'
```

For tracking deltas across PRs, store the JSON as a CI artifact and diff `median` values across the base ref and the PR ref. The point is not the absolute number; the point is whether the PR moved the median outside the noise band of past runs (typically ±5%).

---

## 7. Common harness mistakes

- **`<profileable>` missing** — Macrobench reports "Trace section not found" or empty `FrameTimingMetric`. Fix: add the manifest tag.
- **Both `:app` and `:baselineprofile` missing the `androidx.baselineprofile` plugin** — `./gradlew :app:generateBaselineProfile` is not registered. Fix: apply the plugin in both modules.
- **Test run on a debug variant** — `BaselineProfileMode.Require` fails because no profile was packaged. Fix: run against the `benchmark` (release-equivalent) variant.
- **`baselineProfile(project(":baselineprofile"))` missing on `:app`** — generation runs but the profile never ships in the APK. Fix: add the dependency.
- **Profile generated against the wrong package** — `BaselineProfileRule.collect(packageName = "...")` and `MacrobenchmarkRule.measureRepeated(packageName = "...")` must match the **release** application ID, including `applicationIdSuffix` if any.
- **`device.findObject(...)` returns null** — the test tag is on a debug-only composable, or `.testTag(...)` was added inside a `BasicText` whose semantics get merged with the parent. Fix: add `Modifier.semantics { testTagsAsResourceId = true }` on the root `LazyColumn` if needed, and verify `By.res(...)` resolves with `device.dumpWindowHierarchy(System.out)` once.
- **Flings eaten by gesture nav** — `feed.setGestureMargin(device.displayWidth / 5)` before `fling()`.
