---
name: deferring-state-reads
description: Use this skill to push frequently-changing Jetpack Compose state reads (scroll position, animation values, drag offsets) out of the Composition phase and down into Layout or Draw using lambda-based modifiers like Modifier.offset { }, Modifier.layout { }, Modifier.graphicsLayer { }, Modifier.drawBehind { }, and Modifier.drawWithCache { }. Covers the three-phase model (Composition, Layout, Draw), why a state read at phase N invalidates phase N and every phase below, the modifier-phase cheat sheet, and lambda providers (() -> T) for hoisting hot values across composables. Use when the developer mentions every-frame work, scroll jank, animation jank, dropped frames, animated alpha or offset, "the whole subtree recomposes on scroll", Modifier.alpha(state.value), Modifier.offset(x.dp), or graphicsLayer.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - recomposition
  - phases
  - lambda-modifiers
  - graphicslayer
  - scroll-perf
  - animation-jank
---

# Deferring State Reads — Move Hot Reads from Composition to Draw

Compose runs three phases per frame — **Composition → Layout → Draw**. A state read at phase N invalidates phase N and every phase below it. The single biggest perf win in any animation- or scroll-driven UI is moving a state read from Composition down to Layout or Draw via a lambda-based modifier. This skill teaches Claude how to spot the wrong-phase read and migrate it.

## When to use this skill

- An animation triggers full subtree recomposition on every frame (`Modifier.alpha(progress.value)`, `Modifier.offset(x.dp)`, `Modifier.padding(state.dp)` — `padding` has no lambda overload, so use `Modifier.layout { ... }` or `Modifier.offset { IntOffset(...) }` instead when the inset is animated).
- A scroll position is read directly in a composable body (`val y = scrollState.value`) and the parent recomposes on every pixel of scroll.
- The developer says "every frame", "scroll jank", "animation jank", "dropped frames", or reports the whole screen recomposing on drag.
- A `@TraceRecomposition` log shows the parent's recomposition counter incrementing once per animation tick.
- The developer is passing a hot value (animation progress, scroll offset, drag delta) as a `Float` parameter across composables.

## When NOT to use this skill

- The state changes once per user interaction (button click, dialog open) — the lambda-modifier rewrite buys nothing and adds noise.
- The state read genuinely needs to drive Composition (show/hide a different composable, swap a different component tree). Lambda modifiers cannot decide which composable to emit.
- The non-skippable parent is non-skippable for a different reason (unstable parameter). Diagnose with `../../stability/diagnosing-compose-stability/SKILL.md` first.
- The developer wants to filter many high-frequency inputs into one rare boolean — that is `../choosing-derivedstateof/SKILL.md`.

## Prerequisites

- Familiarity with the three-phase model. If unsure which phase a read happens in, read `references/three-phases.md` before doing the migration.
- Compose UI **1.7+** for `rememberGraphicsLayer()` and the modern `Modifier.animateItem()` shape. Lambda forms of `Modifier.offset { }`, `Modifier.graphicsLayer { }`, `Modifier.drawBehind { }`, and `Modifier.drawWithCache { }` exist on every supported Compose version.
- Ability to confirm recomposition counts in Layout Inspector or via `@TraceRecomposition` (see `../debugging-recompositions/SKILL.md` if it exists in this repo, otherwise the skydoves `compose-stability-analyzer` runtime).
- A **release** build for any final measurement — debug builds run interpreted and lie about cost.

## Workflow

- [ ] **1. Identify the state read site and the modifier consuming it.** Look for `state.value`, `by state`, `animatedFloat.value`, `scrollState.value`, `dragOffset.value` in a composable body. Note which modifier consumes it.

- [ ] **2. Classify the modifier — value form or lambda form.** Most positioning/sizing/drawing modifiers ship in two flavors:

| Value form (read in Composition) | Lambda form (read in Layout or Draw) |
|---|---|
| `Modifier.offset(x = dp, y = dp)` | `Modifier.offset { IntOffset(x, y) }` |
| `Modifier.padding(start = dp, …)` | `Modifier.layout { measurable, constraints -> … }` (no lambda overload of `padding`; use a custom layout to inset with hot state) |
| `Modifier.size(dp)` | `Modifier.layout { … }` (custom) |
| `Modifier.alpha(float)` | `Modifier.graphicsLayer { alpha = … }` |
| `Modifier.rotate(float)` | `Modifier.graphicsLayer { rotationZ = … }` |
| `Modifier.scale(float)` | `Modifier.graphicsLayer { scaleX = …; scaleY = … }` |
| `Modifier.background(color)` | `Modifier.drawBehind { drawRect(color) }` |

If a value form is being fed a hot state, swap to the lambda form. The lambda is invoked on every Layout (or Draw) pass without re-running Composition.

- [ ] **3. Migrate value-form modifiers to lambda form.** The lambda body re-executes when the read state changes, but it does so during Layout or Draw — Composition is never invalidated.

- [ ] **4. For multiple per-frame transforms, hoist into a single `Modifier.graphicsLayer { … }` block.** It covers `translationX`/`translationY`, `scaleX`/`scaleY`, `rotationZ`, `alpha`, `cameraDistance`, `clip`, and `shape` in one Draw-phase node, and is preferred over chaining `alpha` + `offset` + `rotate`.

- [ ] **5. Hoist hot values across composables as lambda providers.** Pass `() -> Float` instead of `Float` so the receiving composable can defer the read into its own lambda modifier without invalidating its caller. This is the single most important cross-composable perf trick after lambda modifiers themselves.

- [ ] **6. Verify in Layout Inspector / `@TraceRecomposition`.** The parent composable's recomposition count MUST stop incrementing per animation frame. If it still climbs, the wrong-phase read survived elsewhere — re-grep the file for `.value` and `by state`.

- [ ] **7. For per-frame Draw work that is more than a transform, use `Modifier.drawBehind { }` or `Modifier.drawWithCache { }`.** `drawBehind` re-runs every Draw pass; `drawWithCache` caches the build step (e.g. paths, brushes) and only re-runs the `onDraw` block on state change.

## Patterns

### Pattern: Animated offset

```kotlin
// WRONG
val animatedX = animateFloatAsState(targetX, label = "x")
Box(Modifier.offset(x = animatedX.value.dp))
// WRONG because: reading .value in Composition phase invalidates the whole subtree on every animation frame.
```

```kotlin
// RIGHT
val animatedX = animateFloatAsState(targetX, label = "x")
Box(Modifier.offset { IntOffset(animatedX.value.toInt(), 0) })
```

The lambda runs in Layout, so Composition is never invalidated. The animation still drives the visual position via Layout-only invalidation.

### Pattern: Animated alpha (and other transforms)

```kotlin
// WRONG
val animatedAlpha by animateFloatAsState(targetAlpha, label = "alpha")
Box(Modifier.alpha(animatedAlpha))
// WRONG because: Modifier.alpha(Float) reads the value in Composition; the entire Box subtree recomposes every frame.
```

```kotlin
// RIGHT
val animatedAlpha by animateFloatAsState(targetAlpha, label = "alpha")
Box(Modifier.graphicsLayer { alpha = animatedAlpha })
```

`graphicsLayer { }` reads inside the Draw phase only. For combined transforms, fold them all into the same block:

```kotlin
// RIGHT — one Draw-phase node, three transforms
Box(
    Modifier.graphicsLayer {
        alpha = progress
        scaleX = 1f + 0.2f * progress
        scaleY = 1f + 0.2f * progress
        translationX = progress * 32.dp.toPx()
    }
)
```

### Pattern: Scroll-driven sticky header

```kotlin
// WRONG
val offset = scrollState.value
Header(Modifier.offset(y = offset.dp))
// WRONG because: scrollState.value updates every pixel of scroll, and reading it in the parent body recomposes Header (and every sibling) per scroll tick.
```

```kotlin
// RIGHT
Header(Modifier.offset { IntOffset(0, scrollState.value) })
```

The lambda captures `scrollState` (a stable holder), reads `.value` only when Layout runs, and isolates invalidation to the Layout phase of `Header`.

### Pattern: Lambda providers across composables

```kotlin
// WRONG
@Composable
fun Parent(scrollOffset: Float) {
    Child(scrollOffset)
}

@Composable
fun Child(scrollOffset: Float) {
    Box(Modifier.offset { IntOffset(0, scrollOffset.toInt()) })
}
// WRONG because: scrollOffset is read in Parent's signature each frame; Parent and every sibling of Child recompose per frame even though only Child cares.
```

```kotlin
// RIGHT
@Composable
fun Parent(scrollOffset: () -> Float) {
    Child(scrollOffset)
}

@Composable
fun Child(scrollOffset: () -> Float) {
    Box(Modifier.offset { IntOffset(0, scrollOffset().toInt()) })
}
```

The lambda parameter is a stable function reference. Calling it inside `Modifier.offset { }` defers the read to Layout. Parent never re-reads the hot state, so Parent never recomposes.

### Pattern: Per-frame Draw work — `drawBehind` / `drawWithCache`

```kotlin
// WRONG
val color = animatedColor.value
Box(Modifier.background(color))
// WRONG because: Modifier.background reads color in Composition; per-frame color animation invalidates the subtree.
```

```kotlin
// RIGHT — Draw phase only, no caching needed for a solid color
Box(Modifier.drawBehind { drawRect(animatedColor.value) })
```

```kotlin
// RIGHT — heavier work; cache the Path/Brush across frames
Box(
    Modifier.drawWithCache {
        val brush = Brush.linearGradient(
            colors = listOf(start, end),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawBehind { drawRect(brush, alpha = animatedAlpha.value) }
    }
)
```

`drawWithCache` re-builds the cache only when the `remember`-keys captured by the lambda change; the `onDrawBehind` block re-runs on every Draw with the latest state.

## Three phases reference

Compose runs every frame in this order. **A read at phase N invalidates phase N and everything below it.** Push reads as low as possible.

| Phase | What it does | Cost when invalidated |
|---|---|---|
| **Composition** | Run @Composable functions, build/diff the UI tree | Highest — all skippability gates re-run, child composables potentially recompose |
| **Layout** | `measure()` and `placeRelative()` for each node | Medium — re-measure and re-place affected subtree |
| **Draw** | Record draw commands into the canvas | Lowest — single render pass |

### Modifier phase cheat sheet (most common cases)

| Modifier | Phase the state is read in |
|---|---|
| `Modifier.offset(Dp)` | Composition |
| `Modifier.offset { IntOffset }` | **Layout** |
| `Modifier.padding(Dp)` / `padding(PaddingValues)` | Composition (no lambda overload exists) |
| `Modifier.size(Dp)` | Composition |
| `Modifier.layout { measurable, constraints -> … }` | **Layout** (escape hatch when a hot state must drive measurement, e.g. an animated inset — `padding` ships no lambda form) |
| `Modifier.alpha(Float)` | Composition |
| `Modifier.rotate(Float)` | Composition |
| `Modifier.scale(Float)` | Composition |
| `Modifier.graphicsLayer { … }` | **Draw** |
| `Modifier.background(Color)` | Composition |
| `Modifier.drawBehind { … }` | **Draw** |
| `Modifier.drawWithCache { … }` | **Draw** (cache rebuilds when its captured state changes) |

For the deeper mechanics — invalidation propagation, the backwards-write rule, and a comprehensive per-modifier breakdown — see `references/three-phases.md`.

## Mandatory rules

- **MUST** prefer lambda-based modifiers for any state that changes more often than once per user interaction (animation values, scroll position, drag offsets, gesture deltas).
- **MUST** use `Modifier.graphicsLayer { … }` for animated `alpha`, `scale`, `rotate`, `translation`. Never `Modifier.alpha(state.value)` / `Modifier.rotate(state.value)` / `Modifier.scale(state.value)` for hot state.
- **MUST NOT** read frequently-changing state in a composable body when a lambda modifier could read it later. Every avoided `.value` read in a composable body is one fewer Composition invalidation per frame.
- **MUST NOT** write to a `MutableState` already read in the same composition pass — that is a backwards write (see `references/three-phases.md`); the runtime aborts the recomposition with a cost.
- **PREFERRED:** lambda providers (`() -> T`) over plain values when the value is hot and crosses composable boundaries. Pair with lambda modifiers at the receiving end.
- **PREFERRED:** `Modifier.drawWithCache { }` over `Modifier.drawBehind { }` when the per-frame work involves rebuildable resources (paths, brushes, gradients) that depend on size or theme.

## Verification

- [ ] Layout Inspector shows the parent composable's recomposition count is 0 (or near 0) per animation frame. Only Layout/Draw counters increment.
- [ ] `@TraceRecomposition(traceStates = true)` (skydoves/compose-stability-analyzer) confirms in **release + R8 + real device** that the wrapping composable does not recompose per frame; only the sub-node attached to the lambda modifier re-runs.
- [ ] A grep over the migrated file finds no `Modifier.alpha(`/`Modifier.rotate(`/`Modifier.scale(`/`Modifier.offset(<dp expr>)` patterns fed by hot state.
- [ ] Cross-composable hot values are passed as `() -> T` lambda providers, not as `T` values.
- [ ] The release build still renders correctly — value-form to lambda-form is a behavioral no-op only when the lambda body matches the original arithmetic (watch for `dp` vs `px` confusion: `Modifier.offset { IntOffset(x, y) }` takes pixels).

## References

- Android Developers — Phases of Jetpack Compose: https://developer.android.com/develop/ui/compose/phases
- Android Developers — Performance: phases: https://developer.android.com/develop/ui/compose/performance/phases
- Android Developers — Graphics modifiers: https://developer.android.com/develop/ui/compose/graphics/draw/modifiers
- Android Developers — Custom modifiers (Modifier.Node): https://developer.android.com/develop/ui/compose/custom-modifiers
- Ben Trengrove — Debugging recomposition: https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Why test perf in release: https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- Chris Banes — Compose perf tag: https://chrisbanes.me/tags/jetpack-compose-performance/
- skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- skydoves — Jetpack Compose Mechanism: https://speakerdeck.com/skydoves/jetpack-compose-mechanism
- `references/three-phases.md` — detailed walkthrough of Composition / Layout / Draw, downward invalidation, the backwards-write rule, and the comprehensive modifier-phase cheat sheet.
