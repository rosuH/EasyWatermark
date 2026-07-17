---
name: using-stability-analyzer-ide-plugin
description: Use this skill to install and operate the `skydoves/compose-stability-analyzer` IntelliJ / Android Studio plugin so the developer sees Compose stability feedback live in the editor instead of waiting for a Gradle build. Covers installing the plugin from disk, configuring the `Settings → Tools → Compose Stability Analyzer` panel, reading the four gutter colors (green stable, red unstable, yellow runtime, gray no-params), the per-parameter hover documentation, the inline parameter hint badges, and the `UnstableComposable` weak-warning inspection with its `@Suppress("NonSkippableComposable")` and `@Suppress("ParamsComparedByRef")` quick fixes. Use when the user mentions gutter icons, inline hints, the stability inspection, the IDEA plugin, real-time stability feedback while editing, or asks why a composable is flagged as non-skippable in the editor.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - stability
  - intellij-plugin
  - android-studio
  - gutter-icons
  - inline-hints
  - inspection
  - compose-stability-analyzer
  - editor-feedback
---

# Using the Stability Analyzer IDE Plugin — live in-editor stability feedback

The Compose Compiler reports tell the developer about stability after a build completes. The `skydoves/compose-stability-analyzer` IntelliJ plugin tells them while they edit. Gutter icons mark every `@Composable` with a color, hover docs render the per-parameter table with reasons, inline hints render colored badges next to each parameter type, and the `UnstableComposable` weak-warning inspection surfaces actionable problems with quick fixes.

The plugin uses the Kotlin K2 Analysis API to evaluate the same stability rules that drive `../understanding-stability-inference/SKILL.md`, so editor feedback agrees with what `../diagnosing-compose-stability/SKILL.md` would later show in the compiler reports. This shortens the loop from "ship → build → read reports" to "type → see the gutter".

## When to use this skill

- The developer wants real-time stability feedback while editing a Composable file.
- A reviewer is reading a PR locally and wants the inline signal next to each composable.
- A new joiner wants to see at a glance which composables in the file are non-skippable.
- The user asks why the editor flagged a function with a weak warning called `UnstableComposable`.
- The user mentions gutter icons, inline hints, the stability inspection, the IDEA plugin, the K2 Analysis API for Compose, or the `Compose Stability Analyzer` tool window.

## When NOT to use this skill

- CI gating that fails the build on stability regressions. Use `../enforcing-stability-in-ci/SKILL.md`.
- Build-time Compose Compiler reports analysis. Use `../diagnosing-compose-stability/SKILL.md`.
- Active runtime tracing or visual cascade investigation through the plugin's tool window. Use `../visualizing-recomposition-cascades/SKILL.md`.
- Conceptual questions about how the compiler classifies a type. Use `../understanding-stability-inference/SKILL.md`.

## Prerequisites

- Android Studio 2024.2+ or IntelliJ IDEA 2024.2+ (build range 242 through 261, covering 2024.2 through the 2026.1 EAP).
- Kotlin 2.3.0 in the IDE plugin runtime, required by the K2 Analysis API the plugin relies on.
- The Compose Compiler Gradle plugin already wired in the project (`org.jetbrains.kotlin.plugin.compose`). The analyzer evaluates stability against the same compiler model.
- The plugin is currently installed from disk during development; the JetBrains Marketplace listing is pending.

## Workflow

### 1. Build the plugin from source

Clone `skydoves/compose-stability-analyzer` and build the IDEA module.

```bash
git clone https://github.com/skydoves/compose-stability-analyzer
cd compose-stability-analyzer
./gradlew :compose-stability-analyzer-idea:buildPlugin
```

The packaged plugin lands at `compose-stability-analyzer-idea/build/distributions/*.zip`.

### 2. Install from disk

In the IDE, open **Settings → Plugins → ⚙ → Install Plugin from Disk…** and pick the ZIP produced in step 1. Restart the IDE when prompted. **DO NOT** look for the plugin in the JetBrains Marketplace yet; the listing is under review at the time of writing.

### 3. Confirm the settings panel

Open **Settings → Tools → Compose Stability Analyzer**. Verify `isStabilityCheckEnabled` is on. The full set of toggles, all read from `StabilitySettingsState`, is:

| Key | Default | Effect |
|---|---|---|
| `isStabilityCheckEnabled` | `true` | Master switch for all editor features. |
| `isStrongSkippingEnabled` | `true` | Match Compose Compiler 2.0.20+ runtime: classify with strong skipping on. |
| `showGutterIcons` | `true` | Render the colored icon in the gutter beside each `@Composable`. |
| `showGutterIconsOnlyForUnskippable` | `false` | Hide green/gray icons; only show problems. Use on dense files. |
| `showGutterIconsInTests` | `false` | Render gutter icons inside test source roots too. |
| `showWarnings` | `true` | Enable the `UnstableComposable` inspection. |
| `showInlineHints` | `true` | Render colored parameter-type badges inline. |
| `showOnlyUnstableHints` | `false` | Suppress inline badges for stable parameters. |
| `stableGutterColorRGB` | `#5FB865` (95, 184, 101) | Gutter / hint color for stable composables. |
| `unstableGutterColorRGB` | `#E8684A` (232, 104, 74) | Gutter / hint color for unstable composables. |
| `runtimeGutterColorRGB` | `#F0C674` (240, 198, 116) | Gutter / hint color for runtime-stability composables. |
| `stableHintColorRGB` / `unstableHintColorRGB` / `runtimeHintColorRGB` | per defaults above | Inline hint color overrides. |
| `ignoredTypePatterns` | `""` | Newline- or comma-separated glob list of types to treat as stable. |
| `stabilityConfigurationPath` | `""` | Optional path to the same `stability_config.conf` the compiler reads. |
| `isHeatmapEnabled` | `true` | Allow the live recomposition heatmap (see `../visualizing-recomposition-cascades/SKILL.md`). |

Point `stabilityConfigurationPath` at the project's `stability_config.conf` so the in-editor classification matches the compiler's classification.

### 4. Read the gutter colors

Open any Kotlin file containing `@Composable` functions. Confirm a colored icon appears beside each. The four colors and their meanings:

| Color | Hex | Meaning |
|---|---|---|
| Green | `#5FB865` | Skippable; every parameter stable. |
| Red | `#E8684A` | At least one unstable parameter; not skippable. |
| Yellow | `#F0C674` | At least one runtime-stability parameter (resolved across separate compilation units). |
| Gray | `#808080` | No parameters (still restartable but trivially skippable). |

A gray gutter is fine; the function has nothing to compare. A green gutter means the analyzer believes Compose will skip recomposition when arguments are equal. A red or yellow gutter is a diagnostic: the function will recompose more than necessary when the parent recomposes.

### 5. Hover for the parameter table

Hover any composable name. The popup, served by `StabilityDocumentationProvider`, shows the skippability badge, a per-parameter table with the stability classification per parameter, and the suggested fix when the analyzer can name one (e.g. "wrap `List<Item>` in `ImmutableList`"). Use this for triage before opening the full compiler reports.

### 6. Tune inline hints for dense files

`StabilityInlayHintsProvider` renders a colored badge next to each parameter type in the function signature. On a screen with many composables this can become noisy. In **Settings → Tools → Compose Stability Analyzer** enable `showOnlyUnstableHints` so badges disappear for stable parameters and only the actionable ones remain. For an even quieter file, enable `showGutterIconsOnlyForUnskippable` so the gutter only shows red/yellow.

### 7. Read the `UnstableComposable` inspection

`StabilityInspection` (short name `UnstableComposable`, group `Compose`, severity weak warning) flags non-skippable composables with at least one unstable parameter. It runs on the fly and on **Code → Inspect Code…**. The inspection surfaces in two ways:

- A weak-warning underline in the editor.
- A finding in the **Inspection Results** tool window grouped under `Compose / Unstable Composable`.

It is intentionally a weak warning: skippability is a diagnostic, not a build-blocking error. It will not turn the file red, will not block a commit, and will not fail CI on its own. Treat it as a backlog signal.

### 8. Apply quick fixes only when justified

The inspection ships two suppression intentions, surfaced via Alt+Enter on the underlined composable:

- `@Suppress("NonSkippableComposable")` — applied by `AddSuppressAnnotationFix` / `AddSuppressIntentionAction`. Silences the inspection on a function the developer has actively decided is acceptable (e.g. a debug overlay never on a hot path).
- `@Suppress("ParamsComparedByRef")` — same fix path, narrower scope: silences the warning when the parameter is intentionally compared by reference.

Apply suppressions on the specific function only. **DO NOT** push them to file scope.

### 9. Annotate hot paths with `@TraceRecomposition`

`AddTraceRecompositionIntention` is available via Alt+Enter on any `@Composable`. It inserts the `@TraceRecomposition` annotation, which is the runtime instrumentation entry point used by the live heatmap and by `../../measurement/tracing-recompositions-at-runtime/SKILL.md`. Use it on suspected hot composables once the gutter has identified them.

## Patterns

### Pattern: red gutter inside a hot path is actionable, even when the build is green

```text
// WRONG
PriceTicker has a red gutter icon.
Build is green, the team ships, jank reports come in two days later.
```

```text
// WRONG because: the inspection is a weak warning by design and does not block the build.
// The gutter is the only signal until the developer opens the compiler reports.
// Treat red gutters on hot-path composables as actionable; confirm with
// `../diagnosing-compose-stability/SKILL.md`, then fix via `../stabilizing-compose-types/SKILL.md`.
```

```text
// RIGHT
PriceTicker has a red gutter icon.
Hover -> "price: Price unstable (var price)".
Open the compiler reports for confirmation.
Stabilize `Price` (val + immutable fields) per `../stabilizing-compose-types/SKILL.md`.
Gutter turns green; confirm with the live heatmap per `../visualizing-recomposition-cascades/SKILL.md`.
```

### Pattern: suppress on the function, never on the file

```kotlin
// WRONG
@file:Suppress("UnstableComposable")

package com.example.feature.cart
```

```text
// WRONG because: file-level suppression hides every future regression in that file.
// New unstable composables added to the file will never surface in the inspection.
```

```kotlin
// RIGHT — narrow, justified suppression on a single function
@Suppress("NonSkippableComposable")
@Composable
fun DebugOverlay(state: MutableState<DebugInfo>) {
    // dev-only overlay; never on hot path; intentional MutableState parameter.
}
```

### Pattern: do not chase 100% green gutters

```text
// WRONG
"All composables in :feature-search must show green gutters before merge."
```

```text
// WRONG because: skippability is a diagnostic, not a KPI. Some composables legitimately
// take unstable parameters (interop with Java POJOs, third-party data classes the team
// does not own). The goal is "no regression in hot-path composables", not "all green".
// Use `../enforcing-stability-in-ci/SKILL.md` to gate against regressions, not absolutes.
```

```text
// RIGHT
Hot-path composables (list rows, scroll items, animation tickers) are green or yellow.
Cold-path composables (settings screens, debug tools) may be red and that is acceptable.
```

### Pattern: silence noise via settings, not via inspection-disable

```text
// WRONG
Settings -> Editor -> Inspections -> Compose -> Unstable Composable -> off
```

```text
// WRONG because: turning the inspection off globally silences real regressions
// across the whole codebase. The next unstable parameter slipping into a hot
// composable will not surface anywhere in the editor.
```

```text
// RIGHT
Settings -> Tools -> Compose Stability Analyzer:
  showGutterIconsOnlyForUnskippable = true
  showOnlyUnstableHints = true
The inspection stays on; only the visual noise is reduced.
```

### Pattern: align the in-editor classifier with the compiler

```kotlin
// RIGHT — in Settings -> Tools -> Compose Stability Analyzer
isStrongSkippingEnabled = true
stabilityConfigurationPath = "<repo-root>/stability_config.conf"
```

The compiler runs with strong skipping on by default in Kotlin 2.0.20+ and consults the same `stability_config.conf` (see `../stabilizing-compose-types/SKILL.md`). Matching both settings means the gutter color the developer sees agrees with what the compiler emits in `../diagnosing-compose-stability/SKILL.md`.

## Mandatory rules

- **MUST** treat a red or yellow gutter on any composable inside a hot path as actionable. Confirm with the compiler reports per `../diagnosing-compose-stability/SKILL.md`, then fix via `../stabilizing-compose-types/SKILL.md`.
- **MUST** point `stabilityConfigurationPath` at the same `stability_config.conf` the Compose compiler reads, so the editor and the compiler agree on classifications.
- **MUST NOT** apply file-level `@file:Suppress("UnstableComposable")`. Suppress on the specific function with `@Suppress("NonSkippableComposable")` or `@Suppress("ParamsComparedByRef")`, and document why in a comment.
- **MUST NOT** disable the inspection globally to silence noise. Tune `showGutterIconsOnlyForUnskippable = true` and `showOnlyUnstableHints = true` in **Settings → Tools → Compose Stability Analyzer** first.
- **MUST NOT** chase 100% green gutters. Skippability is a diagnostic, not a KPI. Stability config is a contract, not a magic spell — marking a mutable type stable means the compiler trusts the developer; break the contract and recompositions silently go missing.
- **PREFERRED:** keep `isStrongSkippingEnabled = true` so the analyzer's classification matches the compiler's actual runtime behavior on Kotlin 2.0.20+.
- **PREFERRED:** apply `@TraceRecomposition` via the **Add @TraceRecomposition** intention on suspected hot composables, then follow `../../measurement/tracing-recompositions-at-runtime/SKILL.md` to wire the runtime side.
- **PREFERRED:** install from disk during development; the JetBrains Marketplace listing is pending and **MUST NOT** be assumed available yet.

## Verification

- [ ] Plugin installed from disk via **Settings → Plugins → ⚙ → Install Plugin from Disk…**; the IDE has been restarted.
- [ ] **Settings → Tools → Compose Stability Analyzer** is present and `isStabilityCheckEnabled` is on.
- [ ] Opening a Kotlin file with `@Composable` declarations shows a colored gutter icon beside every one of them.
- [ ] Hovering a composable name shows the parameter-stability table popup served by `StabilityDocumentationProvider`.
- [ ] Inline parameter-type hints render next to each parameter in the function signature; toggling `showOnlyUnstableHints` hides hints for stable parameters.
- [ ] The `UnstableComposable` inspection (group `Compose`, weak warning) flags at least one known-unstable composable when one exists in the open file.
- [ ] Pressing Alt+Enter on a flagged composable offers `@Suppress("NonSkippableComposable")` and `@Suppress("ParamsComparedByRef")` quick fixes.
- [ ] Pressing Alt+Enter on any `@Composable` offers the **Add @TraceRecomposition** intention.
- [ ] `stabilityConfigurationPath` points at the same `stability_config.conf` used by the Compose Compiler in the project's Gradle config.

## References

- skydoves/compose-stability-analyzer — https://github.com/skydoves/compose-stability-analyzer
- The `compose-stability-analyzer-idea` module within that repo — https://github.com/skydoves/compose-stability-analyzer/tree/main/compose-stability-analyzer-idea
- skydoves, "Optimize App Performance by Mastering Stability" — https://medium.com/proandroiddev/optimize-app-performance-by-mastering-stability-in-jetpack-compose-69f40a8c785d
- Compose stability overview — https://developer.android.com/develop/ui/compose/performance/stability
- Strong Skipping — https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- IntelliJ Platform K2 Analysis API — https://plugins.jetbrains.com/docs/intellij/analysis-api.html

For CI-side gating that turns red gutters into a build failure, see `../enforcing-stability-in-ci/SKILL.md`. For the build-time Compose Compiler reports the gutter colors agree with, see `../diagnosing-compose-stability/SKILL.md`. For the upstream type fixes that turn red gutters green, see `../stabilizing-compose-types/SKILL.md`. For the active investigation tools (cascade visualizer, live heatmap) inside the same plugin, see `../visualizing-recomposition-cascades/SKILL.md`. For the runtime instrumentation that the **Add @TraceRecomposition** intention sets up, see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`.
