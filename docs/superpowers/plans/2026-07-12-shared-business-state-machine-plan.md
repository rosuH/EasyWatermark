# Plan: Shared business logic / UI state machine (KMP)

**Date:** 2026-07-12  
**Status:** **Architecture accepted (owner 2026-07-12)** — implementation not started  
**Owner decisions locked:**
- **SESSION_ARCH = B** — multiplatform `androidx.lifecycle.ViewModel` in `commonMain` + constructor-injected IO **ports** (not pure AppSession-only; not whole Android `MainViewModel` body dump)
- **UI = CMP-first** — product screens live in shared Compose Multiplatform; **SwiftUI is not the long-term product UI**. SwiftUI/Desktop-native only for entry + system edges (picker / share / save / permission / window chrome), matching AGENTS.md “UI route of record”
- **PORTS = constructor_inject** (prefer over sprawling `expect/actual` factories)
- **Consumers:** Android `:app`, Desktop `:desktopApp`, iOS via **CMP hosts** (not SwiftUI state ownership)

**Supersedes (scope only):** S4d-191 “no shared ViewModel without consumer” — owner named consumers = three platforms on CMP + shared VM  
**Does not supersede:** ADR-0004 renderer split, DataStore plain per-platform factories, Android native production raster, pick-policy docs

---

## 0. Locked architecture (B + CMP-first)

```text
                    commonMain
        ┌─────────────────────────────────────┐
        │ WatermarkSessionViewModel : ViewModel│
        │  viewModelScope · StateFlow · Intents │
        │  → WatermarkConfigEditor / …Editors  │
        │  → MediaLibraryPort                  │
        │  → ImagePipelinePort                 │
        │  → ExportStorePort                   │
        └──────────────────┬──────────────────┘
                           │ collected by
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   Android CMP         Desktop CMP        iOS CMP host
   ComposeMainActivity DesktopWindow      ComposeUIViewController
   + Android ports     + Desktop ports    + iOS ports
        │                  │                  │
        └──────── system edges only ──────────┘
              (picker / Photos / share / FS)
```

| Layer | Location | Notes |
|-------|----------|--------|
| Product UI (Launch / Gallery / Editor / Export sheet / Templates) | **shared CMP** | Grow existing shells; stop growing SwiftUI product UI |
| Session / business state machine | **commonMain ViewModel** | KMP Lifecycle ViewModel |
| Config / templates / prefs writes | existing Editors + Repos | Already commonMain |
| Decode / raster / encode / MediaStore / Photos | **Ports** per platform | Android keeps native production renderer |
| SwiftUI | **Shrink** | Entry, PHPicker, ShareLink, Save-to-Photos glue only |

**Rejected:** moving current `MainViewModel.kt` body (Bitmap/MediaStore/Activity) into commonMain unchanged.  
**Rejected:** long-term dual state in `WatermarkWorkflow` `@Published` + VM (iOS must bind CMP to shared VM).

---

## 2. Current truth (research snapshot)

### Already in `shared/commonMain` (foundation)

| Piece | Notes |
|-------|--------|
| Models / rules | `WaterMark`, `ImageInfo`, `MediaRef`, `ImageFormat`, `WatermarkConfigRules`, `WatermarkConfigChange`, `FuncType`… |
| Repos | `WaterMarkRepository`, `UserConfigRepository`, `TemplateRepository` + Room entity |
| Editors | `WatermarkConfigEditor`, `OutputPrefsEditor`, `TemplateEditor` |
| Partial UI state types | `UiState`, `LaunchScreenUiState`, `LaunchScreenState`, `Image` (gallery row, `MediaRef`) |
| Geometry / Desktop·iOS raster | `WatermarkGeometry`, `WatermarkCellComposer` (not Android production) |

### Still Android-owned (~1033-line `MainViewModel`)

Rough partition of **46** public/private methods:

| Bucket | Examples | CommonMain-ready? |
|--------|----------|-------------------|
| **A. Config dispatch** | `updateText*`, `onWaterMarkChanged` → already delegates to editors | Already shared; only glue left |
| **B. Navigation / selection state** | `onBackPressed`, gallery check, dismiss → editor, `LaunchScreenState` transitions, `goTemplate*` | **Yes — pure state machine** |
| **C. Gallery query** | `query` / MediaStore projection, `SystemPickerImageSelected` MediaStore path | **No** — port |
| **D. Export pipeline** | `saveImage` → `generateList` → `generateImage` (Bitmap/Canvas/`WatermarkRenderer`/MediaStore) | **Orchestration yes; pixels no** |
| **E. Compress / crash / version** | `compressImg`, `extraCrashInfo`, `saveUpgradeInfo` | Compress = port; crash = Android edge |
| **F. DI host** | `viewModelScope`, Koin `Context`, `MemorySettingRepo` (empty) | Stay platform |

### Off-Android consumers today (parallel state, not shared machine)

| Platform | State ownership |
|----------|-----------------|
| **Desktop** | `DesktopWindow` local `mutableStateOf` + direct `WatermarkConfigEditor` / repos; no `LaunchScreenState` / `process(Action)` |
| **iOS** | `WatermarkWorkflow` `@Published` fields + bridges; mirror of config, not Android launch/gallery/export state machine |

**Problem:** three UIs, three ad-hoc state holders, one shared *persistence* layer. Config writes are shared; **session / navigation / batch export lifecycle are not**.

### Existing Action is not portable

`Action` lives in `LaunchScreen.kt` and carries `Uri`, `ContentResolver`, `FuncTitleModel` (Android `@StringRes`/`@DrawableRes`). Cannot move as-is.

---

## 3. Target architecture (B locked)

### 3.1 Multiplatform ViewModel + ports (chosen)

| Option | Verdict |
|--------|---------|
| A. Pure Kotlin `AppSession` | Not chosen — owner prefers Android-familiar ViewModel |
| **B. Multiplatform Lifecycle ViewModel + ports** | **Accepted** |
| C. Lift entire `MainViewModel` body | **Reject** — Bitmap/MediaStore/Activity coupling |

```text
commonMain WatermarkSessionViewModel : androidx.lifecycle.ViewModel
  - StateFlow state + optional SharedFlow effects
  - dispatch(AppIntent) / typed public methods
  - WatermarkConfigEditor, OutputPrefsEditor, TemplateEditor
  - constructor ports: MediaLibraryPort, ImagePipelinePort, ExportStorePort
androidMain / desktopMain / iosMain
  - port implementations only
Android MainViewModel
  - thin: factory + optional Android-only edges (crash, compress) OR deleted once parity proven
Desktop / iOS CMP
  - viewModel { WatermarkSessionViewModel(platformPorts) } or manual retain + viewModelScope
```

**Deps (Phase 0/1):** add KMP `lifecycle-viewmodel` to `:shared` `commonMain` (align with catalog lifecycle line; use AndroidX multiplatform artifact per current Google/JetBrains docs). Desktop may need `kotlinx-coroutines-swing` if not already present for `viewModelScope` on JVM.

**Do not** introduce `expect class MainViewModel`. Ports = **constructor-injected interfaces** in commonMain.

### 3.2 State model (evolve existing types)

Keep and extend current common types:

```text
AppSessionState
  route: Launch | Gallery | Editor | TemplateOverlay | ExportSheet | About? 
  gallery: List<Image>            // already common
  selection: List<ImageInfo>      // already common (MediaRef)
  current: ImageInfo?
  waterMark: WaterMark            // mirrored from repo flow OR single source = repo
  templates: List<Template>       // from repo flow
  userPrefs: UserPreferences
  export: ExportJobState          // isSaving, completed, total, finished, per-item JobState
  editorUi: UiState               // template dialogs (already common)
  effects: Channel/SharedFlow     // optional one-shots
```

**Single source of truth for watermark config:** prefer **repo `Flow` as source**, session only holds **UI/session fields** (route, selection, export job). Avoid dual-write drift (Desktop/iOS currently mirror fields into `@Published` / `mutableStateOf` — migrate toward collecting repo flows).

### 3.3 Intent model (replace `Action`)

Platform-neutral sealed intents, e.g.:

| Intent | Meaning |
|--------|---------|
| `OpenGallery` | Enter gallery route (permission already granted at edge) |
| `GalleryItemsLoaded(List<Image>)` | Result of port query |
| `ToggleGalleryItem(MediaRef, checked)` | Selection toggle |
| `ConfirmGallerySelection` | → Editor if non-empty |
| `DismissGallery` | Back to Launch / keep selection rules as today |
| `ImagesPicked(List<MediaRef>)` | System picker / Desktop files / iOS PHPicker **after** edge maps to MediaRef |
| `SelectCurrent(MediaRef)` | Filmstrip |
| `ApplyConfig(WatermarkConfigChange)` | Typed; drop raw `Any` |
| `ApplyTemplate(Template)` / `SaveTemplate` / `DeleteTemplate` | |
| `SetOutputPrefs(ImageFormat, quality)` | via `OutputPrefsEditor` |
| `RequestExport` | Start batch; emits effects / calls pipeline port |
| `CancelExport` | |
| `NavigateBack` | Same semantics as `onBackPressed` |
| `OpenTemplates` / `CloseTemplates` | maps to `UiState` |

**Forbidden in Intent:** `ContentResolver`, `Uri`, `Activity`, `Bitmap`, `FuncTitleModel`.

Android Activity maps:

- `Uri` → `MediaRef` at edge  
- `LoadImages(resolver)` → port.listImages() → `GalleryItemsLoaded`  
- `WaterMarkChange(item, any)` → `WatermarkConfigChange.from(item.type, any)` once at edge → `ApplyConfig`

### 3.4 Effects (one-shot, not state)

| Effect | Platform handler |
|--------|------------------|
| `RequestSystemPicker` | Android Photo Picker / Desktop FileDialog / iOS PhotosPicker |
| `RequestPermission(ReadMedia)` | Android only |
| `ShareExports(List<MediaRef>)` | Android / iOS share sheet; Desktop open-folder substitute |
| `ShowMessage(code)` | Toast / snackbar / status string |
| `OpenAbout` | Android route; iOS/Desktop optional |

### 3.5 Ports (minimal surface)

```kotlin
// conceptual — names TBD in implementation ADR
interface MediaLibraryPort {
    suspend fun listImages(): List<Image>           // Android MediaStore; Desktop empty or folder; iOS limited / N/A
}

interface ImagePipelinePort {
    /** Decode source, apply current WaterMark, return encoded bytes + suggested name/format. */
    suspend fun renderExport(
        source: MediaRef,
        config: WaterMark,
        prefs: UserPreferences,
    ): ExportArtifact   // bytes + ImageFormat + width/height + error code
}

interface ExportStorePort {
    suspend fun persist(artifact: ExportArtifact): MediaRef  // MediaStore / file / Photos
}
```

**Android `ImagePipelinePort`:** existing `generateImage` body (native renderer).  
**Desktop/iOS:** existing Skiko compose paths (`DesktopWatermarkFlow` / `IosWatermarkRenderBridge`).  
Orchestration (loop, progress, JobState) lives in **AppSession**, not in three copies of for-loops.

---

## 4. What stays platform forever (honest boundary)

| Concern | Why |
|---------|-----|
| Android production text/icon/compose raster | ADR-0004 / S4d-17 / S4d-190 closed |
| System pickers & permissions | OS policy |
| MediaStore vs Photos vs filesystem paths | OS policy |
| `FuncTitleModel` string/drawable ids | Android resources; Desktop/iOS use strings catalogs / CMP resources later |
| Crash recovery Activity, recovery mode | Android process model |
| About dynamic color / Play links | Android-first product surface |

Shared machine **coordinates** these; it does not implement them.

---

## 5. Migration phases (shippable slices)

Each phase: commonTest for pure reduce/orchestrate + Android smoke + one off-Android consumer touch where claimed.

### Phase 0 — Contracts + toolchain (docs + Gradle only)

- [x] Owner: **B + CMP-first + constructor ports** (2026-07-12)  
- [x] Owner: keep **Android SKILL** + **performance** guarantees during migration (2026-07-12)  
- [x] ADR-0017 Accepted — shared ViewModel + ports + CMP-first + skill/perf gates  
- [x] Wire `:shared` commonMain `lifecycle-viewmodel` + desktop `coroutines-swing`  
- [x] Phase 0 scaffold `WatermarkSessionViewModel` + commonTest construct smoke  
- [ ] Freeze: no new product features inside Android `MainViewModel` except bugfixes / thin adapters  

**Exit:** ADR Accepted; deps compile on android + desktop + both iOS; skill/perf gates in ADR §4–§5.

### Phase 1 — Shared ViewModel owns nav/selection (Android still green)

- [x] `AppIntent` / `ExportJobState` / pure `SessionReducer` in commonMain  
- [x] `WatermarkSessionViewModel` owns: route, gallery selection, back, template `UiState`; selection commit via repo effects  
- [x] Android `MainViewModel` **extends** session VM; maps `Action` → `AppIntent`; export still Android methods  
- [x] commonTest `SessionReducerTest` + host/desktop compile green  

**Exit:** Android UI behavior unchanged; commonTest for pure transitions; goldens untouched (no render change).

### Phase 2 — Export orchestration in shared ViewModel

- [x] Export progress loop in `WatermarkSessionViewModel.requestExport` / `AppIntent.RequestExport`  
- [x] `ExportPipelinePort` + Android `AndroidExportPipelinePort` wrap of legacy `generateImage` (native renderer + MediaStore)  
- [x] `MainViewModel.saveImage` → `requestExport` only  
- [x] `ExportOrchestrationTest` + full compile/unit green  

**Exit:** Android export path wrap-not-rewrite; shared progress state; goldens untouched (algorithm same).

### Phase 3 — Desktop CMP binds shared ViewModel

- [x] `DesktopExportPipelinePort` (desktopMain) — Skiko compose + unique output file  
- [x] `DesktopWindow` retains `WatermarkSessionViewModel` + port; Open image / drop batches use `exportAndAwait`  
- [x] Preview / Save-as / fixture sample still use `runSaveFlow` (in-memory bytes path)  
- [x] desktopTest + headless green  

**Exit:** Desktop product open/drop export path shares session orchestration.

### Phase 4 — iOS CMP binds shared ViewModel (SwiftUI shrinks)

- [x] `IosExportPipelinePort` — Skiko via `IosWatermarkRenderBridge` wrap  
- [x] `IosAppServices` — one DataStore graph: bridges + `WatermarkSessionViewModel`  
- [x] `WatermarkWorkflow.render` → `exportPickedImageBytes` (session export); PHPicker/Save/Share stay Swift edges  
- [x] iosSimulatorArm64Test + iosApp xcodebuild green  

**Exit:** iOS product render/export uses shared session; remaining SwiftUI is system glue + config mirrors (further CMP growth is Phase 5+).

### Phase 5 — Retire Android-only VM bulk + Action

- [x] Config `update*` → shared `AppIntent.ApplyConfig` / `WatermarkConfigEditor` on session VM  
- [x] `Action` documented as Android edge adapter only (no growth without Android-only types)  
- [x] System-picker path extracted; MediaStore/compress/crash remain intentional Android edges  
- [x] MainViewModel ~523 lines (from ~1k pre-session / ~593 post-export); unit+desktop green  

**Exit:** Android host is edge + Koin shell; session owns config/nav/export orchestration.

### Explicitly later / optional

- Shared About session (needs product decision)  
- In-app gallery on Desktop/iOS (product decision; Android gallery remains primary per pick policy)  
- colorpicker-compose unification (UI, not session)  
- Koin common module (still optional; constructor injection is enough)

---

## 6. Testing strategy

| Layer | Gate |
|-------|------|
| Pure session reduce | `commonTest` with fake ports (selection, back, export progress) |
| Android host | Existing unit suite + manual export smoke; strict goldens when render path touched |
| Desktop | `--headless` + window smoke; session-driven export |
| iOS | `iosSimulatorArm64Test` + XCUITest fixture path |
| Regression | Do **not** claim three-platform pixel parity from session move |
| **Android SKILL** | Compose/UI slices: baseline → change → visual verify (repo migrate skill); production v2.10.0 truth |
| **Performance** | Heavy work off Main; Android export path = wrap-not-rewrite; no extra full-res decode on slider ticks; note wall-time/jank when moving export/preview |

---

## 7. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Dual state (repo Flow + session copy of WaterMark) | Session does not own WaterMark copy long-term; collect repo |
| Export behavior drift | Android port = move code, not rewrite algorithm |
| Scope explosion (“full gallery on iOS”) | Phase 4 uses existing pick policy; no MediaStore clone |
| S4d-191 process conflict | This plan is the **owner decision** that unlocks shared session |
| Large PR | One phase per PR; Android-green always |

---

## 8. Effort order of magnitude

| Phase | Rough effort |
|-------|----------------|
| 0 contracts | 1–2 days |
| 1 Intent + nav/selection | ~1 week |
| 2 Export orchestration + ports | ~1–2 weeks |
| 3 Desktop adoption | ~1 week |
| 4 iOS adoption | ~1–2 weeks |
| 5 cleanup | ~3–5 days |

**Not a weekend.** Comparable to a small multi-milestone program; each phase must ship independently.

---

## 9. Owner decisions (recorded 2026-07-12)

```text
SESSION_ARCH: B_mp_lifecycle_vm
UI: CMP_first          # SwiftUI not long-term product UI
PORTS: constructor_inject
DESKTOP_ADOPT: phase3
IOS_ADOPT: phase4      # via CMP host + ports; shrink SwiftUI
START_PHASE: 0         # next: ADR + lifecycle-viewmodel in :shared
```

---

## 10. First implementation tickets

1. **ADR** — Shared `WatermarkSessionViewModel` + ports + CMP-first UI (Accept)  
2. **P0** — `:shared` commonMain `lifecycle-viewmodel` compile all targets  
3. **P1a** — `AppIntent` / state types / `WatermarkSessionViewModel` nav+selection + commonTest  
4. **P1b** — Android maps `Action` → intent; green assemble + unit  
5. **P2…** only after P1 Android parity  

Do **not** start P2/P3 until P1 proves zero Android behavior change.

---

## 11. Relation to “Android looks fine”

Android product logic working is **necessary but not sufficient** for shared session:

- Data layer shared ≠ session shared  
- iOS/Desktop still re-implement selection/export/status in Swift/Compose locals  
- This plan unifies **session**, keeps **pixels** platform-split by closed ADRs  

---

*End of plan*
