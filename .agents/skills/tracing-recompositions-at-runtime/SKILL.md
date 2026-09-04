---
name: tracing-recompositions-at-runtime
description: Use this skill to instrument a Jetpack Compose composable with `@TraceRecomposition` from `skydoves/compose-stability-analyzer` so per-recomposition diffs (which state or parameter changed, what value transition) print to logcat under the `Recomposition` tag. Works in release-with-debug-symbols builds where Android Studio Layout Inspector cannot reach, and feeds the IntelliJ / Android Studio plugin's live recomposition heatmap (green under 10, yellow 10–50, red 50+). Covers the Gradle plugin setup, the `ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)` runtime gate that keeps the instrumentation out of production, and the handoff to debug-time Layout Inspector and CI `stabilityCheck`. Use when the user mentions `@TraceRecomposition`, "trace recomposition", "compose-stability-analyzer", "recomposition logcat", "recomposition heatmap", "release-mode recomposition counts", or needs to confirm a stability fix in a release-like build.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - recomposition
  - trace-recomposition
  - compose-stability-analyzer
  - logcat
  - heatmap
  - runtime-tracing
---

# Tracing Recompositions at Runtime — `@TraceRecomposition`, logcat, and the live heatmap

Layout Inspector counts recompositions and surfaces Argument Change Reasons, but it works **only in debug**, where Live Literals and the interpreted Compose runtime inflate counts. `@TraceRecomposition` from `skydoves/compose-stability-analyzer` instruments a composable at compile time and emits per-recomposition diffs (which state changed, what value transition) to logcat under the `Recomposition` tag. The instrumentation works in any build the developer enables it for — including release-with-debug-symbols — and feeds the IntelliJ / Android Studio plugin's live recomposition heatmap.

This skill is the **release-mode complement** to `../../recomposition/debugging-recompositions/SKILL.md`. Layout Inspector is debug-only, fast to set up, and good for the first triage. `@TraceRecomposition` is the ground-truth confirmation: instrument the suspect composable, ship a release+R8 build of the dev APK, run the user journey, and read the per-recomposition log lines.

## When to use this skill

- A composable recomposes more than expected and Layout Inspector counts are inconclusive (the count differs between debug and release, or the suspect is an inline composable not covered by Layout Inspector).
- A stability or strong-skipping fix needs to be confirmed against a release-equivalent build before merging.
- A developer wants per-state-and-per-parameter change diffs printed inline rather than clicking through the Layout Inspector tree.
- A team wants to baseline a composable's recomposition count for an SLO ("PriceTicker recomposes ≤ once per price update; never per parent tick").
- The user mentions `@TraceRecomposition`, "trace recomposition", "compose-stability-analyzer", "recomposition logcat", "recomposition heatmap", or "release-mode recomposition".

## When NOT to use this skill

- The developer just wants recomposition counts in debug — Layout Inspector is faster to set up. See `../../recomposition/debugging-recompositions/SKILL.md`.
- The build is a **production release** with no diagnosis intent — the instrumentation must be gated off. See the runtime-toggle pattern below.
- The team needs a CI gate that fails on **future** stability regressions — runtime tracing is for diagnosis; gating is `../../stability/enforcing-stability-in-ci/SKILL.md` (`stabilityCheck`).
- The need is per-frame timing or end-to-end user-perceived perf, not recomposition counts — that is `../generating-baseline-profiles/SKILL.md` with `MacrobenchmarkRule` + `FrameTimingMetric`.

## Prerequisites

- The Gradle plugin `com.github.skydoves.compose.stability.analyzer` (latest, v0.7.3+) added to the module that owns the composables to instrument.
- An `Application` subclass declared in the manifest, so `ComposeStabilityAnalyzer.setEnabled(...)` can be called from `onCreate()`.
- Compose Compiler reachable from the same module — `org.jetbrains.kotlin.plugin.compose` applied (Kotlin 2.0+).
- A `BuildConfig` field or feature flag the runtime toggle can read. `BuildConfig.DEBUG` works; a custom `BuildConfig.ENABLE_RECOMPOSITION_TRACE` is preferred for production-style profiling builds.
- For the IntelliJ / Android Studio heatmap: the `compose-stability-analyzer` plugin installed from the JetBrains marketplace (or built locally from the GitHub repo).
- Familiarity with `../../recomposition/debugging-recompositions/SKILL.md` so the developer has already named the suspect composable in debug before reaching for runtime tracing.

## Workflow

### 1. Apply the Gradle plugin

In the module's `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.compose.stability.analyzer)
}
```

In `gradle/libs.versions.toml`:

```toml
[versions]
composeStabilityAnalyzer = "0.7.3"

[plugins]
compose-stability-analyzer = { id = "com.github.skydoves.compose.stability.analyzer", version.ref = "composeStabilityAnalyzer" }
```

### 2. Configure the analyzer

Same `build.gradle.kts`, alongside the plugins block:

```kotlin
composeStabilityAnalyzer {
    enabled.set(true) // compile-time switch; runtime toggle below gates emission
}
```

`enabled.set(true)` controls whether the compiler weaves in the instrumentation. With `enabled.set(false)` no `@TraceRecomposition` annotations have any effect. Leaving it on across all build types is fine — the runtime toggle is the actual production gate.

### 3. Annotate the composables to trace

```kotlin
import com.skydoves.compose.stability.runtime.TraceRecomposition

@TraceRecomposition(traceStates = true)
@Composable
fun PriceTicker(price: Price) {
    Text(price.formatted)
}
```

`traceStates = true` extends the diff to `mutableStateOf` reads inside the composable body, not just parameters. Start with `true` for first investigation; flip to `false` once the cause is known to keep logs compact.

### 4. Add the runtime toggle in `Application.onCreate()`

```kotlin
import com.skydoves.compose.stability.runtime.ComposeStabilityAnalyzer

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)
    }
}
```

Without this call, every annotated composable still emits to logcat — including in release. Gate the toggle behind `BuildConfig.DEBUG` or a custom `BuildConfig.ENABLE_RECOMPOSITION_TRACE` so the production APK is silent.

### 5. Reproduce the symptom and read logcat

```bash
adb logcat -s Recomposition:D
```

Sample output for an animated `PriceTicker` whose price changes from `99.0` to `99.5` (illustrative — exact log shape depends on the analyzer version):

```
D/Recomposition: [Recomposition #1] PriceTicker
D/Recomposition:   ├─ [param] price: Price changed (Price(99.0) → Price(99.5))
D/Recomposition: [Recomposition #2] PriceTicker
D/Recomposition:   ├─ [param] price: Price unchanged (skipped via strong-skipping equals)
```

The number after `#` is a per-instance counter — cumulative across the lifetime of the composable's restart scope. A composable that prints `[Recomposition #50]` while only being on screen for two seconds is the smoking gun.

### 6. Open the live heatmap in Android Studio (optional)

Install the **Compose Stability Analyzer** plugin from JetBrains Marketplace. With the plugin installed and the app running, the editor gutter next to each `@TraceRecomposition`-annotated composable shows a color-coded badge. Indicative thresholds (illustrative — see the plugin's settings panel for the current bands):

- **Green** — fewer than 10 recompositions in the current session.
- **Yellow** — 10 to 50 recompositions.
- **Red** — more than 50 recompositions.

Click the badge to jump to a side panel listing each `[Recomposition #N]` entry with its diff. The panel mirrors logcat but groups by composable instance so the developer can spot which `LazyColumn` row is misbehaving without scrolling logcat.

### 7. Chain back to the upstream fix

Runtime tracing names the composable and the changing parameter. The fix lives elsewhere:

- Param recomposes because the type is unstable → `../../stability/diagnosing-compose-stability/SKILL.md` and `../../stability/stabilizing-compose-types/SKILL.md`.
- Param recomposes because of a captured lambda or `Flow` → `../../recomposition/using-strong-skipping-correctly/SKILL.md` and `../../side-effects/collecting-flows-safely/SKILL.md`.
- State read happened in the wrong phase (Composition vs Layout vs Draw) → `../../recomposition/deferring-state-reads/SKILL.md`.
- Layout Inspector showed an Argument Change Reason status that needed to be acted on → `../../recomposition/debugging-recompositions/SKILL.md`.

Once the fix lands, re-run the trace; the post-fix logcat should show one initial `[Recomposition #1]` and no subsequent entries during the same scenario.

### 8. Gate against future regressions in CI

`@TraceRecomposition` is for diagnosis. Preventing the **next** regression is a CI concern: enable `stabilityCheck` in CI per `../../stability/enforcing-stability-in-ci/SKILL.md`, which fails the build when a previously-skippable composable becomes non-skippable.

## Patterns

### Pattern: annotate the suspect, run a release-with-debug build, read the diff

```kotlin
// RIGHT
@TraceRecomposition(traceStates = true)
@Composable
fun PriceTicker(price: Price) {
    Text(price.formatted)
}
```

Sample logcat output (illustrative — exact log shape depends on the analyzer version; the price changes once per second; the surrounding row recomposes once per parent tick):

```
D/Recomposition: [Recomposition #3] PriceTicker
D/Recomposition:   ├─ [param] price: Price changed (Price(99.0) → Price(99.5))
D/Recomposition: [Recomposition #4] PriceTicker
D/Recomposition:   ├─ [param] price: Price unchanged
D/Recomposition:   ├─ [reason] parent restart scope re-invoked; strong-skipping equals matched
```

The second entry is the desirable shape: parent ticked, the `equals()` guard fired, body skipped.

### Pattern: gate the runtime toggle on a build flag

```kotlin
// WRONG
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // ComposeStabilityAnalyzer.setEnabled(...) is never called.
        // Every annotated composable emits to logcat in every build, including release.
    }
}
// WRONG because: shipping with tracing enabled adds logcat I/O on every recomposition,
// which adds nontrivial overhead on hot composables (LazyColumn rows in particular)
// and pollutes user-installed-app logs on shared devices.
```

```kotlin
// RIGHT
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)
    }
}
```

For a release-with-debug-symbols profiling APK, prefer a dedicated flag over `BuildConfig.DEBUG`:

```kotlin
// RIGHT — explicit profiling flag, decoupled from debug
ComposeStabilityAnalyzer.setEnabled(BuildConfig.ENABLE_RECOMPOSITION_TRACE)
```

### Pattern: do not ship `@TraceRecomposition` annotations in production releases

```kotlin
// WRONG
// Production release with @TraceRecomposition still annotated on hot composables and
// ComposeStabilityAnalyzer.setEnabled(true) hard-coded in Application.
// WRONG because: logcat I/O on every recomposition adds nontrivial overhead on hot
// composables. The instrumentation also captures parameter values into log strings,
// which can leak PII if a composable receives a user model.
```

```kotlin
// RIGHT — annotation present, runtime gate keeps it dormant in release
@TraceRecomposition(traceStates = true)
@Composable
fun PriceTicker(price: Price) { Text(price.formatted) }

// Application:
ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG) // dormant in release
```

### Pattern: dial back `traceStates` once the cause is known

```kotlin
// First investigation — verbose
@TraceRecomposition(traceStates = true)
@Composable
fun Feed(state: FeedState) { /* ... */ }
```

```kotlin
// Cause identified, fix shipped, keep the annotation as a tripwire — quieter
@TraceRecomposition(traceStates = false)
@Composable
fun Feed(state: FeedState) { /* ... */ }
```

`traceStates = true` logs every `mutableStateOf` read transition inside the body. That is gold for first triage and noise once the cause is known. Toggling to `false` keeps the per-recomposition counter (still useful as a tripwire) without the per-state diff.

### Pattern: pair runtime tracing with the CI stability gate

`@TraceRecomposition` finds the regression a developer is chasing **right now**. It does nothing about the regression a teammate ships **next week**. Pair it with `stabilityCheck`:

```text
# RIGHT — both layers in place
- @TraceRecomposition annotates suspect composables; runtime toggle gated on BuildConfig.DEBUG.
- ./gradlew :app:stabilityCheck runs in CI per ../../stability/enforcing-stability-in-ci/SKILL.md.
- The .stability baseline updates only after a deliberate review.
```

```text
# WRONG — diagnosis without prevention
- @TraceRecomposition is the only mechanism in place.
- Next PR introduces an unstable type; nothing in CI catches it; the perf regression
  ships and is found again at runtime weeks later.
```

## Mandatory rules

- **MUST** gate `ComposeStabilityAnalyzer.setEnabled(...)` on a build config field (`BuildConfig.DEBUG` or a dedicated `BuildConfig.ENABLE_RECOMPOSITION_TRACE`) or a feature flag. Never hard-code `setEnabled(true)`.
- **MUST** combine runtime tracing with the `stabilityCheck` CI gate from `../../stability/enforcing-stability-in-ci/SKILL.md`. Runtime tracing is for diagnosis; CI gating prevents the next regression. One without the other is half a workflow.
- **MUST NOT** ship a production release with `@TraceRecomposition` instrumentation enabled. The annotation may remain on composables, but `ComposeStabilityAnalyzer.setEnabled(...)` MUST resolve to `false` in the production build.
- **MUST NOT** treat the `[Recomposition #N]` count as a hard SLO without a context (which scenario? which device? release or debug?). Track the count delta across a fixed scenario instead — "post-fix the price-ticker scenario emits 1 entry vs pre-fix 30".
- **MUST** name both the composable and the changing parameter when reporting a finding ("`PriceTicker` recomposes per parent tick because `price` is reported as Changed, but the `Price` data class is `@Immutable` and `equals()` should match"). "It recomposes a lot" is not a finding.
- **PREFERRED:** start with `traceStates = true` for first investigation (richer logs); set to `false` once the cause is known so the trace becomes a quieter tripwire.
- **PREFERRED:** install the IntelliJ / Android Studio plugin to get the gutter heatmap (illustrative bands: green <10, yellow 10–50, red 50+ — confirm against the plugin's current settings) — it surfaces which composable is the offender without grepping logcat.
- **PREFERRED:** keep `@TraceRecomposition` annotations on a small, deliberate set of composables (the screen's hot composables, the `LazyColumn` row composable). Annotating every composable defeats the signal-to-noise ratio of the heatmap.

## Verification

- [ ] `com.github.skydoves.compose.stability.analyzer` plugin applied to the module that owns the composables to instrument.
- [ ] `composeStabilityAnalyzer { enabled.set(true) }` configured.
- [ ] `Application.onCreate()` calls `ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)` (or another build-flag gate). The call is **not** hard-coded `true`.
- [ ] At least one composable annotated with `@TraceRecomposition(traceStates = true)`.
- [ ] `adb logcat -s Recomposition:D` prints `[Recomposition #N] <ComposableName>` lines while reproducing the scenario.
- [ ] Each emitted line names a parameter or state and reports `changed (oldValue → newValue)` or `unchanged`.
- [ ] In a release build (or with the gate flipped off), `adb logcat -s Recomposition:D` prints nothing — the instrumentation is dormant.
- [ ] CI stability gate (`./gradlew :app:stabilityCheck`) is configured per `../../stability/enforcing-stability-in-ci/SKILL.md` so the next regression is caught before it ships.

## References

- `skydoves/compose-stability-analyzer` (Gradle plugin + runtime + IDE plugin) — https://github.com/skydoves/compose-stability-analyzer
- skydoves, "Optimize App Performance by Mastering Stability in Jetpack Compose" — https://medium.com/proandroiddev/optimize-app-performance-by-mastering-stability-in-jetpack-compose-69f40a8c785d
- skydoves, "6 Jetpack Compose Guidelines to Optimize App Performance" — https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- Ben Trengrove, "Jetpack Compose: Debugging recomposition" — https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Ben Trengrove, "Why you should always test Compose performance in release" — https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- Compose stability — diagnose — https://developer.android.com/develop/ui/compose/performance/stability/diagnose
- Compose performance overview — https://developer.android.com/develop/ui/compose/performance

For the debug-time entry point with Layout Inspector and Argument Change Reasons, see `../../recomposition/debugging-recompositions/SKILL.md`. For the CI gate that prevents the next stability regression, see `../../stability/enforcing-stability-in-ci/SKILL.md`. For acting on the cause once the offending parameter is named, see `../../stability/diagnosing-compose-stability/SKILL.md`, `../../stability/stabilizing-compose-types/SKILL.md`, `../../recomposition/using-strong-skipping-correctly/SKILL.md`, and `../../recomposition/deferring-state-reads/SKILL.md`. For end-to-end user-perceived perf measurement (frame timing, cold startup), see `../generating-baseline-profiles/SKILL.md`.
