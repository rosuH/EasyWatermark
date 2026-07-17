---
name: using-efficient-effects
description: Use this skill to choose the cheapest correct effect API in Jetpack Compose — `LaunchedEffect`, `DisposableEffect`, `SideEffect`, `rememberUpdatedState`, and skydoves/compose-effects' `RememberedEffect` and `ViewModelStoreScope`. Covers stale-callback bugs in long-lived `LaunchedEffect`, setup/teardown for non-coroutine subscribers, avoiding a coroutine scope just to react to a key change, and per-row ViewModels in a `LazyColumn`. Trigger when the user mentions LaunchedEffect, DisposableEffect, RememberedEffect, SideEffect, rememberUpdatedState, ViewModelStoreScope, effect restarts unexpectedly, leaked listener, or per-item ViewModel.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - launchedeffect
  - disposableeffect
  - rememberedeffect
  - rememberupdatedstate
  - viewmodelstorescope
  - side-effects
---

# Using Efficient Effects — pick the cheapest correct effect API

Compose ships three effect APIs by default: `LaunchedEffect` (coroutine, restarts on key change), `DisposableEffect` (setup/teardown with a cleanup block), and `SideEffect` (runs on every successful composition). The `skydoves/compose-effects` library adds `RememberedEffect` — a non-coroutine analog of `LaunchedEffect`. Picking the wrong API wastes a coroutine scope, leaks a listener, or silently drops the latest version of a callback. This skill is the decision tree.

## When to use this skill

- The user asks which effect API to choose, or why a `LaunchedEffect` restarts unexpectedly.
- A long-lived `LaunchedEffect` calls a stale version of a callback that the parent has updated.
- A non-Compose subscriber (listener, observer, broadcast receiver, GL surface) needs setup at composition entry and teardown on key change or exit.
- A composable just needs to react to a key change synchronously and a coroutine scope is overkill.
- A `LazyColumn` row needs its own `ViewModel` instance (e.g. one VM per chat row, one VM per feed card) and the parent VM store would leak state across rows.
- The user is debugging unexpected effect restarts, leaked listeners, or per-frame allocations from `SideEffect`.

## When NOT to use this skill

- Pure derivation of one `State<T>` from another — use `derivedStateOf`. See `../../recomposition/choosing-derivedstateof/SKILL.md`.
- Collecting a flow that originates outside the composition — use `collectAsStateWithLifecycle`. See `../collecting-flows-safely/SKILL.md`.
- One-shot computation that must survive recomposition without re-running — use `remember { ... }`, not `LaunchedEffect(Unit)`.

## Prerequisites

- Familiarity with Compose's composition lifecycle: enter, recompose, leave. Effects run after composition commits.
- Compose UI 1.4+.
- For `RememberedEffect`, add `com.github.skydoves:compose-effects` (latest GA — see https://github.com/skydoves/compose-effects).
- For `ViewModelStoreScope`, also depend on `com.github.skydoves:compose-effects-viewmodel`. Import path: `com.skydoves.compose.effects.viewmodel.ViewModelStoreScope`.
- For `ViewModelStoreScope` with Hilt, also `androidx.hilt:hilt-navigation-compose` for `hiltViewModel()`.

## Workflow — decision tree

1. **Need a coroutine?** → `LaunchedEffect(key1, key2, ...)`. The block is launched in the composition's `CoroutineScope` and cancelled/relaunched whenever any key changes. Use for network calls, animations, suspending work.
2. **Need setup + cleanup with no coroutine?** → `DisposableEffect(key) { onDispose { ... } }`. The `onDispose` block runs on key change AND when the composable leaves composition. Use for listeners, observers, manual subscriptions.
3. **Need to react synchronously to a key change with no coroutine?** → `RememberedEffect(key) { ... }` from `skydoves/compose-effects`. Cheaper than spinning a `LaunchedEffect` scope just to call a synchronous function. No `onDispose` — pair with `DisposableEffect` if you need teardown.
4. **Long-lived `LaunchedEffect` that consumes a callback that may change?** → wrap the callback with `val latest by rememberUpdatedState(callback)` and reference `latest` inside the effect. The effect keeps the same coroutine; the callback reference stays fresh.
5. **Per-composable `ViewModel` (one per row in a `LazyColumn`, etc.)?** → wrap the row body in `ViewModelStoreScope(key = <stableId>) { ... }` from `com.github.skydoves:compose-effects-viewmodel` and call `hiltViewModel()` (or `viewModel()`) inside. The `key` is required and must be a stable identifier (the row's id, etc.); each key gets its own store.
6. **Side effect that must run after every successful composition?** → `SideEffect { ... }`. Rare. Use for publishing Compose state to a non-Compose system that you cannot subscribe to via `DisposableEffect` (e.g. updating a `View`'s field on every commit). Avoid for per-frame work — it really does run every commit.

## Patterns

### Pattern: stale callback inside a long-lived `LaunchedEffect`

```kotlin
// WRONG
@Composable
fun Timer(onTick: () -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            onTick()
        }
    }
}
// WRONG because: onTick is captured by the first composition's value; later parents pass an
// updated lambda but the running coroutine still holds the original reference -> the new
// onTick is silently ignored.
```

```kotlin
// RIGHT
@Composable
fun Timer(onTick: () -> Unit) {
    val latest by rememberUpdatedState(onTick)
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            latest()
        }
    }
}
// rememberUpdatedState updates the ref on every recomposition; the coroutine reads the freshest
// reference each tick without restarting.
```

### Pattern: `DisposableEffect` for a non-coroutine subscriber

```kotlin
// WRONG
@Composable
fun LocationBadge(client: LocationClient) {
    LaunchedEffect(client) {
        try {
            val listener = LocationListener { /* ... */ }
            client.register(listener)
            awaitCancellation()
        } finally {
            client.unregisterAll()
        }
    }
}
// WRONG because: spawning a coroutine just to hold a listener wastes a scope and obscures the
// teardown contract; awaitCancellation is the wrong primitive for a non-suspending subscriber.
```

```kotlin
// RIGHT
@Composable
fun LocationBadge(client: LocationClient) {
    DisposableEffect(client) {
        val listener = LocationListener { /* ... */ }
        client.register(listener)
        onDispose { client.unregister(listener) }
    }
}
```

### Pattern: `RememberedEffect` over `LaunchedEffect` for synchronous key reactions

```kotlin
// WRONG (over-engineered)
LaunchedEffect(themeKey) {
    applyTheme(themeKey)
}
// WRONG because: spawns and cancels a coroutine scope just to call a synchronous function on
// key change. No suspension happens; the scope is pure overhead.
```

```kotlin
// RIGHT (with com.github.skydoves:compose-effects)
import com.skydoves.compose.effects.RememberedEffect

RememberedEffect(themeKey) {
    applyTheme(themeKey)
}
```

### Pattern: `ViewModelStoreScope` for per-row ViewModels in a `LazyColumn`

```kotlin
// WRONG
@Composable
fun Feed(rows: List<Row>) {
    LazyColumn {
        items(rows, key = { it.id }) { row ->
            // hiltViewModel() here resolves to the parent screen's store -> every row shares
            // the same VM instance, leaking state across rows.
            val vm = hiltViewModel<RowVm>()
            RowContent(row, vm)
        }
    }
}
// WRONG because: every row's hiltViewModel call resolves against the parent NavBackStackEntry
// store -> one VM is shared across all rows -> per-row state collapses into one slot.
```

```kotlin
// RIGHT
import com.skydoves.compose.effects.viewmodel.ViewModelStoreScope

@Composable
fun Feed(rows: List<Row>) {
    LazyColumn {
        items(rows, key = { it.id }) { row ->
            ViewModelStoreScope(key = row.id) {
                val vm = hiltViewModel<RowVm>()
                RowContent(row, vm)
            }
        }
    }
}
// ViewModelStoreScope(key = ...) installs a composable-scoped ViewModelStore tied to the
// supplied key; each row gets its own VM and onCleared fires when the row leaves composition.
// The key MUST be stable across recompositions — the row's id is the canonical choice.
```

### Pattern: `remember { }` vs `LaunchedEffect(Unit)` for one-shot work

```kotlin
// WRONG
@Composable
fun Greeter(name: String) {
    LaunchedEffect(Unit) {
        Log.d("Greeter", "First seen: $name")
    }
}
// WRONG because: LaunchedEffect(Unit) re-runs after configuration change recreation when the
// composable is re-attached to a new composition. For "do this once and never again across
// the lifetime of this composable instance", use remember.
```

```kotlin
// RIGHT
@Composable
fun Greeter(name: String) {
    remember { Log.d("Greeter", "First seen: $name") }
}
// remember { } evaluates its block once per slot and never again unless the slot is removed
// and re-created. No coroutine, no effect machinery.
```

## Mandatory rules

- **MUST** wrap externally-passed callbacks in `rememberUpdatedState` when consumed inside a long-lived `LaunchedEffect` (one whose key set does not change when the callback identity changes). Otherwise the effect captures the stale lambda forever.
- **MUST** prefer `DisposableEffect` over `LaunchedEffect { try { } finally { } }` for non-coroutine subscribers. The cleanup contract is explicit and runs on key change as well as composition exit.
- **MUST NOT** use `LaunchedEffect(Unit)` to run a one-shot operation that must survive recomposition without re-running. Use `remember { ... }` for that.
- **MUST NOT** use `SideEffect` for per-frame work or for anything that allocates. It runs on every successful commit.
- **MUST** key effects on every value the effect closes over that should restart it. Lying about the key set is the most common source of "my effect doesn't see the new value" bugs.
- **MUST** use `ViewModelStoreScope(key = <stableId>) { hiltViewModel<T>() }` (from `com.github.skydoves:compose-effects-viewmodel`) when calling `hiltViewModel()` inside a `LazyColumn` / `LazyRow` item that needs its own VM instance — without it, all rows share the parent screen's store. The `key` parameter is required and MUST be a stable id (e.g. the row's domain id).
- **PREFERRED:** `RememberedEffect(key) { ... }` from `skydoves/compose-effects` over `LaunchedEffect(key) { ... }` when the body does not suspend. Cheaper than allocating a coroutine scope on every key change.
- **PREFERRED:** for flow consumption, do not write `LaunchedEffect(viewModel) { viewModel.flow.collect { ... } }` — use `collectAsStateWithLifecycle()` instead. See `../collecting-flows-safely/SKILL.md`.

## Verification

- [ ] `@TraceRecomposition` on the host composable shows the expected number of effect runs per key change (typically: one teardown + one setup per key change; zero on unrelated recompositions). See `../../measurement/tracing-recompositions-at-runtime/SKILL.md`.
- [ ] Add a logcat line in every `DisposableEffect.onDispose { ... }` and confirm it fires on key change AND on backgrounding the host screen.
- [ ] Drive the parent to update a callback rapidly (e.g. counter in state); confirm the long-lived `LaunchedEffect` sees the latest value via `rememberUpdatedState`.
- [ ] For `ViewModelStoreScope` rows, log the row VM's `init { }` and `onCleared()` — they should fire per row, scoped to that row's identity, not once for the whole list.
- [ ] No `SideEffect { }` block in the codebase performs allocation-heavy work; it should be a thin pointer write to a non-Compose subscriber.
- [ ] No `LaunchedEffect(Unit) { /* non-suspending work */ }` remains — replace with `remember { ... }` (one-shot) or `RememberedEffect(key) { ... }` (key-driven).

## References

- Android Developers — Side effects in Compose: https://developer.android.com/develop/ui/compose/side-effects
- Android Developers — Lifecycle of composables: https://developer.android.com/develop/ui/compose/lifecycle
- Skydoves — compose-effects (`RememberedEffect`, `ViewModelStoreScope`): https://github.com/skydoves/compose-effects
- Skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- Ben Trengrove — Debugging recomposition: https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Sibling skill: `../collecting-flows-safely/SKILL.md` for `collectAsStateWithLifecycle` and the Flow-as-parameter antipattern.
- Sibling skill: `../../recomposition/choosing-derivedstateof/SKILL.md` for the State→State derivation case (not an effect).
- Sibling skill: `../../measurement/tracing-recompositions-at-runtime/SKILL.md` for `@TraceRecomposition` setup.
