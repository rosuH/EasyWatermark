---
name: enforcing-stability-in-ci
description: Use this skill to set up a CI gate that fails the build when Compose stability silently regresses, using the `skydoves/compose-stability-analyzer` Gradle plugin (primary) or the `j-roskopf/ComposeGuard` plugin (alternative for non-Android multiplatform). Covers applying `com.github.skydoves.compose.stability.analyzer`, configuring the `composeStabilityAnalyzer { stabilityValidation { ... } }` DSL, generating a `.stability` baseline with `:stabilityDump`, committing it to version control, and wiring `:stabilityCheck` into a GitHub Actions or other CI job. Use when the team wants a stability SLO, when an unstable parameter slipped into a shared data class and went unnoticed until jank was reported, when migrating a large app to strong skipping, or when the user mentions stabilityCheck, stabilityDump, baseline drift, ComposeGuard, or "fail the build on stability regression".
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - stability
  - ci
  - gradle-plugin
  - stability-baseline
  - compose-stability-analyzer
  - composeguard
  - regression-prevention
  - github-actions
---

# Enforcing Stability in CI — make stability regressions fail the build

Stability is a property that silently regresses. A new `var` in a shared data class can disable skipping across dozens of screens, and nothing in the build output complains. Treat stability like binary compatibility: commit a baseline, diff it on every PR, fail the build when the diff goes the wrong way. This skill sets that gate up using the `skydoves/compose-stability-analyzer` Gradle plugin and points to `j-roskopf/ComposeGuard` for non-Android multiplatform projects.

## When to use this skill

- The team wants a stability service-level objective enforced in CI, not a manual check.
- A PR introduced an unstable parameter and nobody noticed until field reports of dropped frames came in.
- Migrating a large app to strong skipping and need a regression net while refactoring.
- Standardizing stability practice across a monorepo with many feature modules.
- The user mentions `stabilityCheck`, `stabilityDump`, baseline drift, ComposeGuard, or "fail the build on stability regression".

## When NOT to use this skill

- A single-developer throwaway prototype. The baseline maintenance cost is not worth it.
- Stability has not been diagnosed at all yet. Run `../diagnosing-compose-stability/SKILL.md` first to make sure the current state is acceptable; baselining a broken state just locks the brokenness in.
- The conceptual question is "why did the compiler classify X as Y?". Use `../understanding-stability-inference/SKILL.md`.

## Prerequisites

- Kotlin 2.0+ with the Compose compiler Gradle plugin (`org.jetbrains.kotlin.plugin.compose`). Strong skipping is default on.
- Gradle 8+.
- A working CI pipeline that already runs `./gradlew assemble` or equivalent.
- Compose Compiler reports already understood (read `../diagnosing-compose-stability/SKILL.md` first).
- Ability to commit generated files to the repository (the `.stability` baseline must be checked in).

## Workflow

### 1. Apply the plugin

Add the plugin to the version catalog and apply it to every module that ships UI composables.

```toml
# gradle/libs.versions.toml
[versions]
compose-stability-analyzer = "0.7.3"

[plugins]
compose-stability-analyzer = { id = "com.github.skydoves.compose.stability.analyzer", version.ref = "compose-stability-analyzer" }
```

```kotlin
// app/build.gradle.kts (and every UI feature module)
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.stability.analyzer)
}
```

The plugin must be applied **after** the Compose compiler plugin so it can read the same compiler output.

### 2. Configure the DSL block

```kotlin
// app/build.gradle.kts
composeStabilityAnalyzer {
    enabled.set(true)

    stabilityValidation {
        enabled.set(true)
        outputDir.set(layout.projectDirectory.dir("stability"))
        failOnStabilityChange.set(true)
        ignoredPackages.set(
            listOf(
                "com.example.preview",     // @Preview-only composables
                "com.example.benchmark",   // microbenchmark fixtures
            )
        )
        ignoredClasses.set(
            listOf(
                "com.example.testing.FakeRepository",
            )
        )
        stabilityConfigurationFiles.add(
            rootProject.layout.projectDirectory.file("stability_config.conf")
        )
    }
}
```

Key settings:

- `outputDir` — where `.stability` baseline files are written. Pick a path **inside** the module so it is naturally co-located with the source. Do not write to `build/`; that directory is gitignored and the baseline must be committed.
- `failOnStabilityChange` — set to `true` for CI. Locally, developers may toggle this to `false` for iteration speed, but the committed value must be `true`.
- `ignoredPackages` / `ignoredClasses` — exclude `@Preview` composables, benchmark fixtures, and test doubles that intentionally violate stability rules.
- `stabilityConfigurationFiles` — point at the same `stability_config.conf` the Compose compiler uses, so the plugin's verdict matches the compiler's.

### 3. Generate the baseline locally

```bash
./gradlew :app:stabilityDump
# or, for a specific variant:
./gradlew :app:debugStabilityDump
```

The plugin writes one `.stability` file per module per variant under `outputDir`. Commit them.

```bash
git add app/stability/
git commit -m "chore(stability): seed baseline"
```

A single `.stability` entry looks like this:

```text
@Composable
public fun com.example.CounterDisplay(count: com.example.MainViewModel): kotlin.Unit
  skippable: false
  restartable: true
  params:
    - count: RUNTIME (requires runtime check)
```

Each entry records the composable signature, `skippable` / `restartable` flags, and the per-parameter stability classification. The diff format is line-stable across runs, so PR reviewers see a meaningful change set.

### 4. Wire `stabilityCheck` into CI

Add a Gradle task invocation to the same job that compiles. Earlier signal beats a separate stage.

```yaml
# .github/workflows/ci.yml
name: CI
on:
  pull_request:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - name: Assemble and check stability
        run: ./gradlew assemble :app:stabilityCheck
      - name: Upload stability diff on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: stability-diff
          path: app/build/reports/stability/
```

`stabilityCheck` reads the committed baseline, regenerates the current snapshot in `build/`, diffs them, and fails the task if any composable became less skippable or any class became less stable. The PR check turns red and the developer sees exactly which composable regressed and why.

### 5. Handle a regression

When `stabilityCheck` fails, the task output names the regressed composables and the new unstable parameter or class. The developer has two valid responses:

- **Fix the regression.** Stabilize the offending type (`var` → `val`, `List` → `ImmutableList`, `Flow` parameter → `collectAsStateWithLifecycle` upstream). Re-run `:stabilityDump` locally to confirm the baseline is unchanged, then push.
- **Update the baseline with justification.** Some regressions are acceptable (e.g. an interop boundary that must accept a Java POJO). Run `:stabilityDump` to update the baseline, commit it, and add a note in the PR description explaining why the regression is intentional. Code review enforces the justification — the diff is plain text and reviewable.

Treat baseline updates the same way the team treats binary-compatibility baseline updates: visible in code review, requires explanation.

### 6. Tune ignores conservatively

`ignoredPackages` and `ignoredClasses` exist for legitimate exemptions: previews, benchmarks, test doubles, generated code. Do **not** use them to silence regressions in production code. Every ignore entry needs a comment explaining why; reviewers should reject ignore additions that lack a rationale.

```kotlin
// RIGHT
ignoredPackages.set(
    listOf(
        "com.example.preview",  // @Preview composables intentionally use mutable state for tooling
        "com.example.benchmark" // microbenchmark fixtures need fresh allocations per iteration
    )
)
```

```kotlin
// WRONG
ignoredPackages.set(listOf("com.example.feature.cart"))
// WRONG because: silencing an entire production package hides real regressions in cart.
// The team will rediscover the lost skipping as field jank weeks later.
```

### 7. Alternate: ComposeGuard for multiplatform / non-Android

If the project is Compose Multiplatform (desktop, iOS via KMP) or otherwise outside the Android Gradle plugin's reach, use `j-roskopf/ComposeGuard`. It implements the same idea (commit a baseline, diff in CI, fail on regression) and exposes `<variant>ComposeCompilerGenerate` and `<variant>ComposeCompilerCheck` tasks. Wire it the same way as `stabilityDump` / `stabilityCheck` above. The skydoves plugin is the primary recommendation for Android-only projects because it integrates with `@TraceRecomposition` for runtime correlation.

## Patterns

### Pattern: commit the baseline, diff it in PRs

```text
# RIGHT
app/stability/
  app-debug.stability   <-- committed
  app-release.stability <-- committed

# CI runs:
./gradlew :app:stabilityCheck   # diffs current vs committed baseline; fails on regression
```

```text
# WRONG
app/build/stability/    <-- in build/, gitignored, never committed
# WRONG because: every CI run starts from a fresh build/, the baseline does not exist,
# stabilityCheck either no-ops or always passes. The gate is theater.
```

### Pattern: failOnStabilityChange in CI, optional locally

```kotlin
// RIGHT — committed default
composeStabilityAnalyzer {
    stabilityValidation {
        failOnStabilityChange.set(true)
    }
}
```

```kotlin
// WRONG — committed default
composeStabilityAnalyzer {
    stabilityValidation {
        failOnStabilityChange.set(false)
    }
}
// WRONG because: turns the gate into an informational warning. Developers learn to ignore
// the warning. Stability regresses anyway. Set true and let CI block the merge.
```

Local override is fine via a Gradle property the developer can pass:

```kotlin
failOnStabilityChange.set(
    providers.gradleProperty("composeStabilityStrict").orNull?.toBoolean() ?: true
)
```

```bash
# Local fast iteration — opt out explicitly per invocation
./gradlew :app:stabilityCheck -PcomposeStabilityStrict=false
```

### Pattern: run stabilityCheck in the assemble job, not a separate one

```yaml
# RIGHT — earlier signal, single workflow run
- run: ./gradlew assemble :app:stabilityCheck
```

```yaml
# WRONG — separate jobs split signal
jobs:
  assemble:
    steps: [./gradlew assemble]
  stability:
    needs: assemble
    steps: [./gradlew :app:stabilityCheck]
```

```text
# WRONG because: the stability job re-resolves dependencies and re-runs Compose compilation
# from scratch, doubling CI time and delaying the failure signal. Same job, single Gradle daemon.
```

### Pattern: pair the CI gate with `@TraceRecomposition` at runtime

The plugin proves a class is *classified* stable. It does not prove the running app *actually* recomposes less. Pair the CI gate with runtime tracing (see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`) so the team has both signals: compile-time classification stability and runtime recomposition counts. A green CI gate with rising recomposition counts means a runtime-only invalidation source (e.g. a misplaced `mutableStateOf` read) that classification cannot catch.

## Mandatory rules

- **MUST** commit the `.stability` baseline file(s) to version control. A baseline that lives in `build/` is not a baseline.
- **MUST** set `failOnStabilityChange.set(true)` in the committed Gradle config so CI blocks merges on regression. Local opt-out via a Gradle property is acceptable; committing `false` is not.
- **MUST NOT** add a package or class to `ignoredPackages` / `ignoredClasses` without a one-line comment explaining why. Unjustified ignores are how stability silently rots back in.
- **MUST NOT** baseline a known-bad starting state and call the work done. Run `../diagnosing-compose-stability/SKILL.md` and `../stabilizing-compose-types/SKILL.md` first; baseline only what the team is willing to defend.
- **MUST** apply the plugin to **every** UI module, not just `:app`. A regression in a feature module is invisible to a `:app`-only check until the feature is wired into the main graph.
- **PREFERRED:** run `:stabilityCheck` in the same CI job as compile, behind `assemble`. Earlier signal, one Gradle daemon, half the wall-clock time.
- **PREFERRED:** when ComposeGuard is the better fit (KMP/desktop/iOS), use it — but match the workflow shape exactly: commit the baseline, fail the build on diff, justify ignores in code review.
- **PREFERRED:** point `stabilityConfigurationFiles` at the same `stability_config.conf` the Compose compiler reads, so the plugin and the compiler agree on classifications.

Stability is a contract, not a magic spell — and the CI gate is the contract enforcer. Skippability is a diagnostic, not a KPI: the goal is "no regressions from the baseline the team agreed on", not "100% skippable".

## Verification

- [ ] `composeStabilityAnalyzer` plugin block present in every UI module's `build.gradle.kts`.
- [ ] `app/stability/` (or the configured `outputDir`) is committed to the repo and contains at least one `.stability` file per shipping variant.
- [ ] `failOnStabilityChange.set(true)` is the committed default.
- [ ] CI workflow runs `./gradlew :app:stabilityCheck` (or the equivalent module path) on every pull request.
- [ ] Test the gate: temporarily change a `val` to `var` in a shared data class on a throwaway branch, push, and confirm the PR check turns red with the regressed composable named in the failure output. Revert the test change.
- [ ] Every entry in `ignoredPackages` and `ignoredClasses` has a comment explaining why it is exempt.
- [ ] When a developer runs `./gradlew :app:stabilityDump` locally, the only changes (if any) are in the `.stability` files and are reviewable as plain text.

## References

- skydoves/compose-stability-analyzer — https://github.com/skydoves/compose-stability-analyzer
- skydoves, "Optimize App Performance by Mastering Stability" — https://medium.com/proandroiddev/optimize-app-performance-by-mastering-stability-in-jetpack-compose-69f40a8c785d
- Compose stability overview — https://developer.android.com/develop/ui/compose/performance/stability
- Strong Skipping — https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Ben Trengrove, "New ways of optimizing stability in Jetpack Compose" — https://medium.com/androiddevelopers/new-ways-of-optimizing-stability-in-jetpack-compose-038106c283cc
- Chris Banes, "Composable metrics" — https://chrisbanes.me/posts/composable-metrics/
- Compose Compiler release notes — https://developer.android.com/jetpack/androidx/releases/compose-compiler
- ComposeGuard (alternative for KMP) — https://github.com/j-roskopf/ComposeGuard
- skydoves/compose-stability-inference — https://github.com/skydoves/compose-stability-inference

For runtime correlation of CI-green stability with actual recomposition counts, see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`. For the conceptual classification model that determines what `stabilityCheck` is checking, see `../understanding-stability-inference/SKILL.md`. For the upstream type fixes that resolve regressions surfaced by the gate, see `../stabilizing-compose-types/SKILL.md`.
