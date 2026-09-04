---
name: stabilizing-compose-types
description: Use this skill to fix unstable Jetpack Compose types once a stability diagnosis has identified them. Covers the three-tier strategy — make the type truly stable with val plus immutable fields, mark with @Immutable or @Stable when the source is owned, and use stabilityConfigurationFiles for third-party or Java types. Explains the compiler-level difference between @Immutable and @Stable (static expression promotion), kotlinx.collections.immutable for List/Set/Map parameters, and the StableHolder wrapper escape hatch. Use when the developer asks how to stabilize a User class, a List parameter, java.time.LocalDateTime, a Flow parameter, or when the compiler report shows unstable params and the developer wants the fix. The diagnostic step lives in a sibling skill.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - stability
  - immutable
  - stable-marker
  - kotlinx-collections-immutable
  - stability-configuration-file
  - strong-skipping
---

# Stabilizing Compose Types — Three Tiers, In Order

Once a diagnosis (see `../diagnosing-compose-stability/SKILL.md`) has named the unstable types, this skill walks Claude through fixing them. The strategy is a strict three-tier waterfall: (1) make the type **truly stable** by structural rewrite (`val` + immutable fields) — no annotation needed; (2) annotate with `@Immutable` or `@Stable` when the source is owned and the contract is honored; (3) use `stabilityConfigurationFile` for third-party or Java types. **DO NOT** invert this order — annotations are a contract, not a magic spell.

## When to use this skill

- The compiler report (composables.txt / classes.txt) shows one or more `unstable` parameters.
- The developer asks how to stabilize a domain class, a `List<Foo>` parameter, `java.time.LocalDateTime`, or a third-party type.
- The developer mentions `@Immutable`, `@Stable`, `compose-stable-marker`, `compose-runtime-annotation`, `kotlinx.collections.immutable`, `stabilityConfigurationFiles`, or `stability_config.conf`.
- A `@TraceRecomposition` log shows recomposition happening because an argument is allocated fresh every recomposition.

## When NOT to use this skill

- The unstable types are not yet identified — run `../diagnosing-compose-stability/SKILL.md` first.
- The symptom is a wrong-phase state read (`Modifier.alpha(state.value)`); use `../../recomposition/deferring-state-reads/SKILL.md`.
- The symptom is a `derivedStateOf` problem; use `../../recomposition/choosing-derivedstateof/SKILL.md`.
- A `Flow<T>` parameter is the cause; the fix is to collect upstream — see `../../side-effects/collecting-flows-safely/SKILL.md` rather than annotating `Flow` as stable.

## Prerequisites

- `../diagnosing-compose-stability/SKILL.md` has been run; the developer has a concrete list of unstable types.
- Kotlin **2.0.0+** with `org.jetbrains.kotlin.plugin.compose` applied. Strong Skipping is on by default.
- For pure-Kotlin / data modules without `compose-runtime`: either `androidx.compose.runtime:runtime-annotation` (official) or `com.github.skydoves:compose-stable-marker` (legacy) on the classpath. Both expose `@Stable` / `@Immutable` / `@StableMarker` without dragging in the full runtime.
- For tier-3 fixes: Compose Compiler **1.5.5+** for `stabilityConfigurationFiles` DSL support (plural; the older singular `stabilityConfigurationFile` property is deprecated, see footnote below).

## Workflow — decision tree

- [ ] **1. Do we own the source of the unstable type?** If yes, use tier 1 or tier 2. If no (Java stdlib, third-party SDK), jump to tier 3.

- [ ] **2. Tier 1 — restructure to truly stable.** Can every property be a `val` of an already-stable type? If yes, **MUST** make that change first. No annotation is needed and no contract is implied. The compiler will infer stability automatically.

```kotlin
// WRONG
data class Snack(
    var name: String,
    val tags: Set<String>,
)
// WRONG because: `var name` is an observable that does not notify Compose, and `Set<String>` is the standard library interface so its stability is Unknown — the data class is unstable on two axes.
```

```kotlin
// RIGHT
@Immutable
data class Snack(
    val name: String,
    val tags: ImmutableSet<String>,
)
```

- [ ] **3. Is the unstable property a collection?** Replace `kotlin.collections.List`, `Set`, `Map` with `kotlinx.collections.immutable.ImmutableList`, `ImmutableSet`, `ImmutableMap`. Build with `persistentListOf()`, `persistentSetOf()`, `persistentMapOf()` factories or `.toImmutableList()` adapters. The kotlinx-collections-immutable artifact ships a known-stable bitmask `0b1` recognized by the Compose Compiler. **PREFERRED:** prefer this over whitelisting `kotlin.collections.*` in `stability_config.conf` because the type system enforces immutability.

- [ ] **4. Is the source a pure-Kotlin / data module that does not depend on `compose-runtime`?** Use `androidx.compose.runtime:runtime-annotation` (official, recommended) or `com.github.skydoves:compose-stable-marker` (legacy). Both let the data module annotate types without pulling in the full Compose runtime. Skydoves hot take #6: pure-Kotlin / data modules can use compose-stable-marker (or newer official compose-runtime-annotation) without pulling in full compose-runtime.

```kotlin
// shared data module — build.gradle.kts
dependencies {
    // Official, preferred:
    compileOnly("androidx.compose.runtime:runtime-annotation:<version>")
    // Or legacy skydoves:
    // implementation("com.github.skydoves:compose-stable-marker:<version>")
}
```

- [ ] **5. Tier 2 — choose `@Immutable` or `@Stable`.** If every property is `val` AND every nested value is itself immutable AND `equals()` is structural, use `@Immutable`. Otherwise — for types whose values can change but whose mutations are observed by Compose (e.g. holders containing `MutableState`) — use `@Stable`.

The compiler treats `@Immutable` more aggressively than `@Stable`. For `@Immutable` types, when **every** constructor argument at a call site is a compile-time constant (e.g. literal numbers, top-level `val`s of stable types, or other `@Immutable`-with-constant-args), the compiler performs **static expression promotion**: the constructed instance is hoisted into a singleton, the lambda capture sites are de-duplicated, and the `@Immutable` parameter is marked `@static` in `composables.txt`. Most articles describe `@Stable` and `@Immutable` as interchangeable — they are not.

```kotlin
// RIGHT — @Immutable: every property val, every property type immutable
@Immutable
data class ThemeColors(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
)

// RIGHT — @Stable: mutable but mutations notify Compose via Snapshot
@Stable
class CartState {
    var total: Money by mutableStateOf(Money.ZERO)
    val lines: SnapshotStateList<Line> = mutableStateListOf()
}
```

- [ ] **6. Tier 3 — third-party or Java types.** Use `stabilityConfigurationFiles` (plural). Create `stability_config.conf` at the project root listing exact-class or wildcard patterns; wire it into the `composeCompiler { }` block via `stabilityConfigurationFiles.add(...)`. Full grammar lives in `references/stability-config-syntax.md`.

```kotlin
// build.gradle.kts (root or composable module)
composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("stability_config.conf")
    )
}
```

> **Footnote — legacy form.** Older projects may still wire the file via the singular property `stabilityConfigurationFile = ...`. That property is `@Deprecated("Use the stabilityConfigurationFiles option instead")` in modern Compose Compiler Gradle plugin releases — prefer the plural `stabilityConfigurationFiles.add(...)` shown above.

```text
# stability_config.conf — opt-in contract
java.time.LocalDateTime
java.time.LocalDate
kotlin.collections.List
kotlin.collections.Set
kotlin.collections.Map
com.example.thirdparty.**
```

- [ ] **7. Nothing in tiers 1-3 fits?** Wrap in a `@Stable class StableHolder<T>(val item: T)`. This is the escape hatch — the holder's identity is stable, equality delegates to `item`, and Compose can skip on it.

```kotlin
@Stable
class StableHolder<T>(val item: T) {
    override fun equals(other: Any?) = other is StableHolder<*> && other.item == item
    override fun hashCode() = item?.hashCode() ?: 0
}
```

- [ ] **8. `Flow<T>` parameters — DO NOT annotate as stable.** Skydoves hot take #4: `Flow` parameters are unstable. Don't pass flows to composables; collect them in a ViewModel or with `collectAsStateWithLifecycle`.

```kotlin
// WRONG
@Composable
fun Feed(items: Flow<List<Item>>) { /* ... */ }
// WRONG because: Flow has no observable identity; the composable cannot skip on it, and a downstream `collectAsState` here detaches from lifecycle.
```

```kotlin
// RIGHT — collect upstream and pass the resolved value
@Composable
fun FeedRoute(viewModel: FeedViewModel = viewModel()) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    Feed(items = items)
}

@Composable
fun Feed(items: ImmutableList<Item>) { /* ... */ }
```

- [ ] **9. Re-run the diagnostic skill** to verify the previously unstable params are now `stable` or `runtime`.

## Patterns

### Pattern: data class with `var` and `Set` field

```kotlin
// WRONG
data class Snack(
    var name: String,
    val tags: Set<String>,
)
// WRONG because: `var` blocks compile-time stability inference; `Set<String>` is an interface with unknown implementations.
```

```kotlin
// RIGHT
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class Snack(
    val name: String,
    val tags: ImmutableSet<String> = persistentSetOf(),
)
```

### Pattern: `List<Item>` parameter on a composable

```kotlin
// WRONG
@Composable
fun ItemList(items: List<Item>) { /* ... */ }
// WRONG because: `kotlin.collections.List` is an interface; the compiler cannot prove every implementation is immutable, so the parameter is `unstable` and skipping is disabled (or pinned to `===` under Strong Skipping, which still fails on every fresh allocation).
```

```kotlin
// RIGHT
import kotlinx.collections.immutable.ImmutableList

@Composable
fun ItemList(items: ImmutableList<Item>) { /* ... */ }
```

Producer side:

```kotlin
val items: ImmutableList<Item> = repository.items().toImmutableList()
```

### Pattern: `java.time.LocalDateTime` flagged unstable

The Java time types are separately compiled, no annotation, so the inference falls through to "unknown". Tier 3 is the right answer.

```text
# stability_config.conf
java.time.LocalDateTime
java.time.LocalDate
java.time.Instant
java.time.ZonedDateTime
java.time.Duration
```

```kotlin
// WRONG — annotate a wrapper to "force" stability without honoring the contract
@Stable
class DateWrapper(var date: LocalDateTime)
// WRONG because: `var` field, no Snapshot notification — the @Stable contract is broken; recompositions will be silently missed.
```

```kotlin
// RIGHT — whitelist the immutable JDK type via stability config
// stability_config.conf:  java.time.LocalDateTime
@Immutable
data class Order(val placedAt: LocalDateTime, val total: Money)
```

### Pattern: `Flow<T>` parameter on a composable

```kotlin
// WRONG
@Composable
fun Detail(productFlow: Flow<Product>) {
    val product by productFlow.collectAsState(initial = null)
    /* ... */
}
// WRONG because: `Flow` is a cold producer with no observable identity; the composable can never skip on it, and `collectAsState` (no `WithLifecycle`) keeps collecting in the background.
```

```kotlin
// RIGHT — hoist collection to the route
@Composable
fun DetailRoute(viewModel: DetailViewModel = viewModel()) {
    val product by viewModel.product.collectAsStateWithLifecycle()
    Detail(product = product)
}

@Composable
fun Detail(product: Product?) { /* ... */ }
```

Cross-reference: `../../side-effects/collecting-flows-safely/SKILL.md`.

### Pattern: inline composable wrap as a "fix"

```kotlin
// WRONG — wrapping a Row in an extracted composable to "force" skippability
@Composable
fun ItemRow(item: Item) {
    Row { /* ... */ }
}
// WRONG because: `Row`/`Column`/`Box` are inline composables — they are NOT restartable/skippable to begin with (skydoves hot take #3). Wrapping them creates a new restart scope, which can change behavior unpredictably without addressing the root unstable parameter.
```

```kotlin
// RIGHT — make the parameter stable, leave the inline composable alone
@Immutable
data class Item(val id: Long, val name: String)

@Composable
fun ItemRow(item: Item) {
    Row { /* ... */ }
}
```

### Pattern: `@Immutable` versus `@Stable` — pick the stronger one

```kotlin
// SUFFICIENT but suboptimal
@Stable
data class ThemeColors(
    val primary: Color,
    val onPrimary: Color,
)
// Sufficient because: the compiler still treats it as stable.
// Suboptimal because: @Immutable would additionally enable static expression promotion when ThemeColors is constructed with all-constant args.
```

```kotlin
// PREFERRED
@Immutable
data class ThemeColors(
    val primary: Color,
    val onPrimary: Color,
)
```

In `composables.txt`, calls like `ThemeColors(Color(0xFFFFFFFF), Color(0xFF000000))` will then be reported as `stable @static themeColors: ThemeColors` — the constructed instance is hoisted to a singleton. **MUST** prefer `@Immutable` whenever the type qualifies.

## Mandatory rules

- **MUST NOT** annotate a type as `@Stable` or `@Immutable` unless the contract is honored. Skydoves hot take #2: stability config is a contract, not a magic spell — break it and recompositions are silently missed.
- **MUST** prefer `@Immutable` over `@Stable` whenever every property is a `val` of an already-immutable type. The compiler emits stronger optimizations (static expression promotion, lambda-singleton, compile-time default eval) for `@Immutable`.
- **MUST** prefer `kotlinx.collections.immutable` over annotating a mutable collection as stable. The compiler ships a known-stable bitmask for these types.
- **MUST NOT** wrap inline composables (`Row`/`Column`/`Box`) to "force" skippability. Inline composables are not restartable in the first place; wrapping changes scoping without addressing the root cause.
- **MUST NOT** pass `Flow<T>` as a composable parameter. Collect upstream with `collectAsStateWithLifecycle`.
- **MUST** re-run `../diagnosing-compose-stability/SKILL.md` after applying fixes; the previously unstable params **MUST** now report `stable` or `runtime`.
- **PREFERRED:** `stabilityConfigurationFiles` (plural; `stabilityConfigurationFiles.add(...)`) over scattering annotations across modules the team does not own.
- **PREFERRED:** in pure-Kotlin / data modules, use `androidx.compose.runtime:runtime-annotation` (official) or `com.github.skydoves:compose-stable-marker` (legacy) so the annotation is available without a full `compose-runtime` dependency.

## Verification

- [ ] Re-run release-mode build with `composeCompiler { reportsDestination = ... }` enabled.
- [ ] In `composables.txt`, every previously `unstable` parameter is now `stable` or `runtime`.
- [ ] In `classes.txt`, every fixed class is now `stable class …` or `runtime stable class …` — no `unstable` remains for the targeted types.
- [ ] If `stabilityConfigurationFiles` was used, every entry has been verified to honor the contract (no hidden mutation, structural `equals`, no observable that bypasses Snapshot).
- [ ] `@TraceRecomposition` (skydoves/compose-stability-analyzer) on the previously hot composable shows recomposition counts dropping to the expected number per state change.
- [ ] No `@Stable` / `@Immutable` annotation has been added to a type that still contains a `var` or an unstable nested type.

## References

- Android Developers — Fix stability: https://developer.android.com/develop/ui/compose/performance/stability/fix
- Android Developers — Stability overview: https://developer.android.com/develop/ui/compose/performance/stability
- Android Developers — Strong Skipping: https://developer.android.com/develop/ui/compose/performance/stability/strongskipping
- Ben Trengrove — Stability Explained: https://medium.com/androiddevelopers/jetpack-compose-stability-explained-79c10db270c8
- Ben Trengrove — New Ways of Optimizing Stability: https://medium.com/androiddevelopers/new-ways-of-optimizing-stability-in-jetpack-compose-038106c283cc
- Ben Trengrove — Strong Skipping Mode Explained: https://medium.com/androiddevelopers/jetpack-compose-strong-skipping-mode-explained-cbdb2aa4b900
- Manuel Vivo — Consuming Flows Safely: https://medium.com/androiddevelopers/consuming-flows-safely-in-jetpack-compose-cde014d0d5a3
- skydoves — Optimize App Performance by Mastering Stability: https://medium.com/proandroiddev/optimize-app-performance-by-mastering-stability-in-jetpack-compose-69f40a8c785d
- skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- skydoves — compose-stable-marker: https://github.com/skydoves/compose-stable-marker
- kotlinx.collections.immutable: https://github.com/Kotlin/kotlinx.collections.immutable
- `references/stability-config-syntax.md` — full grammar of `stability_config.conf` with worked examples.
