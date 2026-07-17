# Reading `classes.txt` — Compose Compiler stability dump

`classes.txt` is the per-class stability dump emitted by the Compose Compiler when `composeCompiler { reportsDestination = ... }` is set. Each line documents one class, its computed stability, and the per-field reasoning that produced that result. This file is the second stop after `composables.txt` whenever a non-skippable composable surfaces — it tells you **why** a parameter type was flagged unstable.

## File location

```
<module>/build/compose_compiler/<module>_<variant>-classes.txt
```

Released-variant only is required; debug variants will dump but the values are mixed with Live-Literals-affected types and **MUST NOT** be trusted.

## Top-level grammar

Each class is one block beginning with one of these prefixes:

```
stable class …
unstable class …
runtime stable class …
runtime unstable class …
```

The block then lists each declared field, again prefixed with one of `stable` / `unstable` / `runtime`. Annotations (`@Immutable`, `@Stable`) are reflected in the prefix, not printed verbatim.

### Prefix meanings

- `stable class Foo` — the compiler proved at compile time that every field is stable and every field is a `val`. Skipping works without runtime help.
- `unstable class Foo` — at least one field is a `var` (mutable observable that does NOT notify Compose), or at least one field's type is itself unstable. Skipping is disabled wherever this type appears as a parameter.
- `runtime stable class Foo` — the class itself is stable, but it has at least one type parameter or one separately-compiled field whose stability cannot be proved at compile time. The compiler emits a synthetic `$stable: Int` field and computes stability **at runtime** based on the substituted type's own `$stable`. This is **not** a problem to fix.
- `runtime unstable class Foo` — same as above but the class has known-unstable shape (e.g. a `var` field plus a generic). Treat as `unstable`.

The `$stable` field encodes a bitmask. For a class `Container<T1, T2>` the bitmask is computed from each `Tn`'s own `$stable`. Skydoves' compose-stability-inference catalogs known masks: `kotlin.Pair = 0b11`, `kotlin.Triple = 0b111`, `kotlinx.collections.immutable.ImmutableList = 0b1`, `java.math.BigInteger = 0b0`.

## Worked examples

### Plain stable class — every field a `val` of a primitive

```text
stable class User {
  stable val id: Int
  stable val name: String
}
```

Every field is `val`; `Int` and `String` are stable primitives. Skippable wherever it appears. No annotation needed; no fix needed.

### Plain unstable class — single `var` field

```text
unstable class Counter {
  unstable var count: Int
}
```

Even though `Int` is stable, `var` makes the field a mutable observable that does not notify Compose. The whole class is unstable. **Fix:** convert to `val` and lift the mutable state to a `mutableStateOf`/`MutableState` held by the composable or its ViewModel.

### Mixed — stable field, unstable field

```text
unstable class CartLine {
  stable val sku: String
  unstable val items: List<LineItem>
}
```

`items` is a `kotlin.collections.List` interface — its stability is unknown to the compiler. The whole class is unstable because **unstable dominates** in the Combined stability lattice. **Fix:** swap `List<LineItem>` for `ImmutableList<LineItem>` from kotlinx.collections.immutable, or whitelist `kotlin.collections.*` in `stability_config.conf`.

### Runtime-stable container — generic with a stable type-parameter slot

```text
runtime stable class Box {
  stable val value: T
}
```

`Box<T>` is itself stable (single `val`, structural equals), but the compiler cannot prove `T` until call-site substitution. It emits a `$stable: Int` field on `Box` and queries the substituted `T`'s own `$stable` at runtime. `Box<Int>` will resolve stable; `Box<some unstable class>` will resolve unstable. **DO NOT** annotate `Box` with `@Stable` — the runtime check is correct and stronger.

### Runtime — separately compiled module

If `User` lives in a different Gradle module that was compiled in a previous build, the compiler emits:

```text
runtime stable class User
```

This is the same situation as the generic case: the compiler trusts the upstream `$stable` field. Multi-module projects routinely show many `runtime …` lines and that is fine.

### Annotated stable class

```text
stable class ThemeColors {
  stable val primary: Color
  stable val onPrimary: Color
}
```

If `ThemeColors` was annotated `@Immutable`, it shows the same `stable class …` prefix — the annotation is consumed by the prefix, not echoed. The diagnostic value is in the absence of `unstable` markers.

### Unstable due to interface field

```text
unstable class Repository {
  unstable val source: DataSource    // DataSource is an interface
}
```

Interfaces have `Stability.Unknown` — the compiler cannot enumerate implementations. The fix is to either annotate `DataSource` with `@Stable` (if every implementation honors the contract), pass the concrete type instead, or whitelist via `stability_config.conf`.

## Reading checklist

When triaging a class block:

1. Read the class-level prefix. `stable` and `runtime stable` are not problems; only `unstable` and `runtime unstable` are.
2. For an `unstable` class, scan for the first `unstable` field — that is the root cause to fix.
3. If every field is `val` and every field type is `stable`, but the class still says `unstable`, look for an interface or generic without a substituted argument.
4. If you see `runtime stable`, **DO NOT** "fix" it. Cross-reference with `composables.txt`: if the call sites pass stable substitutions, skipping works at runtime.
5. Cross-reference with `composables.txt` to check whether the unstable class actually appears as a parameter on a hot-path composable. An unstable class that never crosses a composable boundary does not need fixing.

## Common pitfalls

- **Custom `equals` not detected as structural.** The compiler treats data-class generated `equals` as structural by default. Hand-written `equals` that is not structural will not be flagged unstable per se, but will silently miss recompositions because cached parameter comparisons use `equals`. Stability config is a contract.
- **`@StableMarker` meta-annotations.** A class annotated with a `@StableMarker`-tagged annotation is treated as stable. If the class still appears as `unstable` in classes.txt, the annotation is not on the classpath the compiler used; verify the dependency is `compileOnly` or `implementation` on the annotated module.
- **Sealed hierarchies.** `unstable class Outcome` followed by stable subclasses indicates that the parent abstract class was treated as `Stability.Unknown`. Annotate the sealed parent with `@Immutable` if every subclass is immutable.
- **Inline classes.** Reported by their underlying type. `value class Email(val raw: String)` shows up as `stable class Email { stable val raw: String }`.

## See also

- `reading-composables-txt.md` for the per-composable annotations that point you here.
- Android Developers — Diagnose stability: https://developer.android.com/develop/ui/compose/performance/stability/diagnose
- skydoves/compose-stability-inference (the 12-phase algorithm and bitmask catalog): https://github.com/skydoves/compose-stability-inference
