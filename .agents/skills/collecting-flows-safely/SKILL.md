---
name: collecting-flows-safely
description: Use this skill to migrate Compose UI from `collectAsState()` to `collectAsStateWithLifecycle()`, hoist `Flow<T>` parameters out of composables, and apply `.conflate()` / `.distinctUntilChanged()` / `snapshotFlow` so background CPU and battery stop draining and chatty flows stop invalidating the UI per emission. Covers ViewModel `StateFlow`/`SharedFlow` consumers, sensor and location streams, and the "Flow as composable parameter" antipattern. Trigger when the user mentions `collectAsState`, `collectAsStateWithLifecycle`, lifecycle-aware flow collection, `Lifecycle.State.STARTED`, background battery drain from a Compose screen, `snapshotFlow`, `Flow` parameter on a composable, conflate, or distinctUntilChanged.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - collectasstatewithlifecycle
  - flow
  - lifecycle
  - battery
  - snapshotflow
  - side-effects
---

# Collecting Flows Safely — keep upstream work tied to the UI lifecycle

`collectAsState()` keeps collecting whenever the composable is in composition, including while the app is backgrounded — that wastes CPU and battery. `collectAsStateWithLifecycle()` ties collection to a `Lifecycle.State` (default `STARTED`) so backgrounding pauses upstream work. For chatty flows, pair the consumer with `.conflate()` and `.distinctUntilChanged()` so Compose only sees meaningful changes. Skydoves hot take #4: a `Flow<T>` parameter on a composable is unstable and blocks skipping for the whole composable — collect at the caller and pass the resolved value.

## When to use this skill

- A `ViewModel` or `Repository` exposes `StateFlow<T>`, `SharedFlow<T>`, or a cold `Flow<T>` to a Compose screen.
- The user reports background battery drain, "the screen keeps working when the app is in the recents tray", or wakelock noise on logcat.
- A composable consumes sensor data, location, GPS, websocket, or animation frames coming from outside Compose.
- A composable's signature is `fun MyScreen(state: Flow<State>)` (Flow-as-parameter antipattern).
- The user asks about `collectAsState` vs `collectAsStateWithLifecycle`, or about `Lifecycle.State.STARTED` / `RESUMED` semantics.
- The user wants Compose state to drive a non-Compose subscriber (analytics on visible item index, etc.) — that is the State→Flow direction served by `snapshotFlow`.

## When NOT to use this skill

- The flow is constructed and consumed entirely inside one composable's `remember { ... }` block — that is composition-internal state, prefer `mutableStateOf` directly. See `../using-efficient-effects/SKILL.md` for choosing the right effect API.
- The data source is already `State<T>` (e.g. `mutableStateOf`, `Animatable.asState()`) — do not wrap it in a flow just to call `collectAsStateWithLifecycle()`.
- A truly always-on background listener that must run while the Activity is `STOPPED`. Move that work to a `Service` / `WorkManager` / `repeatOnLifecycle` in the Activity, not into Compose.

## Prerequisites

- Compose UI 1.4+ (any modern release).
- Add `androidx.lifecycle:lifecycle-runtime-compose` (2.6+; the artifact that exposes `collectAsStateWithLifecycle`). Maven coordinates: `androidx.lifecycle:lifecycle-runtime-compose:<version>`.
- Kotlin coroutines basics (`StateFlow`, `SharedFlow`, `conflate`, `distinctUntilChanged`).
- Read `../../recomposition/deferring-state-reads/SKILL.md` if the high-frequency emissions are driving animation values — phase deferral may be a better fix than `.conflate()`.

## Workflow

1. **Audit every `collectAsState()` call.** Search the module for `\.collectAsState\(`. Replace each call with `collectAsStateWithLifecycle()` unless the producing flow is created inside the same composable scope.
2. **Provide an `initialValue`** when the flow is not a `StateFlow` (cold `Flow<T>` or `SharedFlow<T>`). For `StateFlow<T>`, the overload reads `.value` — no initial value needed.
3. **Pick the right `minActiveState`.** Default `STARTED` matches the framework's `repeatOnLifecycle` default. Use `RESUMED` for widgets that should only collect while the Activity owns input focus (e.g. always-visible foreground HUD with an aggressive sensor source). Do not use `CREATED` — that defeats the purpose.
4. **For high-frequency producers (>1 emission per ~100 ms)**, add `.conflate()` upstream of `collectAsStateWithLifecycle()` so the consumer keeps only the latest value across a frame. If consecutive emissions can be value-equal, also add `.distinctUntilChanged()` — and ensure the emitted type has a correct `equals()`.
5. **Hoist `Flow<T>` parameters out of composables.** Replace `fun Foo(prices: Flow<Price>)` with `fun Foo(price: Price)`. Collect at the caller. If the producer must stay private to the parent, expose a `() -> Price` lambda provider rather than the raw `Flow`.
6. **For State → Flow direction**, use `snapshotFlow { ... }` inside a `LaunchedEffect`. That is the supported bridge from Compose's snapshot system to coroutine flows; combine with `.distinctUntilChanged()` to avoid spurious emissions.
7. **Verify** with `@TraceRecomposition` (see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`) and a logcat sanity check with the app backgrounded — upstream emissions should stop.

## Patterns

### Pattern: replace `collectAsState` with `collectAsStateWithLifecycle`

```kotlin
// WRONG
import androidx.compose.runtime.collectAsState

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsState()
    HomeContent(state)
}
// WRONG because: collection continues while the app is backgrounded -> wasted CPU and battery,
// and any upstream operators (network polling, db queries) keep running.
```

```kotlin
// RIGHT
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun HomeScreen(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeContent(state)
}
```

### Pattern: custom `minActiveState` for an always-visible widget

```kotlin
// RIGHT
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun TopBar(viewModel: HomeViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    TopBarContent(state)
}
```

### Pattern: do not pass `Flow<T>` as a composable parameter (skydoves hot take #4)

```kotlin
// WRONG
@Composable
fun PriceTicker(prices: Flow<Price>) {
    val price by prices.collectAsState(initial = Price.ZERO)
    Text(price.formatted)
}
// WRONG because: Flow is unstable -> blocks skipping for the whole composable; the collection
// lifecycle is also unclear (recreated on every recomposition unless the caller remembers it).
```

```kotlin
// RIGHT
@Composable
fun PriceTicker(price: Price) {
    Text(price.formatted)
}

// Caller collects once and passes the value:
@Composable
fun TickerScreen(viewModel: TickerViewModel) {
    val price by viewModel.price.collectAsStateWithLifecycle()
    PriceTicker(price)
}
```

### Pattern: high-frequency flow needs `.conflate()` + `.distinctUntilChanged()`

```kotlin
// WRONG
@Composable
fun TiltIndicator(repository: SensorRepository) {
    val tilt by repository.tilt.collectAsStateWithLifecycle(initialValue = 0f)
    TiltUi(tilt)
}
// WRONG because: hundreds of emissions per second invalidate the consuming composable per emission;
// most are dropped frames worth of work.
```

```kotlin
// RIGHT
@Composable
fun TiltIndicator(repository: SensorRepository) {
    val flow = remember(repository) {
        repository.tilt.conflate().distinctUntilChanged()
    }
    val tilt by flow.collectAsStateWithLifecycle(initialValue = 0f)
    TiltUi(tilt)
}
```

### Pattern: State → Flow with `snapshotFlow`

```kotlin
// RIGHT
@Composable
fun FeedAnalytics(listState: LazyListState, analytics: Analytics) {
    LaunchedEffect(listState, analytics) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index -> analytics.logFirstVisibleIndex(index) }
    }
}
// snapshotFlow bridges snapshot State to a cold Flow without paying the cost of recomposing
// every time firstVisibleItemIndex changes — the read happens inside the LaunchedEffect's coroutine,
// not in the composition phase.
```

### Pattern: prefer `snapshotFlow` over `derivedStateOf` for fire-and-forget reactions

```kotlin
// LESS PREFERRED for an effect that only emits side effects
val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
LaunchedEffect(isAtTop) { if (isAtTop) reportTop() }
```

```kotlin
// PREFERRED — no derived state slot in composition; reads happen in the coroutine
LaunchedEffect(listState) {
    snapshotFlow { listState.firstVisibleItemIndex == 0 }
        .distinctUntilChanged()
        .filter { it }
        .collect { reportTop() }
}
```

## Mandatory rules

- **MUST** call `collectAsStateWithLifecycle()` (from `androidx.lifecycle:lifecycle-runtime-compose`) for any `Flow` that originates outside the composition (ViewModel, repository, sensor, network, websocket, location).
- **MUST NOT** pass `Flow<T>` as a composable parameter. Collect at the caller, pass the resolved `T` (or a `() -> T` lambda provider for very hot producers). Flow is unstable and blocks skipping for the entire composable.
- **MUST** add `.conflate()` and/or `.distinctUntilChanged()` upstream of `collectAsStateWithLifecycle()` for any flow emitting more often than ~once per 100 ms. Ensure `equals()` is correct on the emitted type when using `distinctUntilChanged`.
- **MUST** wrap the operator chain in `remember(key)` when applying `.conflate()` / `.distinctUntilChanged()` inline, so a new chain is not created on every recomposition.
- **MUST NOT** use `Lifecycle.State.CREATED` for `minActiveState` — it leaves collection running while the activity is invisible, defeating the migration.
- **MUST NOT** rewrap an existing `State<T>` into a flow just to call `collectAsStateWithLifecycle()`. Keep the `State` direct.
- **PREFERRED:** `snapshotFlow { ... }` over `derivedStateOf { ... }` when the only consumer is a fire-and-forget effect (analytics, logging, side-channel emit) instead of UI.

## Verification

- [ ] `grep -R "collectAsState(" src/` returns 0 hits, or each remaining hit collects a flow created inside the same composable.
- [ ] Background the app and watch logcat: upstream operators (`Repository` log lines, sensor callbacks) stop within one frame and resume on foregrounding.
- [ ] `@TraceRecomposition(traceStates = true)` on the consuming composable shows one recomposition per *meaningful* emission, not per raw upstream emission. See `../../measurement/tracing-recompositions-at-runtime/SKILL.md`.
- [ ] Battery Historian / Studio Energy Profiler shows no foreground-only work attributed to the screen while the app is backgrounded.
- [ ] No composable in the module declares a parameter of type `Flow<*>` (`grep -R ": Flow<" src/`).
- [ ] When `minActiveState = RESUMED` is used, the rationale (focus-required widget) is documented in code.

## References

- Manuel Vivo — Consuming flows safely in Jetpack Compose: https://medium.com/androiddevelopers/consuming-flows-safely-in-jetpack-compose-cde014d0d5a3
- Android Developers — Lifecycle-aware coroutines (`repeatOnLifecycle`): https://developer.android.com/topic/libraries/architecture/coroutines
- `androidx.lifecycle:lifecycle-runtime-compose` release notes: https://developer.android.com/jetpack/androidx/releases/lifecycle
- Android Developers — Compose side effects (`snapshotFlow`, `LaunchedEffect`): https://developer.android.com/develop/ui/compose/side-effects
- Skydoves — 6 Jetpack Compose Guidelines (Flow-as-parameter antipattern): https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- Ben Trengrove — Why test perf in release: https://medium.com/androiddevelopers/why-should-you-always-test-compose-performance-in-release-4168dd0f2c71
- Sibling skill: `../using-efficient-effects/SKILL.md` for `LaunchedEffect` / `DisposableEffect` / `RememberedEffect` selection.
- Sibling skill: `../../measurement/tracing-recompositions-at-runtime/SKILL.md` for verifying emission counts at runtime.
