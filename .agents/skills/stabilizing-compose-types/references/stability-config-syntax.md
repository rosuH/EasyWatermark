# `stability_config.conf` — full grammar and worked examples

`stabilityConfigurationFiles` (plural) is a Compose Compiler 1.5.5+ DSL knob that points the compiler at one or more plain-text files listing types it should treat as stable. It is the right tool for marking third-party or Java types stable without scattering annotations across modules the team does not own.

This file is **a contract**, not a magic spell (skydoves hot take #2). The compiler trusts every listed type to honor the stability contract: structural `equals`, no observable mutation that bypasses Snapshot. Listing a mutable type here causes silent missed recompositions — there is no warning, no crash, just stale UI.

## Wire-up

```kotlin
// build.gradle.kts (root or any compose module)
composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("stability_config.conf")
    )
}
```

The file path is conventionally `<root>/stability_config.conf`. The same file can be shared across all composable modules in a multi-module project — declare the `stabilityConfigurationFiles.add(...)` call in the root `build.gradle.kts` `subprojects { … }` block or in a convention plugin. Because `stabilityConfigurationFiles` is a `ListProperty`, you can `.add(...)` multiple files (e.g. one per feature module) and the compiler unions them.

> **Footnote — legacy singular property.** Older docs and snippets show `stabilityConfigurationFile = file("stability_config.conf")` (singular `RegularFileProperty`). That property is `@Deprecated("Use the stabilityConfigurationFiles option instead")` in modern Compose Compiler Gradle plugin releases. It still works for now, but new code should use the plural `stabilityConfigurationFiles.add(...)` form above.

## Grammar

```
# Comments start with #. Empty lines are allowed.

# 1. Single fully-qualified class
java.time.LocalDateTime

# 2. Single package — direct children only (one segment)
com.example.data.*

# 3. Package and all subpackages (recursive)
com.example.data.**

# 4. Generic class with bitmask — controls per-type-parameter influence
com.example.GenericClass<*,_>
# bit value `*` → this type parameter participates in stability inference
# bit value `_` → this type parameter is ignored (treated as stable regardless)
# bits read left-to-right matching the type parameter declaration order
```

### Pattern resolution rules

- **Exact class names beat wildcards.** A line `com.example.data.User` beats a line `com.example.data.*`.
- **Wildcards match by package only**, never by type-name prefix. `com.example.data.U*` is **not** valid.
- **`*` is a single segment.** `com.example.*` matches `com.example.User` but NOT `com.example.sub.User`.
- **`**` is recursive.** `com.example.**` matches `com.example.User` AND `com.example.sub.deep.Thing`.
- **Generic bitmask is positional.** `Wrapper<*,_,*>` says: parameter 0 affects stability, parameter 1 does not, parameter 2 does. The compiler then queries the substituted types' own `$stable` for each `*` slot.

## Worked examples

### `java.time` — single classes

The Java time types are immutable by design but separately compiled, so the inference falls through to `Stability.Unknown`. Whitelist the immutable subset:

```text
java.time.Instant
java.time.LocalDate
java.time.LocalDateTime
java.time.LocalTime
java.time.OffsetDateTime
java.time.ZonedDateTime
java.time.Duration
java.time.Period
java.time.Year
java.time.YearMonth
java.time.MonthDay
java.time.ZoneId
java.time.ZoneOffset
```

**MUST NOT** add `java.util.Date` to this list — it is mutable. Use `java.time.Instant` instead.

### `kotlin.collections` — make List/Set/Map stable project-wide

```text
# Whitelist the read-only collection interfaces.
# Contract: every call site MUST pass a truly immutable implementation.
kotlin.collections.List
kotlin.collections.Set
kotlin.collections.Map
```

This is a **strong** opt-in. The compiler will now treat any `List<T>` parameter as stable iff `T` is stable. The contract: every producer in the codebase **MUST NOT** pass a `MutableList` cast as `List`. Any code that hands out an aliased mutable collection will silently miss recompositions when the underlying collection mutates.

**PREFERRED:** swap to `kotlinx.collections.immutable.ImmutableList` instead — the type system enforces immutability, no contract risk.

### Recursive package match — own internal data module

```text
# Stability whitelist for the entire `com.example.data` package tree.
# Audited 2026-04: every class is data class with val fields.
com.example.data.**
```

Use this when the team owns and audits the package. Add a comment recording when the audit ran. Pair with `../enforcing-stability-in-ci/SKILL.md` so a future PR adding a `var` field causes a CI failure.

### Generic with bitmask — `dagger.Lazy` pattern

`dagger.Lazy<T>` is a stable wrapper: `equals` is structural and the lazy value is itself the only field. Stability depends on `T`.

```text
# Lazy wrapper: stability depends on T (bit 0 = *).
dagger.Lazy<*>
```

For a multi-arg generic where only the first parameter matters:

```text
# Wrapper<KEY, VALUE>: stability depends on VALUE only.
com.example.cache.Cached<_,*>
```

For a wrapper that is always stable regardless of substituted types (e.g. an opaque token):

```text
com.example.token.Opaque<_>
```

### Combining with annotations

Stability config and annotations are additive. A class annotated `@Immutable` does not need a config entry; a class listed in the config does not need an annotation. Use the config when the source is **not owned**; use the annotation when the source **is owned** so the contract sits next to the code that must honor it.

## Caveats

- **Silent misses.** The compiler does not validate that a config-listed type actually honors the stability contract. A mistyped FQCN simply matches nothing; a wrongly-listed mutable type silently causes stale UI. There is no diagnostic.
- **Re-run reports after every change.** `composables.txt` will show the previously `unstable` parameters flip to `stable` — verify this. If the parameters are still `unstable`, the FQCN is wrong or the package wildcard is too narrow.
- **CI gating recommended.** Skydoves' `compose-stability-analyzer` plugin (`./gradlew :app:stabilityCheck`) and the community `ComposeGuard` plugin both detect stability regressions on PRs. Pair stability config with CI so a teammate cannot silently break the contract.
- **Multi-module sharing.** Declare the file in a convention plugin or `subprojects { composeCompiler { … } }` block; otherwise modules will diverge.
- **Strong Skipping interaction.** Under Strong Skipping (Kotlin 2.0.20+), unstable types are compared by `===` instead of disabling skipping. Stability config still helps because `equals`-comparable parameters can re-skip on equal values from a fresh allocation; without the config, a freshly-allocated `LocalDateTime` with the same instant value will fail `===` and force recomposition.

## Reading checklist

1. Confirm the FQCN matches what `classes.txt` reports — copy/paste, do not hand-type.
2. Prefer the narrowest pattern that does the job. `java.time.LocalDateTime` beats `java.**`.
3. For generics, match the type-parameter count exactly. `Pair<*,*>` not `Pair<*>`.
4. Add a comment with the date and rationale for every entry. Future maintainers need to know the audit basis.
5. After editing, re-run the diagnostic skill to verify the entries took effect.

## See also

- Android Developers — Fix stability: https://developer.android.com/develop/ui/compose/performance/stability/fix
- Compose Compiler release notes: https://developer.android.com/jetpack/androidx/releases/compose-compiler
- Ben Trengrove — New Ways of Optimizing Stability: https://medium.com/androiddevelopers/new-ways-of-optimizing-stability-in-jetpack-compose-038106c283cc
- skydoves — compose-stability-inference (12-phase algorithm and bitmask catalog): https://github.com/skydoves/compose-stability-inference
- skydoves — compose-stability-analyzer (CI plugin): https://github.com/skydoves/compose-stability-analyzer
