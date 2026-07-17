# Modifier.Node Anatomy — Lifecycle, Interfaces, and Manual Invalidation

This reference expands the Workflow in `../SKILL.md` with the deeper mechanics: the full lifecycle diagram, every specialized node interface with its override surface, when to use `DelegatingNode` to compose multiple node behaviors, and the manual-invalidation knobs.

---

## 1. The lifecycle diagram

```
                ┌────────────────────────────────────────────┐
                │  Modifier chain applied for the first time │
                └─────────────────────┬──────────────────────┘
                                      ▼
                       ModifierNodeElement.create()
                              (returns Node)
                                      │
                                      ▼
                          Modifier.Node.onAttach()
                  ┌───────────────────┴───────────────────┐
                  │  - coroutineScope is now valid        │
                  │  - currentValueOf(local) works (CLC)  │
                  │  - register listeners / observers     │
                  └───────────────────┬───────────────────┘
                                      ▼
            ┌─────────── Steady-state lifetime ─────────────┐
            │                                               │
            │   On each subsequent apply where Element.     │
            │   equals(previous) is FALSE:                  │
            │                                               │
            │     ModifierNodeElement.update(node)          │
            │       ↳ mutate node.var fields                │
            │       ↳ default: auto-invalidates             │
            │       ↳ override shouldAutoInvalidate=false   │
            │         and call invalidate*() yourself       │
            │                                               │
            │   On lazy-layout reuse:                       │
            │     Modifier.Node.onReset()                   │
            │       ↳ reset transient state for reuse       │
            │                                               │
            └───────────────────┬───────────────────────────┘
                                ▼
                    Modifier.Node.onDetach()
            ┌───────────────────┴───────────────────┐
            │  - coroutineScope is cancelled        │
            │  - release listeners / observers      │
            │  - drop any external resources        │
            └───────────────────────────────────────┘
```

Key invariants:

- `onAttach()` runs exactly once per **node instance** going into the tree. A node may go through `attach → detach → attach` cycles in lazy layouts; `onReset` is the hook for "I'm being reused, clean up transient state but stay attached".
- `coroutineScope` is **lazily** created on first access. It is cancelled on `onDetach()`. Do not capture it across detaches.
- `update()` is called **only** when the new Element does not `equals` the previous one. Compose has already decided the node is the right kind; you mutate fields in place and (by default) the framework invalidates draw/layout/placement automatically.

## 2. Specialized node interfaces — full reference

A `Modifier.Node` is a behavior-less hook into the tree. Behavior comes from interfaces. Each entry below lists the override surface you implement and the "use when" guidance.

### `DrawModifierNode`

Override:
```kotlin
fun ContentDrawScope.draw()
```

**Use when** the modifier paints — backgrounds, borders, overlays, custom decorations. Replaces `Modifier.drawBehind { }` / `Modifier.drawWithCache { }` for custom modifiers. Call `drawContent()` to draw the wrapped content; place draw calls before/after `drawContent()` to paint behind/in front.

### `LayoutModifierNode`

Override:
```kotlin
fun MeasureScope.measure(
    measurable: Measurable,
    constraints: Constraints,
): MeasureResult
```

Optional intrinsic measurement overrides:
```kotlin
fun IntrinsicMeasureScope.minIntrinsicWidth(measurable, height): Int
fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable, height): Int
fun IntrinsicMeasureScope.minIntrinsicHeight(measurable, width): Int
fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable, width): Int
```

**Use when** the modifier participates in measurement or placement — custom padding, custom layouts, aspect-ratio enforcement, anything that translates `Constraints` differently. Replaces `Modifier.layout { }` and most one-off custom `Layout` calls.

### `SemanticsModifierNode`

Override:
```kotlin
fun SemanticsPropertyReceiver.applySemantics()
```

Optional:
```kotlin
val shouldMergeDescendantSemantics: Boolean   // default false
val shouldClearDescendantSemantics: Boolean   // default false
```

**Use when** the modifier contributes accessibility properties — content description, role, click action, custom actions. Replaces `Modifier.semantics { }` for custom modifiers.

### `PointerInputModifierNode`

Override:
```kotlin
fun onPointerEvent(
    pointerEvent: PointerEvent,
    pass: PointerEventPass,
    bounds: IntSize,
)
fun onCancelPointerInput()
```

**Use when** the modifier handles raw pointer input — gesture detection, drag handlers, custom hit testing. Replaces `Modifier.pointerInput { }` for custom modifiers; for high-level gestures (tap, drag) you usually delegate to the `gesture` family node modifiers via `DelegatingNode` instead.

### `CompositionLocalConsumerModifierNode`

Adds:
```kotlin
fun <T> currentValueOf(local: CompositionLocal<T>): T
```

**Use when** any node callback needs to read a `CompositionLocal` — theme tokens, density, layout direction, ripple configuration. Reads register against the calling phase's invalidation list (Draw / Layout / Pointer), NOT a Composition restart scope. Implement alongside the relevant behavior interface — `DrawModifierNode + CompositionLocalConsumerModifierNode` is the canonical pair for theme-aware drawing.

### `LayoutAwareModifierNode`

Overrides:
```kotlin
fun onPlaced(coordinates: LayoutCoordinates)
fun onRemeasured(size: IntSize)
```

**Use when** the modifier needs to react to its own size or local placement — sizing reporters, overlay anchoring relative to this node, "tell me when my width changes" use cases. Replaces `Modifier.onSizeChanged { }` and `Modifier.onPlaced { }`.

### `GlobalPositionAwareModifierNode`

Override:
```kotlin
fun onGloballyPositioned(coordinates: LayoutCoordinates)
```

**Use when** the modifier needs to react to global / window-relative positioning — popup anchoring, scroll-target detection, "where am I on screen" use cases. More expensive than `LayoutAwareModifierNode` (fires on any ancestor placement change); prefer `LayoutAwareModifierNode` if local coordinates suffice.

### `ObserverModifierNode`

Adds:
```kotlin
fun observeReads(block: () -> Unit)            // wrap state reads
fun onObservedReadsChanged()                    // callback when any tracked read changes
```

**Use when** the modifier needs to observe arbitrary state reads outside of the standard Layout/Draw passes — bridging snapshot reads to imperative work (e.g. mirroring a state into an external system). Carry costs; reach for it only when the standard invalidation paths do not fit.

### `DelegatingNode`

Adds:
```kotlin
protected fun <T : DelegatableNode> delegate(delegatableNode: T): T
protected fun <T : DelegatableNode> undelegate(instance: T)
```

Note: the bound is `DelegatableNode`, not `Modifier.Node`. Both `Modifier.Node` and `DelegatingNode` itself implement `DelegatableNode`, so anything you can attach to the tree can be delegated.

**Use when** assembling multi-behavior modifiers without piling interfaces on one class. Each delegated child is a real `Modifier.Node` with its own `onAttach`/`onDetach`/`coroutineScope`, but its lifecycle is bound to the host. Canonical pattern:

```kotlin
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

**PREFERRED:** reach for `DelegatingNode` once a single node would implement more than ~3 specialized interfaces. The delegated children are independently testable and reusable.

### `TraversableNode`

Implement `TraversableNode` to expose a `traverseKey: Any` discriminator. The actual walk happens through top-level extension functions on `DelegatableNode` — they are **not** members of `TraversableNode`:

```kotlin
fun DelegatableNode.traverseAncestors(
    key: Any?,
    block: (TraversableNode) -> Boolean,
)

fun DelegatableNode.traverseChildren(
    key: Any?,
    block: (TraversableNode) -> Boolean,
)

fun DelegatableNode.traverseDescendants(
    key: Any?,
    block: (TraversableNode) -> TraverseDescendantsAction,
)
```

The ancestor/child blocks return `Boolean` (continue when `true`, stop when `false`); the descendants block returns a `TraverseDescendantsAction` (`ContinueTraversal`, `SkipSubtreeAndContinueTraversal`, `CancelTraversal`).

**Use when** the modifier needs to find sibling/ancestor nodes of the same kind — focus scopes finding the nearest focus owner, scroll containers locating a nested scroll source. The `traverseKey` is the discriminator; nodes sharing a key are visible to each other's traversal.

## 3. Manual invalidation knobs

Default behavior: `update()` triggers an automatic invalidation appropriate for the node's interfaces (draw / layout / placement / semantics). For most migrations this is exactly right and you do not touch the knobs.

When you need finer control:

```kotlin
private class CustomNode(...) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {
    override val shouldAutoInvalidate: Boolean get() = false

    fun mutateAffectingDrawOnly() {
        // ...
        invalidateDraw()
    }

    fun mutateAffectingMeasurement() {
        // ...
        invalidateMeasurement()      // forces a remeasure (and thus replace + redraw)
    }

    fun mutateAffectingPlacementOnly() {
        // ...
        invalidatePlacement()        // re-place without remeasure
    }
}
```

Use cases:

- A coroutine-driven animation mutates `var alpha` and only needs a redraw → `invalidateDraw()`.
- A node holds a measured size that depends on an external signal → `invalidateMeasurement()` when the signal changes.
- A scroll-driven offset changes only placement → `invalidatePlacement()`.

Auto-invalidation costs the framework one comparison; manual invalidation costs you the discipline to call the right one. **PREFERRED:** leave `shouldAutoInvalidate = true` (default) unless profiling shows the auto-invalidation does extra work you can avoid.

## 4. Common composition recipes

### Drawing modifier that reacts to theme

```kotlin
private class ThemedShadowNode(
    var elevation: Dp,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        val tokens = currentValueOf(LocalThemeTokens)
        // draw shadow based on tokens.shadowColor and elevation
        drawContent()
    }
}
```

### Layout-aware modifier with coroutine reporter

```kotlin
private class SizeReportNode(
    var onSize: (IntSize) -> Unit,
) : Modifier.Node(), LayoutAwareModifierNode {
    override fun onRemeasured(size: IntSize) {
        coroutineScope.launch { onSize(size) }
    }
}
```

### Multi-behavior via DelegatingNode

```kotlin
private class HoverableCardNode(
    color: Color,
    onClick: () -> Unit,
) : DelegatingNode(), LayoutAwareModifierNode {
    private val background = delegate(BackgroundNode(color))
    private val click = delegate(ClickNode(onClick))
    private val ripple = delegate(RippleNode())

    var measuredSize: IntSize = IntSize.Zero
        private set

    override fun onRemeasured(size: IntSize) {
        measuredSize = size
        ripple.bounds = size
    }

    fun update(color: Color, onClick: () -> Unit) {
        background.color = color
        click.onClick = onClick
    }
}
```

## 5. Migration cheat sheet — `composed { }` source → `Modifier.Node` target

| `composed { }` body uses … | Target node interface(s) |
|---|---|
| `drawBehind { }` / `drawWithCache { }` | `DrawModifierNode` |
| `layout { }` / custom `Layout` | `LayoutModifierNode` |
| `pointerInput { }` | `PointerInputModifierNode` (or delegate to a gesture node) |
| `LocalFoo.current` | add `CompositionLocalConsumerModifierNode` |
| `LaunchedEffect { ... }` | `onAttach { coroutineScope.launch { ... } }` |
| `DisposableEffect { onDispose { x.dispose() } }` | `onDetach { x.dispose() }` |
| `onSizeChanged { }` / `onPlaced { }` | `LayoutAwareModifierNode` |
| `onGloballyPositioned { }` | `GlobalPositionAwareModifierNode` |
| `semantics { }` | `SemanticsModifierNode` |
| Multiple of the above | `DelegatingNode` over single-purpose children |

Once the source body is decomposed into the table above, the migration is mechanical: write the `data class FooElement`, write the `class FooNode` implementing the matching interfaces, move each side-effect into the right lifecycle hook, and expose `fun Modifier.foo(...) = this then FooElement(...)`.
