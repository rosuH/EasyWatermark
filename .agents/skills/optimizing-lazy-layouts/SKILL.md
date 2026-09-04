---
name: optimizing-lazy-layouts
description: Use this skill to fix scroll jank, lost item state, and broken animateItem() animations in LazyColumn, LazyRow, LazyVerticalGrid, and LazyHorizontalGrid. Covers stable item keys, contentType for mixed-type feeds, Modifier.animateItem() requirements, hoisting modifier chains and painters out of the items lambda, and validating item composable stability. Use when the developer mentions LazyColumn jank, dropped frames while scrolling, items losing scroll state on insert/remove/reorder, mixed feeds of cards/headers/ads feeling sluggish, animateItem() not animating, RecyclerView view-type analog, key parameter, or contentType parameter. The prefetch-window tuning lives in a sibling skill.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - lazy-column
  - lazy-row
  - lazy-grid
  - scroll-jank
  - item-key
  - content-type
  - animate-item
---

# Optimizing Lazy Layouts — Keys, contentType, and animateItem()

Lazy layouts compose only what's visible, but two things still cost: re-composition of items that should have been reused (missing `key`), and per-item allocation that compounds with scroll velocity (missing `contentType`, modifier chains created inside `items { }`). Both have a one-line fix. This skill teaches Claude how to apply that fix correctly and to validate that item composables are themselves skippable. Prefetch tuning is a separate concern — see `../configuring-lazy-prefetch/SKILL.md`.

## When to use this skill

- The developer reports scroll jank, dropped frames, or stutter on a `LazyColumn`, `LazyRow`, `LazyVerticalGrid`, or `LazyHorizontalGrid`.
- Items lose scroll position, focus, or composition state on insert, remove, or reorder.
- A mixed-type feed (cards + headers + ads + carousels) feels sluggish even though each individual row is lightweight.
- `Modifier.animateItem()` was added but no animation runs on inserts or removals.
- The compiler report shows item composables as `unstable`/non-skippable, or `@TraceRecomposition` shows item composables recomposing on every scroll tick.

## When NOT to use this skill

- The bottleneck is the prefetch window (heavy items, high-velocity scroll, want a wider ahead/behind window) → use `../configuring-lazy-prefetch/SKILL.md`.
- The item composable itself takes an unstable parameter (`List<Foo>`, `Flow<Foo>`, a domain `var`) → first run `../../stability/diagnosing-compose-stability/SKILL.md` and then `../../stability/stabilizing-compose-types/SKILL.md`.
- An animation inside an item reads `state.value` in Composition phase, recomposing the row every frame → use `../../recomposition/deferring-state-reads/SKILL.md`.
- Scroll position derivation (e.g. `firstVisibleItemIndex == 0`) is the hot path → use `../../recomposition/choosing-derivedstateof/SKILL.md`.

## Prerequisites

- Compose Foundation **1.7+** for `Modifier.animateItem()` (the GA replacement for the experimental `animateItemPlacement`).
- Kotlin **2.0.0+** with `org.jetbrains.kotlin.plugin.compose` applied. Strong Skipping is on by default; non-skippable item composables become amplified at scroll speed.
- A real device + release build for measurement. Skydoves hot take #5: debug builds lie (Live Literals, interpreted mode). See `../../measurement/generating-baseline-profiles/SKILL.md` when ready to measure.

## Workflow

- [ ] **1. Audit every `items(...)` call.** Walk every `LazyListScope.items(list)`, `items(count)`, `itemsIndexed(list)`, and the `LazyGridScope` equivalents. For each, decide: does each element have a stable identity that outlives a single composition? If yes — and it almost always does — supply `key = { it.id }` using a server-side stable ID. **MUST NOT** use the list index, `UUID.randomUUID()` evaluated per emission, or `hashCode()` of a mutable object.

```kotlin
// WRONG
LazyColumn { items(snacks) { snack -> SnackRow(snack) } }
// WRONG because: index-based identity → insert/remove discards composition state and breaks animateItem().
```

```kotlin
// RIGHT
LazyColumn {
    items(
        items = snacks,
        key = { it.id },
        contentType = { it::class },
    ) { snack ->
        SnackRow(snack, Modifier.animateItem())
    }
}
```

- [ ] **2. Add `contentType` for heterogeneous lists.** Lazy layouts maintain a per-type composition cache analogous to RecyclerView's view-type. When item N + 1 has the same `contentType` as a recycled slot, the cached composition is reused; otherwise it is discarded and rebuilt. For homogeneous lists Compose infers a single content type and `contentType` is optional. For mixed feeds (cards, headers, ads, carousels, dividers) **MUST** supply a stable type discriminator.

- [ ] **3. Validate item composable stability.** Run `../../stability/diagnosing-compose-stability/SKILL.md`. If the item composable accepts an `unstable` parameter, no amount of `key`/`contentType` work will help — the row recomposes on every scroll-driven snapshot tick anyway. Fix with `../../stability/stabilizing-compose-types/SKILL.md` before tuning further.

- [ ] **4. Hoist allocation-heavy values out of the items lambda.** The items lambda runs once per item per scroll-driven (re)composition. Painters, color resolutions, shapes, and `BorderStroke` instances built inside the lambda are reallocated each pass. Hoist constants and `remember`-based caches above the `LazyColumn` or to the call site. Modifier chains are themselves cheap because Compose deduplicates them structurally — hoist a `Modifier` only when profiling proves it matters.

- [ ] **5. Add `Modifier.animateItem()` for visual continuity.** Pair with a stable `key`. The animation runs on inserts, removals, and reorders; without `key` the animation cannot bind to identity and silently no-ops. The default fade-in / fade-out / placement spring is usually correct; tune with `fadeInSpec`, `fadeOutSpec`, `placementSpec` only when the design system requires it.

- [ ] **6. Cache common painters / colors / shapes outside the items block.** `painterResource(...)`, `MaterialTheme.colorScheme.surface`, `RoundedCornerShape(...)` resolutions on every item composition add up. Hoist to the screen-level composable and pass down, or `remember` once at the `LazyColumn` parent.

- [ ] **7. Verify with `@TraceRecomposition` and Layout Inspector.** During a controlled scroll, expect each item composable to recompose **at most once** per real state change — not per scroll tick. Layout Inspector → Recomposition Counts column should plateau, not climb monotonically.

## Patterns

### Pattern: missing `key`

```kotlin
// WRONG
LazyColumn {
    items(snacks) { snack -> SnackRow(snack) }
}
// WRONG because: items default to index-based identity. On insert/remove/reorder, every position past the change point has a different "identity", composition state and scroll-restoration are lost, and Modifier.animateItem() has nothing to animate from.
```

```kotlin
// RIGHT
LazyColumn {
    items(snacks, key = { it.id }) { snack -> SnackRow(snack) }
}
```

### Pattern: random or unstable key

```kotlin
// WRONG
items(snacks, key = { UUID.randomUUID() }) { snack -> SnackRow(snack) }
// WRONG because: a fresh key on every recomposition guarantees the cached composition is discarded every time — strictly worse than no key.
```

```kotlin
// WRONG
items(snacks, key = { it.hashCode() }) { snack -> SnackRow(snack) }
// WRONG because: hashCode() of a mutable type changes when fields mutate, breaking identity continuity for the same logical item.
```

```kotlin
// RIGHT
items(snacks, key = { it.id }) { snack -> SnackRow(snack) }
```

### Pattern: mixed feed without `contentType`

```kotlin
// WRONG
items(feed, key = { it.id }) { item ->
    when (item) {
        is FeedItem.Card -> CardRow(item)
        is FeedItem.Ad -> AdRow(item)
        is FeedItem.Header -> HeaderRow(item)
    }
}
// WRONG because: cached compositions of one type are discarded when scrolled into a different type's slot — every row crossing a type boundary is a fresh build instead of a recycled update.
```

```kotlin
// RIGHT
items(
    items = feed,
    key = { it.id },
    contentType = { it::class },
) { item ->
    when (item) {
        is FeedItem.Card -> CardRow(item)
        is FeedItem.Ad -> AdRow(item)
        is FeedItem.Header -> HeaderRow(item)
    }
}
```

### Pattern: `Modifier.animateItem()` without a stable key

```kotlin
// WRONG
items(snacks) { snack ->
    SnackRow(snack, Modifier.animateItem())
}
// WRONG because: animateItem() binds animation state to the item's key. With no key, identity is index-based, so an insert at position 0 looks like every-row-changed and nothing animates correctly.
```

```kotlin
// RIGHT
items(snacks, key = { it.id }) { snack ->
    SnackRow(snack, Modifier.animateItem())
}
```

### Pattern: allocation inside the items lambda

```kotlin
// WRONG
items(snacks, key = { it.id }) { snack ->
    val placeholder = painterResource(R.drawable.snack_placeholder)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    Card(border = border) {
        AsyncImage(snack.imageUrl, placeholder = placeholder)
    }
}
// WRONG because: painterResource resolution and BorderStroke allocation happen on every item composition; at high scroll velocity these compound into measurable allocation pressure.
```

```kotlin
// RIGHT
@Composable
fun SnackList(snacks: ImmutableList<Snack>) {
    val placeholder = painterResource(R.drawable.snack_placeholder)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    LazyColumn {
        items(snacks, key = { it.id }, contentType = { it::class }) { snack ->
            Card(border = border) {
                AsyncImage(snack.imageUrl, placeholder = placeholder)
            }
        }
    }
}
```

Note: Compose deduplicates structurally-equal `Modifier` chains internally, so reallocating `Modifier.fillMaxWidth().padding(16.dp)` per item is a *micro*-optimization. Hoist a Modifier only when profiling identifies it as the bottleneck — premature `remember { Modifier.… }` adds noise without measurable benefit.

### Pattern: unstable item composable swallows all gains

```kotlin
// WRONG
@Composable
fun SnackRow(snack: Snack, tags: List<String>) { /* ... */ }

// Caller:
items(snacks, key = { it.id }) { snack ->
    SnackRow(snack, tags = snack.tags)
}
// WRONG because: List<String> is an unstable parameter under inference; every scroll-driven recomposition recomposes the row body even though the snack didn't change.
```

```kotlin
// RIGHT
@Immutable
data class Snack(val id: Long, val name: String, val tags: ImmutableList<String>)

@Composable
fun SnackRow(snack: Snack) { /* ... */ }

items(snacks, key = { it.id }, contentType = { it::class }) { snack ->
    SnackRow(snack)
}
```

Cross-reference: `../../stability/stabilizing-compose-types/SKILL.md`.

### Pattern: `LazyVerticalGrid` with mixed spans

```kotlin
// RIGHT — keys + contentType apply to grids identically
LazyVerticalGrid(columns = GridCells.Fixed(2)) {
    items(
        items = feed,
        key = { it.id },
        contentType = { it::class },
        span = { item -> if (item is FeedItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
    ) { item ->
        when (item) {
            is FeedItem.Header -> HeaderRow(item, Modifier.animateItem())
            is FeedItem.Card -> CardCell(item, Modifier.animateItem())
        }
    }
}
```

## Mandatory rules

- **MUST** specify a `key` for every `items(...)` block where item identity outlives a single composition (effectively: every list backed by domain objects).
- **MUST** use server-side stable IDs as keys. **MUST NOT** use the list index, **MUST NOT** use `UUID.randomUUID()` evaluated per emission, **MUST NOT** use `hashCode()` of a mutable object.
- **MUST** specify `contentType` for heterogeneous lists (cards + headers + ads, etc.). Use a stable type discriminator such as `it::class` or a sealed `enum`.
- **MUST NOT** use `Modifier.animateItem()` without a stable `key` — the animation silently no-ops.
- **MUST** validate item composable stability with `../../stability/diagnosing-compose-stability/SKILL.md` before blaming the lazy layout. An unstable item parameter cancels every gain from `key`/`contentType`.
- **MUST NOT** wrap `items { }` in extra inline composable wrappers (`Row { items { } }`) hoping to "force" skippability — `Row`/`Column`/`Box` are NOT restartable/skippable to begin with (skydoves hot take #3).
- **PREFERRED:** combine with `../configuring-lazy-prefetch/SKILL.md` for high-velocity scroll surfaces only after item-level fixes are in place.
- **PREFERRED:** measure in release + R8 + on a real device (skydoves hot take #5) before declaring a fix complete.

## Verification

- [ ] Reproduce the original scroll jank on a release build on a real device, then re-record after the fix; the dropped-frame rate measurably decreases.
- [ ] Insert / remove / reorder operations preserve scroll position and per-item state (focus, expansion, scrubbed video position).
- [ ] `Modifier.animateItem()` runs the expected fade and placement animation on inserts and removals.
- [ ] Layout Inspector → Recomposition Counts column on item composables plateaus during steady scroll instead of climbing monotonically.
- [ ] `@TraceRecomposition` on the item composable shows recompositions only on real state changes, not on every scroll-driven invalidation.
- [ ] The compiler report (`composables.txt`) shows the item composable as `restartable skippable` with all parameters `stable` or `runtime`.

## References

- Android Developers — Lists and grids: https://developer.android.com/develop/ui/compose/lists
- Android Developers — Performance overview: https://developer.android.com/develop/ui/compose/performance
- Android Developers — Practical performance codelab: https://developer.android.com/codelabs/jetpack-compose-performance
- Android Developers — What's new in Jetpack Compose (April 2025, 1.8): https://android-developers.googleblog.com/2025/04/whats-new-in-jetpack-compose-april-25.html
- Ben Trengrove — Debugging recomposition: https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Chris Banes — Compose performance tag: https://chrisbanes.me/tags/jetpack-compose-performance/
- skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- skydoves — compose-performance hub: https://github.com/skydoves/compose-performance
