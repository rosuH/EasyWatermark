---
name: avoiding-subcomposition-pitfalls
description: Use this skill when a Compose tree uses SubcomposeLayout, BoxWithConstraints, or Scaffold and the developer reports extra measure passes, slow first frame, or layout passes running content composition repeatedly. Covers why SubcomposeLayout composes its slots during the measure phase, why BoxWithConstraints forces a subcomposition for every new Constraints value, why nesting Scaffold or BoxWithConstraints multiplies the cost, when a custom Layout or Modifier.layout { } replaces SubcomposeLayout, and how to use SubcomposeLayoutState's slot reuse policy and precompose APIs when SubcomposeLayout is genuinely required. Use when the developer mentions BoxWithConstraints, SubcomposeLayout, Scaffold, "extra measure pass", "double measurement", "first frame slow", "subcompose", or notices that wrapping content in BoxWithConstraints regresses scroll perf inside a LazyColumn.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - subcomposelayout
  - boxwithconstraints
  - scaffold
  - layout-phase
  - measure-pass
  - subcomposition
---

# Avoiding Subcomposition Pitfalls — Keep Composition Out of the Measure Pass

`SubcomposeLayout` runs its content's composition **during the measure pass**, not during the parent's composition pass. That trades extra layout cost for the ability to use measured constraints inside the children's composition. `BoxWithConstraints`, `material3.Scaffold`, and the lazy layouts are all built on top of it, so the cost is invisible until it nests or lands inside a hot path. This skill teaches Claude how to detect the misuse, how to replace `SubcomposeLayout` with a cheaper primitive when its power is not needed, and how to keep it efficient when it is.

## When to use this skill

- The developer wraps content in `BoxWithConstraints` purely to read `maxWidth` / `maxHeight` and pick between a few composables, and the screen feels heavy at first frame or on configuration change (rotation, IME show).
- A `BoxWithConstraints` or `Scaffold` is nested inside another `BoxWithConstraints` or `Scaffold`, multiplying subcomposition cost.
- A `BoxWithConstraints` sits inside a `LazyColumn`/`LazyRow` item and the developer reports scroll jank that did not exist before the wrap was added.
- The developer manually authors a `SubcomposeLayout` block but only uses constraints to compute final positions, never to drive child composition.
- The Layout Inspector or a Perfetto trace shows `Compose:applyChanges` or measure-phase composition counts that scale with parent constraint changes (rotation, drag-to-resize, animated container size).
- A custom `SubcomposeLayout`'s `measurePolicy` block allocates a fresh composable lambda inside `subcompose(slotId) { … }` on every measurement. AndroidX maintains an internal lint check named `ComposableLambdaInMeasurePolicy` (in `compose/lint/internal-lint-checks/`) that flags exactly this pattern on its own codebase; published APIs like `BoxWithConstraints`, `material.Scaffold`, and `material3.TabRow` suppress that check because the trade-off is fundamental to their public contract. App-side code that reproduces the pattern pays the same cost without the suppression rationale.

## When NOT to use this skill

- The developer needs to compose a child whose content depends on another child's measured size — that is the canonical `SubcomposeLayout` use case and there is no cheaper primitive. Keep `SubcomposeLayout`; tune it (see Pattern: Tuning a justified SubcomposeLayout).
- The slow path is inside a `LazyColumn` item but the cause is unstable parameters, not subcomposition. Diagnose with `../../stability/diagnosing-compose-stability/SKILL.md` first.
- The state read driving repeated measurement is an animation value rather than a structural constraint. That is `../deferring-state-reads/SKILL.md`.
- The user wants to optimize lazy list prefetch — that is `../../lists/configuring-lazy-prefetch/SKILL.md`.

## Prerequisites

- Familiarity with Compose's three phases (Composition, Layout, Draw). If unsure, read `../deferring-state-reads/references/three-phases.md` first.
- A **release** build with R8 for any final measurement. `../../measurement/testing-compose-in-release-mode/SKILL.md` covers why debug builds lie.
- Layout Inspector or Perfetto trace tooling. `../../measurement/tracing-recompositions-at-runtime/SKILL.md` covers wiring `androidx.compose.runtime:runtime-tracing` so that measure-phase work shows up in system traces.
- A modern Compose UI version that exposes the `SubcomposeSlotReusePolicy(maxSlotsToRetainForReuse: Int)` factory and pausable subcomposition on the lazy-list path. Both ship in the current `androidx.compose.ui:ui` / `androidx.compose.foundation:foundation` artifacts; check the release notes at https://developer.android.com/jetpack/androidx/releases/compose-ui and https://developer.android.com/jetpack/androidx/releases/compose-foundation if pinning to an older release.

## Workflow

- [ ] **1. Identify every `SubcomposeLayout` user in the hot path.** Grep the affected screen for `BoxWithConstraints`, `Scaffold`, `SubcomposeLayout`, and any custom `@Composable` that delegates to one. The `material3.Scaffold` and `foundation.layout.BoxWithConstraints` are both implemented with `SubcomposeLayout`; their cost is identical.

- [ ] **2. For each user, decide whether the constraints are actually consumed during composition.** A `BoxWithConstraints` that reads `maxWidth` only to pass it to a child as a parameter is wasting the subcomposition machinery. A `BoxWithConstraints` that picks between two completely different `@Composable` trees based on `maxWidth < 600.dp` is using it correctly.

- [ ] **3. Replace unjustified `BoxWithConstraints` with a cheaper primitive.** If the consumer only needs the final size in pixels (e.g. to compute an offset, an alpha, a draw command), use `Modifier.layout { measurable, constraints -> … }` or `Modifier.onSizeChanged { … }` instead. Both run during the layout phase without spinning up a new composition.

- [ ] **4. Avoid nesting subcomposing layouts.** Two `Scaffold`s nested, or a `BoxWithConstraints` inside a `Scaffold` slot that itself contains another `BoxWithConstraints`, multiplies measure-phase composition. Hoist the outer one to the screen root and use ordinary `Box` / `Column` / `Row` for the inner branches.

- [ ] **5. Never wrap a `LazyColumn`/`LazyRow` item in `BoxWithConstraints`.** Each item subcomposes during measurement. If the item needs the available width, pass it down once from the parent (the `LazyColumn` already knows its constraints) or use `Modifier.fillMaxWidth().onSizeChanged { … }` for one-time sizing.

- [ ] **6. When `SubcomposeLayout` is justified, configure `SubcomposeLayoutState(SubcomposeSlotReusePolicy(n))`.** The state object retains up to `n` previously-used slots so the next subcomposition reuses their slot table and node subtree instead of paying for a fresh composition. The factory `SubcomposeSlotReusePolicy(maxSlotsToRetainForReuse: Int)` ships in `androidx.compose.ui.layout`.

- [ ] **7. For predictable upcoming slots, call `state.precompose(slotId, content)` on a previous frame.** It returns a `PrecomposedSlotHandle`; the next `subcompose(slotId, …)` during measurement skips the composition step. This is exactly how the lazy layouts spread item composition across frames.

- [ ] **8. Verify in a Perfetto system trace.** Add `androidx.compose.runtime:runtime-tracing` and confirm that `Compose:recompose` and `Compose:applyChanges` no longer fire during scroll or resize ticks at the rate of the parent's constraint changes. The measured screen should show subcomposition only on actual structural change (e.g. rotation), not on every frame.

## Patterns

### Pattern: `BoxWithConstraints` used to read size — replace with `Modifier.onSizeChanged`

```kotlin
// WRONG
@Composable
fun ParallaxHeader(scrollOffset: () -> Float) {
    BoxWithConstraints {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Image(
            painter = painterResource(R.drawable.hero),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .graphicsLayer {
                    translationX = scrollOffset() * widthPx * 0.2f
                },
            contentDescription = null,
        )
    }
}
// WRONG because: BoxWithConstraints subcomposes its content during the measure phase. Each
// new constraint (rotation, IME show, animated parent size) re-runs that subcomposition just
// to read maxWidth, when a Modifier.onSizeChanged would have given the same value with no
// extra composition.
```

```kotlin
// RIGHT
@Composable
fun ParallaxHeader(scrollOffset: () -> Float) {
    var widthPx by remember { mutableStateOf(0f) }
    Image(
        painter = painterResource(R.drawable.hero),
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .graphicsLayer {
                translationX = scrollOffset() * widthPx * 0.2f
            },
        contentDescription = null,
    )
}
```

`onSizeChanged` runs in the layout phase (no subcomposition); the underlying `onRemeasured` is invoked on every layout pass and forwards to the user callback when the measured size differs from the previous size. The `graphicsLayer` block runs in Draw with the latest `widthPx` and the latest scroll offset.

### Pattern: `BoxWithConstraints` to pick a layout — keep it but hoist it

```kotlin
// WRONG
@Composable
fun ProductCard(product: Product) {
    BoxWithConstraints {
        if (maxWidth < 360.dp) {
            CompactProductCard(product)
        } else {
            WideProductCard(product)
        }
    }
}

@Composable
fun ProductGrid(products: List<Product>) {
    LazyVerticalGrid(columns = GridCells.Fixed(2)) {
        items(products) { ProductCard(it) }
    }
}
// WRONG because: every grid cell wraps its content in BoxWithConstraints. Each cell pays
// for a subcomposition during the grid's measure pass. With 200 cells visible across a long
// scroll session, that is 200 redundant subcompositions on every measurement.
```

```kotlin
// RIGHT
@Composable
fun ProductGrid(products: List<Product>) {
    BoxWithConstraints {
        val isCompact = maxWidth / 2 < 360.dp
        LazyVerticalGrid(columns = GridCells.Fixed(2)) {
            items(products) { product ->
                if (isCompact) CompactProductCard(product) else WideProductCard(product)
            }
        }
    }
}
```

The single `BoxWithConstraints` at the grid root subcomposes once per parent constraint change. The decision flows into every item as a plain `Boolean`, which is stable and skippable.

### Pattern: Nested `Scaffold` — collapse to one

```kotlin
// WRONG
@Composable
fun ProfileScreen(...) {
    Scaffold(topBar = { TopBar(...) }) { outer ->
        Scaffold(
            modifier = Modifier.padding(outer),
            bottomBar = { ProfileTabBar(...) },
        ) { inner ->
            ProfileContent(modifier = Modifier.padding(inner))
        }
    }
}
// WRONG because: each Scaffold is a SubcomposeLayout with its own measure-phase composition.
// Nesting them doubles that cost on every frame whose constraints change (IME show, rotation,
// nav bar inset change).
```

```kotlin
// RIGHT
@Composable
fun ProfileScreen(...) {
    Scaffold(
        topBar = { TopBar(...) },
        bottomBar = { ProfileTabBar(...) },
    ) { padding ->
        ProfileContent(modifier = Modifier.padding(padding))
    }
}
```

`Scaffold` already supports a top bar, bottom bar, snackbar host, and FAB in a single subcomposition. Use the slots provided rather than nesting another `Scaffold`.

### Pattern: Tuning a justified `SubcomposeLayout`

When a screen genuinely needs measure-time access to constraints (a tab strip whose tabs size by their own content while sharing the row's max width, a lookahead-driven shared element container, a custom popover that aligns to a measured anchor), keep `SubcomposeLayout` but tune the state.

```kotlin
@Composable
fun BadgeRow(badges: List<Badge>) {
    val slotState = remember {
        SubcomposeLayoutState(SubcomposeSlotReusePolicy(maxSlotsToRetainForReuse = 8))
    }
    SubcomposeLayout(state = slotState) { constraints ->
        val measurables = subcompose(slotId = "badges") {
            badges.fastForEach { Badge(it) }
        }
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.sumOf { it.width }
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            var x = 0
            placeables.fastForEach { p ->
                p.place(x, 0)
                x += p.width
            }
        }
    }
}
```

`SubcomposeSlotReusePolicy(8)` lets the layout retain up to eight previously-composed slots. When a slot id reappears (e.g. a badge enters the screen again after exiting), the layout reuses the existing slot table instead of rerunning composition. The lazy layouts use a similar mechanism with their own policy implementation.

For **predictable upcoming slots**, call `state.precompose(slotId, content)` on an earlier frame and dispose the returned `PrecomposedSlotHandle` if the slot is not needed after all. The next `subcompose(slotId, …)` during measurement reuses the precomposed content with no composition step.

### Pattern: `LazyListScope.item { }` containing `BoxWithConstraints`

```kotlin
// WRONG
LazyColumn {
    items(rows) { row ->
        BoxWithConstraints {
            val isWide = maxWidth > 600.dp
            RowContent(row, isWide)
        }
    }
}
// WRONG because: each row item subcomposes during the LazyColumn's measure pass. Scroll the
// list and every newly-visible item pays for a fresh subcomposition. On a 60Hz feed this is
// the difference between smooth and dropped frames.
```

```kotlin
// RIGHT
@Composable
fun RowFeed(rows: List<Row>) {
    BoxWithConstraints {
        val isWide = maxWidth > 600.dp
        LazyColumn {
            items(rows, key = { it.id }) { row -> RowContent(row, isWide) }
        }
    }
}
```

The single `BoxWithConstraints` at the feed root subcomposes only when the feed itself is remeasured. The per-row item is a plain `RowContent` call with a stable `Boolean` parameter, which strong skipping handles.

## Mandatory rules

- **MUST NOT** use `BoxWithConstraints` only to read `maxWidth`/`maxHeight` for a `Modifier`-level effect. Use `Modifier.onSizeChanged { … }` or `Modifier.layout { … }` instead.
- **MUST NOT** place a `BoxWithConstraints` inside a `LazyColumn`/`LazyRow`/`LazyVerticalGrid` item. Hoist the constraint read to the lazy layout's parent and pass the resolved value down.
- **MUST NOT** nest `Scaffold` inside another `Scaffold`. Use the slots (`topBar`, `bottomBar`, `snackbarHost`, `floatingActionButton`) the outer `Scaffold` already exposes.
- **MUST** keep a `SubcomposeLayout` when the children's composition genuinely depends on a measured value (parent constraints, sibling size). The point is to use the right tool, not to ban `SubcomposeLayout`.
- **MUST** pass a `SubcomposeSlotReusePolicy` to `SubcomposeLayoutState` whenever slots come and go. The default no-op policy disposes slots eagerly and pays full composition cost on every reappearance.
- **PREFERRED:** for predictable upcoming slots, call `state.precompose(slotId, content)` on a previous frame and let the measure-pass `subcompose` reuse the result.
- **PREFERRED:** measure once with `Modifier.onSizeChanged { … }` over reading `maxWidth` in a `BoxWithConstraints` body when the value only feeds a `graphicsLayer { }`, `drawBehind { }`, or other Layout/Draw-phase consumer.

## Verification

- [ ] A Perfetto system trace recorded with `androidx.compose.runtime:runtime-tracing` shows that `Compose:recompose` and `Compose:applyChanges` no longer fire on every measurement of the affected subtree. They should fire only on real structural change (rotation, navigation, list item insert).
- [ ] Layout Inspector's recomposition count for any subtree wrapped by the prior `BoxWithConstraints` does not increment per parent constraint change.
- [ ] In any retained custom `SubcomposeLayout`, the `subcompose(slotId) { … }` content lambda is hoisted into a `remember`-stable reference (or is cheap and intentional, with a one-line comment explaining why a fresh lambda per measurement is safe here). This is the pattern AndroidX's internal `ComposableLambdaInMeasurePolicy` lint enforces on the AndroidX codebase.
- [ ] A grep over the migrated file finds zero `BoxWithConstraints` blocks inside `LazyColumn`/`LazyRow`/`LazyVerticalGrid` items.
- [ ] No `Scaffold` is nested inside another `Scaffold` in the migrated file.
- [ ] For every retained `SubcomposeLayout`, a `SubcomposeSlotReusePolicy` is passed to `SubcomposeLayoutState`, OR the comment explicitly notes that all slots are permanent and reuse is unnecessary.

## References

- Android Developers — Custom layouts: https://developer.android.com/develop/ui/compose/layouts/custom
- Android Developers — Phases of Jetpack Compose: https://developer.android.com/develop/ui/compose/phases
- Android Developers — `SubcomposeLayout` reference: https://developer.android.com/reference/kotlin/androidx/compose/ui/layout/package-summary#SubcomposeLayout(androidx.compose.ui.Modifier,kotlin.Function2)
- Compose UI source — `SubcomposeLayout.kt`: https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/layout/SubcomposeLayout.kt
- Compose foundation source — `BoxWithConstraints.kt`: https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/foundation/foundation-layout/src/commonMain/kotlin/androidx/compose/foundation/layout/BoxWithConstraints.kt
- Material3 source — `Scaffold.kt` (uses `SubcomposeLayout` for the chrome layout): https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/material3/material3/src/commonMain/kotlin/androidx/compose/material3/Scaffold.kt
- Chris Banes — "Composable metrics" (subcompose cost in Perfetto traces): https://chrisbanes.me/posts/composable-metrics/
- skydoves — Jetpack Compose Mechanism (composition vs subcomposition): https://speakerdeck.com/skydoves/jetpack-compose-mechanism
- `../deferring-state-reads/references/three-phases.md` — three-phase model background that motivates "do not run composition during measure".
- `../../lists/configuring-lazy-prefetch/SKILL.md` — the lazy layouts use `precompose` and pausable subcomposition; the same APIs are available to custom `SubcomposeLayout` users.
