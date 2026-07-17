---
name: configuring-lazy-prefetch
description: Use this skill to tune Jetpack Compose lazy-layout prefetch with LazyLayoutCacheWindow (Compose Foundation 1.9+, @ExperimentalFoundationApi) and pausable composition in prefetch (Compose Foundation 1.10+, default on). Covers configurable Dp-based ahead/behind cache windows plumbed through rememberLazyListState(cacheWindow = ...), NestedPrefetchScope for items containing inner lazy layouts (HorizontalPager inside a LazyColumn row), version requirements, and the trade-off between memory pressure and idle-frame work. Use when the developer mentions dropped frames at high scroll velocity, prefetch window, ahead/behind extents, LazyLayoutCacheWindow, NestedPrefetchScope, pausable composition for prefetch, or wants composition retained for items briefly scrolled past. Item-level fixes (keys, contentType) live in a sibling skill.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - lazy-prefetch
  - lazy-layout-cache-window
  - pausable-composition
  - nested-prefetch
  - frame-timing
  - macrobenchmark
---

# Configuring Lazy Prefetch — Cache Window and Pausable Composition

Lazy layouts pre-compose items just outside the viewport so they're ready when the user scrolls. Compose Foundation 1.9 added `LazyLayoutCacheWindow` for configurable ahead/behind extents. Compose Foundation 1.10 made prefetch composition pausable by default — work spreads across multiple frames instead of one. Most apps still rely on legacy single-frame prefetch and unknowingly pay for it during heavy scrolling. This skill teaches Claude when (and only when) to widen the window or implement nested prefetch, and how to validate the change with Macrobenchmark.

## When to use this skill

- Macrobenchmark `FrameTimingMetric` shows dropped frames at high scroll velocity even though item composables are skippable.
- Items are content-heavy: large images, decoded videos, nested grids, or expensive measurement work.
- The developer wants composition retained for items the user scrolled past briefly (e.g. flick-back gesture).
- An outer lazy layout contains items with their own inner lazy layouts (e.g. `LazyColumn` rows that each host a `HorizontalPager` or inner `LazyRow`).
- The developer mentions `LazyLayoutCacheWindow`, `NestedPrefetchScope`, "prefetch window", "ahead extent", or "pausable composition for prefetch".

## When NOT to use this skill

- Items are cheap and short; default behavior is sufficient — do not pre-emptively configure a window.
- Item composables are non-skippable / unstable; widening the window only spreads the same waste over more frames. First fix `../optimizing-lazy-layouts/SKILL.md` and the underlying stability via `../../stability/diagnosing-compose-stability/SKILL.md`.
- The bottleneck is per-item layout or draw rather than composition. Use Android Studio Profiler / Layout Inspector to confirm; if measure or draw dominates, prefetch tuning will not help — adjust item content instead.
- Wrong-phase state reads inside items (animation reads `state.value` in Composition) — use `../../recomposition/deferring-state-reads/SKILL.md`.

## Prerequisites

- Compose Foundation **1.9+** for the `LazyLayoutCacheWindow` API. The cache window is a `@ExperimentalFoundationApi` factory `LazyLayoutCacheWindow(ahead: Dp, behind: Dp)` plumbed through `rememberLazyListState(cacheWindow = ...)` (or the equivalent grid/staggered-grid state). It is **NOT** a parameter on `LazyColumn` / `LazyRow` / `LazyVerticalGrid` / `LazyHorizontalGrid` directly. Every call site requires `@OptIn(ExperimentalFoundationApi::class)`.
- Compose Foundation **1.10+** for pausable composition in prefetch by default. On 1.10+ the prefetch composer can pause and resume across frames, so a single heavy item no longer blows a frame budget.
- Item-level hygiene already in place: `key`, `contentType`, stable item composables. Run `../optimizing-lazy-layouts/SKILL.md` first and confirm before tuning the window.
- Macrobenchmark module set up for `FrameTimingMetric`. See `../../measurement/generating-baseline-profiles/SKILL.md` for the Macrobenchmark scaffold.

## Workflow

- [ ] **1. Confirm Compose Foundation version.** In `gradle/libs.versions.toml` or the relevant `build.gradle.kts`, verify `androidx.compose.foundation:foundation` is at **1.9.0+** (cache window) or **1.10.0+** (pausable prefetch on by default). If on 1.10+ and not measuring jank yet, **MUST** stop here — the default is good enough for most apps.

- [ ] **2. Re-validate item-level fixes are in place.** Open `../optimizing-lazy-layouts/SKILL.md`. Every `items(...)` call has stable `key`, mixed feeds have `contentType`, item composables are skippable. Without this baseline, prefetch tuning is treating the symptom.

- [ ] **3. Measure the baseline with Macrobenchmark `FrameTimingMetric` before changing anything.** Skydoves hot take #5: debug builds lie. Always measure release + R8 + real device. Capture `frameDurationCpuMs` p50/p95/p99 over a fixed scroll journey so the after-comparison is meaningful. See `../../measurement/generating-baseline-profiles/SKILL.md`.

- [ ] **4. Construct a `LazyLayoutCacheWindow` only after measurement justifies it.** Build the window with the top-level factory `LazyLayoutCacheWindow(ahead: Dp, behind: Dp)` and plumb it through `rememberLazyListState(cacheWindow = window)`, then pass that state to `LazyColumn(state = state) { ... }`. The cache window is **not** a parameter on `LazyColumn` itself. Every call site needs `@OptIn(ExperimentalFoundationApi::class)`.

```kotlin
// RIGHT — measured tall, image-heavy items benefit from a deeper ahead extent
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeavyFeed(items: ImmutableList<HeavyItem>) {
    val state = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(ahead = 200.dp, behind = 100.dp)
    )
    LazyColumn(state = state) {
        items(items, key = { it.id }, contentType = { it::class }) { item ->
            HeavyItemRow(item, Modifier.animateItem())
        }
    }
}
```

- [ ] **5. Implement `NestedPrefetchScope` for items that host inner lazy layouts.** When a `LazyColumn` row contains a `HorizontalPager`, inner `LazyRow`, or inner `LazyVerticalGrid`, the outer prefetch can warm only the row's outer composition by default. Implementing nested prefetch chains the work so the inner layout's first item composes during the outer prefetch slot, not on the user's first horizontal swipe.

- [ ] **6. Re-measure with the same Macrobenchmark journey.** Compare `frameDurationCpuMs` p95/p99 before and after. If the rate did not decrease, **MUST** revert the window change and look elsewhere — likely measure or draw dominates, or item composition was already keeping up.

- [ ] **7. If still dropping frames on Compose Foundation 1.10+ with pausable prefetch, profile to confirm the bottleneck.** Use Android Studio CPU profiler / system trace to determine whether composition, measurement, or draw is the dominant phase per frame. Prefetch tuning only addresses composition cost; for measure/draw, simplify item content (smaller images, fewer subcompositions, `graphicsLayer` for alpha/scale) instead.

- [ ] **8. Change one variable at a time.** **MUST NOT** combine cache-window tuning with stability/key fixes in the same commit or PR. Profiling needs an isolated cause-and-effect comparison; bundling two changes makes the regression bisect impossible.

## Patterns

### Pattern: default vs configured

```kotlin
// OK — short, light items: default behavior is correct
LazyColumn {
    items(items, key = { it.id }, contentType = { it::class }) { Item(it) }
}
```

```kotlin
// RIGHT — measured-heavy items justify a wider ahead window (Compose Foundation 1.9+)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeavyFeed(items: ImmutableList<HeavyItem>) {
    val state = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(ahead = 200.dp, behind = 100.dp)
    )
    LazyColumn(state = state) {
        items(items, key = { it.id }, contentType = { it::class }) { HeavyItem(it) }
    }
}
```

### Pattern: nested prefetch for inner lazy layouts

```kotlin
// RIGHT — the outer LazyColumn warms the inner pager's first item via nested prefetch
LazyColumn {
    items(rows, key = { it.id }, contentType = { it::class }) { row ->
        HorizontalPager(
            state = rememberPagerState(pageCount = { row.pages.size }),
            // Implement NestedPrefetchScope on the inner pager so outer prefetch chains in.
        ) { pageIndex ->
            PagerPage(row.pages[pageIndex])
        }
    }
}
```

Without nested prefetch, only the outer row warms — the user's first horizontal swipe still pays composition cost for the inner page. With nested prefetch, the inner first page is composed during the outer prefetch slot.

### Pattern: anti-pattern — "be safe" wide window

```kotlin
// WRONG
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WideWindow(items: ImmutableList<Item>) {
    val state = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(ahead = 2000.dp, behind = 2000.dp)
    )
    LazyColumn(state = state) { /* ... */ }
}
// WRONG because: a 2000.dp ahead/behind window pre-composes a screenful of extra items on every scroll tick. Memory pressure rises (especially with image-heavy items) and a single direction-reverse wastes most of the prefetched work. Wide windows are not "safe defaults" — they are deliberate trade-offs that MUST be backed by Macrobenchmark numbers.
```

```kotlin
// RIGHT — start narrow, widen only if FrameTimingMetric demands it
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NarrowWindow(items: ImmutableList<Item>) {
    val state = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(ahead = 200.dp, behind = 100.dp)
    )
    LazyColumn(state = state) { /* ... */ }
}
```

### Pattern: tuning before fixing item stability

```kotlin
// WRONG
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Feed(items: List<Item>) {        // unstable parameter
    val state = rememberLazyListState(
        cacheWindow = LazyLayoutCacheWindow(ahead = 400.dp, behind = 200.dp)
    )
    LazyColumn(state = state) {
        items(items, key = { it.id }) { item -> ItemRow(item) }
    }
}
// WRONG because: pre-composing additional unstable rows just spreads the same wasted work over more frames. The cache window cannot fix non-skippable item composables.
```

```kotlin
// RIGHT — fix stability first, then leave the window at default; widen only if Macrobenchmark still shows jank
@Composable
fun Feed(items: ImmutableList<Item>) {
    LazyColumn {
        items(items, key = { it.id }, contentType = { it::class }) { item -> ItemRow(item) }
    }
}
```

Cross-reference: `../optimizing-lazy-layouts/SKILL.md` and `../../stability/stabilizing-compose-types/SKILL.md`.

### Pattern: relying on pausable composition (Compose Foundation 1.10+)

```kotlin
// RIGHT — on 1.10+, prefetch composition is pausable by default; a single heavy item no longer blows the frame budget
// gradle/libs.versions.toml:
//   compose-foundation = "1.10.0"
LazyColumn {
    items(items, key = { it.id }, contentType = { it::class }) { HeavyItem(it) }
}
```

Pausable composition lets the prefetch composer suspend mid-item when the frame deadline approaches and resume on the next idle frame. **PREFERRED:** upgrade to Compose Foundation 1.10+ and benefit from this default before manually widening the cache window.

## Mandatory rules

- **MUST** stay on default behavior unless a Macrobenchmark `FrameTimingMetric` baseline proves the prefetch window is the bottleneck. Premature tuning increases memory pressure without fixing jank.
- **MUST** state the Compose Foundation minimum version when introducing `LazyLayoutCacheWindow` (1.9+) or relying on pausable prefetch defaults (1.10+).
- **MUST** annotate any call site that uses `LazyLayoutCacheWindow(...)` with `@OptIn(ExperimentalFoundationApi::class)`. Plumb the window through `rememberLazyListState(cacheWindow = ...)` (or the matching grid/staggered-grid state factory), then pass that state to the lazy layout — the cache window is **not** a parameter on `LazyColumn` / `LazyRow` / `LazyVerticalGrid` / `LazyHorizontalGrid` themselves.
- **MUST** express ahead/behind extents in `Dp` (the API takes `Dp`, not item counts).
- **MUST** complete `../optimizing-lazy-layouts/SKILL.md` (keys, contentType, item stability) before tuning the cache window. Prefetch cannot fix non-skippable items.
- **MUST NOT** combine cache-window tuning with stability/key fixes in one PR — change one variable at a time, re-measure, then iterate. Skydoves hot take #5: always measure release + R8 + real device.
- **MUST NOT** widen the window "to be safe". Wider window = more memory + more wasted work on direction reversals.
- **PREFERRED:** upgrade to Compose Foundation **1.10+** to get pausable prefetch by default before manually tuning the window.
- **PREFERRED:** implement `NestedPrefetchScope` for any item that contains an inner lazy layout (HorizontalPager, inner LazyRow, inner LazyVerticalGrid) before widening the outer window.

## Verification

- [ ] `androidx.compose.foundation:foundation` resolves to **1.9.0+** (cache window) or **1.10.0+** (pausable prefetch by default) in the dependency report.
- [ ] Macrobenchmark `FrameTimingMetric` `frameDurationCpuMs` p95 / p99 measurably decreases on the same scroll journey after the change, on a real device with R8 enabled.
- [ ] Layout Inspector shows prefetch items composing during idle frames (not piled into the same frame as user scroll input).
- [ ] Memory profiler does not show a regression after widening the window — heap and resident set sizes stay within budget.
- [ ] If `NestedPrefetchScope` was added, the user's first inner-pager swipe runs at native frame rate (no first-swipe stutter).
- [ ] No commit bundles cache-window tuning with key / contentType / stability fixes — each change ships and is measured in isolation.

## References

- Android Developers — Lists and grids: https://developer.android.com/develop/ui/compose/lists
- Android Developers — Performance overview: https://developer.android.com/develop/ui/compose/performance
- Android Developers — What's new in Jetpack Compose (May 2025, 1.9): https://android-developers.googleblog.com/2025/05/whats-new-in-jetpack-compose.html
- Android Developers — What's new in Jetpack Compose (December 2025, 1.10): https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html
- Android Developers — Macrobenchmark FrameTimingMetric: https://developer.android.com/topic/performance/baselineprofiles/measure-baselineprofile
- Ben Trengrove — Why you should always test Compose performance in release: https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- skydoves — compose-performance hub: https://github.com/skydoves/compose-performance
