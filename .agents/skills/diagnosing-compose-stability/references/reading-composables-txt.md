# Reading `composables.txt` — Compose Compiler per-function dump

`composables.txt` lists every `@Composable` function the compiler emitted code for, along with the flags that control recomposition behavior and the per-parameter stability resolution. This is the highest-signal artifact in the Compose Compiler Reports — when the developer asks "why does this recompose", the answer is usually one specific line in this file.

## File location

```
<module>/build/compose_compiler/<module>_<variant>-composables.txt
```

A CSV mirror lives next to it (`<module>_<variant>-composables.csv`) for CI parsing.

## Top-level grammar

Each function is one block of the form:

```
[restartable] [skippable] [readonly] [scheme("…")] fun <Name>(
  <param-flag> <name>: <type>,
  …
)
```

The leading flags are space-separated and can appear in any combination. The order observed in real reports is `restartable skippable readonly scheme(…)`. Each parameter line carries a flag prefix.

### Function-level flags

- **`restartable`** — the function has its own restart scope. The composer will re-invoke it on invalidation. This is the default for any non-inline `@Composable fun` with a body. Inline composables (`Row`, `Column`, `Box`) **never** appear with `restartable` because they fold into the caller's scope. Skydoves hot take #3: wrapping a `Row` in another composable to "force skippability" changes recomposition scoping and is rarely the right fix.
- **`skippable`** — the compiler emitted a `skipToGroupEnd()` guard. If every parameter compares equal to the previous call, the body is skipped. Requires every parameter to be `stable` under the inference rules (or, under Strong Skipping Mode default since Kotlin 2.0.20, every unstable parameter is compared by `===` instead of `equals`). The absence of this flag on a `restartable` function is the diagnosis.
- **`readonly`** — the compiler proved the function does not write to composition state. Side-effect-free. Reported for things like simple selectors. Informational, not a fix target.
- **`scheme("[androidx.compose.ui.UiComposable]")`** — composable scheme. UI composables emit nodes; non-UI composables (e.g. those returning a value) carry different schemes. Almost always present on render functions. Informational.
- **`@NonRestartableComposable`** — explicit annotation; appears as the absence of `restartable`. Used for trivial helpers where a restart scope would be wasted overhead.
- **`@NonSkippableComposable`** — explicit annotation forcing always-recompose under strong skipping; appears as the absence of `skippable`.

### Parameter-level flags

Each parameter line is prefixed with one or more of:

- **`stable`** — the type is provably stable (compile-time `Stability.Certain`). Compose will use `equals()` for the skip check.
- **`unstable`** — the type is `Stability.Unknown` or `Stability.Combined` resolving to unstable. Pre-strong-skipping this disabled the whole function's skipping; under Strong Skipping Mode the compiler still emits a `===` comparison and the function is still `skippable`, but a freshly-allocated instance every recomposition will fail `===` and force the body to run.
- **`runtime`** — `Stability.Runtime`; the compiler emits a runtime `$stable` query. Not a problem; behaves like `stable` if the substituted type reports stable at call site.
- **`@static`** — the argument expression is a compile-time constant (literal, `top-level val` of a stable type, or singleton). The compiler hoists it; the parameter cannot trigger a recomposition.
- **`@dynamic`** — the argument expression is not provably static. Default classification.

The same parameter often carries two prefixes: `stable @static index: Int` means "type is stable AND the call site passes a constant".

## Worked example — HighlightedSnacks (CORPUS canonical)

```text
restartable scheme("[androidx.compose.ui.UiComposable]") fun HighlightedSnacks(
  stable index: Int,
  unstable snacks: List<Snack>,
  stable onSnackClick: Function1<Long, Unit>,
)
```

Diagnosis walkthrough:

1. **`restartable`** present, **`skippable`** missing → every recomposition of the parent re-invokes this function regardless of argument equality.
2. **`stable index: Int`** — primitive, fine.
3. **`unstable snacks: List<Snack>`** — `kotlin.collections.List` is an interface; the compiler cannot prove every implementation is immutable, so `Stability.Unknown`. **This is the cause of the missing `skippable` flag.**
4. **`stable onSnackClick: Function1<Long, Unit>`** — function types are stable per the fast-path inference rules. Under Strong Skipping the lambda is auto-`remember`-wrapped at the call site so identity remains stable across recompositions.
5. Cross-reference `Snack` in `classes.txt`. If `Snack` is `stable class …`, the only fix needed is the `List` wrapper (swap to `ImmutableList<Snack>` or whitelist `kotlin.collections.*`).
6. Hand off to `../stabilizing-compose-types/SKILL.md` with the concrete type to fix.

## Other shapes you will see

### Fully skippable composable

```text
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun ProductRow(
  stable @dynamic product: Product,
  stable @dynamic onClick: Function0<Unit>,
)
```

Both flags set; both params stable. Nothing to do.

### Skippable with a runtime-resolved param

```text
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun Card(
  runtime @dynamic content: Function2<Composer, Int, Unit>,
)
```

The `content` slot type is generic; the compiler emits a runtime `$stable` query. Behaves stably at call sites that pass stable lambdas. Not a fix target.

### Static-promoted constants

```text
restartable skippable scheme("[androidx.compose.ui.UiComposable]") fun Banner(
  stable @static title: String,
  stable @static color: Color,
)
```

`@static` means the call site passed compile-time constants (e.g. literal string, `Color(0xFF112233)` evaluated at compile time when the type is `@Immutable`). These params cannot trigger recomposition at all. This is the **bonus** that `@Immutable` (versus `@Stable`) unlocks for all-constant constructors — see the stabilizing-compose-types skill.

### Non-restartable helper

```text
scheme("[androidx.compose.ui.UiComposable]") fun spacing(
  stable size: Dp,
)
```

No `restartable` prefix — this function carries `@NonRestartableComposable` or is inline. It folds into its caller's restart scope. Not a fix target; this is by design.

### Read-only selector

```text
restartable readonly scheme("[androidx.compose.ui.UiComposable]") fun rememberFormattedDate(
  stable instant: Instant,
)
```

`readonly` marks pure derivations. They participate in skipping but never write composition state.

## Reading checklist

When triaging composables.txt:

1. Filter to lines containing `restartable` but **NOT** `skippable`. These are the only entries worth attention.
2. For each, scan the parameter list for the first `unstable` prefix.
3. Note the type. Map it back to `classes.txt` to get the root-cause field.
4. Check whether the function is on a hot path (LazyColumn item, animation tick subscriber). If not, deprioritize.
5. **DO NOT** treat a `runtime` parameter as a problem. Verify call sites instead.
6. **DO NOT** rewrite a function as `@NonSkippableComposable` to "fix" the report — that hides the diagnostic without fixing the cause.

## Common pitfalls

- **Strong Skipping noise.** Under Kotlin 2.0.20+ with Strong Skipping default-on, `restartable` functions will almost always also be `skippable`. The diagnosis shifts: an `unstable` parameter no longer prevents skippability, but a freshly-allocated argument every recomposition still forces the body to run because `===` fails. The fix is the same — make the type stable or pass a stable instance.
- **Inline composables missing.** `Row`, `Column`, `Box` etc. are not present in this file because they are inline. Wrapping them does not change recomposition the way devs expect — see skydoves hot take #3.
- **Lambda parameters showing as `stable`.** Function types are always stable. The capture freshness is handled by Strong Skipping's auto-`remember`. Pre-strong-skipping, an unstable receiver captured by the lambda could still cause recomposition; check the receiver's class entry.
- **`Function2<Composer, Int, Unit>` reported as `runtime`.** This is the desugared `@Composable () -> Unit`. It is fine.

## See also

- `reading-classes-txt.md` for tracing an `unstable` parameter back to its root-cause field.
- Android Developers — Diagnose stability: https://developer.android.com/develop/ui/compose/performance/stability/diagnose
- Ben Trengrove — Strong Skipping explained: https://medium.com/androiddevelopers/jetpack-compose-strong-skipping-mode-explained-cbdb2aa4b900
- Chris Banes — Composable Metrics: https://chrisbanes.me/posts/composable-metrics/
