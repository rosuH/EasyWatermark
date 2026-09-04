# Compose / CMP large-screen official best practices

**Date:** 2026-08-09  
**Scope:** Jetpack Compose + Material 3 Adaptive + Navigation 3 Scenes + foldables + input; Compose Multiplatform notes where JetBrains documents them.  
**Primary sources only** (developer.android.com, Android Developers Blog, JetBrains KMP docs).  
**Audience:** EasyWatermark large-screen / tablet / desktop / foldable layout work (photo editor: preview + controls).

---

## 0. Glossary and library map

| Concept | Official role |
| --- | --- |
| **Window size class** | Opinionated viewport breakpoints (compact / medium / expanded / large / extra-large) for app-window metrics — **not** device type (`isTablet`). ([Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)) |
| **Canonical layouts** | Proven multi-pane patterns: list-detail, feed, supporting pane. ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)) |
| **Material 3 Adaptive** | Compose library for WSC, posture, `ListDetailPaneScaffold`, `SupportingPaneScaffold`, navigation suite. ([Get started](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps), [releases](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive)) |
| **Navigation 3 Scenes** | `Scene` + `SceneStrategy` layer that can render multi-pane layouts from a single back stack (incl. Material list-detail Scene). ([Create custom layouts using Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes)) |
| **DisplayFeature / FoldingFeature** | Jetpack WindowManager fold/hinge geometry and state. ([Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)) |

**AndroidX adaptive artifacts (Compose):**

```kotlin
// Pane scaffolds + posture building blocks
implementation("androidx.compose.material3.adaptive:adaptive")
implementation("androidx.compose.material3.adaptive:adaptive-layout")
implementation("androidx.compose.material3.adaptive:adaptive-navigation")

// Nav bar ↔ rail (and drawer overrides)
implementation("androidx.compose.material3:material3-adaptive-navigation-suite")

// Nav3 Material multi-pane SceneStrategy
implementation("androidx.compose.material3.adaptive:adaptive-navigation3")
```

Sources: [Build a list-detail layout](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail), [Build adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation), [Nav3 Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes).

**CMP commonMain (JetBrains):**

```kotlin
commonMain.dependencies {
    implementation("org.jetbrains.compose.material3.adaptive:adaptive:1.3.0-alpha07")
    // material3-adaptive-navigation-suite also available in common code
}
```

Source: [Adaptive layouts | Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html), [What's new in CMP 1.7](https://kotlinlang.org/docs/multiplatform/whats-new-compose-170.html).

---

## 1. WindowSizeClass breakpoints and Compact / Medium / Expanded mapping

### 1.1 What they are

Window size classes are **opinionated viewport breakpoints** for the **app window** (not the physical device). Width and height are classified **separately**; width is usually the primary layout driver because vertical scrolling is ubiquitous. ([Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes))

They are **dynamic** across the process lifetime (orientation, multi-window, fold/unfold, desktop resize). Do **not** use them as `isTablet` logic. ([Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes))

### 1.2 Official breakpoints (width and height)

| Size class | Breakpoint | Typical device representation (majority cases only) |
| --- | --- | --- |
| **Compact width** | width **&lt; 600dp** | ~all phones in portrait |
| **Medium width** | **600dp ≤** width **&lt; 840dp** | tablets portrait; many large unfolded inners portrait |
| **Expanded width** | **840dp ≤** width **&lt; 1200dp** | tablets landscape; many large unfolded inners landscape |
| **Large width** | **1200dp ≤** width **&lt; 1600dp** | large tablet displays |
| **Extra-large width** | width **≥ 1600dp** | desktop displays |
| **Compact height** | height **&lt; 480dp** | phones in landscape |
| **Medium height** | **480dp ≤** height **&lt; 900dp** | tablets landscape; phones portrait |
| **Expanded height** | height **≥ 900dp** | tablets portrait |

Source table: [Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes) (last updated 2026-08-04).

**Note:** Compact / medium / expanded map to Material Design window-size-class guidance; large / extra-large were added for desktop and connected displays. Most apps only need width WSC; also consider height for landscape phone / open flippable where width is medium but height is compact (two-pane often impractical). ([Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes))

### 1.3 How many content panes?

Material / adaptive guidance maps panes to width class roughly as:

- **Compact & medium width:** typically **one** content pane (navigate list → detail as separate destinations).
- **Expanded width:** **two** related panes side-by-side (list-detail, supporting pane).

Sources: [Get started with adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps) (content panes section), [Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts).

Canonical list-detail docs are explicit: **expanded-width** shows list + detail together; **medium and compact** show list **or** detail. ([Canonical layouts — list-detail](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts))

### 1.4 Canonical API: compute WSC in Compose

```kotlin
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass

@Composable
fun MyApp(
    windowSizeClass: WindowSizeClass =
        currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true).windowSizeClass
) {
    // High-level layout decision — pass derived flags down, don't re-query everywhere
    val showTopAppBar =
        windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)

    MyScreen(
        showTopAppBar = showTopAppBar,
        /* ... */
    )
}
```

Sources: [Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes), [Support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes) (snippet `AdaptiveLayoutSnippets.kt`).

Breakpoint constants used by APIs include `WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND` (600), `WIDTH_DP_EXPANDED_LOWER_BOUND` (840), height medium lower bound (480), etc. ([Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes), [Nav3 Scenes example](https://developer.android.com/guide/navigation/navigation-3/scenes)).

### 1.5 Content-level vs nested composable rules

Official Compose adaptive rules:

1. **App-level / content-level** composables may make large layout changes from **window** metrics / WSC.
2. **Nested / reusable** composables should **not** read global window size; they should receive derived flags **or** measure their **own** constraints (`BoxWithConstraints`, adaptive grids).
3. **Always pass enough data** for every size (unidirectional data flow); don't load detail only as a side effect of “we're expanded.”
4. **Hoist state** so resizing / fold / multi-window doesn't lose selection or expand/collapse flags.

Source: [Support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes).

```kotlin
// Content-level: decision comes from parent
@Composable
fun AdaptivePane(
    showOnePane: Boolean,
    /* ... */
) {
    if (showOnePane) {
        OnePane(/* ... */)
    } else {
        TwoPane(/* ... */)
    }
}

// Nested: decide from *this* composable's constraints, not device size
@Composable
fun Card(imageUrl: String, title: String, description: String) {
    BoxWithConstraints {
        if (maxWidth < 400.dp) {
            Column {
                Image(imageUrl)
                Title(title)
            }
        } else {
            Row {
                Column {
                    Title(title)
                    Description(description)
                }
                Image(imageUrl)
            }
        }
    }
}
```

Source: [Support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes).

### 1.6 Testing guidance

Test especially at compact / medium / expanded **breakpoint widths**; if you already have a compact layout, optimize **expanded** next, then medium. ([Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes))  
Use `@Preview` form factors, host-side screenshots, resizable emulator, `DeviceConfigurationOverride`. ([Get started with adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps))

---

## 2. Canonical layout patterns

Material + Android document three primary canonical layouts. ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts), [M3 overview](https://m3.material.io/foundations/layout/canonical-layouts/overview))

### 2.1 List-detail

**When:** User explores a list of items and opens descriptive / supplementary detail (email, contacts, messaging, media browsers). ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts))

| Width class | Behavior |
| --- | --- |
| Expanded | List + detail side-by-side; selection updates detail in place |
| Medium / compact | List **or** detail; selecting item replaces list with detail; back restores list |

**State preservation on resize** (official):

- Expanded → compact with both visible → **detail remains**, list hides.
- Compact detail-only → expanded → list + detail; list shows selection for detail content.
- Compact list-only → expanded → list + **placeholder** detail.

Source: [Canonical layouts — list-detail](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts).

**Compose APIs:**

- Low-level: `ListDetailPaneScaffold` + `rememberListDetailPaneScaffoldNavigator`
- Navigable wrapper: `NavigableListDetailPaneScaffold` (navigation + predictive back)
- Nav3: `ListDetailSceneStrategy` / Material `rememberListDetailSceneStrategy` (see §4)

**Minimal scaffold:**

```kotlin
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MyListDetailPaneScaffold() {
    val navigator = rememberListDetailPaneScaffoldNavigator()
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            // Listing pane
        },
        detailPane = {
            // Details pane
        }
    )
}
```

Source: [Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts) (`CanonicalLayoutSamples.kt`).

**Navigable pattern (list-detail guide):** up to **three** panes — list, detail, optional extra; large windows side-by-side, small windows one pane. Dependencies: `adaptive`, `adaptive-layout`, `adaptive-navigation` (≥ 1.1.0-beta1 guidance on that page). ([Build a list-detail layout](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail))

```kotlin
NavigableListDetailPaneScaffold(
    navigator = navigator,
    listPane = {
        AnimatedPane {
            ListContent(
                words = sampleWords,
                selectionState = navigator.currentDestination?.contentKey?.let {
                    SelectionVisibilityState.ShowSelection(it)
                } ?: SelectionVisibilityState.NoSelection,
                onWordClick = { word ->
                    scope.launch {
                        navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, word)
                    }
                },
                animatedVisibilityScope = this@AnimatedPane,
                sharedTransitionScope = this@SharedTransitionLayout
            )
        }
    },
    detailPane = {
        AnimatedPane {
            DetailContent(
                definedWord = navigator.currentDestination?.contentKey,
                animatedVisibilityScope = this@AnimatedPane,
                sharedTransitionScope = this@SharedTransitionLayout,
                onClosePane = {
                    scope.launch {
                        navigator.navigateBack(
                            backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange
                        )
                    }
                }
            )
        }
    }
)
```

Source: [Build a list-detail layout](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail) (`ListDetailSample.kt`).

**Back behavior options** (same guide): `PopUntilScaffoldValueChange` (default/recommended), `PopUntilContentChange`, `PopUntilCurrentDestinationChange`, `PopLatest`.

**Samples:** [list-detail-compose](https://github.com/android/adaptive-apps-samples/tree/main/CanonicalLayouts/list-detail-compose).

### 2.2 Supporting pane

**When:** Primary content needs **secondary content that is only meaningful in relation to the primary** (tool palettes, comments, related videos, media editing controls). Distinct from list-detail: supporting content is **not** independently meaningful as a full destination the way a product detail often is. ([Canonical layouts — supporting pane](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts))

**Official use cases include media editing tools with palettes / effects / settings in a support pane.** ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)) ← **directly relevant to EasyWatermark.**

**Space split guidance:**

| Width | Layout |
| --- | --- |
| Compact | Supporting content **below** main, or in **bottom / side sheet** via control |
| Medium | Side-by-side; ~**50/50** split |
| Expanded | Side-by-side; ~**70% main / 30% supporting** |

Source: [Canonical layouts — supporting pane](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts).

**Compose APIs:** `SupportingPaneScaffold`, `NavigableSupportingPaneScaffold`, `rememberSupportingPaneScaffoldNavigator`.

```kotlin
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MySupportingPaneScaffold() {
    val navigator = rememberSupportingPaneScaffoldNavigator()
    SupportingPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        mainPane = {
            // Primary content
        },
        supportingPane = {
            // Supplementary content
        }
    )
}
```

Source: [Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts).

**Full navigable pattern** (show/hide supporting pane, close control, predictive back):

```kotlin
val scaffoldNavigator = rememberSupportingPaneScaffoldNavigator()
val scope = rememberCoroutineScope()
val backNavigationBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange

NavigableSupportingPaneScaffold(
    navigator = scaffoldNavigator,
    mainPane = {
        AnimatedPane(
            modifier = Modifier
                .safeContentPadding()
                .background(Color.Red)
        ) {
            if (scaffoldNavigator.scaffoldValue[SupportingPaneScaffoldRole.Supporting] ==
                PaneAdaptedValue.Hidden
            ) {
                Button(
                    modifier = Modifier.wrapContentSize(),
                    onClick = {
                        scope.launch {
                            scaffoldNavigator.navigateTo(SupportingPaneScaffoldRole.Supporting)
                        }
                    }
                ) { Text("Show supporting pane") }
            } else {
                Text("Supporting pane is shown")
            }
        }
    },
    supportingPane = {
        AnimatedPane(modifier = Modifier.safeContentPadding()) {
            Column {
                if (scaffoldNavigator.scaffoldValue[SupportingPaneScaffoldRole.Supporting] ==
                    PaneAdaptedValue.Expanded
                ) {
                    IconButton(
                        modifier = Modifier.align(Alignment.End).padding(16.dp),
                        onClick = {
                            scope.launch {
                                scaffoldNavigator.navigateBack(backNavigationBehavior)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                Text("Supporting pane")
            }
        }
    }
)
```

Source: [Build a supporting pane layout](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout) (`SampleSupportingPaneScaffold.kt`).

**Samples:** [supporting-pane-compose](https://github.com/android/adaptive-apps-samples/tree/main/CanonicalLayouts/supporting-pane-compose).

### 2.3 Feed

**When:** Many equivalent content elements (news, social) in a configurable grid; emphasis via size/span. ([Canonical layouts — feed](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts))

```kotlin
@Composable
fun MyFeed(names: List<String>) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 180.dp),
    ) {
        items(names) { name ->
            Text(name)
        }
    }
}
```

On compact widths that cannot fit multiple columns, `LazyVerticalGrid` behaves like `LazyColumn`. Use `maxLineSpan` for full-width headers. ([Canonical layouts — feed](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts))  
Sample: [feed-compose](https://github.com/android/adaptive-apps-samples/tree/main/CanonicalLayouts/feed-compose).

### 2.4 Supporting panel vs supporting pane (terminology)

Official Android docs use **supporting pane** as the canonical name. “Supporting panel” is often used informally for the same secondary area (or for a sheet-hosted supporting region on compact). Prefer **supporting pane** when mapping to `SupportingPaneScaffold` / M3 canonical layout docs. ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts), [M3 supporting pane](https://m3.material.io/foundations/layout/canonical-layouts/supporting-pane))

### 2.5 Adaptive navigation suite (chrome, not content panes)

```kotlin
implementation("androidx.compose.material3:material3-adaptive-navigation-suite")
```

`NavigationSuiteScaffold` default:

- **Navigation bar** if width **or** height is compact, **or** device is in **tabletop** posture  
- **Navigation rail** otherwise  

```kotlin
var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }

NavigationSuiteScaffold(
    navigationSuiteItems = {
        AppDestinations.entries.forEach {
            item(
                icon = {
                    Icon(
                        it.icon,
                        contentDescription = stringResource(it.contentDescription)
                    )
                },
                label = { Text(stringResource(it.label)) },
                selected = it == currentDestination,
                onClick = { currentDestination = it }
            )
        }
    }
) {
    when (currentDestination) {
        AppDestinations.HOME -> HomeDestination()
        AppDestinations.FAVORITES -> FavoritesDestination()
        AppDestinations.SHOPPING -> ShoppingDestination()
        AppDestinations.PROFILE -> ProfileDestination()
    }
}
```

Override example (permanent drawer only on expanded width):

```kotlin
val adaptiveInfo = currentWindowAdaptiveInfo()
val customNavSuiteType = with(adaptiveInfo) {
    if (windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)) {
        NavigationSuiteType.NavigationDrawer
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    }
}

NavigationSuiteScaffold(
    navigationSuiteItems = { /* ... */ },
    layoutType = customNavSuiteType,
) {
    // Content...
}
```

Source: [Build adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation).

Official do's list also names `NavigationSuiteScaffold`, `ListDetailPaneScaffold`, `SupportingPaneScaffold` as the go-to adaptive APIs. ([Adaptive do's and don'ts](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts))

---

## 3. Foldable hinges, posture, DisplayFeatures

### 3.1 Concepts

Foldables add **posture** and **hinge/fold geometry** beyond window size. Adaptive apps respond to tabletop / book posture as well as size. ([Get started with adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps), [Learn about foldables](https://developer.android.com/develop/adaptive-apps/guides/foldables/learn-about-foldables))

| Posture | Fold orientation | Typical UX |
| --- | --- | --- |
| **Tabletop** | `HALF_OPENED` + **horizontal** fold | Media / camera: content above fold, controls below |
| **Book** | `HALF_OPENED` + **vertical** fold | Reading two-page layout; alternate camera aspect |

Source: [Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware).

When `HALF_OPENED`, two postures exist depending on fold orientation (tabletop vs book). ([Learn about foldables](https://developer.android.com/develop/adaptive-apps/guides/foldables/learn-about-foldables))

### 3.2 WindowManager APIs

- `WindowInfoTracker.getOrCreate(context).windowLayoutInfo(activity)` → stream of `WindowLayoutInfo`
- `WindowLayoutInfo.displayFeatures: List<DisplayFeature>`
- `FoldingFeature` properties:
  - `state`: `FLAT` | `HALF_OPENED`
  - `orientation`: `HORIZONTAL` | `VERTICAL`
  - `occlusionType`: `NONE` | `FULL`
  - `isSeparating`: whether fold/hinge creates two logical areas
  - `bounds`: rectangle for positioning relative to fold/hinge

**Compose Material 3 Adaptive** also exposes posture via `currentWindowAdaptiveInfo().windowPosture`. ([Get started with adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps))

**Do not place important controls too close to a fold/hinge when `isSeparating` is true**; use `occlusionType` to decide whether content may sit in feature bounds. Dual-screen hinged devices may need tabletop/book layouts even when state is `FLAT`. ([Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware))

### 3.3 Collect layout info (Kotlin Flow)

```kotlin
class DisplayFeaturesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        lifecycleScope.launch(Dispatchers.Main) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@DisplayFeaturesActivity)
                    .windowLayoutInfo(this@DisplayFeaturesActivity)
                    .collect { newLayoutInfo ->
                        // Use newLayoutInfo to update the layout.
                    }
            }
        }
    }
}
```

```kotlin
val foldingFeature = layoutInfo.displayFeatures
    .filterIsInstance<FoldingFeature>()
    .firstOrNull()
```

Source: [Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware).

### 3.4 Tabletop / book detectors

```kotlin
fun isTableTopPosture(foldFeature: FoldingFeature?): Boolean {
    contract { returns(true) implies (foldFeature != null) }
    return foldFeature?.state == FoldingFeature.State.HALF_OPENED &&
        foldFeature.orientation == FoldingFeature.Orientation.HORIZONTAL
}

fun isBookPosture(foldFeature: FoldingFeature?): Boolean {
    contract { returns(true) implies (foldFeature != null) }
    return foldFeature?.state == FoldingFeature.State.HALF_OPENED &&
        foldFeature.orientation == FoldingFeature.Orientation.VERTICAL
}
```

Source: [Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware).

Media apps in tabletop: **playback above fold**, controls / supplementary content below. Camera apps: viewfinder top, controls bottom. ([Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware))  
Sample: [Jetcaster](https://github.com/android/compose-samples/tree/main/Jetcaster) (tabletop with Compose).

### 3.5 Experimental MediaQuery posture / pointer / keyboard (Compose UI)

Experimental `mediaQuery { ... }` can branch on `windowPosture`, `pointerPrecision`, `keyboardKind`, etc. Requires `ComposeUiFlags.isMediaQueryIntegrationEnabled = true`. Prefer `derivedMediaQuery` for frequently changing width/height. ([MediaQuery docs mirrored in adaptive skill](https://developer.android.com/develop/adaptive-apps/guides/mediaquery); skill mirror: `skills/adaptive/references/.../mediaquery/index.md`)

```kotlin
if (mediaQuery { windowPosture == UiMediaScope.Posture.Tabletop }) {
    TabletopLayout()
} else {
    FlatLayout()
}
```

Android Developers Blog also highlights MediaQuery and non-touch input as part of adaptive quality (2026). ([Adaptive development for the expanding Android ecosystem](https://developer.android.com/blog/posts/adaptive-development-for-the-expanding-android-ecosystem))

---

## 4. Navigation patterns for multi-pane (including Navigation 3 Scenes)

There are **two official Compose-era multi-pane approaches**. Both appear in developer.android.com; product direction in Google's `adaptive` skill prefers **Nav3 SceneStrategy** when the app already uses Navigation 3.

### 4.1 Approach A — Material 3 Adaptive pane scaffolds (no Nav3 required)

- `ListDetailPaneScaffold` / `NavigableListDetailPaneScaffold`
- `SupportingPaneScaffold` / `NavigableSupportingPaneScaffold`
- Own navigator (`ThreePaneScaffoldNavigator`) manages pane visibility + back within the scaffold

Documented in: [Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts), [list-detail guide](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail), [supporting pane guide](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout), [adaptive do's](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts).

### 4.2 Approach B — Navigation 3 Scenes (recommended by Google adaptive skill for multi-pane)

A **Scene** renders one or more `NavEntry`s; a **SceneStrategy** decides whether it can form a Scene from the back stack and how entries are arranged. `NavDisplay` walks strategies in order; fallback is `SinglePaneSceneStrategy`. ([Create custom layouts using Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes))

Custom `Scene` must implement **correct `equals` / `hashCode`** (include key, entries, previousEntries) for transitions and overlays. ([Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes))

**Custom list-detail strategy** activates only if:

1. Window width ≥ medium (`WIDTH_DP_MEDIUM_LOWER_BOUND`), **and**
2. Back stack metadata marks list + detail entries.

```kotlin
@Composable
fun <T : Any> rememberListDetailSceneStrategy(): ListDetailSceneStrategy<T> {
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return remember(windowSizeClass) {
        ListDetailSceneStrategy(windowSizeClass)
    }
}

class ListDetailSceneStrategy<T : Any>(
    val windowSizeClass: WindowSizeClass
) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)) {
            return null
        }
        val detailEntry =
            entries.lastOrNull()?.takeIf { it.metadata.contains(DetailKey) } ?: return null
        val listEntry = entries.findLast { it.metadata.contains(ListKey) } ?: return null
        val sceneKey = listEntry.contentKey
        return ListDetailScene(
            key = sceneKey,
            previousEntries = entries.dropLast(1),
            listEntry = listEntry,
            detailEntry = detailEntry
        )
    }
    // listPane() / detailPane() metadata helpers...
}
```

```kotlin
NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    sceneStrategies = listOf(listDetailStrategy),
    entryProvider = entryProvider {
        entry<ConversationList>(metadata = ListDetailSceneStrategy.listPane()) { /* list UI */ }
        entry<ConversationDetail>(metadata = ListDetailSceneStrategy.detailPane()) { /* detail UI */ }
    }
)
```

Source: [Create custom layouts using Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes).

**Material Adaptive Nav3 Scene** (`androidx.compose.material3.adaptive:adaptive-navigation3`):

- `rememberListDetailSceneStrategy()`
- Metadata: `listPane(detailPlaceholder = { ... })`, `detailPane()`, `extraPane()`
- Handles complex multi-pane (list / detail / extra) and adapts to window size & device state

```kotlin
val backStack = rememberNavBackStack(ProductList)
val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()

NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    sceneStrategies = listOf(listDetailStrategy),
    entryProvider = entryProvider {
        entry<ProductList>(
            metadata = ListDetailSceneStrategy.listPane(
                detailPlaceholder = { ContentYellow("Choose a product from the list") }
            )
        ) { /* ... */ }
        entry<ProductDetail>(metadata = ListDetailSceneStrategy.detailPane()) { product -> /* ... */ }
        entry<Profile>(metadata = ListDetailSceneStrategy.extraPane()) { /* ... */ }
    }
)
```

Source: [Create custom layouts using Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes).

**Google adaptive skill (2026-08-06) operational rule** (repo `skills/adaptive/SKILL.md`):

> IMPORTANT: You must use the Navigation 3 `SceneStrategy` approach to implement multi-pane layouts. Do not use `ListDetailPaneScaffold` or `SupportingPaneScaffold`.

That skill still documents `SupportingPaneSceneStrategy` + `mainPane()` / `supportingPane()` metadata for supporting-pane relationships, and `ListDetailSceneStrategy` for list-detail. Prefer skill guidance when EasyWatermark multi-pane work is done **inside Navigation 3**; keep scaffold APIs as the documented alternative when not on Nav3, or when integrating legacy samples.

Also skill guidance for list-detail:

- Avoid list-detail when detail needs **substantial full-screen space** (images/media) unless explicitly requested.
- On dual-pane, **deactivate phone-style full-screen chrome** that hid bars/rails.
- **No back arrow on detail** when shown as dual-pane list-detail.

Source: Google `adaptive` skill workflow (mirrors [A guide to making an app adaptive](https://developer.android.com/agents/skills/jetpack-compose/adaptive/skill)).

### 4.3 Single back stack, dual presentation

Whether scaffolds or Scenes: multi-pane is a **presentation** of related destinations, not a second independent activity stack. On compact, navigation destination shows one pane; on expanded, related panes appear together. ([Get started — content panes](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps))

---

## 5. Input: mouse / keyboard / stylus vs touch on large screens

### 5.1 Why it matters

Large-screen users commonly use keyboard, mouse, trackpad, stylus. Adaptive apps must support input beyond touch. ([Get started with adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps), [Adaptive do's and don'ts](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts), [Input compatibility on large screens](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens))

Compose 1.7+: **keyboard tab navigation** and mouse/trackpad **click, select, scroll** work by default for standard components. ([Get started with adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps))

### 5.2 Keyboard

| Area | Official expectation |
| --- | --- |
| Navigation | Tab / arrows; make custom UI `focusable()`; use `focusGroup()` for complex grids/lists |
| Keystrokes | App-specific: Enter send, Space play/pause — `onKeyEvent` / `onKeyUp` |
| Shortcuts | Ctrl+Z / C / S etc.; publish via Keyboard Shortcuts Helper (`onProvideKeyboardShortcuts`) |

```kotlin
// Focusable custom box
var color by remember { mutableStateOf(Green) }
Box(
    Modifier
        .background(color)
        .onFocusChanged { color = if (it.isFocused) Blue else Green }
        .focusable()
) {
    Text("Focusable 1")
}

// Shortcut
Box(
    Modifier
        .onKeyEvent {
            if (it.isAltPressed && it.key == Key.A) {
                true
            } else false
        }
        .focusable()
)
```

Source: [Input compatibility on large screens](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens).

### 5.3 Mouse / trackpad

Most apps need:

1. **Right-click** → same as long-press context menu  
2. **Hover** → pointer icon + visual feedback on interactive / list items  
3. **Drag and drop** across multi-window (photos into editor, etc.)

Advanced: `pointerInput` + `PointerEvent` / `PointerType` / `PointerEventType`. ([Input compatibility on large screens](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens))

Blog note: Compose 1.11 trackpad parity with mouse; testing APIs `TrackpadInjectionScope` / `performTrackpadInput`. ([Adaptive development blog](https://developer.android.com/blog/posts/adaptive-development-for-the-expanding-android-ecosystem))

### 5.4 Stylus

Material3 `TextField` handwriting; drawing surfaces via `pointerInteropFilter` + `MotionEvent` (tool type, pressure, tilt). Palm rejection: handle `ACTION_CANCEL` / `FLAG_CANCELED`. ([Input compatibility on large screens](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens))

### 5.5 MediaQuery for input capability (experimental)

- `pointerPrecision`: Fine / Coarse / Blunt / None — e.g. larger targets when blunt  
- `keyboardKind`: Physical / Virtual / None  

([MediaQuery guide](https://developer.android.com/develop/adaptive-apps/guides/mediaquery))

### 5.6 ChromeOS input translation

ChromeOS enables input translation (two-finger scroll, wheel, coordinate mapping) by default; only disable via `android.hardware.type.pc` feature if custom gestures conflict. ([Input compatibility on large screens](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens))

---

## 6. Do / don't checklist for a photo editor (preview + controls) like EasyWatermark

Mapped from official guidance to an **editor = primary preview + supporting controls** product.

### 6.1 Choose the right canonical layout

| Do | Don't |
| --- | --- |
| Model **editor as supporting pane**: main = photo preview / composition surface; supporting = watermark controls, templates, opacity, tile mode, export options. Official “media editing tool with palettes… in a support pane.” ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)) | Force **list-detail** for the editor chrome itself. List-detail is for collections → item detail (gallery of photos → one photo's meta), not tool palettes. Skill: avoid list-detail when detail needs full image space. (`skills/adaptive/SKILL.md`) |
| On **compact**: keep large preview; put controls in **bottom sheet / scrollable bottom panel / secondary route** with an explicit control. ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)) | Stretch a phone-stacked controls column to fill a 12" tablet without re-layout. ([Adaptive do's](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts)) |
| On **medium**: side-by-side ~50/50 if both panes stay usable. ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)) | Assume medium always means dual pane if height is compact (landscape phone). ([Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes)) |
| On **expanded+**: ~**70% preview / 30% controls** (or `PaneExpansionState` drag split where available). ([Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts), [M3 adaptive releases — pane expansion](https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive)) | Hard-code `isTablet` / raw `Display.getSize`. ([Support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes), [Adaptive do's](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts)) |

### 6.2 Gallery / multi-image (filmstrip)

| Do | Don't |
| --- | --- |
| Treat **image library / multi-select gallery** as list-detail or feed if browsing many images. | Collapse filmstrip + editor tools into one overloaded pane without hierarchy. |
| Use `LazyVerticalGrid(columns = GridCells.Adaptive(...))` for gallery grids. ([Canonical layouts — feed](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts)) | Fixed 1-column gallery on expanded desktop windows. |

### 6.3 Navigation chrome

| Do | Don't |
| --- | --- |
| Use `NavigationSuiteScaffold` for app-level destinations (home / editor / about) if multi-destination. ([Build adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation)) | Keep a bottom nav bar that is hard to reach on large handhelds. |
| **Hide navigation** when preview needs focus (camera/fullscreen photo guidance in skill: hide nav when distracting). (`skills/adaptive/SKILL.md`, [Get started](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps)) | Leave permanent bottom chrome covering the image on compact landscape. |
| On dual-pane, **disable mobile full-screen modes** that hide rails/bars inappropriately. (`skills/adaptive/SKILL.md`) | Show a back arrow on a dual-pane “detail” that never left the list. |

### 6.4 Foldables

| Do | Don't |
| --- | --- |
| In **tabletop**, bias preview to upper region and controls to lower (media/camera pattern). ([Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware)) | Place primary toolbar buttons **on the hinge**. |
| Keep watermark/export state across fold (ViewModel / saveable). ([Get started — configuration continuity](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps)) | Reload config from disk only on size class change as a side effect. ([Support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes)) |

### 6.5 Input & productivity (Desktop / ChromeOS / keyboard)

| Do | Don't |
| --- | --- |
| Shortcuts: save/export, undo/redo, open, toggle controls panel; register in Keyboard Shortcuts Helper. ([Input compatibility](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens)) | Touch-only gestures with no keyboard equivalent for core actions. |
| Hover affordances on sliders, icon buttons, filmstrip thumbnails. ([Input compatibility](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens)) | Invisible hit targets that only work with fat-finger touch. |
| Drag-and-drop images into the editor from other apps (multi-window / desktop). ([Input compatibility](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens)) | Accept only in-app picker on desktop. |
| Focus order: filmstrip → preview tools → text fields → export. Use `focusGroup`. ([Input compatibility](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens)) | Focus traps inside bottom sheets after resize. |

### 6.6 Platform / resizability hygiene

| Do | Don't |
| --- | --- |
| Stay resizable; multi-window ready. `resizeableActivity` true (default ≥ API 24). ([Adaptive do's](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts)) | Lock orientation / aspect ratio for the editor activity. Letterboxing harms Play discoverability. ([Adaptive do's](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts)) |
| Use `WindowMetrics` / `currentWindowAdaptiveInfo`, never deprecated `Display.getSize/getMetrics`. ([Adaptive do's](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts)) | Size UI from physical screen diagonals. |
| Target Android 16+: system **ignores** orientation/aspect/resizability restrictions when sw ≥ 600dp — design for free resize anyway. ([Support different display sizes](https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes) note) | Rely on forced portrait as a layout strategy. |

### 6.7 EasyWatermark-specific layout sketch (non-normative mapping)

| Surface | Compact | Medium | Expanded / Desktop |
| --- | --- | --- | --- |
| Launch / library | Single pane; adaptive grid of recent if needed | Same or two-column grid | Feed-style grid |
| **Editor** | Preview dominant; controls sheet / bottom panel | Preview \| controls side-by-side (~50/50) | Supporting pane scaffold / Nav3 supporting scene (~70/30); optional filmstrip as third strip |
| Templates | Modal sheet or supporting route | Supporting pane or list-detail if browsing many templates | List-detail if template browser is collection-heavy |
| About / settings | Single pane | Single or supporting | Optional dual with category list |

This is product mapping **from** official patterns, not an Android mandate.

### 6.8 CMP note for shared editor UI

JetBrains documents the same WSC + canonical layout mindset for CMP common code via `org.jetbrains.compose.material3.adaptive:adaptive` and navigation suite in commonMain. Deeper foldable / WindowManager edges remain more Android-specific. ([CMP adaptive layouts](https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html), [CMP 1.7 material3 adaptive](https://kotlinlang.org/docs/multiplatform/whats-new-compose-170.html))

---

## 7. Code snippets — canonical APIs (official)

### 7.1 WindowSizeClass

```kotlin
val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
// or with large / xlarge width breakpoints:
val windowSizeClass =
    currentWindowAdaptiveInfo(supportLargeAndXLargeWidth = true).windowSizeClass
```

Source: [Use window size classes](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes).

### 7.2 NavigationSuiteScaffold

See §2.5 — [Build adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation).

### 7.3 ListDetailPaneScaffold

See §2.1 — [Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts), [list-detail guide](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail).

### 7.4 SupportingPaneScaffold

See §2.2 — [Canonical layouts](https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts), [supporting pane guide](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout).

### 7.5 Material Nav3 ListDetailSceneStrategy

See §4.2 — [Create custom layouts using Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes).

### 7.6 FoldingFeature posture

See §3.4 — [Make your app fold aware](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware).

---

## 8. Official do / don't summary (platform-wide)

From [Adaptive do's and don'ts](https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts):

**Do**

- Build with Compose + Material 3 Adaptive library  
- Base layouts on window size classes  
- Create multi-pane layouts (list-detail, supporting pane)  
- Make the app resizable / multi-window  
- Support keyboard, mouse, stylus  
- Use `NavigationSuiteScaffold`, `ListDetailPaneScaffold`, `SupportingPaneScaffold` (or Nav3 Scene equivalents)

**Don't**

- Lock orientation (`screenOrientation`)  
- Restrict aspect ratio  
- Set `resizeableActivity="false"`  
- Stretch single-column UI across large windows  
- Use deprecated `Display` size APIs  
- Ignore non-touch input  

---

## 9. Primary source index

| Topic | URL |
| --- | --- |
| Adaptive hub | https://developer.android.com/develop/adaptive-apps |
| Get started | https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps |
| Window size classes | https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes |
| Support different display sizes | https://developer.android.com/develop/adaptive-apps/guides/support-different-display-sizes |
| Canonical layouts | https://developer.android.com/develop/adaptive-apps/guides/canonical-layouts |
| List-detail implementation | https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail |
| Supporting pane implementation | https://developer.android.com/develop/ui/compose/layouts/adaptive/build-a-supporting-pane-layout |
| Adaptive navigation | https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation |
| Adaptive do's and don'ts | https://developer.android.com/develop/adaptive-apps/guides/adaptive-dos-and-donts |
| Foldables overview | https://developer.android.com/develop/adaptive-apps/guides/foldables/learn-about-foldables |
| Fold-aware implementation | https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware |
| Navigation 3 Scenes | https://developer.android.com/guide/navigation/navigation-3/scenes |
| Input on large screens | https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens |
| Material 3 Adaptive releases | https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive |
| Build adaptive apps (Compose entry) | https://developer.android.com/develop/ui/compose/build-adaptive-apps |
| Adaptive skill (agents) | https://developer.android.com/agents/skills/jetpack-compose/adaptive/skill |
| Blog: adaptive ecosystem | https://developer.android.com/blog/posts/adaptive-development-for-the-expanding-android-ecosystem |
| M3 canonical layouts | https://m3.material.io/foundations/layout/canonical-layouts/overview |
| CMP adaptive layouts | https://kotlinlang.org/docs/multiplatform/compose-adaptive-layouts.html |
| CMP 1.7 adaptive modules | https://kotlinlang.org/docs/multiplatform/whats-new-compose-170.html |
| Samples | https://github.com/android/adaptive-apps-samples , https://github.com/android/compose-samples (JetNews, Reply, Jetcaster) |

---

## 10. Practical recommendation for EasyWatermark (synthesis)

1. **Editor chrome → supporting pane** (preview main / controls supporting), not list-detail.  
2. Drive **high-level** split from `currentWindowAdaptiveInfo()` / WSC; drive **local** control density from constraints.  
3. If product nav stays on **Navigation 3**, implement multi-pane with **SceneStrategy** (`SupportingPaneSceneStrategy` / list-detail only for gallery) per Google adaptive skill.  
4. Keep **single session state** (watermark config, selection) across resize/fold.  
5. Desktop/ChromeOS: shortcuts, hover, DnD import; optional tabletop control split later.  
6. CMP shared UI can depend on JetBrains `material3.adaptive` for WSC + scaffolds in `commonMain`; platform shells own pickers/export.

*End of research note.*
