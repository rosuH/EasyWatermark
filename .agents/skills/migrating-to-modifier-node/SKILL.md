---
name: migrating-to-modifier-node
description: Use this skill to author new custom Jetpack Compose modifiers and migrate legacy ones from Modifier.composed { } to Modifier.Node + ModifierNodeElement<T>. Covers the persistent-node lifecycle (onAttach, onDetach, onReset, coroutineScope), the specialized node interfaces (DrawModifierNode, LayoutModifierNode, SemanticsModifierNode, PointerInputModifierNode, CompositionLocalConsumerModifierNode, LayoutAwareModifierNode, GlobalPositionAwareModifierNode, ObserverModifierNode, DelegatingNode, TraversableNode), why ModifierNodeElement MUST be a data class for diffing, and the manual-invalidation knobs (invalidateDraw, invalidateMeasurement, invalidatePlacement, shouldAutoInvalidate). Use when the developer mentions Modifier.composed, custom modifier, ModifierNodeElement, Modifier.Node, "rewriting our drawBehind helper", node lifecycle, or sees Modifier.composed { } in a code review.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - modifier-node
  - modifier-composed
  - custom-modifier
  - draw-modifier-node
  - layout-modifier-node
  - composition-local-consumer
---

# Migrating to Modifier.Node — Persistent Nodes Over `composed { }`

`Modifier.composed { }` allocates a fresh composable scope per modifier per composition: it cannot be skipped, cannot be hoisted, and forces the parent to run on every recomposition. `Modifier.Node` is a persistent node diffed by `ModifierNodeElement.equals()` — created once on first apply, updated in place on subsequent applies. There is **no per-recomposition allocation, no fresh composable scope, and no parent invalidation chain**, which is why Android Developers describes the system as "designed from the ground up to be far more performant" than the legacy `composed { }` factory (see `developer.android.com/develop/ui/compose/custom-modifiers`). This skill teaches Claude how to author new modifiers as `Modifier.Node` and migrate legacy `composed { }` factories.

## When to use this skill

- A custom modifier currently uses `Modifier.composed { }` (search the module for `Modifier.composed`).
- Authoring a new custom modifier from scratch — never start with `composed { }`.
- A code review surfaces a `composed { }` factory.
- The custom modifier needs a `CoroutineScope` (animation loop, debouncer), reads a `CompositionLocal`, participates in layout / drawing / pointer input, or tracks layout coordinates.
- A `@TraceRecomposition` log shows the parent composable recomposing on every frame because a `composed { }` modifier is in the chain.

## When NOT to use this skill

- The "modifier" is actually a one-line composable wrapper that can stay a `@Composable` function — leave it alone.
- The built-in modifier composition (`Modifier.padding(...).clickable(...)`) is sufficient, no custom node behavior needed.
- The fix the developer needs is reordering an existing chain, not authoring a new node — see `../ordering-modifier-chains/SKILL.md`.
- The custom modifier reads a hot animation value via `Modifier.composed { }` only to feed a value-form modifier underneath — the underlying issue is a wrong-phase state read; see `../../recomposition/deferring-state-reads/SKILL.md`.

## Prerequisites

- **Compose UI 1.5+** — `Modifier.Node` and the specialized node interfaces are stable here.
- **Kotlin 2.0+** with `org.jetbrains.kotlin.plugin.compose` applied. Strong Skipping is on by default; non-skippable modifiers compound at scroll velocity.
- A release build for any final measurement (skydoves hot take #5: debug builds run interpreted and lie).
- For the full per-interface override surface and lifecycle diagram, read `references/modifier-node-anatomy.md` before authoring anything beyond a `DrawModifierNode`.

## Workflow

- [ ] **1. Identify every `Modifier.composed { }` factory in the module.** Grep for `Modifier.composed`. Each match is one migration target. Note what the body does — `remember`, `drawBehind`, `LaunchedEffect`, a `CompositionLocal` read, a pointer handler — because that decides which specialized node interface(s) you need.

- [ ] **2. Sketch the three pieces every Modifier.Node migration produces.**
  - **(a) The public extension** — `fun Modifier.foo(...): Modifier = this then FooElement(...)`. Same name and signature as the old `composed { }` factory.
  - **(b) The `ModifierNodeElement<T>` `data class`** — holds the parameters; implements `create()` (called once on first apply) and `update(node: T)` (called on subsequent applies).
  - **(c) The `Modifier.Node` subclass** — holds mutable state (`var` fields), implements one or more specialized node interfaces, and runs lifecycle hooks (`onAttach`, `onDetach`, `onReset`).

- [ ] **3. Make the Element a `data class`.** The compiler-synthesized `equals()`/`hashCode()` are how Compose decides whether to call `update()` vs leave the node alone. **MUST** be `data class`. A plain `class` falls back to referential equality, the diff thinks every apply is a new modifier, and `update()` is never called — your node holds stale parameters silently.

- [ ] **4. Pick the right specialized node interface(s).** A `Modifier.Node` is empty by itself; behavior comes from interfaces it implements. The common ones:

| Interface | Use when the modifier needs to … |
|---|---|
| `DrawModifierNode` | draw (replaces `drawBehind`/`drawWithCache`) |
| `LayoutModifierNode` | measure/place (replaces `layout { }` and custom `Layout`) |
| `SemanticsModifierNode` | contribute to accessibility |
| `PointerInputModifierNode` | handle pointer / gesture input (replaces `pointerInput`) |
| `CompositionLocalConsumerModifierNode` | read a `CompositionLocal` from inside the node |
| `LayoutAwareModifierNode` | get notified when this node's size/coordinates change |
| `GlobalPositionAwareModifierNode` | get notified about position in the window/root |
| `ObserverModifierNode` | observe arbitrary state reads with a custom observer (`observeReads { ... }`) |
| `DelegatingNode` | compose multiple node behaviors by delegating to child nodes |
| `TraversableNode` | walk the modifier chain (parent/child traversal) |

A node MAY implement several interfaces at once — e.g. `DrawModifierNode + CompositionLocalConsumerModifierNode + LayoutAwareModifierNode`. For complex multi-behavior modifiers, **PREFERRED:** compose smaller `DelegatingNode` children rather than one mega-node implementing five interfaces.

- [ ] **5. Use the built-in `coroutineScope` for async work.** Every `Modifier.Node` exposes a `coroutineScope: CoroutineScope` lazily tied to the node's attach/detach lifecycle. Launch animations, observers, debouncers there from `onAttach()`. **MUST NOT** create your own `CoroutineScope` inside `onAttach` — you will leak it past `onDetach`.

- [ ] **6. Trigger re-runs explicitly when needed.** When you mutate node state from inside `update()` or a coroutine and need a redraw / re-measure / re-place, call `invalidateDraw()`, `invalidateMeasurement()`, or `invalidatePlacement()`. By default, `update()` triggers an auto-invalidation; for fine control, override `shouldAutoInvalidate = false` and invalidate manually.

- [ ] **7. Implement `onAttach`/`onDetach`/`onReset` for resource lifecycle.** `onAttach` runs when the node joins the tree; `onDetach` when it leaves; `onReset` when the node is reused (only relevant inside lazy layouts). **MUST** release listeners, observers, and external subscriptions in `onDetach`.

- [ ] **8. Verify migration: no `Modifier.composed { }` remains.** Re-grep the module. If any `composed { }` calls survive, list them with rationale; otherwise the migration is complete.

## Patterns

### Pattern: migrate `composed { drawBehind }` to `DrawModifierNode`

```kotlin
// WRONG (legacy)
fun Modifier.circle(color: Color): Modifier = composed {
    val computed = remember(color) { color.copy(alpha = 0.5f) }
    drawBehind { drawCircle(computed) }
}
// WRONG because: composed { } opens a fresh composable scope per parent recomposition; the modifier can never be skipped and forces the parent to recompose on every read it does inside.
```

```kotlin
// RIGHT
private data class CircleElement(val color: Color) : ModifierNodeElement<CircleNode>() {
    override fun create(): CircleNode = CircleNode(color)
    override fun update(node: CircleNode) { node.color = color }
}

private class CircleNode(var color: Color) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawCircle(color.copy(alpha = 0.5f))
        drawContent()
    }
}

fun Modifier.circle(color: Color): Modifier = this then CircleElement(color)
```

The `data class` Element gives `equals()` / `hashCode()` for free. When the caller passes the same `color`, `equals()` returns true and the node is left alone. When the color changes, `update()` mutates `node.color` in place — no allocation, no Composition invalidation in the parent.

### Pattern: coroutine work in a node (replace `composed { LaunchedEffect }`)

```kotlin
// WRONG (legacy)
fun Modifier.pulse(period: Long): Modifier = composed {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(period) {
        while (true) { alpha.animateTo(0.3f); alpha.animateTo(1f); delay(period) }
    }
    graphicsLayer { this.alpha = alpha.value }
}
// WRONG because: every parent recomposition allocates a new composable scope, and the LaunchedEffect's keying logic re-evaluates inside that scope.
```

```kotlin
// RIGHT
private data class PulseElement(val period: Long) : ModifierNodeElement<PulseNode>() {
    override fun create(): PulseNode = PulseNode(period)
    override fun update(node: PulseNode) { node.period = period }
}

private class PulseNode(var period: Long) : Modifier.Node(), DrawModifierNode {
    private var alpha by mutableFloatStateOf(1f)

    override fun onAttach() {
        coroutineScope.launch {
            while (true) {
                animate(1f, 0.3f) { value, _ -> alpha = value; invalidateDraw() }
                animate(0.3f, 1f) { value, _ -> alpha = value; invalidateDraw() }
                delay(period)
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        drawRect(Color.Black.copy(alpha = 1f - alpha), blendMode = BlendMode.DstIn)
    }
}

fun Modifier.pulse(period: Long): Modifier = this then PulseElement(period)
```

The node's built-in `coroutineScope` is cancelled automatically on `onDetach`. No leak, no manual `DisposableEffect`.

### Pattern: read a `CompositionLocal` inside a node

```kotlin
// WRONG (legacy)
fun Modifier.themedBorder(width: Dp): Modifier = composed {
    val tokens = LocalThemeTokens.current
    drawBehind { drawRect(tokens.outline, style = Stroke(width.toPx())) }
}
// WRONG because: every CompositionLocal read inside composed { } pins the modifier to a fresh scope per parent recomposition.
```

```kotlin
// RIGHT
private data class ThemedBorderElement(val width: Dp) : ModifierNodeElement<ThemedBorderNode>() {
    override fun create(): ThemedBorderNode = ThemedBorderNode(width)
    override fun update(node: ThemedBorderNode) { node.width = width }
}

private class ThemedBorderNode(
    var width: Dp,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        val tokens = currentValueOf(LocalThemeTokens)
        drawContent()
        drawRect(tokens.outline, style = Stroke(width.toPx()))
    }
}

fun Modifier.themedBorder(width: Dp): Modifier = this then ThemedBorderElement(width)
```

`CompositionLocalConsumerModifierNode` exposes `currentValueOf(local)` from inside any node callback. Reads are tracked by the Draw invalidation list, not by a Composition restart scope, so changing `LocalThemeTokens` redraws the node without recomposing the parent.

### Pattern: forgetting `data class` (the silent-stale-node bug)

```kotlin
// WRONG
private class CircleElement(val color: Color) : ModifierNodeElement<CircleNode>() {
    override fun create() = CircleNode(color)
    override fun update(node: CircleNode) { node.color = color }
}
// WRONG because: not a data class -> equals() is referential -> every apply looks like a different element -> Compose tears down and recreates the node every time, OR the diff fails and update() is never called, leaving the node with the original color forever.
```

```kotlin
// RIGHT
private data class CircleElement(val color: Color) : ModifierNodeElement<CircleNode>() {
    override fun create() = CircleNode(color)
    override fun update(node: CircleNode) { node.color = color }
}
```

### Pattern: holding a reference to the calling composable

```kotlin
// WRONG
private class HostingNode(val composer: Composer) : Modifier.Node() { /* ... */ }
// WRONG because: a Modifier.Node outlives any single composition pass; holding a Composer/composition-scoped object leaks it and invokes undefined behavior.
```

```kotlin
// RIGHT — accept primitive/stable parameters; read CompositionLocals via CompositionLocalConsumerModifierNode if you need composition context.
private data class HostingElement(val tag: String) : ModifierNodeElement<HostingNode>() {
    override fun create() = HostingNode(tag)
    override fun update(node: HostingNode) { node.tag = tag }
}
private class HostingNode(var tag: String) : Modifier.Node() { /* ... */ }
```

### Pattern: composing multiple behaviors with `DelegatingNode`

```kotlin
// RIGHT — one public modifier, three small nodes delegated under one element
private data class CardEffectsElement(
    val color: Color,
    val onClick: () -> Unit,
) : ModifierNodeElement<CardEffectsNode>() {
    override fun create() = CardEffectsNode(color, onClick)
    override fun update(node: CardEffectsNode) {
        node.update(color, onClick)
    }
}

private class CardEffectsNode(
    color: Color,
    onClick: () -> Unit,
) : DelegatingNode() {
    private val background = delegate(BackgroundNode(color))
    private val click = delegate(ClickNode(onClick))

    fun update(color: Color, onClick: () -> Unit) {
        background.color = color
        click.onClick = onClick
    }
}
```

`DelegatingNode` is the canonical way to assemble multi-behavior modifiers without one node implementing every interface. The delegated children share the host's lifecycle.

## Specialized node interfaces

Cheat sheet — full override surface and "use when" guidance lives in `references/modifier-node-anatomy.md`:

- `DrawModifierNode` — implement `ContentDrawScope.draw()`. Replaces `drawBehind`/`drawWithCache` for custom modifiers.
- `LayoutModifierNode` — implement `MeasureScope.measure(...)`. Replaces `Modifier.layout { }`.
- `SemanticsModifierNode` — implement `SemanticsPropertyReceiver.applySemantics()`.
- `PointerInputModifierNode` — implement `onPointerEvent(...)` and `onCancelPointerInput()`.
- `CompositionLocalConsumerModifierNode` — exposes `currentValueOf(local)` inside any node callback.
- `LayoutAwareModifierNode` — `onPlaced(coordinates)` / `onRemeasured(size)`.
- `GlobalPositionAwareModifierNode` — `onGloballyPositioned(coordinates)`.
- `ObserverModifierNode` — wrap state reads with `observeReads { ... }` and react in `onObservedReadsChanged()`.
- `DelegatingNode` — `delegate(otherNode)` to compose behaviors.
- `TraversableNode` — walk parents/children/descendants via the top-level extension functions on `DelegatableNode`: `traverseAncestors(key, block)`, `traverseChildren(key, block)`, `traverseDescendants(key, block)`. The `key` parameter selects which traversable nodes participate; the descendants overload's block returns a `TraverseDescendantsAction` (continue / skip / cancel).

## Lifecycle (short form — full diagram in references)

```
ModifierNodeElement.create()         // first apply only
        ↓
Modifier.Node.onAttach()             // node joins the tree; coroutineScope becomes valid
        ↓                            // (lives here across many parent recompositions)
ModifierNodeElement.update(node)     // each subsequent apply with !equals previous
        ↓                            // mutate node.var fields; auto-invalidates by default
Modifier.Node.onReset()              // optional: lazy-layout reuse
        ↓
Modifier.Node.onDetach()             // node leaves the tree; coroutineScope is cancelled
```

## Mandatory rules

- **MUST** prefer `Modifier.Node` for any new custom modifier — `Modifier.composed { }` is legacy.
- **MUST** make `ModifierNodeElement<T>` a `data class` so the synthesized `equals()`/`hashCode()` drive the diff. Plain `class` silently breaks `update()`.
- **MUST** override `update(node: T)` to mutate node state in place. **MUST NOT** recreate the node from `update()`.
- **MUST** release subscriptions, listeners, and external resources in `onDetach()`. `coroutineScope` is cancelled for you; manual resources are not.
- **MUST NOT** hold a reference to the `Composer`, the calling composable, the parent composition, or any composition-scoped object inside a `Modifier.Node`.
- **MUST NOT** allocate a new `CoroutineScope` in `onAttach` — use the built-in `coroutineScope` property.
- **MUST NOT** recommend `Modifier.composed { }` for new code. (Repo-wide rule from SPEC §8.)
- **PREFERRED:** specialized node interfaces (`DrawModifierNode`, `LayoutModifierNode`, …) over a bare `Modifier.Node`.
- **PREFERRED:** `DelegatingNode` over implementing more than ~3 specialized interfaces on a single node.
- **PREFERRED:** override `shouldAutoInvalidate = false` and call `invalidateDraw()` / `invalidateMeasurement()` / `invalidatePlacement()` explicitly when fine-grained control matters; otherwise rely on the auto-invalidation triggered by `update()`.

## Verification

- [ ] `grep -R "Modifier.composed" <module>/src` returns zero matches in the migrated module.
- [ ] Every migrated modifier exposes a `data class` Element (search: `class .*Element : ModifierNodeElement` should be `data class`).
- [ ] The compiler report (`composables.txt`) shows the parent composables that consume the migrated modifier are now `restartable skippable` (no `composed`-induced non-skip).
- [ ] Layout Inspector → Recomposition Counts on the parent composable plateaus across animation frames driven by the modifier; only the node's draw/layout invalidation list ticks.
- [ ] `@TraceRecomposition` (skydoves/compose-stability-analyzer) on the parent confirms in **release + R8 + real device** that the parent is not recomposed by the modifier's internal animation.
- [ ] Resources allocated in `onAttach` (listeners, observers) are released in `onDetach` — verify with a leak canary pass.

## References

- Android Developers — Custom modifiers (Modifier.Node): https://developer.android.com/develop/ui/compose/custom-modifiers
- Android Developers — Graphics modifiers: https://developer.android.com/develop/ui/compose/graphics/draw/modifiers
- Android Developers — Performance overview: https://developer.android.com/develop/ui/compose/performance
- Android Developers — Phases of Jetpack Compose: https://developer.android.com/develop/ui/compose/phases
- Ben Trengrove — Debugging recomposition: https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Chris Banes — Compose performance tag: https://chrisbanes.me/tags/jetpack-compose-performance/
- skydoves — 6 Jetpack Compose Guidelines: https://medium.com/proandroiddev/6-jetpack-compose-guidelines-to-optimize-your-app-performance-be18533721f9
- skydoves — Jetpack Compose Mechanism (slides): https://speakerdeck.com/skydoves/jetpack-compose-mechanism
- `references/modifier-node-anatomy.md` — full per-interface override surface, lifecycle diagram, `DelegatingNode` composition recipes, and manual-invalidation guidance.
