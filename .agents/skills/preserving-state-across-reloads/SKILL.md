---
name: preserving-state-across-reloads
description: Use this skill to keep Jetpack Compose state alive across HotSwan hot reloads by understanding the three escalating tiers Compose HotSwan uses (tier 1 targeted recomposition, tier 2 composition reset, tier 3 Activity.recreate) and choosing edits and state holders that stay inside tier 1 where scroll position, lazy items, dialog state, and per-composable remember values all survive. Explains which edits force escalation, which state holders survive each tier, and how to hoist transient UI state when the iteration loop must escalate. Use when the developer says "scroll jumped to top after a hot reload", "lost dialog state", "lazy column re-fetched", "tab selection reset", asks why HotSwan reload escalated to tier 2 or 3, plans a refactor and needs to know which scope it touches, or wants to know which state holders survive composition reset.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - hot-reload
  - hotswan
  - state-preservation
  - remember-saveable
  - recomposition
  - composition-reset
---

# Preserving State Across Reloads: keep edits inside HotSwan tier 1

Compose HotSwan applies every hot reload through three escalating tiers. Tier 1 (targeted recomposition) preserves the most: scroll position, navigation back stack, `remember` and `rememberSaveable` values, `ViewModel` state, lazy layout items, dialog and bottom sheet state. Tier 2 (composition reset) disposes and recreates compositions, dropping per-composable `remember`. Tier 3 (`Activity.recreate()`) restarts the Activity and only state held by `ViewModel` or `SavedInstanceState` survives.

The iteration loop is fastest and least surprising when every reload stays inside tier 1. This skill covers which edits trigger which tier, which state holders survive each tier, and how to hoist transient UI state when an edit must escalate.

## When to use this skill

- The developer reports "scroll jumped to top after a hot reload", "lost dialog state", "lazy column re-fetched", or "tab selection reset to zero".
- The developer is about to refactor a composable and wants to know which recomposition scope the change touches.
- The HotSwan tool window status shows a tier 2 or tier 3 reload and the developer wants to understand why it escalated.
- The developer asks which state holders survive `Activity.recreate()` versus a composition reset.
- The user mentions "tier 1", "tier 2", "tier 3", "composition reset", "HotSwan state preservation", or "rememberSaveable across reload".

## When NOT to use this skill

- The change was rejected as a schema violation entirely (added a parameter, changed a constructor, added a new resource ID) and HotSwan fell back to a full incremental build. See `../understanding-hot-reload-limits/SKILL.md`.
- HotSwan is not installed or the watcher is not running. See `../setting-up-compose-hotswan/SKILL.md`.
- The developer wants an AI agent to drive the loop autonomously. See `../iterating-with-ai-and-mcp/SKILL.md`.
- The recomposition itself is too wide independent of hot reload (a parent invalidating a whole subtree on every state tick). Diagnose with `../../recomposition/debugging-recompositions/SKILL.md` first.

## Prerequisites

- Compose HotSwan installed and `WATCHING` against the running app. Setup lives in `../setting-up-compose-hotswan/SKILL.md`.
- Familiarity with Compose recomposition scopes; cross-link `../../recomposition/debugging-recompositions/SKILL.md` for the underlying mechanics.
- Familiarity with `rememberSaveable` for state that must survive process death and (in this context) composition reset.

## The three tiers

| Tier | Mechanism | Preserves | Loses | Triggered when |
|---|---|---|---|---|
| 1. Targeted recomposition | recomposes only the affected scopes in place | navigation back stack, scroll position, `remember`, `rememberSaveable`, `ViewModel`, lazy layout items, dialog and bottom sheet state | nothing | simple body change inside one composable scope |
| 2. Composition reset | dispose and recreate all compositions from scratch | Activity, `ViewModel`, navigation (via `NavController`), `rememberSaveable` (depends on retention) | per-composable `remember` values not retained by a `Saveable`, scroll position not held by a saveable state holder | tier 1 unavailable (theme change, root-scope structural change) |
| 3. `Activity.recreate()` | recreate the entire Activity | `ViewModel`, `SavedInstanceState` | scroll, transient dialog state, anything not saved | composition fails or schema mismatch detected |

The tier that ran is reported in the HotSwan tool window status after every reload. Read it after each edit to confirm the loop stayed where it was supposed to.

## Workflow

### 1. Read the tier from the tool window after every reload

The HotSwan tool window prints the tier (1, 2, or 3) for each reload. If the developer expected tier 1 and the status reports tier 2 or 3, the edit touched a wider scope than intended. Read the tier before deciding whether the lost state is a configuration problem or expected behaviour.

### 2. If state was lost on tier 2, audit the offended state holder

Tier 2 disposes per-composable `remember` blocks. Walk the composable that lost state and convert its local `remember` to `rememberSaveable` for any value that the developer wants to keep across reloads that may escalate. This is the single highest-leverage change for a HotSwan-driven iteration loop.

### 3. Hoist user-facing transient state into a `ViewModel` for long iterations

When the developer is iterating on a screen for a long stretch and individual edits keep escalating to tier 2 or tier 3, hoist transient UI state (selected tab, expanded item, scroll position, dialog open) into a `ViewModel`. `ViewModel` survives all three tiers, so the iteration loop never loses the workbench state.

### 4. Avoid theme and root `CompositionLocal` mutations during a fast loop

Editing a value used by `MaterialTheme`, or by any `staticCompositionLocalOf`, invalidates the root content lambda of the corresponding `CompositionLocalProvider`. HotSwan cannot scope that to a single recomposition target and escalates to tier 2. Move the colour, dimension, or typography under iteration into a local override on the composable being tuned, then move it back into the theme once the value is final.

### 5. Avoid `staticCompositionLocalOf` for any value the developer is editing

Even outside theme, any value provided through `staticCompositionLocalOf` invalidates the entire content of the provider on change. Use `compositionLocalOf` (which tracks reads) for values that may change during a hot-reload session.

## Patterns

### Pattern: local `remember` lost on tier 2

```kotlin
// WRONG (for tier 2 reloads)
@Composable
fun TabScreen() {
    var selected by remember { mutableIntStateOf(0) }
    Tabs(selected = selected, onSelected = { selected = it })
}
// WRONG because: a tier 2 composition reset disposes remember; the selected tab snaps back to 0 after a reload that escalates.
```

```kotlin
// RIGHT
@Composable
fun TabScreen() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Tabs(selected = selected, onSelected = { selected = it })
}
```

### Pattern: theme colour edit forces tier 2

```kotlin
// WRONG (forces tier 2)
val LightColors = lightColorScheme(primary = Color(0xFFEE0044))
// WRONG because: editing a top-level colour used by MaterialTheme invalidates the root CompositionLocal; the reload escalates to tier 2 and disposes per-composable remember.
```

```kotlin
// RIGHT (stays in tier 1)
@Composable
fun PrimaryButton(text: String) {
    Button(
        onClick = {},
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEE0044)),
    ) { Text(text) }
}
```

Move the literal back into the theme once the visual value is final. The escalation only matters during the fast iteration loop.

### Pattern: lazy list scroll position survives tier 2 and tier 3

```kotlin
// RIGHT
@Composable
fun Feed(items: List<Item>) {
    val state = rememberLazyListState()
    LazyColumn(state = state) {
        items(items, key = { it.id }) { Item(it) }
    }
}
```

`rememberLazyListState` is backed by a `Saveable`, so the scroll position survives composition reset and Activity recreation. Combined with stable `key`s, the visible items stay rendered after a reload that escalates.

### Pattern: hoist iteration-critical state into a `ViewModel`

```kotlin
// RIGHT
class FeedViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FeedState())
    val uiState: StateFlow<FeedState> = _uiState.asStateFlow()
}

@Composable
fun FeedScreen(viewModel: FeedViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Feed(state.items)
}
```

`ViewModel` outlives every tier of HotSwan reload (and configuration changes generally), so screen-level workbench state stays untouched even on tier 3.

### Pattern: avoid `staticCompositionLocalOf` for values under iteration

```kotlin
// WRONG (every edit forces tier 2)
val LocalAccent = staticCompositionLocalOf { Color.Red }
// WRONG because: changing the provided value invalidates the entire content of CompositionLocalProvider; HotSwan cannot scope that and escalates.
```

```kotlin
// RIGHT
val LocalAccent = compositionLocalOf { Color.Red }
```

`compositionLocalOf` tracks reads and only invalidates the actual readers; HotSwan can keep the reload inside tier 1.

## Mandatory rules

- **MUST** prefer `rememberSaveable` over `remember` for any UI state the developer wants to keep across hot reloads that may escalate.
- **MUST** keep theme and root `CompositionLocal` edits out of a fast iteration session; they force tier 2.
- **MUST NOT** assume tier 1 always runs. Read the tier reported in the HotSwan tool window after every reload.
- **MUST NOT** use `staticCompositionLocalOf` for values the developer is actively editing during a hot reload session.
- **PREFERRED:** hoist transient UI state (selected tab, expanded item, dialog open, scroll position) into a `ViewModel` when the iteration loop is long.
- **PREFERRED:** rely on `rememberLazyListState` (and the matching grid / pager state holders) for scroll and visible-item state because they are saveable by default.

## Verification

- [ ] Editing a composable body keeps scroll position and dialog state, and the tool window reports tier 1
- [ ] Editing a `MaterialTheme` colour escalates to tier 2 (status confirms the tier)
- [ ] State backed by `rememberSaveable` survives a tier 2 composition reset
- [ ] State backed by a `ViewModel` survives a tier 3 `Activity.recreate()`
- [ ] `rememberLazyListState` keeps scroll position after a tier 2 reload

## References

- HotSwan state-preservation docs: https://github.com/skydoves/compose-hotswan-web (under `/docs/state-preservation`)
- HotSwan JetBrains plugin listing: https://plugins.jetbrains.com/plugin/30551-compose-hotswan/
- Android Developers, saving UI state with `rememberSaveable`: https://developer.android.com/develop/ui/compose/state-saving
- Android Developers, `ViewModel` overview: https://developer.android.com/topic/libraries/architecture/viewmodel
- Android Developers, `CompositionLocal`: https://developer.android.com/develop/ui/compose/compositionlocal
