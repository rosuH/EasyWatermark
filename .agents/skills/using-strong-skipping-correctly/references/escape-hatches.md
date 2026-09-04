# Strong Skipping Escape Hatches — full reference

A complete reference for the four Compose compiler annotations that opt a function or lambda out of a default skipping/memoization behavior. All four ship in `androidx.compose.runtime`.

This is a companion to `../SKILL.md`. The SKILL covers the decision tree at a high level; this file covers each annotation's exact contract, signature, code sample, and when it is the wrong tool.

---

## Decision matrix

| Annotation | Applies to | Default behavior it disables | Use when | Avoid when |
|---|---|---|---|---|
| `@NonRestartableComposable` | `@Composable` function | Generates a restart scope (recomposition entry point) | Trivially small composables where the bookkeeping costs more than the body. Compose UI uses it internally on tiny wrapper functions. | App code, almost always. The compiler usually picks the right thing; a developer guessing this is wrong. |
| `@NonSkippableComposable` | `@Composable` function | Skips body when all params compare equal | Side-effect-only composables (logger, telemetry tick, debug overlay) that must run on every recomposition regardless of param equality. | The composable "must always run" because of a misplaced state read or a bug in upstream stability. Fix the root cause first. |
| `@DontMemoize` | Lambda **expression** site (under strong skipping) | Wrapping the lambda in `remember(captures) { ... }` | The lambda must capture the latest values per call (telemetry snapshot, fresh closure intentionally). Rare; only when measured. | Default UI callbacks. The auto-`remember` is what makes strong skipping work; opt-out without justification is a regression. |
| `@ReadOnlyComposable` | `@Composable` function | Allowing emission of nodes / participation in restart scope as a normal composable | Pure-getter composables that read CompositionLocals or other composable state but emit nothing — e.g. `MaterialTheme.colorScheme`. | The function emits any node, calls another composable that emits, or starts a coroutine. The contract is read-only. |

---

## `@NonRestartableComposable`

### Signature

```kotlin
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
public annotation class NonRestartableComposable
```

### What it changes

A normal `@Composable` function is "restartable": the compiler wraps the body in a `RestartableGroup` so the runtime can re-invoke just that function when its inputs invalidate. `@NonRestartableComposable` removes that wrapper. Recomposition flows through the function but cannot stop at it — the parent's restart scope is the nearest entry point.

### When right

Trivial pass-through composables where the restart scope adds bytecode without a measurable benefit. Compose's own `Box`, `Row`, `Column` are *inline* (a different mechanism), but other tiny wrappers in the runtime are annotated `@NonRestartableComposable`.

```kotlin
// RIGHT — internal helper that just forwards to another composable
@NonRestartableComposable
@Composable
internal fun ProvideTextStyle(value: TextStyle, content: @Composable () -> Unit) {
    val merged = LocalTextStyle.current.merge(value)
    CompositionLocalProvider(LocalTextStyle provides merged, content = content)
}
```

### When wrong

Putting it on app-level composables to "make them faster". The compiler chooses restartability based on what it can see; opting out makes the parent recompose on every input change to the child, which is usually slower in practice.

```kotlin
// WRONG
@NonRestartableComposable
@Composable
fun UserCard(user: User) { /* nontrivial body */ }
// WRONG because: the parent now rebuilds UserCard on every parent recomposition
// even when `user` is the same. The annotation is a footgun outside the runtime.
```

---

## `@NonSkippableComposable`

### Signature

```kotlin
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
public annotation class NonSkippableComposable
```

### What it changes

Under strong skipping, every restartable composable is normally also skippable — the compiler emits a guard that compares each param and `skipToGroupEnd()`s when all are equal. `@NonSkippableComposable` removes the guard. The body runs on every recomposition the parent triggers, regardless of param equality.

### When right

Side-effect-only composables that must observe every recomposition tick.

```kotlin
// RIGHT — telemetry tick that should fire every time, not just on input change
@NonSkippableComposable
@Composable
fun ScreenViewLogger(screen: String) {
    SideEffect { analytics.logScreenTick(screen) }
}
```

```kotlin
// RIGHT — debug overlay
@NonSkippableComposable
@Composable
fun RecompositionCounter(label: String) {
    val ticks = remember { mutableIntStateOf(0) }
    SideEffect { ticks.intValue++ }
    Text("$label: ${ticks.intValue}", color = Color.Red)
}
```

### When wrong

Marking a composable `@NonSkippableComposable` to "force it to update" when the real bug is an unstable param or a missed `mutableStateOf` read. The annotation papers over the diagnostic. Fix the upstream stability first (`../../stability/diagnosing-compose-stability/SKILL.md`).

```kotlin
// WRONG
@NonSkippableComposable
@Composable
fun UserHeader(user: User) { Text(user.name) }
// WRONG because: if UserHeader needs to "always update", the upstream User type is unstable
// or being copy()d unnecessarily. This annotation hides the real cost — every parent
// recomposition pays for the body — instead of fixing the source.
```

---

## `@DontMemoize`

### Signature

```kotlin
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.EXPRESSION)
public annotation class DontMemoize
```

`@DontMemoize` targets **lambda expressions only** — not types. `@Retention(SOURCE)` means it never reaches the binary; the Compose compiler reads it from the AST and skips the auto-`remember` wrapper for that one expression. Putting `@DontMemoize` at a type position (e.g. `(@DontMemoize () -> Unit) -> Unit`) does **not compile** in modern Compose Compiler.

### What it changes

Strong skipping wraps every lambda literal inside a `@Composable` function in `remember(captures) { ... }` so the same instance is reused across recompositions when the captures are equal. `@DontMemoize` placed in front of a specific lambda **expression** opts that one lambda out of the wrapping. The lambda is allocated fresh on every recomposition, capturing the current values.

### When right

The lambda is a snapshot — its purpose is to capture the live state at the moment of invocation, not to be stable across recompositions. Apply the annotation at the lambda **expression site**, not the parameter type.

```kotlin
// RIGHT — annotate the lambda expression that should NOT be auto-remembered
@Composable
fun TraceClicks(counter: Int, content: @Composable (() -> Unit) -> Unit) {
    content(@DontMemoize { Log.d("Click", "counter at click time = $counter") })
}
```

```kotlin
// RIGHT — apply at the call site for a stored handler
val onClick = @DontMemoize { fresh.captures(state) }
```

```kotlin
// RIGHT — apply at an interop call site that needs the latest value
Button(onClick = @DontMemoize { vm.snapshot(latestTick) }) { Text("send") }
```

### When wrong

Default UI callbacks. The auto-`remember` is precisely what makes child composables skippable. Opting out without measuring usually causes the child to recompose on every parent tick.

```kotlin
// WRONG
Button(onClick = @DontMemoize { vm.add(item) }) { Text("add") }
// WRONG because: now the onClick allocates fresh per recompose, fails === in Button's
// strong-skipping check, and Button recomposes every time the parent ticks. Use it only
// when the lambda's contract is "always capture the current value", not as a default.
```

```kotlin
// WRONG — annotation at a type position; will not compile
fun TraceClicks(content: @Composable (@DontMemoize () -> Unit) -> Unit) { /* ... */ }
// WRONG because: @DontMemoize is @Target(EXPRESSION), not TYPE. Move the annotation to the
// lambda literal at the call site, e.g. `content(@DontMemoize { ... })`.
```

`@DontMemoize` is the sharpest of the four annotations. Default to not using it; reach for it only when a measurement (`@TraceRecomposition` or Layout Inspector) shows a captured stale value.

---

## `@ReadOnlyComposable`

### Signature

```kotlin
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class ReadOnlyComposable
```

### What it changes

Declares the function only **reads** composition state (CompositionLocals, ambient data, remembered values) and emits **no nodes**. The compiler skips the per-call group bookkeeping that normal composables incur, making the function cheap enough to call from initialization expressions and parameter defaults.

### When right

Pure getters that surface composition data.

```kotlin
// RIGHT — Compose itself uses this on theme accessors
val ColorScheme.primary: Color
    @ReadOnlyComposable
    @Composable
    get() = LocalColorScheme.current.primary
```

```kotlin
// RIGHT — exposing a computed value from CompositionLocals
@ReadOnlyComposable
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0]
```

### When wrong

The function emits any node (calls `Text`, `Box`, etc.) or starts a coroutine. The contract is read-only; violating it is a compiler error in modern Compose, but on older versions it produced runtime crashes.

```kotlin
// WRONG
@ReadOnlyComposable
@Composable
fun Header(title: String) {
    Text(title)
}
// WRONG because: Text emits a layout node. @ReadOnlyComposable forbids emission.
// The compiler will reject this on Compose Compiler ≥1.5; on older versions it crashed at runtime.
```

---

## Cross-reference cheat sheet

| The developer asked … | Annotation answer | See also |
|---|---|---|
| "Why is my onClick allocating per recompose inside `LazyListScope.items { }`?" | None — `items` is not @Composable; strong skipping does not reach. Hoist a stable lambda or use a method reference. | `../SKILL.md` workflow step 3. |
| "How do I force this telemetry composable to run every time?" | `@NonSkippableComposable` — but only if the upstream stability is already correct. | `../../../stability/diagnosing-compose-stability/SKILL.md`. |
| "I want this lambda to always capture the latest counter." | `@DontMemoize` — but measure first; the default auto-`remember` is correct for almost all UI. | `../../debugging-recompositions/SKILL.md`. |
| "I'm writing a getter for a CompositionLocal-backed value." | `@ReadOnlyComposable`. | Compose source: `androidx.compose.material3.MaterialTheme`. |
| "This wrapper is too small to deserve a restart scope." | `@NonRestartableComposable` — almost never the right call in app code. | Trust the compiler default. |

---

## Verifying the annotation took effect

After adding any of these annotations, regenerate Compose Compiler reports and confirm the change in `*-composables.txt`:

```bash
./gradlew :app:assembleRelease
grep -A1 "MyComposable" app/build/compose_compiler/app-release-composables.txt
```

Expected diffs:

- `@NonRestartableComposable` removes the `restartable` modifier from the line.
- `@NonSkippableComposable` removes the `skippable` modifier from the line.
- `@ReadOnlyComposable` adds `readonly` (older compilers) or omits the call group entirely.
- `@DontMemoize` is invisible in `*-composables.txt`; verify with `@TraceRecomposition` at runtime that the lambda allocates per recompose (the trace shows the lambda parameter as `Changed` every tick).

---

## Sources

- Compose Compiler runtime annotations — https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary
- Strong Skipping Mode — https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Ben Trengrove, "Jetpack Compose Strong Skipping Mode explained" — https://medium.com/androiddevelopers/jetpack-compose-strong-skipping-mode-explained-cbdb2aa4b900
- Chris Banes, "Composable metrics" — https://chrisbanes.me/posts/composable-metrics/
- Compose Compiler source (annotations.kt) — https://cs.android.com/androidx/platform/frameworks/support/+/androidx-main:compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composable.kt
