# Three Phases — Composition, Layout, Draw

This reference expands the Workflow in `../SKILL.md` with the deeper mechanics: what each phase does, how invalidation propagates downward, the backwards-write rule, and a comprehensive modifier-phase cheat sheet.

---

## 1. The three phases

Every Compose frame runs in this order:

1. **Composition** — Compose runs `@Composable` functions from the root down. Each restartable composable opens a "restart scope" that records which `State` objects it reads. The output is a tree of LayoutNodes plus a list of attached modifiers.
2. **Layout** — for each LayoutNode, Compose calls `measure(constraints)` to obtain a size, then `placeRelative(x, y)` (or similar) to position children. Lambda-form modifiers like `Modifier.offset { … }` and `Modifier.layout { measurable, constraints -> … }` read state during this phase. (Note: `Modifier.padding` ships **no** lambda overload — it only exists as `padding(start, top, end, bottom: Dp)`, `padding(horizontal, vertical: Dp)`, `padding(all: Dp)`, and `padding(paddingValues: PaddingValues)`. To inset based on hot state, write a `Modifier.layout { … }` block.)
3. **Draw** — for each LayoutNode, Compose records draw commands into a canvas. `Modifier.graphicsLayer { … }`, `Modifier.drawBehind { … }`, and `Modifier.drawWithCache { … }` run in this phase.

A frame may skip Composition entirely if no composable's read-set changed; it may skip Layout if no layout-affecting state changed; and it may skip Draw if no draw-affecting state changed. Each phase has its own change-tracking pipeline.

## 2. Downward invalidation rule

> **A state read at phase N invalidates phase N and every phase below it.**

| Read happens in | Invalidates Composition | Invalidates Layout | Invalidates Draw |
|---|---|---|---|
| Composition (composable body, value-form modifier) | YES | YES (via re-emit) | YES (via re-emit) |
| Layout (lambda-form layout modifier) | NO | YES | YES (re-record) |
| Draw (lambda-form draw modifier) | NO | NO | YES |

The corollary is the entire reason this skill exists: **every state read you can defer one phase down saves the cost of every phase above it**. Moving an animated alpha from `Modifier.alpha(value)` to `Modifier.graphicsLayer { alpha = value }` skips both Composition and Layout per frame.

## 3. Restart scopes

A `restartable` composable owns a restart scope. The scope subscribes to every snapshot read inside that composable's body. When any read state changes, the scope is invalidated and Composition re-runs that composable on the next frame.

Inline composables (`Row`, `Column`, `Box`, `Layout`) are **not** restartable — they have no scope of their own; reads inside them attach to the nearest enclosing restart scope. This is why moving a read into a lambda-form modifier matters: the lambda is a separate observer registered against Layout or Draw, not against the enclosing composable's restart scope.

## 4. Snapshot reads & lambda modifiers

`State<T>.value` registers a read with whatever observer is currently active.

- Active observer in a composable body → the restart scope (Composition).
- Active observer inside `Modifier.offset { }` → the layout invalidation list (Layout).
- Active observer inside `Modifier.graphicsLayer { }` or `Modifier.drawBehind { }` → the draw invalidation list (Draw).

This is why the lambda-form modifier rewrite works without any other change to the data flow: the same `State` object, the same `.value` access, but a different observer is on the stack at the moment of the read.

## 5. The backwards-write rule

A composable MUST NOT write to a `MutableState` it has already read in the same composition pass. Compose calls this a "backwards write":

```kotlin
@Composable
fun BadCounter() {
    var count by remember { mutableStateOf(0) }
    if (count == 0) {
        count = 1   // WRONG: writes to a State already read in this composition
    }
    Text("$count")
}
```

The runtime detects the write to a previously-read state and aborts the recomposition, marking the scope dirty so it will run again. In the worst case this becomes an infinite loop of aborted recompositions. The fix is to perform the write inside `LaunchedEffect`, `SideEffect`, an event handler, or a derived computation — anywhere outside the synchronous body of the composable.

This rule is relevant to deferring reads because lambda-form modifiers read state in Layout/Draw — phases that are explicitly allowed to read what was set during Composition. If you find yourself wanting to write a state from inside a `Modifier.layout { }` block, you are using the wrong tool; use `LayoutModifierNode`'s `coordinator` or surface the side effect via callbacks.

## 6. Phase skipping in detail

Compose tracks per-LayoutNode flags:

- `needsRemeasure` — set when a layout-affecting state changes or a child reports a new size.
- `needsRelayout` — set when only placement changes (e.g. `Modifier.offset { }` was invalidated but size is unchanged).
- `needsRedraw` — set when a draw-affecting state changes.

A `Modifier.offset { }` invalidation marks `needsRelayout` only — the node is not re-measured, just re-placed. A `Modifier.graphicsLayer { }` invalidation marks `needsRedraw` only. Neither triggers Composition. This is why a 60 fps animation built on lambda modifiers consumes orders of magnitude less CPU than one built on value-form modifiers: the work-per-frame is bounded to the smallest possible phase.

## 7. Comprehensive modifier-phase cheat sheet

Reads happen in the listed phase. For modifiers that take both value and lambda forms, both rows are listed — pick the lambda form for hot state.

### Layout-affecting modifiers

| Modifier | Phase |
|---|---|
| `Modifier.size(Dp)` / `widthIn` / `heightIn` | Composition |
| `Modifier.padding(Dp, …)` | Composition (no lambda overload exists) |
| `Modifier.padding(PaddingValues)` | Composition (no lambda overload exists) |
| `Modifier.offset(x: Dp, y: Dp)` | Composition |
| `Modifier.offset { IntOffset }` | **Layout** |
| `Modifier.absoluteOffset(x, y)` | Composition |
| `Modifier.absoluteOffset { IntOffset }` | **Layout** |
| `Modifier.layout { measurable, constraints -> … }` | **Layout** (escape hatch when a hot state must drive measurement — e.g. an animated inset, since `padding` ships no lambda form) |
| `Modifier.onSizeChanged { }` | **Layout** (callback after measure) |
| `Modifier.onGloballyPositioned { }` | **Layout** (callback after place) |
| `Modifier.aspectRatio(ratio)` | Composition |
| `Modifier.fillMaxWidth(fraction)` | Composition |
| `Modifier.weight(float)` | Composition |

### Draw-affecting modifiers

| Modifier | Phase |
|---|---|
| `Modifier.alpha(Float)` | Composition |
| `Modifier.rotate(Float)` | Composition |
| `Modifier.scale(Float)` | Composition |
| `Modifier.graphicsLayer(...)` value form | Composition |
| `Modifier.graphicsLayer { … }` lambda form | **Draw** |
| `Modifier.background(Color, Shape)` | Composition |
| `Modifier.background(Brush, Shape, Float)` | Composition |
| `Modifier.drawBehind { … }` | **Draw** |
| `Modifier.drawWithContent { … }` | **Draw** |
| `Modifier.drawWithCache { … }` | **Draw** (cache rebuilds when its captured state changes) |
| `Modifier.clip(Shape)` | Composition (Shape) |
| `Modifier.shadow(elevation, shape)` | Composition |

### Notes

- `Modifier.background(Brush, Shape, Float)` reads its alpha argument in Composition. For an animated alpha-tinted background, prefer `Modifier.drawBehind { drawRect(brush, alpha = a.value) }` or fold the alpha into a `graphicsLayer { }` block on the same node.
- `rememberGraphicsLayer()` (Compose UI 1.7+) lets you build a layer once and replay it across draws; useful for shared element transitions and capture-style effects.
- `Modifier.composed { }` is **discouraged for new modifiers** — Android docs recommend authoring as a `Modifier.Node` instead (see the project-level migration skill). `composed { }` is not annotated `@Deprecated` in `androidx.compose.ui`, but it re-creates a fresh composable scope per call site, defeating skipping entirely.

## 8. Quick decision tree

When reviewing a composable that uses a hot state:

1. Is the state read inside a composable body but only used to pick a `dp`/`Float`/`Color` for a modifier? → Move the read into a lambda modifier.
2. Is the state read for `alpha`/`scale`/`rotate`/`translation`? → `Modifier.graphicsLayer { … }`.
3. Is the state read for position only (no size change)? → `Modifier.offset { IntOffset(…) }`.
4. Is the state used for custom drawing? → `Modifier.drawBehind { … }` (simple) or `Modifier.drawWithCache { … }` (rebuildable resources).
5. Is the state used to decide which composable to emit (show/hide, swap)? → Lambda modifiers cannot help. Diagnose stability via `../../stability/diagnosing-compose-stability/SKILL.md`, or accept the Composition cost.
6. Is the state passed as a `Float`/`Int`/`Dp` parameter across composable boundaries? → Convert the parameter to `() -> Float` (lambda provider) and read it in a lambda modifier on the receiving side.

## 9. References

- Android Developers — Phases of Jetpack Compose: https://developer.android.com/develop/ui/compose/phases
- Android Developers — Performance: phases: https://developer.android.com/develop/ui/compose/performance/phases
- Android Developers — Graphics modifiers: https://developer.android.com/develop/ui/compose/graphics/draw/modifiers
- Ben Trengrove — Debugging recomposition: https://medium.com/androiddevelopers/jetpack-compose-debugging-recomposition-bfcf4a6f8d37
- Chris Banes — Composable Metrics: https://chrisbanes.me/posts/composable-metrics/
- skydoves — Jetpack Compose Mechanism: https://speakerdeck.com/skydoves/jetpack-compose-mechanism
