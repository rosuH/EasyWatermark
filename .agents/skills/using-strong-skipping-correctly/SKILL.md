---
name: using-strong-skipping-correctly
description: Use this skill to reason about Jetpack Compose's Strong Skipping Mode — the default since Kotlin 2.0.20 — including what it changes about skippability, when it does and does not auto-`remember` lambdas, and which escape hatches (`@DontMemoize`, `@NonSkippableComposable`, `@NonRestartableComposable`, `@ReadOnlyComposable`) apply where. Covers verifying the mode is active, auditing lambda capture sites, and the gaps where strong skipping does not memoize (`LazyListScope.items {}`, `Modifier.pointerInput {}`, object expressions, non-@Composable scopes). Use when the developer asks "do I still need @Stable?", "does this composable skip?", "why does this still recompose despite strong skipping", "when do I need @DontMemoize or @NonSkippableComposable?", is migrating from older Compose, or sees auto-remembered lambdas in compiler output.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - strong-skipping
  - recomposition
  - lambda-memoization
  - dont-memoize
  - non-skippable-composable
  - kotlin-2.0.20
  - compose-compiler
---

# Using Strong Skipping Correctly — make every restartable composable skippable, intentionally

Strong Skipping Mode is the Compose compiler behavior that became the default with the Kotlin Compose compiler plugin shipped in **Kotlin 2.0.20**. It changes two things at once: every restartable composable becomes skippable regardless of param stability (unstable params are compared with `===`, stable params with `equals`), and every **capturing** lambda literal written inside a `@Composable` function is automatically wrapped in `remember(captures) { ... }`. Both behaviors are on by default; neither requires opt-in flags on Kotlin 2.0.20+. Lambdas with no captures are already compiler-emitted singletons and are NOT wrapped — only capturing lambdas need (and get) the auto-`remember` treatment.

That sounds like "stability no longer matters", and for a lot of UI code it almost is. But the change leaves three sharp edges: stable types still need correct `equals` semantics so the `===` fallback does not invalidate every recompose, lambdas in non-@Composable scopes (`LazyListScope.items { }`, `Modifier.pointerInput { }`, plain object expressions) are not auto-memoized, and a few intentional cases need explicit opt-out via `@DontMemoize` or `@NonSkippableComposable`. This skill walks the verification, audit, and escape-hatch decisions for working in a strong-skipping world.

## When to use this skill

- The developer asks "do I still need `@Stable`?" or "does this composable skip now?".
- A composable still recomposes on every parent tick even though the developer "thought strong skipping fixed that".
- A lambda allocation question comes up — "is this `onClick = { vm.add(it) }` allocating per recompose?".
- Migrating a project from Kotlin 1.9.x / Compose Compiler 1.5.x to Kotlin 2.0.20+ and reconciling reports that now show `restartable skippable` everywhere.
- The developer encounters `@DontMemoize` or `@NonSkippableComposable` in code or compiler output and asks what they do.
- The user mentions "strong skipping", "auto-remember", "@DontMemoize", "@NonSkippableComposable", or "skippable everywhere".

## When NOT to use this skill

- The conceptual question is "what makes a type stable in the first place?" — use `../../stability/understanding-stability-inference/SKILL.md`.
- Stability is regressing silently and the team needs a CI gate — use `../../stability/enforcing-stability-in-ci/SKILL.md`.
- Even with strong skipping on, type-level stability still matters for diagnostic clarity and for `equals`-based skipping to work — use `../../stability/diagnosing-compose-stability/SKILL.md` to fix the underlying types.
- The recomposition is happening but the developer cannot see it — use `../debugging-recompositions/SKILL.md` to instrument first.

## Prerequisites

- Kotlin **2.0.20+** with the Compose compiler Gradle plugin applied via `org.jetbrains.kotlin.plugin.compose`. Strong Skipping is on by default. To turn it **off** on Kotlin 2.0.20+, use `composeCompiler { featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled()) }` (the legacy `enableStrongSkippingMode = true` property is `@Deprecated("Use the featureFlags option instead")` and still works on older Kotlin).
- Compose Compiler reports turned on for the module under audit (see `../../stability/diagnosing-compose-stability/SKILL.md`).
- Builds running in **release** when reading reports — debug adds Live Literals which distort skip behavior (see `../../measurement/testing-compose-in-release-mode/SKILL.md`).
- Familiarity with the per-composable line format in `*-composables.txt` (`restartable skippable fun Foo(...)`).

## Workflow

### 1. Verify strong skipping is actually active

Before claiming any behavior is "because of strong skipping", confirm the toolchain is on a version where it is the default, or the legacy flag is set.

```bash
./gradlew :app:dependencies --configuration kotlinCompilerPluginClasspathRelease | grep -i compose
```

Expect the Compose compiler plugin to come from Kotlin 2.0.20 or later. In `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.android)        // Kotlin 2.0.20+
    alias(libs.plugins.kotlin.compose)        // org.jetbrains.kotlin.plugin.compose
}
```

To **disable** strong skipping on Kotlin 2.0.20+ (the modern feature-flag DSL):

```kotlin
import androidx.compose.compiler.plugins.kotlin.ComposeFeatureFlag

composeCompiler {
    featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())
}
```

The legacy property `composeCompiler { enableStrongSkippingMode = true }` is `@Deprecated("Use the featureFlags option instead")` in modern Compose Compiler Gradle plugin releases. It still works (and is still the only switch on older Kotlin 1.9.x / 2.0.0–2.0.10), but new code should prefer `featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())` when opting out.

Then regenerate Compose Compiler reports and grep for a known previously-unstable signature:

```bash
./gradlew :app:assembleRelease
grep -A1 "restartable" app/build/compose_compiler/app-release-composables.txt | head -40
```

Under strong skipping, **every** restartable composable should print `restartable skippable`, including ones that take `List<Foo>` or other unstable parameters. If a composable is still listed `restartable` without `skippable`, it is using `@NonSkippableComposable` (intentional) or the mode has been turned off (e.g. someone added `featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())` or set the legacy `enableStrongSkippingMode = false`).

### 2. Audit lambda capture sites inside @Composable scopes

Strong skipping wraps every **capturing** lambda literal written inside a `@Composable` function in `remember(captures) { ... }`. The captured-values list is the compiler-generated `remember` key. Capture-less lambdas (e.g. `{ vm.refresh() }` where `vm` is a stable file-scope reference, or `{ /* no-op */ }`) are already compiler-emitted singletons and are NOT auto-`remember`ed — there is nothing to key on. Three consequences:

- A capture-less lambda is a single shared instance across all recompositions of the surrounding function (singleton emitted by the compiler).
- A lambda that captures only stable values is memoized once via `remember(captures)` and re-used across recompositions while the captures compare equal.
- A lambda that captures an **unstable** value (e.g. a `List<Foo>` literal not hoisted) re-captures on every recompose because the unstable capture compares with `===`, and a fresh literal is a new reference.

Inspect the suspect call site. The mental model: "what does this lambda close over, and is each capture stable by `equals` and identity-stable across recompositions?".

```kotlin
// RIGHT — captures stable id + stable callback; auto-remembered by strong skipping
@Composable
fun Row(snack: Snack, onClick: (Long) -> Unit) {
    Button(onClick = { onClick(snack.id) }) { Text(snack.name) }
}
```

```kotlin
// WRONG — captures a freshly allocated list literal; new reference each recompose
@Composable
fun Header(title: String) {
    Toolbar(actions = listOf(Action.Share, Action.Save)) { /* ... */ }
}
// WRONG because: `listOf(...)` allocates a new List every recompose. Even with strong
// skipping the unstable param compares with ===, fails, and Toolbar recomposes every time.
```

```kotlin
// RIGHT — hoist the unstable literal so its identity is stable
@Composable
fun Header(title: String) {
    val actions = remember { listOf(Action.Share, Action.Save) }
    Toolbar(actions = actions) { /* ... */ }
}
```

### 3. Identify the gaps where strong skipping does NOT memoize

Auto-remember only applies to lambdas inside a **`@Composable`** function context. The following are **not** `@Composable` scopes; lambdas inside them are not auto-remembered and need a manual `remember(key)` if allocation matters:

- `LazyListScope.items { ... }`, `LazyGridScope.items { ... }`, `LazyVerticalGrid.items { ... }`, etc. — the `items` block is a regular Kotlin DSL builder.
- `Modifier.pointerInput { ... }`, `Modifier.draggable { ... }`, `Modifier.scrollable { ... }` — non-@Composable lambdas.
- Object expressions and SAM conversions (e.g. `object : DefaultLifecycleObserver { }`).
- `Modifier.drawBehind { ... }`, `Modifier.drawWithCache { ... }` — the lambda parameters are not @Composable.
- Coroutine builders inside non-@Composable scopes.

For these, hoist a stable lambda yourself, keyed on whatever may change:

```kotlin
// WRONG — onClick allocated per item per recompose; strong skipping does not reach here
LazyColumn {
    items(snacks, key = { it.id }) { snack ->
        SnackRow(snack, onClick = { vm.select(snack.id) })
    }
}
// WRONG because: `items { snack -> ... }` is LazyListScope, not a @Composable function.
// Strong skipping does not auto-remember the onClick lambda — a new instance allocates per row,
// per recompose, and SnackRow's `===` comparison on the lambda param fails every time.
```

```kotlin
// RIGHT — hoist a stable handler keyed on the stable callback
LazyColumn {
    items(snacks, key = { it.id }) { snack ->
        val onClick = remember(snack.id) { { vm.select(snack.id) } }
        SnackRow(snack, onClick = onClick)
    }
}
```

```kotlin
// RIGHT — even better: pass the id-aware callback once
LazyColumn {
    items(snacks, key = { it.id }) { snack ->
        SnackRow(snack, onClick = vm::select)   // method reference is stable
    }
}
```

### 4. Decide on escape hatches

Strong skipping is opinionated. A handful of cases legitimately need the opposite behavior. The full reference table is in `references/escape-hatches.md`; the high-level decision tree:

- **`@DontMemoize`** at a lambda **expression** site (the annotation targets `EXPRESSION` only, not `TYPE`) — the lambda must NOT be auto-`remember`ed. Use when the lambda intentionally captures a fresh value per call (e.g. a logger that should record the latest counter, not the one captured by the first composition). Apply it at the call site: `Button(onClick = @DontMemoize { ... })`, never at a parameter type position like `(@DontMemoize () -> Unit)`.
- **`@NonSkippableComposable`** on a function — opt the function out of skipping entirely. Use for side-effect-only composables (a logger, a debug overlay) that must run on every recompose regardless of param equality.
- **`@NonRestartableComposable`** on a function — drop the restart scope entirely. Use only for very small composables where the restart bookkeeping costs more than the function body. Almost never needed in app code; Compose UI uses it internally.
- **`@ReadOnlyComposable`** on a function — declare the function only reads composition values and produces no nodes, so it can run in initialization contexts (e.g. computing a value to pass into another composable). Compose's `MaterialTheme.colorScheme` getters use it.

```kotlin
// RIGHT — logger composable that must always emit
@NonSkippableComposable
@Composable
fun RecompositionLogger(label: String) {
    SideEffect { Log.d("Recomp", "tick: $label @ ${System.currentTimeMillis()}") }
}
```

```kotlin
// RIGHT — annotate the lambda expression that intentionally captures fresh per-call state.
// @DontMemoize is @Target(EXPRESSION) — apply it on the lambda literal, NOT on the type.
@Composable
fun TraceClicks(counter: Int, content: @Composable (() -> Unit) -> Unit) {
    content(@DontMemoize { Log.d("Click", "counter at click time = $counter") })
}
```

### 5. Prove behavior with @TraceRecomposition

Reading reports tells the story up to the function boundary. To confirm a specific composable is or is not being skipped at runtime, instrument with `@TraceRecomposition` from `compose-stability-analyzer`. Cross-link `../../measurement/tracing-recompositions-at-runtime/SKILL.md` for the full setup. The two-line summary:

```kotlin
@TraceRecomposition(traceStates = true)
@Composable
fun SnackRow(snack: Snack, onClick: (Long) -> Unit) { /* ... */ }
```

Logcat will print one line per recomposition with the changed parameters, which directly answers "did strong skipping skip this call or not?".

## Patterns

### Pattern: stability still matters even under strong skipping

Strong skipping does not eliminate the need for `@Immutable` / `@Stable`. It changes the comparison from `equals` to `===` for unstable params. `===` is reference equality. If a stable type is needed in a hot path, mark it `@Immutable` so `equals` is used and structurally-equal instances skip.

```kotlin
// WRONG
data class Filter(val tags: List<String>, val sort: SortOrder)

@Composable
fun FilterBar(filter: Filter) { /* ... */ }
// WRONG because: Filter is unstable (List<String> is unstable). Strong skipping makes
// FilterBar skippable, but only when `prevFilter === newFilter`. A copy() with the same
// content fails identity, recomposes the bar, every time.
```

```kotlin
// RIGHT
@Immutable
data class Filter(val tags: ImmutableList<String>, val sort: SortOrder)

@Composable
fun FilterBar(filter: Filter) { /* ... */ }
// RIGHT because: @Immutable + ImmutableList makes Filter stable. Strong skipping uses
// equals(), and structurally-equal copies skip.
```

### Pattern: literal collection in a hot composable param

```kotlin
// WRONG
@Composable
fun Toolbar(title: String) {
    ActionRow(actions = listOf(Action.Share, Action.Save))
}
// WRONG because: a fresh List allocates per recompose. Strong skipping makes ActionRow
// skippable, but the List param fails === every time and the body re-runs.
```

```kotlin
// RIGHT
private val ToolbarActions = persistentListOf(Action.Share, Action.Save)

@Composable
fun Toolbar(title: String) {
    ActionRow(actions = ToolbarActions)
}
```

### Pattern: lambda inside LazyListScope.items

See workflow step 3. The short version: `items { }` is not @Composable, strong skipping does not reach inside it, hoist a stable callback or use a method reference.

### Pattern: `@DontMemoize` for intentionally-fresh lambdas

```kotlin
// WRONG — auto-remembered lambda captures `counter` from the FIRST composition
@Composable
fun LoggingButton(counter: Int) {
    Button(onClick = { Log.d("Btn", "counter = $counter") }) { Text("hit") }
}
// WRONG because: under strong skipping the lambda is auto-remembered, but `counter` is a
// stable Int capture, so the remember key is the int value. This is actually fine here.
// Where it bites is when the developer EXPECTS each invocation to capture fresh state but
// the lambda is being passed across a memoization boundary that hides the capture.
```

```kotlin
// RIGHT — explicit opt-out where freshness is the contract.
// @DontMemoize is @Target(EXPRESSION) — apply on the lambda literal, never on the type.
@Composable
fun TraceContent(latest: Int, body: @Composable (() -> Unit) -> Unit) {
    body(@DontMemoize { sendTelemetry("snapshot=$latest") })
}
```

`@DontMemoize` is rarely needed in app code. Reach for it only after measuring; the default behavior is correct for almost all UI.

### Pattern: `@NonSkippableComposable` for side-effect-only composables

```kotlin
// RIGHT
@NonSkippableComposable
@Composable
fun FrameTicker(onTick: () -> Unit) {
    SideEffect { onTick() }
}
```

The annotation says "this composable has work to do every recomposition; do not let the skip guard short-circuit it". Use sparingly — most composables that "must run every time" are actually misplaced state reads; fix the read site first.

### Pattern: pair with CI to prevent strong-skipping regressions

Strong skipping makes nearly every composable `skippable` in the report, which makes the report less useful for spotting regressions by eye. Combine with `../../stability/enforcing-stability-in-ci/SKILL.md` so the baseline diff catches a composable that flips from skippable to not.

## Mandatory rules

- **MUST** verify the Kotlin / Compose compiler plugin version before claiming strong skipping is active. Print `./gradlew :app:dependencies` and confirm Kotlin 2.0.20+ (default ON). On older Kotlin, confirm the legacy `enableStrongSkippingMode = true` flag in `composeCompiler { }`. If the project explicitly disables Strong Skipping via `featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())`, treat the rest of this skill as not applicable.
- **MUST NOT** declare a composable "skippable now thanks to strong skipping" without checking the actual `*-composables.txt` report. Annotations like `@NonSkippableComposable` and lambdas inside `LazyListScope.items { }` remain footguns the report makes visible.
- **MUST** prefer `@Immutable` / `@Stable` over relying on reference equality for unstable types passed in hot paths. `===` is a poor substitute for structural `equals` when the producer of the value (`copy()`, `map { }`, builder DSL) does not preserve identity.
- **MUST NOT** use `@DontMemoize` or `@NonSkippableComposable` without a one-line code comment explaining why. These are advanced opt-outs; the next reader will assume they are mistakes if the rationale is not co-located.
- **MUST NOT** treat strong skipping as a substitute for hoisting unstable literals. A `listOf(...)` allocated in a composable param fails `===` regardless of strong skipping. Hoist into `remember { ... }` or a top-level `persistentListOf(...)`.
- **PREFERRED:** combine this skill with `../../stability/enforcing-stability-in-ci/SKILL.md` so a composable regressing from `skippable` to `not skippable` (e.g. someone adds `@NonSkippableComposable` without justification) fails CI.
- **PREFERRED:** when a developer asks "is my lambda allocated per recompose?", confirm the call site is inside a `@Composable` function before answering yes/no. The answer flips entirely at the `@Composable` boundary.

Skippability is a diagnostic, not a KPI: under strong skipping every composable is "skippable" in the report, but only the ones whose params actually compare equal will skip at runtime. The interesting question is no longer "is it skippable?" but "do its params compare equal across the recomposition the developer cares about?".

## Verification

- [ ] Confirmed Kotlin / Compose compiler version is 2.0.20+ (default ON) and that no `featureFlags.add(ComposeFeatureFlag.StrongSkipping.disabled())` opt-out is in effect — or, on older Kotlin, that the legacy `enableStrongSkippingMode = true` flag is set.
- [ ] `*-composables.txt` shows `restartable skippable` on previously-unstable-param composables.
- [ ] Audited the suspect call site for unstable literal captures (`listOf`, `mapOf`, ad-hoc lambdas in non-@Composable scopes).
- [ ] Any `@DontMemoize` / `@NonSkippableComposable` / `@NonRestartableComposable` / `@ReadOnlyComposable` in the changed code has a co-located comment explaining why.
- [ ] Runtime confirmed via `@TraceRecomposition` (see `../../measurement/tracing-recompositions-at-runtime/SKILL.md`) that the composable skips when its inputs are equal.
- [ ] Stability baseline (see `../../stability/enforcing-stability-in-ci/SKILL.md`) covers the changed module so a future regression in skip behavior fails CI.

## References

- Strong Skipping (Android Developers) — https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Ben Trengrove, "Jetpack Compose Strong Skipping Mode explained" — https://medium.com/androiddevelopers/jetpack-compose-strong-skipping-mode-explained-cbdb2aa4b900
- Ben Trengrove, "New ways of optimizing stability in Jetpack Compose" — https://medium.com/androiddevelopers/new-ways-of-optimizing-stability-in-jetpack-compose-038106c283cc
- Compose Compiler release notes — https://developer.android.com/jetpack/androidx/releases/compose-compiler
- Compose stability overview — https://developer.android.com/develop/ui/compose/performance/stability
- Chris Banes, "Composable metrics" — https://chrisbanes.me/posts/composable-metrics/
- skydoves, "Optimize App Performance by Mastering Stability" — https://medium.com/proandroiddev/optimize-app-performance-by-mastering-stability-in-jetpack-compose-69f40a8c785d
- skydoves/compose-stability-analyzer — https://github.com/skydoves/compose-stability-analyzer
- `references/escape-hatches.md` — full reference table of `@NonRestartableComposable`, `@NonSkippableComposable`, `@DontMemoize`, `@ReadOnlyComposable` with code samples and decision matrix.

For diagnosing why a composable is unstable in the first place, see `../../stability/diagnosing-compose-stability/SKILL.md`. For instrumenting a specific composable to see if it actually skips at runtime, see `../debugging-recompositions/SKILL.md` and `../../measurement/tracing-recompositions-at-runtime/SKILL.md`. For preventing a strong-skipping regression in CI, see `../../stability/enforcing-stability-in-ci/SKILL.md`.
