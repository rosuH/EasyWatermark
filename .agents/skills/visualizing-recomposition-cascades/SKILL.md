---
name: visualizing-recomposition-cascades
description: 'Use this skill to drive the active investigation features of the `skydoves/compose-stability-analyzer` IntelliJ / Android Studio plugin: the static Recomposition Cascade visualizer that walks the call graph from a root `@Composable`, and the live Recomposition Heatmap that streams `D/Recomposition` events from a connected device''s logcat into block inlays above each instrumented composable. Covers the `Compose Stability Analyzer` tool window''s three tabs (Explorer, Cascade, Heatmap), the cascade analyzer''s static PSI walk with depth cap and cycle detection, the ADB logcat command the heatmap consumes, and the toggle/clear actions. Use when the user asks "what gets dragged in if I change this composable", "how many times did this recompose during that scroll", "what is the blast radius of this state change", or mentions the cascade tab, the heatmap tab, the recomposition inlays, or `Toggle Recomposition Heatmap`.'
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - stability
  - recomposition
  - cascade
  - heatmap
  - intellij-plugin
  - logcat
  - trace-recomposition
  - compose-stability-analyzer
---

# Visualizing Recomposition Cascades — static blast radius and live heatmap inside the IDE

Passive gutter icons answer "is this composable skippable" (covered by `../using-stability-analyzer-ide-plugin/SKILL.md`). The active investigation tools answer two harder questions. The Cascade visualizer walks the static call graph from a root `@Composable` and shows what else might recompose if the root recomposes. The Live Heatmap subscribes to the device's logcat and renders the actual recomposition counts as block inlays above each instrumented composable in the editor. Together they close the loop between static analysis and runtime evidence.

Both features live inside the same IntelliJ plugin and surface through the right-anchored **Compose Stability Analyzer** tool window, which has three tabs: **Explorer**, **Cascade**, and **Heatmap**.

## When to use this skill

- The developer asks "what gets dragged in if I change this composable" — use the Cascade tab.
- The developer asks "how many times did this recompose during that scroll" or "which composable is hot during this animation" — use the Heatmap tab.
- Triaging a recomposition spike surfaced by `../../recomposition/debugging-recompositions/SKILL.md` and needing the call-site context.
- Preparing a refactor and wanting the blast radius before touching a shared composable.
- The user mentions the Cascade tab, the Heatmap tab, recomposition inlays, **Analyze Recomposition Cascade**, **Toggle Recomposition Heatmap**, or **Clear Recomposition Data**.

## When NOT to use this skill

- Pure source-level diagnosis. The gutter icons and the `UnstableComposable` inspection from `../using-stability-analyzer-ide-plugin/SKILL.md` are enough.
- CI gating against stability regressions. Use `../enforcing-stability-in-ci/SKILL.md`.
- Full release-grade scroll/startup measurement with FrameTimingMetric. Use `../../measurement/generating-baseline-profiles/SKILL.md`. Toggle the heatmap **off** before such runs.
- Conceptual questions about how the compiler classifies a type. Use `../understanding-stability-inference/SKILL.md`.

## Prerequisites

- The IDEA plugin from `../using-stability-analyzer-ide-plugin/SKILL.md` already installed and the **Compose Stability Analyzer** tool window visible in the right rail.
- For the **Cascade** workflow: nothing extra; it is pure static analysis on the open project's PSI.
- For the **Heatmap** workflow:
  - At least one composable annotated with `@TraceRecomposition` (cross-link `../../measurement/tracing-recompositions-at-runtime/SKILL.md` for the runtime setup).
  - An Android device or emulator connected via ADB.
  - The app installed and running.
  - `ComposeStabilityAnalyzer.setEnabled(true)` called in `Application.onCreate` (gated on `BuildConfig.DEBUG` or a dedicated flag — see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`).

## Workflow A — Cascade visualizer

### A1. Place the caret inside a `@Composable`

The action **Analyze Recomposition Cascade** (id `com.skydoves.compose.stability.idea.cascade.AnalyzeCascadeAction`) is enabled only when the caret is inside a `@Composable` function. It is registered in the Editor popup; right-click anywhere inside the composable body to surface it.

### A2. Run the action

Right-click → **Analyze Recomposition Cascade**. The right-anchored **Compose Stability Analyzer** tool window opens to the **Cascade** tab and renders a tree rooted at the selected function.

Internals to know when triaging the result:

- The walk starts from the selected `KtCallExpression` and descends into call targets resolved via `resolveMainReference()`.
- Maximum recursion depth is capped at 10 levels, so cascades from very wide composables stop at depth 10 rather than expanding indefinitely.
- Cycle detection uses a visited set of fully qualified names; recursive composables (e.g. tree renderers) appear once and are not re-expanded.
- Each node carries a stability badge from the same engine that drives the gutter icons in `../using-stability-analyzer-ide-plugin/SKILL.md`.

### A3. Walk and triage

Walk depth-first. For each node:

- Read the stability badge. A red node downstream of the root means recomposing the root will recompose that subtree without skipping.
- Double-click a node to navigate to its source.
- For high-fan-out branches, prune by stabilizing parameters (`../stabilizing-compose-types/SKILL.md`) or by deferring state reads inside hot leaves (`../../recomposition/deferring-state-reads/SKILL.md`).

### A4. Treat the cascade as a static estimate, not a runtime guarantee

The cascade is a **static** call-graph walk. Whether each downstream node actually recomposes at runtime depends on stability and skipping decisions made by the compiler and runtime. Confirm with the live heatmap (Workflow B) before drawing conclusions about real cost.

## Workflow B — Live Heatmap

### B1. Annotate target composables with `@TraceRecomposition`

The heatmap is fed by logcat lines emitted by the `@TraceRecomposition` runtime. Without instrumented composables, no inlays appear. Use the **Add @TraceRecomposition** intention from `../using-stability-analyzer-ide-plugin/SKILL.md`, or add the annotation manually. Full runtime setup (gating, state tracing, throttling) lives in `../../measurement/tracing-recompositions-at-runtime/SKILL.md`.

### B2. Run the app and confirm logcat output

Run a debug build on the connected device. Open the **Logcat** tool window and filter on tag `Recomposition`. Confirm `D/Recomposition` lines stream in when the instrumented composables recompose. The exact format the plugin's parser expects:

```text
[Recomposition #3] UserProfile (tag: user-screen) (2.30ms)
  ├─ [param] user: User changed (User@abc123 → User@def456)
  ├─ [param] count: Int stable (42)
  ├─ [state] counter: Int changed (5 → 6)
  └─ Unstable parameters: [user]
```

If the log lines are not appearing, the runtime is misconfigured — go back to `../../measurement/tracing-recompositions-at-runtime/SKILL.md` before continuing.

### B3. Toggle the heatmap on

In the editor right-click → **Toggle Recomposition Heatmap** (action id `com.skydoves.compose.stability.idea.heatmap.ToggleHeatmapAction`). The action also lives under the **Code** menu.

The plugin's `AdbLogcatService` spawns this exact ADB command to subscribe:

```bash
adb logcat -s Recomposition:D -T 1
```

`-s Recomposition:D` filters to the tag at debug level; `-T 1` starts from the most recent line. If multiple devices are connected, a popup chooser appears.

### B4. Read the inlays

`HeatmapInlayManager` places block inlays above each `@Composable` declaration that has streamed at least one event. Each inlay shows:

- The recomposition count since the heatmap was toggled on (or since the last **Clear Recomposition Data**).
- A color-coded background reflecting the relative heat.

Click an inlay to switch the tool window to the **Heatmap** tab and focus that composable's event history. Hover an inlay for a 5-second tooltip with the latest data, served from `AdbLogcatService.getHeatmapData(name)`.

### B5. Reset accumulated counts

Run **Code → Clear Recomposition Data** (action id `com.skydoves.compose.stability.idea.heatmap.ClearHeatmapDataAction`) to reset the counters between scenarios. Use this when comparing two interactions back-to-back (e.g. a list scroll vs. a search filter) without restarting the app.

### B6. Toggle off before measurement

Toggle the heatmap action again to stop the listener. The heatmap drives logcat I/O and PSI work on every recomposition; **MUST** toggle it off before running Macrobenchmark or Baseline Profile generation per `../../measurement/generating-baseline-profiles/SKILL.md`, or the overhead will skew the timing results.

## Tool window structure

The right-anchored tool window with id `Compose Stability Analyzer` (icon `/icons/stability.svg`) ships with three tabs registered by `StabilityToolWindowFactory`:

| Tab | Backing class | Purpose |
|---|---|---|
| **Explorer** | `StabilityToolWindow` | Hierarchical tree by module → package → file → composable, with stability badges. Double-click navigates to the source. Use this as a survey view across a module before drilling into Cascade or Heatmap. |
| **Cascade** | `CascadePanel` | Populated when **Analyze Recomposition Cascade** runs. Shows the static call-graph tree from the selected root. |
| **Heatmap** | `HeatmapPanel` | Populated when the live heatmap is toggled on. Shows the live event log per composable. |

Use the Explorer tab as the entry point; drop into Cascade for a target; enable Heatmap to confirm.

## Patterns

### Pattern: heatmap with no `@TraceRecomposition` produces no inlays

```text
// WRONG
1. Toggle Recomposition Heatmap on.
2. ADB connects, logcat reader starts.
3. Scroll the screen aggressively.
4. No inlays appear above any composable.
5. Developer concludes "the heatmap is broken".
```

```text
// WRONG because: the heatmap is fed by logcat lines emitted by `@TraceRecomposition`.
// Without at least one instrumented composable there is nothing to render.
// Add `@TraceRecomposition` per `../../measurement/tracing-recompositions-at-runtime/SKILL.md`,
// rebuild, and toggle again.
```

```kotlin
// RIGHT — instrument first, then toggle the heatmap
@TraceRecomposition(traceStates = true)
@Composable
fun PriceTicker(price: Price) {
    Text(price.formatted)
}
```

### Pattern: do not run the heatmap during Macrobenchmark

```text
// WRONG
1. Open Macrobenchmark scroll journey.
2. Toggle Recomposition Heatmap on (forgot from earlier session).
3. Run the benchmark.
4. FrameTimingMetric numbers regress vs. baseline; team panics.
```

```text
// WRONG because: the heatmap drives logcat I/O and PSI updates on every recomposition.
// That overhead skews FrameTimingMetric and any release-grade measurement.
// Toggle the heatmap off before running `../../measurement/generating-baseline-profiles/SKILL.md`
// or any Macrobenchmark journey.
```

```text
// RIGHT
1. Toggle Recomposition Heatmap off (verify no inlays in editor).
2. Run the Macrobenchmark journey.
3. Compare FrameTimingMetric vs. the committed baseline.
```

### Pattern: do not trust the cascade as a runtime guarantee

```text
// WRONG
Cascade from PriceTicker shows 12 downstream composables.
Developer concludes "all 12 will recompose every tick" and refactors all 12.
```

```text
// WRONG because: the cascade is a static call-graph walk. Whether each downstream node
// actually recomposes depends on stability and skipping at runtime. Several of the 12
// may already be skipping today; refactoring them is wasted work.
// Confirm with the live heatmap before drawing conclusions.
```

```text
// RIGHT — the loop: cascade for blast radius, heatmap for confirmation, fix, re-measure
1. Analyze Recomposition Cascade from PriceTicker -> 12 nodes flagged.
2. Annotate the top 3 (by suspected hotness) with `@TraceRecomposition`.
3. Toggle Recomposition Heatmap on, run a representative scenario.
4. Inlays show 2 nodes with high counts; the other 10 stayed cold.
5. Fix the 2 hot nodes via `../stabilizing-compose-types/SKILL.md`.
6. Toggle the heatmap off, re-measure with `../../measurement/generating-baseline-profiles/SKILL.md`.
```

### Pattern: prune by stability, not by structure

```text
// WRONG
Cascade shows a wide subtree; developer wraps every leaf in `key { ... }` to force isolation.
```

```text
// WRONG because: `key` does not make an unstable parameter stable. The leaves still
// receive unstable arguments; the parent still recomposes them. The fix is upstream:
// stabilize the type per `../stabilizing-compose-types/SKILL.md` so the existing
// skipping logic can do its job.
```

```text
// RIGHT
Identify the unstable parameter via the cascade node's badge.
Stabilize the type at its declaration site.
Re-run the cascade to confirm the badge flipped to stable.
```

## Mandatory rules

- **MUST** annotate target composables with `@TraceRecomposition` before toggling the heatmap. Without it, the logcat stream is empty and no inlays render.
- **MUST** toggle the heatmap off before running Macrobenchmark or Baseline Profile generation per `../../measurement/generating-baseline-profiles/SKILL.md`. Logcat overhead skews timing.
- **MUST NOT** ship `ComposeStabilityAnalyzer.setEnabled(true)` in production. Gate on `BuildConfig.DEBUG` or a dedicated flag per `../../measurement/tracing-recompositions-at-runtime/SKILL.md`.
- **MUST NOT** read the cascade as a runtime guarantee. It is a static blast-radius estimate; confirm with the heatmap before refactoring.
- **MUST NOT** chase a green cascade as a goal. Skippability is a diagnostic, not a KPI; some downstream nodes legitimately need to recompose.
- **PREFERRED:** start every investigation from the **Explorer** tab to scan the module, drop into **Cascade** for a target root, then enable the **Heatmap** to confirm the cascade's predictions against runtime evidence.
- **PREFERRED:** run **Clear Recomposition Data** between scenarios when comparing two interactions back-to-back, instead of restarting the app.
- **PREFERRED:** keep the heatmap session focused on a single screen at a time. Inlays accumulate across scenarios and the signal-to-noise ratio drops fast.

## Verification

- [ ] Right-clicking inside a `@Composable` shows **Analyze Recomposition Cascade**; right-clicking outside any composable does not.
- [ ] The Cascade tab populates with a tree rooted at the selected function and stops expanding at depth 10 or at cycles.
- [ ] Double-clicking a cascade node navigates to that composable's source.
- [ ] **Toggle Recomposition Heatmap** prompts a device picker when more than one device is connected, and auto-starts when only one is connected.
- [ ] With the heatmap on and `@TraceRecomposition` applied, `D/Recomposition` lines appear in the **Logcat** window and block inlays appear above the matching composable declarations.
- [ ] Clicking an inlay switches the tool window to the **Heatmap** tab and focuses that composable.
- [ ] Hovering an inlay shows a 5-second tooltip with the latest data.
- [ ] **Clear Recomposition Data** resets the accumulated counts and inlays.
- [ ] Toggling the heatmap action again stops the ADB logcat reader (verify by closing the **Logcat** filter and seeing no further `D/Recomposition` consumption from the plugin).

## References

- skydoves/compose-stability-analyzer — https://github.com/skydoves/compose-stability-analyzer
- The `compose-stability-analyzer-idea` module within that repo — https://github.com/skydoves/compose-stability-analyzer/tree/main/compose-stability-analyzer-idea
- skydoves, "Optimize App Performance by Mastering Stability" — https://medium.com/proandroiddev/optimize-app-performance-by-mastering-stability-in-jetpack-compose-69f40a8c785d
- Compose stability overview — https://developer.android.com/develop/ui/compose/performance/stability
- Layout Inspector recomposition counts (complementary tool) — https://developer.android.com/studio/debug/layout-inspector
- Android Logcat command-line reference — https://developer.android.com/tools/logcat

For the passive in-editor features (gutter icons, hover, inline hints, inspection) of the same plugin, see `../using-stability-analyzer-ide-plugin/SKILL.md`. For the runtime instrumentation that emits the `D/Recomposition` lines the heatmap consumes, see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`. For CI-side gating against stability regressions, see `../enforcing-stability-in-ci/SKILL.md`. For the upstream type fixes that prune cascade branches, see `../stabilizing-compose-types/SKILL.md`. For deferring state reads inside hot leaves discovered via the heatmap, see `../../recomposition/deferring-state-reads/SKILL.md`. For Macrobenchmark journeys that **MUST** run with the heatmap off, see `../../measurement/generating-baseline-profiles/SKILL.md`.
