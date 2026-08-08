# Compose recompose / stability diagnosis — Editor + Gallery

**Date:** 2026-08-08  
**Scope:** Editor screen + in-app image selection (Gallery)  
**Methods:** Compose Compiler Reports (`-PcomposeCompilerReports=true`), static call-graph review, [skydoves/compose-stability-analyzer](https://github.com/skydoves/compose-stability-analyzer) workflow map  
**HEAD context:** `feat/migrate_to_compose` after filmstrip jank fix (`d73a543e`)

> Skippability is a **diagnostic**, not a KPI (skydoves). Goal is fewer wasted recompositions on hot paths (filmstrip rows, gallery grid cells, main preview), not 100% stable flags.

---

## 1. How to research (playbook for this repo)

### 1.1 Build-time: Compose Compiler Reports (done this session)

Already wired (opt-in):

```bash
# Release Kotlin compile — Live Literals off, Strong Skipping on
./gradlew :app:compileReleaseKotlin :shared:compileAndroidMain \
  -PcomposeCompilerReports=true --max-workers=8
```

Outputs:

| Module | Path |
|--------|------|
| `:app` | `app/build/compose_compiler/app-composables.txt`, `app-classes.txt`, `release/app-module.json` |
| `:shared` | `shared/build/compose_compiler/EasyWatermark:shared-composables.txt`, `…-classes.txt`, `android/main/…-module.json` |

Read order: **composables.txt** (which params block skip) → **classes.txt** (why type is unstable) → **module.json** (counts / Strong Skipping flags).

### 1.2 IDE: Compose Stability Analyzer plugin (local)

Install from Marketplace: **Compose Stability Analyzer** ([plugin 28767](https://plugins.jetbrains.com/plugin/28767-compose-stability-analyzer/)).

| Feature | Use for Editor / Gallery |
|---------|---------------------------|
| Gutter icons / param hints | Open `EditorScreen.kt`, `GalleryImageGrid.kt`, `AndroidEditorScreen.kt` |
| Recomposition Cascade | Right-click `EditorScreen` / `GalleryImageGrid` → analyze downstream blast radius |
| Blame this Recomposition | Reverse tree: who feeds unstable `ImageInfo` / lists |
| Stability Doctor | Ranked “what to fix first” (static; becomes MEASURED with heatmap) |

Settings → Tools → Compose Stability Analyzer → enable checks; optional project stability config file.

### 1.3 Runtime: Gradle plugin + `@TraceRecomposition` / Heatmap

Kotlin **2.4.0** matches analyzer **0.12.0** (version table in upstream README).

Recommended opt-in wiring (not applied this session — avoid bulk dep churn; apply as its own slice):

```toml
# gradle/libs.versions.toml
stability-analyzer = { id = "com.github.skydoves.compose.stability.analyzer", version = "0.12.0" }
```

```kotlin
// root build.gradle.kts: alias(libs.plugins.stability.analyzer) apply false
// :app build.gradle.kts: alias(libs.plugins.stability.analyzer)
// MyApp.onCreate: ComposeStabilityAnalyzer.setEnabled(BuildConfig.DEBUG)
```

Then either:

- Annotate hotspots: `@TraceRecomposition(tag = "editor-filmstrip", threshold = 3)` on `EditorFilmstripThumb`, `WaterMarkCanvas`, `GalleryImageCard`, or  
- `composeStabilityAnalyzer { traceAll { enabled.set(true); variants.set(listOf("debug")); threshold.set(2) } }` for module-wide heatmap.

Device: Layout Inspector recomposition counts (debug) + Logcat filter `Recomposition` + IDE **Live Heatmap** / **Reality Check**.

### 1.4 CI later (optional)

`./gradlew :app:stabilityDump` once → commit `app/stability/*.stability` → PR `stabilityCheck` (see skill `enforcing-stability-in-ci`).

---

## 2. Compiler report snapshot (this session)

### Feature flags (both modules)

`StrongSkipping: true`, `IntrinsicRemember: true`, `PausableComposition: true`.

### Counts

| | `:app` release | `:shared` androidMain |
|--|----------------|------------------------|
| restartable | 33 | 250 |
| skippable | 32 | 166 |
| knownUnstableArguments | 16 | 4 |
| inferredUnstableClasses | 4 | 10 |
| effectivelyStableClasses | 1 | 118 |

**Important:** With **Strong Skipping**, composables can still be labeled `restartable skippable` while parameters are **not** marked `stable`. Skipping then uses **referential equality (`===`)** for unstable params. That is why Editor looks “skippable” in the report but can still thrash when parents pass **new list / new ImageInfo instances** every frame.

### Hot composable signatures (extract)

**Editor (shared)** — `EditorScreen` is `restartable skippable` but:

```text
imageList: List<ImageInfo>          # no stable prefix → collection + unstable element
waterMark: WaterMark                # runtime-stable type
selectedImage: ImageInfo?           # unstable element type
templates: List<Template>           # Template class unstable
icons: EditorUiIcons                # runtime (Painter)
```

Same pattern: `EditorPhotoStrip(images: List<ImageInfo>, selectedImage: ImageInfo?)`.

**Editor (app)** — `AndroidEditorScreen` / `WaterMarkCanvas` / `EditorFilmstripThumb` all `restartable skippable`; `ImageInfo` / `List` / `WaterMark` without stable prefix on data params.

**Gallery (shared)** — `GalleryImageGrid` / `GalleryDialogShell`:

```text
images: List<Image>     # Image class is STABLE (good)
checkIcon: Painter      # runtime
isSelected / onSetSelected: stable function types
```

Gallery **model** is healthier than editor’s `ImageInfo`.

---

## 3. Root causes ranked (Editor + Gallery)

### P0 — `ImageInfo` is compiler-**unstable** (Editor filmstrip + preview)

From `classes.txt`:

```text
unstable class ImageInfo {
  stable val uri: MediaRef
  stable var width / height / inSample / scaleX / scaleY   # var → unstable type
  unstable var result: Result<*>?
  runtime var jobState: JobState
  stable var isInDelModel: Boolean
  stable val offsetX / offsetY
}
```

Effects:

1. **Any** mutation of `width`/`jobState`/etc. on a held instance is invisible to equals-based skip if identity is reused incorrectly — or forces new copies when Session rebuilds lists.
2. Session reducer rebuilds `selectedImageList` on select/export progress → new `List` identity → `EditorScreen` / filmstrip parents recompose.
3. Under Strong Skipping, children skip only if each `ImageInfo` param is **same instance**. Export progress that maps to new `ImageInfo` copies → every filmstrip cell may re-enter composition.

**Fix direction (tiered):**

| Tier | Change | Risk |
|------|--------|------|
| A | Split **immutable view** for UI: `ImageInfoUi(uri, width, height, offset…)` with only `val`s; keep mutable export fields off the composition path | Medium (mapping at host) |
| B | Make remaining fields `val` + copy-on-write for export job updates | High (touches export) |
| C | `@Immutable` on a read-only projection + remember keys by `uri` in filmstrip | Low–medium |

Do **not** slap `@Stable` on current `ImageInfo` while it has public `var`s — false contract.

### P1 — Parent collects whole `launchScreenUiStateFlow` (Android Editor)

`ComposeMainActivity` uses `collectAsStateWithLifecycle()` on a **fat** UI state (selection + watermark + export ticks + gallery lists). Any field change recomposes the product shell, including Editor.

**Fix direction:**

- Split flows: `selectedImages`, `curImage`, `waterMark`, `exportJob` as separate collectors (or `derivedStateOf` / select on Flow).
- Filmstrip should only depend on image **identities** + current uri, not export counters.
- Preview should key off `curImage.uri` + watermark fingerprint (Android already has `previewFingerprint()` pattern).

### P2 — `Template` unstable + `List<Template>` into Editor

```text
unstable class Template {
  stable var id: Int
  stable val content: String?
  unstable var creationDate: Instant?
  unstable var lastModifiedDate: Instant?
}
```

Template sheet is not filmstrip-hot, but every Editor open passes `templates` into `EditorScreen` → invalidates skip when list/instance changes.

**Fix:** immutable `Template` (`val` only) or don’t pass templates into the main Editor composition until sheet opens.

### P3 — Gallery is relatively clean; watch selection churn

- `ui.Image` is **stable** (all `val`).
- Risk is **selection callbacks** painting ranges (`GalleryImageGrid.applyRange` fires many `onSetSelected`) → host rebuilds selection set → new `List<Image>` with toggled `check` → full grid item recomposes.
- Prefer selection **Set&lt;uri&gt;** outside the image model, or `key(uri)` + only recompose cards whose selected flag changed (already have `selected: Boolean` on card — ensure list items aren’t new every drag frame).

### P4 — Slot / progressive presentation (iOS-heavy, CMP shared)

`LocalEditorProgressiveSlotPresentation` updates on every Ready → whole progressive strip state object changes. Android gallery path doesn’t use this; iOS does. Filmstrip jank fix (`UserScroll` bind) reduced **work**, not necessarily Compose invalidations from slot list growth.

### P5 — Not the problem this report sees

- Strong Skipping is already on (good).
- App-layer Editor composables are **skippable** at the compiler flag level.
- Gallery `Image` type is stable — don’t over-optimize gallery models first.

---

## 4. Recommended investigation sequence (next work)

1. **Layout Inspector (debug)** on physical Android: open Editor with 20+ images; enable recomposition counts; fling filmstrip; note counts on `EditorFilmstripThumb` / `WaterMarkCanvas` / `EditorPhotoStrip`.
2. **Same for GalleryDialog**: drag-select range; count `GalleryImageCard` recompositions.
3. **Optional:** wire stability-analyzer 0.12.0 + `@TraceRecomposition` on those four composables; run Heatmap + Reality Check (silent waste = new instances with same equals).
4. **Only then** apply one fix PR: prefer **split UI snapshot for images** or **split Session collect** (highest frequency × cost).

---

## 5. Suggested fix PRs (do not combine)

| PR | Scope | Success signal |
|----|--------|----------------|
| 1 | Split `collectAsState` / avoid fat `LaunchScreen` state in Editor composition | Layout Inspector: Editor chrome doesn’t recompose on export tick |
| 2 | Immutable / UI projection for selected images (uri + size + offsets) | Filmstrip cells skip when only jobState changes |
| 3 | Template list not in `EditorScreen` until sheet | Editor skip when templates load |
| 4 | Gallery selection state external to `Image.check` | Drag-select doesn’t rebuild full list models |

---

## 6. Commands cheat sheet

```bash
# Reports
./gradlew :app:compileReleaseKotlin :shared:compileAndroidMain -PcomposeCompilerReports=true

# Grep hot paths
rg -n 'fun me.rosuh.easywatermark.ui.Editor' shared/build/compose_compiler/*composables.txt
rg -n 'unstable class me.rosuh.easywatermark.data.model.ImageInfo' shared/build/compose_compiler/*classes.txt

# Later: stability baseline (after plugin wired)
./gradlew :app:compileDebugKotlin :app:stabilityDump
./gradlew :app:stabilityCheck
```

---

## 7. Artifacts in this folder

- `app-module.json` / `shared-module.json` — aggregate metrics  
- `hot-composables-extract.txt` — Editor/Gallery signatures  
- `unstable-ImageInfo.txt` — class-level unstable fields  
- Compiler full dumps remain under `app/build/compose_compiler/` and `shared/build/compose_compiler/` (local build tree)

---

## 8. Bottom line

| Surface | Static stability health | Real recompose risk |
|---------|-------------------------|---------------------|
| **Editor** | Flags look OK under Strong Skipping | **High** — unstable `ImageInfo` + fat Session collect + list identity churn on filmstrip/preview |
| **Gallery (image pick)** | `Image` stable; grid skippable | **Medium** — selection range updates and list rebuilds, not model instability |

Next concrete engineering step: **Instrument Editor filmstrip + Gallery cards with Layout Inspector / TraceRecomposition**, then land **one** of PR1/PR2 above.
