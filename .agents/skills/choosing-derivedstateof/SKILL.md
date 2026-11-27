---
name: choosing-derivedstateof
description: Use this skill to decide when Jetpack Compose derivedStateOf is the right tool and when it is pure overhead. Covers the "input frequency must exceed output frequency" rule, the mandatory remember { derivedStateOf { } } wrapper, the canonical pitfall of capturing non-state variables by initial value (and the remember(key) fix), and the snapshotFlow alternative for fire-and-forget side effects on derived values. Use when the developer mentions derivedStateOf, scroll-position-driven booleans, threshold checks, firstVisibleItemIndex, "show FAB on scroll", recomposition counts that don't drop after wrapping a value, or asks whether a computed string concatenation should use derivedStateOf.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - recomposition
  - derivedstateof
  - snapshotflow
  - lazylist
  - scroll-perf
  - state
---

# Choosing derivedStateOf — Filter Hot Inputs into Cold Outputs

`derivedStateOf` produces a `State` whose readers only invalidate when the **derived result** changes, even if the input states change far more often. Use it when input frequency exceeds output frequency. Use it for any other shape and it is pure overhead — an extra snapshot subscription with no filtering benefit. This skill teaches Claude when to reach for it, when to refuse, and how to avoid the canonical capture-by-initial-value pitfall.

## When to use this skill

- The developer asks whether a value should be wrapped in `derivedStateOf`.
- A scroll position drives a boolean (`firstVisibleItemIndex == 0`, `scrollState.value > threshold`, "show FAB on scroll").
- A high-frequency input (drag delta, animation progress) feeds a low-frequency output (boolean threshold, bucketed integer).
- A `derivedStateOf` exists in the code but recomposition counts didn't drop, or the value never updates after the first composition (the capture-by-initial-value bug).
- The developer wants a one-shot side effect (analytics, logging, snackbar) when a derived value flips.

## When NOT to use this skill

- The state read is in the wrong phase (e.g. `Modifier.alpha(state.value)`). Fix that with `../deferring-state-reads/SKILL.md` first — `derivedStateOf` does not address phase issues.
- Diagnosing whether a parameter is unstable. That is `../../stability/diagnosing-compose-stability/SKILL.md`.
- Input frequency equals output frequency (e.g. `"$first $last"` from two name states). `derivedStateOf` is pure overhead in that case — use a direct read or a plain `remember`.

## Prerequisites

- Familiarity with state-read-driven recomposition: a snapshot read inside a restartable composable subscribes that composable's restart scope to the state.
- Compose runtime ≥ 1.0 (the API is foundational; behavior described here is stable).
- Ability to confirm recomposition counts via Layout Inspector or `@TraceRecomposition` from skydoves/compose-stability-analyzer.

## Workflow

- [ ] **1. Identify the input state(s) and the value the UI actually consumes.** Write down both update frequencies. Example: `listState.firstVisibleItemIndex` (changes every list item scrolled past) → `Boolean` (changes once when the user crosses index 0).

- [ ] **2. Compare frequencies.** Apply the rule:
    - Input updates **much more often** than output → `derivedStateOf` is the right tool.
    - Input and output update at roughly the same rate → `derivedStateOf` adds an extra snapshot subscription for nothing. Use a direct read or a plain `remember(input1, input2)`.

- [ ] **3. Wrap the derivation in `remember { derivedStateOf { … } }`.** A bare `derivedStateOf { }` would be re-created on every composition, defeating the cache. The `remember` is mandatory.

- [ ] **4. If the lambda captures non-state variables (function parameters, locals, props), pass them as `remember` keys.** A captured non-state variable is read once at first composition and frozen forever — the derivation will silently use the stale value. The fix is `remember(threshold) { derivedStateOf { … > threshold } }`.

- [ ] **5. For one-shot side effects on a derived value (logging, analytics, snackbar), prefer `snapshotFlow { … }.collect { … }` inside `LaunchedEffect`.** That avoids subscribing the composable's restart scope to the derived state; the side effect runs in a coroutine, off the composition path.

- [ ] **6. Verify in Layout Inspector / `@TraceRecomposition`.** The consuming composable's recomposition count MUST only increment when the derived result changes — not on every input tick. If it still climbs per input tick, either (a) the `derivedStateOf` is missing, (b) the `remember` is missing, or (c) something else (a sibling state read, a wrong-phase modifier) is invalidating the same scope.

## Patterns

### Pattern: scroll-to-top FAB — input >> output

```kotlin
// WRONG
val showFab = listState.firstVisibleItemIndex > 0
// WRONG because: this reads firstVisibleItemIndex on every recomposition; the consuming composable invalidates per scrolled item, not just when the boolean flips.
```

```kotlin
// RIGHT
val showFab by remember {
    derivedStateOf { listState.firstVisibleItemIndex > 0 }
}
```

`firstVisibleItemIndex` updates on every list item scrolled past; `showFab` only flips when the user crosses index 0. The `remember` keeps the derived state alive across compositions; the `derivedStateOf` filters out every input change that does not flip the boolean.

### Pattern: pure overhead — input ≈ output

```kotlin
// WRONG
val first by remember { mutableStateOf("Ada") }
val last by remember { mutableStateOf("Lovelace") }
val fullName by remember { derivedStateOf { "$first $last" } }
// WRONG because: first and last change at the same rate as fullName; derivedStateOf adds a snapshot subscription with zero filtering benefit.
```

```kotlin
// RIGHT — direct read, no derivedStateOf needed
val first by remember { mutableStateOf("Ada") }
val last by remember { mutableStateOf("Lovelace") }
val fullName = "$first $last"
```

If a memoization cost concern exists (the concatenation is expensive), use `remember(first, last) { computeFullName(first, last) }` — `derivedStateOf` is still the wrong shape because there is nothing to filter.

### Pattern: captured non-state variable — the silent bug

```kotlin
// WRONG
@Composable
fun Header(threshold: Int, listState: LazyListState) {
    val isLarge by remember {
        derivedStateOf { listState.firstVisibleItemIndex > threshold }
    }
    // WRONG because: threshold is captured inside remember { ... } at first composition; later threshold changes are ignored, and the derivation reuses the stale value forever.
}
```

```kotlin
// RIGHT — threshold is a remember key, so a new derivedStateOf is created when it changes
@Composable
fun Header(threshold: Int, listState: LazyListState) {
    val isLarge by remember(threshold) {
        derivedStateOf { listState.firstVisibleItemIndex > threshold }
    }
}
```

The rule: any non-`State` value captured by the `derivedStateOf` lambda MUST be a key on the surrounding `remember`. Otherwise the derivation locks in the value from first composition.

### Pattern: one-shot side effect → `snapshotFlow`

```kotlin
// WRONG
@Composable
fun Feed(listState: LazyListState, onScrolledPastFold: () -> Unit) {
    val pastFold by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 5 }
    }
    if (pastFold) onScrolledPastFold()
    // WRONG because: side effects in a composable body run on every (re)composition, can fire multiple times for the same flip, and tie the side effect to the composition lifecycle.
}
```

```kotlin
// RIGHT — fire-and-forget side effect via snapshotFlow
@Composable
fun Feed(listState: LazyListState, onScrolledPastFold: () -> Unit) {
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex > 5 }
            .distinctUntilChanged()
            .filter { it }
            .collect { onScrolledPastFold() }
    }
}
```

`snapshotFlow` reads snapshot state inside a coroutine and emits when the read-set's combined value changes. The `LaunchedEffect` keys the collection to `listState`, and `distinctUntilChanged()` ensures one emission per flip. No restart scope is involved — the consuming composable never recomposes for this signal.

### Pattern: derive across many inputs to one bucket

```kotlin
// RIGHT
val priceBucket by remember {
    derivedStateOf {
        when {
            cartTotal.value < 10_000 -> Bucket.Small
            cartTotal.value < 50_000 -> Bucket.Medium
            else -> Bucket.Large
        }
    }
}
```

`cartTotal` may tick by single won/cents; `priceBucket` flips at most twice across the entire range. This is the canonical use case.

## Mandatory rules

- **MUST** wrap every `derivedStateOf` in `remember { … }`. A bare `derivedStateOf { }` is re-created every composition and provides no filtering.
- **MUST** add captured non-state variables (function parameters, local vals, props) as `remember` keys. Otherwise the derivation freezes the values from first composition.
- **MUST NOT** use `derivedStateOf` when input and output frequency match. It is pure overhead; use a direct read or `remember(keys) { … }`.
- **MUST NOT** wrap the result of `collectAsState()` / `collectAsStateWithLifecycle()` in `derivedStateOf` "just to be safe" — that adds a subscription layer for nothing. Filter upstream with `.distinctUntilChanged()` or `.map { }` on the flow.
- **PREFERRED:** `snapshotFlow { … }` over `derivedStateOf` for fire-and-forget side effects. It keeps the composition free of the signal entirely.
- **PREFERRED:** measure recomposition counts before AND after the change. The whole point of `derivedStateOf` is to reduce them; if they didn't drop, something else is wrong (often a wrong-phase read — see `../deferring-state-reads/SKILL.md`).

## Verification

- [ ] Layout Inspector shows the consuming composable recomposes only when the derived result changes (e.g. once when the FAB appears, once when it disappears) — not on every input tick.
- [ ] Every `derivedStateOf` in the file is inside a `remember { … }` (or `remember(keys) { … }`) call.
- [ ] Every `derivedStateOf` lambda's captured non-state variables appear as `remember` keys, OR the lambda only reads `State` objects.
- [ ] Side effects on derived values run via `LaunchedEffect { snapshotFlow { … }.collect { } }`, not by reading the derived state in the composable body.
- [ ] A grep for `derivedStateOf {` shows no occurrences where input and output frequencies match (no `"$first $last"`-shaped uses).

## References

- Android Developers — Side-effects: derivedStateOf: https://developer.android.com/develop/ui/compose/side-effects#derivedstateof
- Android Developers — Phases of Jetpack Compose: https://developer.android.com/develop/ui/compose/phases
- Ben Trengrove — When should I use derivedStateOf?: https://medium.com/androiddevelopers/jetpack-compose-when-should-i-use-derivedstateof-63ce7954c11b
- Ben Trengrove — Debugging recomposition: https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Zach Klipp — How derivedStateOf works (a deep d-er-ive): https://blog.zachklipp.com/how-derivedstateof-works-a-deep-d-er-ive/
- Manuel Vivo — Consuming flows safely: https://medium.com/androiddevelopers/consuming-flows-safely-in-jetpack-compose-cde014d0d5a3
- Why test perf in release: https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
