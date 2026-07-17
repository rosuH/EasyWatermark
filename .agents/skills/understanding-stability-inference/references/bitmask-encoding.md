# Bitmask encoding for generic stability

This file explains how the Compose compiler represents the stability of generic types as an Int bitmask, how the Known Stable Constructs registry uses that representation, how `@StabilityInferred(parameters = ...)` carries the bitmask across module boundaries, and how the runtime evaluates the `$stable: Int` field on the JVM (versus the mangled top-level property used on Kotlin/Native and Kotlin/JS).

## The core idea

Generic types like `Container<T1, T2, T3>` cannot be classified as a flat `Stable` or `Unstable`. Their stability depends on which of the type arguments actually participate in the equality contract. The compiler represents that participation as a bitmask: bit `i` set means `Ti` affects the stability decision; bit `i` cleared means the compiler proved `Ti` does not affect equality (for example, because the parameter is phantom or only used in covariant out-positions that are never compared).

```text
Bitmask  Meaning
0b000    No type argument affects stability — class is unconditionally stable.
0b001    Only T0 affects stability.
0b010    Only T1 affects stability.
0b011    T0 and T1 affect stability.
0b101    T0 and T2 affect stability; T1 is irrelevant.
0b111    All three affect stability.
```

## Known Stable Constructs registry (phase 7)

The compiler hard-codes a list of well-known stable types from the standard library and the AndroidX ecosystem. Each entry pairs a fully qualified class ID with its bitmask. The registry lives in `KnownStableConstructs.kt` in the Compose compiler.

| Class | Bitmask | Reading |
|---|---|---|
| `kotlin.Pair<A, B>` | `0b11` | both A and B participate |
| `kotlin.Triple<A, B, C>` | `0b111` | all three participate |
| `kotlin.Result<T>` | `0b1` | T participates |
| `kotlin.ranges.ClosedRange<T>` | `0b1` | T participates |
| `kotlin.ranges.ClosedFloatingPointRange<T>` | `0b1` | T participates |
| `kotlin.coroutines.EmptyCoroutineContext` | `0b0` | single object, no params |
| `kotlinx.collections.immutable.ImmutableList<E>` | `0b1` | E participates |
| `kotlinx.collections.immutable.ImmutableSet<E>` | `0b1` | E participates |
| `kotlinx.collections.immutable.ImmutableMap<K, V>` | `0b11` | both K and V participate |
| `kotlinx.collections.immutable.PersistentList<E>` | `0b1` | E participates |
| `dagger.Lazy<T>` | `0b1` | T participates |
| `java.util.Comparator<T>` | `0b1` | T participates |
| `java.util.Locale` | `0b0` | no parameters; treated as stable |
| `java.math.BigInteger` | `0b0` | no parameters; treated as stable |
| `java.math.BigDecimal` | `0b0` | no parameters; treated as stable |

When phase 7 hits a registry entry, the compiler returns `Stability.Parameter(type, bitmask)`. At the call site, the bitmask is ANDed against the substituted type-argument stabilities to produce the final classification.

## Call-site evaluation

For a class with bitmask `B` and substituted type arguments `T0, T1, ..., Tn`, the compiler computes:

```text
result = Stability.Combined(
  i = 0..n
    if (bit_i(B) == 1) stabilityOf(Ti)
    else /* skip Ti */
).normalize()
```

Worked example — `Pair<String, List<Foo>>`:

1. Bitmask `0b11`. Both bits set.
2. `T0 = String` → phase 1 → `Certain(stable=true)`.
3. `T1 = List<Foo>` → phase 11 (interface) → `Unknown`.
4. Combined of `[Stable, Unknown]` normalizes to `Unknown`. The pair classifies as `Unknown` at the call site, blocks compile-time skipping, and the runtime probe falls back to `===` identity.

Worked example — `ImmutableList<String>`:

1. Bitmask `0b1`. Only bit 0 set.
2. `T0 = String` → `Certain(stable=true)`.
3. Combined of `[Stable]` collapses to `Certain(stable=true)`. Skipping enabled at compile time.

Worked example — `BigInteger`:

1. Bitmask `0b0`. No bits set.
2. Combined of `[]` → identity element → `Certain(stable=true)`. The erased type arguments are irrelevant; the class is unconditionally stable.

## `@StabilityInferred` — crossing module boundaries

When the compiler classifies a user-defined generic class as `Stability.Parameter`, it cannot embed the result in the call sites of *other modules* — they have not been compiled yet. Instead, it emits a synthetic annotation on the class declaration:

```kotlin
@StabilityInferred(parameters = 0b1)
class Box<T>(val value: T)
```

Downstream modules that use `Box` read this annotation in phase 9 of the algorithm and learn the bitmask without re-running field analysis on `Box`. The annotation is the single source of truth for cross-module stability.

If the annotation is missing — typically because the dependency was compiled with an older Compose compiler, or because it is a Java class — phase 9 returns `Stability.Runtime` with bitmask `0` and the call site is forced into a runtime check that always returns "unstable". Two fixes exist:

1. Recompile the dependency with the current Compose compiler so it gains `@StabilityInferred`.
2. Add the class to `stability_config.conf` so phase 8 fires before phase 9. The config file lets the local module declare the bitmask itself.

## The runtime `$stable: Int` field on the JVM

For every class the compiler classifies as `Stability.Parameter` or `Stability.Combined` containing parameters, the JVM backend synthesizes a synthetic static final `$stable: Int` field directly on the annotated class itself. (The `@StabilityInferred` KDoc in `androidx.compose.runtime.internal` documents this contract.) The field encodes:

```text
high bits — reserved (currently zero)
low bits  — the bitmask, identical to @StabilityInferred(parameters = ...)
```

At runtime, the Compose compiler emits inline expressions at each call site that read the class's synthetic `$stable: Int` field via direct static field access (`getstatic` on the JVM, the mangled top-level property on Native/JS) and AND it against the substituted type-argument stabilities. The compiled call site, not `Composer.changed`, performs the bitmask resolution; `Composer.changed(value: Any?)` itself is just an equality probe that returns `true` when `equals` reports the previous and current values differ.

Implementation files:

- `ClassStabilityTransformer.kt` — emits the `$stable` field.
- `Stability.kt` — the algebraic representation that maps to the bitmask format.
- The compiler emits the call-site read at every composable invocation; the Compose runtime's `Composer.changed` is unaware of `$stable` and only performs the equality check.

## Native and JS — the mangled top-level property

Kotlin/Native and Kotlin/JS do not have JVM static fields with the same lookup semantics. The compiler instead emits a top-level property whose name is mangled from the class FQN with a `$stableprop` suffix:

```text
val androidx_compose_ui_autofill_AutofillManager$stableprop: Int = 0b1
```

(See `compose/ui/ui/bcv/native/current.txt` for the full list of generated properties.) The resolution path is otherwise identical: at each call site the compiler emits an inline read of the property, ANDs it against substituted argument stabilities, and produces the same skip decision as on the JVM.

The mangling rules are private to the compiler — depending on the exact name in user code is not supported. The property exists for the runtime, not for direct access.

## Stability config bitmask syntax

`stability_config.conf` uses a generic-aware syntax to declare per-parameter participation:

```text
com.example.GenericClass<*,_>     # bit 0 affects stability, bit 1 ignored
com.example.PairLike<*,*>         # both bits affect stability (bitmask 0b11)
com.example.PhantomKey<_>         # bit 0 ignored (bitmask 0b0)
```

Tokens:

- `*` — this type parameter participates (sets the corresponding bit).
- `_` — this type parameter is ignored (clears the corresponding bit).

Phase 8 reads these patterns and produces a `Stability.Parameter(type, bitmask)` result equivalent to a Known Stable Constructs hit.

## Practical consequences

- **A separately-compiled generic class with all-stable fields shows as `runtime stable`.** The classification is correct; it is `Combined` containing only `Parameter` and `Certain(true)` components, which the compiler defers to runtime via `$stable`. There is one Int field load and one bitwise AND on the recomposition path — far cheaper than the unskipped recomposition it prevents.
- **A class with `@StabilityInferred(parameters = 0)` is unconditionally stable.** No type parameter participates, so the bitmask is empty; the runtime probe always succeeds.
- **A class with `@StabilityInferred(parameters = 0b1)` and `T = SomeUnstableType` is unstable at that call site.** The runtime AND collapses to "unstable" because bit 0 is set and the substituted argument is unstable.
- **Adding `@Stable` or `@Immutable` to the class skips bitmask emission entirely.** Phase 6 fires before phase 12 ever runs; the class becomes `Certain(stable=true)` and no `$stable` field is emitted. The contract is the developer's to honor.

## Cross-references

- `twelve-phase-algorithm.md` — pseudocode for all 12 phases that produce these bitmasks.
- `../SKILL.md` — the parent skill; explains when to consult these references.
- skydoves/compose-stability-inference — https://github.com/skydoves/compose-stability-inference (source-level walk-through of the algorithm and bitmask).
