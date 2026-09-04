---
name: understanding-hot-reload-limits
description: Use this skill to teach Claude exactly which Kotlin and Compose changes hot-reload under Compose HotSwan and which trigger a full incremental rebuild fallback. Root cause is Android Runtime (ART) class schema immutability; only method bodies are mutable at runtime, so any change to fields, signatures, constructors, interfaces, inline functions, or new resource ids forces a rebuild. Covers the supported-changes table, the rebuild-forcing list, the diff-then-batch workflow that keeps a hot-reload session inside the fast path, and the inline-function and new-resource-id pitfalls. Trigger when the user asks "why did this rebuild?", "why isn't this hot reloading?", wants to learn HotSwan's boundaries before adopting it, or when reviewing a refactor that risks pushing a hot-reload session into a full rebuild.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - hot-reload
  - hotswan
  - art
  - class-redefinition
  - iteration
  - developer-experience
---

# Understanding Hot Reload Limits: stay inside the fast path

Android Runtime (ART) enforces strict rules for class redefinition: a redefined class must have an identical schema (fields, method signatures, interfaces) as the previous version. Only method bodies are mutable at runtime. HotSwan automatically detects schema changes and falls back to a full incremental build, so failures are not silent. Knowing the boundary in advance is what keeps an iteration loop sub-second instead of multi-second.

## When to use this skill

- The developer asks "why did this trigger a full rebuild?", "why isn't this hot reloading?", or "what does HotSwan support?".
- A PR review surfaces a refactor (parameter change, constructor change, interface extraction, new resource id) that would push a hot-reload session into a rebuild.
- The developer is planning a session and wants to order edits so the slow ones happen at the end.
- The developer reports an `inline fun` change that "didn't reload" and is debugging.

## When NOT to use this skill

- Setup or first-run troubleshooting. See `../setting-up-compose-hotswan/SKILL.md`.
- Pure state-preservation question (the reload happened but state was lost). See the state-preservation sibling skill.
- Configuration of the AI iteration loop or MCP server. See the AI-loop sibling skill.

## Prerequisites

- HotSwan installed and verified per `../setting-up-compose-hotswan/SKILL.md` (tool window status `WATCHING`, body-only edit reloads in under one second).
- Familiarity with the Kotlin compiler's distinction between method-body changes and class-schema changes.

## Boundary tables

### Hot-reloadable (no rebuild)

| Change | Notes |
|---|---|
| Composable function body | text, colors, modifiers, layout, control flow inside the function |
| Non-composable function body | ViewModel methods, mappers, utilities, repositories |
| Adding a new composable | same file or new file |
| Reordering composables | HotSwan 1.2.0+ |
| Resource value changes | `strings.xml` value, `colors.xml` value, `dimens.xml` value |
| Extension functions | including suspend, including vararg |
| Adding `data class` properties | API 30+ only |
| Numeric, string, float literal patches | compiled separately for fastest reload |

### Forces full rebuild (fallback)

| Change | Reason |
|---|---|
| Adding or removing function parameters | method signature changes |
| Constructor changes | parameter list, default values, init blocks affecting fields |
| Interface or superclass changes | class hierarchy is part of the schema |
| Adding new resource ids | new `R.string`, `R.drawable`, `R.id` entries require generated `R` class regeneration |
| Inline functions | expanded at every call site; no discrete unit to swap |
| Lambda count change inside a function | internal lambda class renumbering |
| Removing a previously defined function | method table shrinks; schema changes |
| Adding `data class` properties below API 30 | constructor schema change without ART support |

## Workflow when a change does not hot-reload

1. **Watch the HotSwan tool window status** at the moment of save. A schema change surfaces as a "falling back to incremental build" status. That is the explicit signal, not a silent failure.
2. **Read the change diff.** Decide: is this a body-only change, or a schema change (signature, constructor, interface, field, new resource id, inline body, lambda count)?
3. **If a refactor must change a signature, batch it.** First make the body-only changes that get the screen to a working visual state and hot-reload them. Then, in a separate save, change the signature and accept the rebuild as the last step of the session.
4. **For inline functions, drop `inline` for the duration of iteration.** Refactor the function out of `inline` while iterating, hot-reload as needed, and restore `inline` before commit. If iteration is rare or the perf characteristic must be preserved, accept the rebuild.
5. **For new resource ids, batch all id additions first**, accept one rebuild, then iterate on values. Editing `<string name="title">Updated Title</string>` (existing id) hot-reloads; adding `<string name="brand_new_id">...</string>` does not.

## Patterns

### Pattern: function-body change (fast path) vs signature change (rebuild)

```kotlin
// RIGHT (body-only change, hot-reloads)
@Composable
fun Greeting() {
    Text("Hello, World", color = Color.Blue)
}
```

```kotlin
// WRONG for the fast path (signature change, forces rebuild)
@Composable
fun Greeting(name: String) {
    Text("Hello, $name")
}
// WRONG because: adding a parameter changes the method signature, which violates ART's class
// schema constraint. To stay inside the fast path, introduce the parameter with a default value
// in one save (still a rebuild; accept it once), then iterate on the body across subsequent saves.
```

### Pattern: adding a new composable (fast path) vs changing a constructor (rebuild)

```kotlin
// RIGHT (new composable in the same file, hot-reloads on HotSwan 1.2.0+)
@Composable
fun SecondaryAction() {
    Text("New")
}
```

```kotlin
// WRONG for the fast path (constructor change)
data class User(val id: Long, val name: String, val age: Int)
// WRONG because: adding `age` extends the constructor. On API 30+ HotSwan supports adding
// data class properties; below API 30 the schema change forces a rebuild. When targeting older
// minSdk, batch property additions and accept a rebuild for each.
```

### Pattern: inline function body change (rebuild)

```kotlin
// WRONG for the fast path
inline fun <T> Modifier.observer(value: T, body: (T) -> Modifier): Modifier = body(value)
// WRONG because: inline functions are expanded at every call site at compile time. There is no
// discrete unit to swap, so editing the inline body forces a full rebuild of every call site.
// Drop `inline` for the duration of iteration, or accept the rebuild.
```

```kotlin
// RIGHT for the fast path (non-inline variant during iteration)
fun <T> Modifier.observer(value: T, body: (T) -> Modifier): Modifier = body(value)
```

### Pattern: resource value change (fast path) vs new resource id (rebuild)

```xml
<!-- RIGHT: value change on an existing id, hot-reloads -->
<string name="title">Updated Title</string>
```

```xml
<!-- WRONG for the fast path: new id, forces rebuild -->
<string name="brand_new_id">Hello</string>
<!-- WRONG because: adding a new string id grows the generated R class. R class regeneration
     is a schema change that ART cannot redefine in place. -->
```

### Pattern: lambda count change (rebuild)

```kotlin
// RIGHT (body change inside the existing lambda, hot-reloads)
@Composable
fun Row() {
    Button(onClick = { log("tapped") }) { Text("Tap") }
}
```

```kotlin
// WRONG for the fast path (adds a second lambda, renumbers inner lambda classes)
@Composable
fun Row() {
    Button(onClick = { log("tapped") }) { Text("Tap") }
    Button(onClick = { log("second") }) { Text("Second") }
}
// WRONG because: a Kotlin function's compiled lambdas are anonymous classes named like
// Row$lambda$1, Row$lambda$2. Adding a lambda renumbers them and changes the class table,
// which is a schema change. The fix is to add the second composable as a separate top-level
// function (which is supported), then call it from Row once the structural change has rebuilt.
```

## Mandatory rules

- **MUST** consult the boundary tables before suggesting a change to a composable that the developer is iterating on. Suggesting a parameter add when the developer wanted speed wastes the iteration loop.
- **MUST NOT** claim a change will hot-reload without checking it against the supported-changes table above.
- **MUST NOT** disable HotSwan to "force a clean rebuild". Use Gradle's `:app:clean` for a clean build; HotSwan auto-falls-back when needed and turning it off costs the next round-trip.
- **MUST** treat new resource ids and inline-function body changes as rebuild-forcing on every Android API level. The data-class-property exception is API 30+ only.
- **PREFERRED:** order an iteration session so body-only edits come first and signature, constructor, interface, or new-resource-id changes are batched at the end.
- **PREFERRED:** when a refactor needs a parameter add, introduce it once with a default value (accept the rebuild), then iterate on the body across subsequent saves.

## Verification

- [ ] A body-only change to a composable hot-reloads on save with the tool window status remaining `WATCHING`.
- [ ] Adding a parameter to a composable triggers the fallback status in the tool window.
- [ ] Adding a new `<string>` resource triggers the fallback; editing an existing `<string>` value does not.
- [ ] Editing the body of an `inline` function triggers the fallback; converting it to a non-`inline` function and editing the body hot-reloads.
- [ ] Adding a property to a `data class` hot-reloads on a device running API 30+; on API 29 and below it triggers the fallback.

## References

- HotSwan supported-changes documentation: https://github.com/skydoves/compose-hotswan-web (under `/docs/supported-changes`).
- HotSwan limitations documentation: https://github.com/skydoves/compose-hotswan-web (under `/docs/limitations`).
- Android Runtime class redefinition (JVMTI) reference: https://source.android.com/docs/core/runtime/jvmti
- Sibling skill `../setting-up-compose-hotswan/SKILL.md` for installation and the first save-to-reload verification.
