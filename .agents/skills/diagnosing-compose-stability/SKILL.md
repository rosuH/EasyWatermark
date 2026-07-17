---
name: diagnosing-compose-stability
description: Use this skill to diagnose Jetpack Compose stability problems by enabling and reading the Compose Compiler Reports (classes.txt, composables.txt, composables.csv, module.json). Covers the Gradle DSL, the release-only build requirement, and how to interpret per-class and per-composable stability annotations including stable, unstable, runtime, restartable, skippable, readonly, @static, and @dynamic markers. Use when the developer asks "why does this recompose", reports jank, dropped frames, slow scroll, high recomposition count, suspects an unstable parameter, mentions Compose Compiler Reports, classes.txt, composables.txt, module.json, or wants to know which composables are non-skippable. The fix lives in a sibling skill — this one only diagnoses.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - stability
  - compose-compiler-reports
  - recomposition
  - skippability
  - classes-txt
  - composables-txt
  - diagnostics
---

# Diagnosing Compose Stability — Read the Compiler Reports First

Compose skips recomposition by comparing parameters. When a parameter is unstable, skipping is disabled — this skill tells Claude how to find out which parameters are unstable and why. The output is a prioritized list of unstable types and non-skippable composables; the fix lives in `../stabilizing-compose-types/SKILL.md`.

## When to use this skill

- The developer asks "why does this recompose?", reports jank, dropped frames, or scroll stutter.
- A `@TraceRecomposition` log shows recomposition counts that exceed the number of meaningful state changes.
- The developer mentions Compose Compiler Reports, `classes.txt`, `composables.txt`, `composables.csv`, `module.json`, or "non-skippable".
- The developer asks how to find unstable parameters, or whether `List<Foo>`, `LocalDateTime`, or a domain type is stable.
- A reviewer asks for evidence that a perf-sensitive composable is skippable.

## When NOT to use this skill

- The unstable types are already known — jump straight to `../stabilizing-compose-types/SKILL.md`.
- The symptom is a wrong-phase state read (`Modifier.alpha(state.value)`); use `../../recomposition/deferring-state-reads/SKILL.md`.
- The symptom is a `derivedStateOf` misuse; use `../../recomposition/choosing-derivedstateof/SKILL.md`.
- The developer wants CI gating instead of one-shot diagnosis; use `../enforcing-stability-in-ci/SKILL.md`.

## Prerequisites

- Kotlin **2.0.0+** with the Compose Compiler Gradle plugin applied: `id("org.jetbrains.kotlin.plugin.compose")`. The pre-2.0 `kotlinCompilerExtensionVersion` flow is obsolete.
- A buildable **release** variant of the target module. Reports MUST be produced in release; debug adds Live Literals which makes constants look dynamic and skews every report.
- Apply the `org.jetbrains.kotlin.plugin.compose` Gradle plugin (Kotlin 2.0+). The `composeCompiler { … }` extension is owned by that plugin; AGP version is incidental.
- Optional but **PREFERRED:** the developer has a stability baseline goal, not a 100-percent-skippable goal (skydoves hot take #1 — skippability is a diagnostic, not a KPI).

## Workflow

- [ ] **1. Enable the reports in the module's `build.gradle.kts`.** Scope to release only — debug builds emit misleading data.

```kotlin
// app/build.gradle.kts (or any compose module)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

composeCompiler {
    // Only emit reports for release to avoid Live Literals noise.
    val isReleaseBuild = providers.gradleProperty("composeCompilerReports").orNull == "true"
    if (isReleaseBuild) {
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }
}
```

Or the always-on form:

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
    // Optional — opt mutable third-party types into stability:
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("stability_config.conf")
    )
}
```

- [ ] **2. Build the release variant** so Live Literals and interpreted-mode noise are absent:

```bash
./gradlew :app:assembleRelease -PcomposeCompilerReports=true
```

For a library module: `./gradlew :feature-feed:assembleRelease`. The release flavor is required — see RIGHT/WRONG below.

- [ ] **3. Locate the four output files.** They are written to `<module>/build/compose_compiler/`:

```
app/build/compose_compiler/
├── app_release-classes.txt        # per-class stability
├── app_release-composables.txt    # per-composable signatures
├── app_release-composables.csv    # CSV mirror of the above (CI-friendly)
└── app_release-module.json        # aggregate counts
```

If any file is missing, the plugin did not run for that variant — re-check that `composeCompiler { reportsDestination = ... }` is on the right module and that the build was release.

- [ ] **4. Open `composables.txt` first.** This is the highest-signal file. Search for `restartable` lines that are **not** followed by `skippable`. Each one is a recomposition entry point that cannot be skipped. Inside each block, read the per-parameter prefix: `stable`, `unstable`, `@static`, `@dynamic`. Any `unstable` parameter blocks skipping. See `references/reading-composables-txt.md` for the full grammar.

- [ ] **5. Open `classes.txt` to learn _why_ a class is unstable.** For every type flagged `unstable` in composables.txt, find its declaration in classes.txt. The line tells you whether a `var` field, a generic parameter, or an unstable nested type is the cause. The `runtime stable class Box { stable val value: T }` shape means "this class is stable iff the runtime `$stable: Int` field of the substituted T says so" — see `references/reading-classes-txt.md`.

- [ ] **6. Open `module.json` for triage numbers.** Counts of skippable composables, restartable composables, stable classes, etc. Use this to compare before/after a fix or to decide which module to attack first. **DO NOT** treat these counts as a target — they exist to spot regressions, not to chase 100 percent.

- [ ] **7. Prioritize by hot path.** A non-skippable composable that runs once at startup is irrelevant; one inside a `LazyColumn` item is critical. Cross-reference with measurement (`@TraceRecomposition` from skydoves/compose-stability-analyzer, or Macrobenchmark `FrameTimingMetric`) before fixing — see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`.

- [ ] **8. Hand off to the fix skill.** Produce a list of unstable types + offending composables and apply `../stabilizing-compose-types/SKILL.md`.

## Patterns

### Pattern: HighlightedSnacks — read a non-skippable composable

The compiler reports surface the cause directly inside the function signature. Walk the developer through this real shape.

```text
restartable scheme("[androidx.compose.ui.UiComposable]") fun HighlightedSnacks(
  stable index: Int,
  unstable snacks: List<Snack>,                  // <-- blocks skipping
  stable onSnackClick: Function1<Long, Unit>,
)
```

Diagnosis script:

1. The function is `restartable` but **NOT** prefixed with `skippable`. Therefore it always recomposes when its parent does.
2. The cause is the `unstable snacks: List<Snack>` parameter. `kotlin.collections.List` is an interface; the compiler cannot prove its implementations are immutable.
3. Open `classes.txt` and find `Snack`. If `Snack` itself is `unstable`, fix the data class first; if it is `stable` then only the `List` wrapper is the problem.
4. Hand off to the fix skill: replace `List<Snack>` with `kotlinx.collections.immutable.ImmutableList<Snack>`, or add `kotlin.collections.*` to `stability_config.conf` if the developer is comfortable with that contract.

### Pattern: "stable but runtime" — what `runtime` means

```text
runtime stable class Box {
  stable val value: T
}
```

This is **not** unstable. The compiler emits a synthetic `$stable: Int` field at runtime and queries it during composition. The class is stable iff the substituted `T` reports stable. **DO NOT** annotate `runtime` classes with `@Stable` to "promote" them — the runtime check is the correct mechanism.

### Pattern: WRONG vs RIGHT — running the build

```bash
# WRONG
./gradlew :app:assembleDebug
# WRONG because: debug enables Live Literals; constant 0 dp becomes a getter, every literal looks dynamic, and counts in module.json drift versus what ships to users.
```

```bash
# RIGHT
./gradlew :app:assembleRelease -PcomposeCompilerReports=true
```

### Pattern: WRONG vs RIGHT — reading the report file

```text
// WRONG — reading composables.txt without checking the leading flags
fun MyScreen(...)
// WRONG because: skipping the `restartable`/`skippable` prefix discards the only data point that determines whether unstable params actually cost anything.
```

```text
// RIGHT — every diagnosis quotes the full prefix and per-param annotations
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun MyScreen(
  stable user: User,
  stable onClick: Function0<Unit>,
)
```

### Pattern: empty output directory

If `build/compose_compiler/` is missing or empty after a release build:

1. Confirm the `org.jetbrains.kotlin.plugin.compose` plugin is applied to **this** module — the extension is per-module.
2. Confirm the build actually compiled Kotlin sources (not an up-to-date no-op). Touch a file or run `./gradlew :app:clean :app:assembleRelease`.
3. Confirm `reportsDestination` is set inside a `composeCompiler { }` block, not the legacy `kotlinOptions` freeCompilerArgs flow.

## Mandatory rules

- **MUST** build the **release** variant. Debug builds emit Live Literals which makes constants look dynamic and inflates the report's "unstable" surface area.
- **MUST** read `composables.txt` per-parameter annotations (`stable`/`unstable`/`@static`/`@dynamic`), not just the function name. The flag prefix is the diagnosis.
- **MUST** cross-reference unstable params back to `classes.txt` to identify the root cause (a `var`, a generic, an unstable field type, or an interface).
- **MUST NOT** chase 100 percent skippability. Skydoves hot take #1: skippability is a diagnostic, not a KPI. A composable that runs once at startup does not need to be skippable.
- **MUST NOT** annotate a class as `@Stable` based on a report alone — that decision belongs to the fix skill, which evaluates the contract.
- **MUST NOT** read `composables.csv` line counts and call it done. The counts are a regression sentinel; the per-line annotations are the actual diagnosis.
- **PREFERRED:** wire the same flags into CI via `../enforcing-stability-in-ci/SKILL.md` (skydoves `compose-stability-analyzer` plugin or the community `ComposeGuard` plugin) so regressions surface on PR review.
- **PREFERRED:** record a baseline `module.json` per release so future regressions are visible by diff.

## Verification

- [ ] `./gradlew :app:assembleRelease` (or module-specific) completes successfully with the `org.jetbrains.kotlin.plugin.compose` plugin applied.
- [ ] All four files exist: `<module>_release-classes.txt`, `<module>_release-composables.txt`, `<module>_release-composables.csv`, `<module>_release-module.json`.
- [ ] At least one of: a list of unstable parameter sites copied from `composables.txt`, OR a confirmed-zero count from `module.json` justifying that no fix is needed.
- [ ] Each unstable parameter has been traced back to a class in `classes.txt` so the fix skill receives a concrete root cause (a `var`, a `List`, a `LocalDateTime`, etc.).
- [ ] The developer understands that `runtime stable class …` is not a problem — it is the compiler's correct lazy-stability emission.

## References

- Android Developers — Diagnose stability: https://developer.android.com/develop/ui/compose/performance/stability/diagnose
- Android Developers — Stability overview: https://developer.android.com/develop/ui/compose/performance/stability
- Compose Compiler release notes: https://developer.android.com/jetpack/androidx/releases/compose-compiler
- Ben Trengrove — Jetpack Compose Stability Explained: https://medium.com/androiddevelopers/jetpack-compose-stability-explained-79c10db270c8
- Chris Banes — Composable Metrics: https://chrisbanes.me/posts/composable-metrics/
- Why test perf in release: https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- skydoves — Optimize App Performance by Mastering Stability: https://medium.com/proandroiddev/optimize-app-performance-by-mastering-stability-in-jetpack-compose-69f40a8c785d
- skydoves — compose-stability-analyzer: https://github.com/skydoves/compose-stability-analyzer
- `references/reading-classes-txt.md` — full grammar for classes.txt with worked examples (stable / unstable / runtime / generic).
- `references/reading-composables-txt.md` — full grammar for composables.txt including restartable, skippable, readonly, scheme, and per-parameter `stable` / `unstable` / `@static` / `@dynamic`.
