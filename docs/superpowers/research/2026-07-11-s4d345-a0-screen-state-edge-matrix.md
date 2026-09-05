# S4d-345 — A0 three-platform screen / state / event / edge matrix

**Date:** 2026-07-11
**Type:** read-only mapping (no code change)
**Branch:** `feat/migrate_to_compose`
**Process contract:** [codex-goal-v2.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal-v2.md) §7.3 (S4d-344 finish → **A0 matrix** → parallel A1/A2/A3 → A4 → A5 → Phase B)
**Rule:** production consumers only — DEBUG witnesses, tests, and theoretical callers do **not** count.
**Consumer-first extraction (§6.12):** pure state/use-case → commonMain only with **≥2 named production platform consumers** and no platform types in I/O.

---

## 1. Evidence sources (production code)

| Layer | Paths consulted |
|---|---|
| Shared UI inventory | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/**` (shells, `compose/*`, `save/*`, `about/*`, `Routes.kt`, theme) |
| Shared domain | `shared/.../domain/{WatermarkConfigEditor,OutputPrefsEditor,TemplateEditor}.kt` + repos |
| Android entry | `app/.../ui/ComposeMainActivity.kt` NavHost: `LaunchRoute` → dialog `GalleryDialogRoute` → `EditorRoute` → `AboutRoute` → `OpenSourceRoute`; recovery branch |
| Android wrappers | `LaunchScreen.kt`, `EditorScreen.kt`, `compose/GalleryDialog.kt`, `about/AboutScreen.kt` / `OpenSourceScreen.kt`, `save/SaveExportSheet.kt`, `MainViewModel.kt`, `render/WatermarkRenderer.kt` |
| Desktop | `desktopApp/.../Main.kt` (`launchDesktopWindow` / `--headless`), `DesktopWindow.kt`, `DesktopWatermarkFlow.kt` |
| iOS production | `iosApp/iosApp/ContentView.swift`, `WatermarkWorkflow.swift`; `shared/iosMain/.../IosSharedComposeHost.kt` **production** hosts (not `*Witness` methods) |
| iOS bridges | `IosWatermarkConfigBridge`, `IosTemplateBridge`, `IosUserConfigBridge`, render/decode edges |

**DEBUG-only (excluded from production counts):** `IosSharedComposeHost.*Witness()`, ContentView `#if DEBUG` `-sharedComposeWitnesses` hosts, XCUITest fixtures.

---

## 2. Legend

| Status | Meaning |
|---|---|
| **shared root** | Product screen structure owned by commonMain shell; platform only injects edges |
| **shared component** | Shared control/row/host used inside a platform root |
| **platform edge** | System/native surface that must stay off commonMain product UI |
| **candidate** | Residual product UI still platform-owned; real A1/A2/A3 work |

Ownership columns: **UI** = who draws the product surface; **state** = who holds ephemeral UI state; **IO** = pick/save/share/media; **persist** = DataStore/Room; **render** = watermark raster/compose.

---

## 3. Matrix (product surfaces)

### 3.1 Launch / source acquisition

| Field | Content |
|---|---|
| **Surface + states** | Empty launch; “choose images”; permission denied / granted (Android); pick cancelled |
| **Events → owner** | Choose images → Android permissions + navigate gallery / iOS PhotosPicker / Desktop FileDialog or drop; About → Android nav only |
| **commonMain production UI** | `LaunchScreenShell` |
| **Android root + edges** | `LaunchScreen` → shell; edges: `READ_MEDIA_*` / storage permission, `stringResource`/`painterResource`, animated logo `ColoredImageVIew`, `MainViewModel` actions, Nav to gallery/about |
| **Desktop root + edges** | **No launch product screen** — window opens already in editor/product flow; source via AWT `FileDialog` multi-select, drag/drop (`DesktopSaveDecision`) |
| **iOS root + edges** | Production `IosLaunchScreenHost` + `LaunchScreenShell` (`SharedComposeLaunchScreen`); edge: PhotosPicker on choose; `WatermarkWorkflow` holds bytes/state |
| **Ownership** | UI: shared shell (A/iOS) / none (Desktop). State: Android `MainViewModel` / iOS `WatermarkWorkflow` / Desktop window locals. IO: platform pickers. Persist: n/a. Render: n/a |
| **Status** | **shared root** (Android, iOS); Desktop **platform edge** (entry=window) |
| **Next lane / block** | A1: thin remaining Android permission/logo glue. A2: optional Desktop empty-state shell only if product wants parity (not required for migration). A3: launch root already shared. **S4d-338 N/A** |

### 3.2 Gallery selection

| Field | Content |
|---|---|
| **Surface + states** | Multi-select grid; selected count; empty library; confirm / dismiss |
| **Events → owner** | Toggle select, confirm → Android `MainViewModel` + nav Editor; dismiss → nav back |
| **commonMain production UI** | `GalleryDialogShell`, `GalleryDialogTopBarShell`, `GalleryImageGrid`, `GallerySelectedCountFab`, `AnimatedTransitionHost` |
| **Android root + edges** | `GalleryDialog` → shell; Coil/`MediaRef`→`Uri`, system picker fallback, dialog animation callbacks, image list load via ContentResolver |
| **Desktop** | **None** — no multi-select gallery product; multi-file via Open/drop |
| **iOS** | **No production gallery root** — only DEBUG `galleryDialogShellWitness`. Source is single PhotosPicker (and fixture) |
| **Ownership** | UI: shared (Android only). State/IO: Android VM + MediaStore. Desktop/iOS: different acquisition model |
| **Status** | **shared root** Android; Desktop/iOS **platform edge** (no gallery product surface) |
| **Next** | A1: keep thinning Coil/picker edges. A3 gallery **candidate** only if product decides iOS multi-select gallery (not required for engine parity). **S4d-338 N/A**. PHPicker grid automation residual is **toolchain**, not a product root gap |

### 3.3 Editor shell (layout frame)

| Field | Content |
|---|---|
| **Surface + states** | Editor chrome: top bar, preview region, photo strip, bottom controls/tabs |
| **Events → owner** | Tab/option select → platform state; back/about/save → platform |
| **commonMain production UI** | `EditorScreenShell`, `EditorTopBarShell`, `EditorPreviewFrame`, `EditorPhotoStrip`, `EditorBottomControlsShell`, `EditorBottomTabRow`, `EditorOptionCarousel`, `EditorOptionControlFrame`, `EditorOptionItem` |
| **Android** | `EditorScreen` → shells; edges: `MainViewModel`, Coil strip, native `WaterMarkCanvas` inside preview slot, save sheet show, multi-pick contracts |
| **Desktop** | `DesktopWindow` → **production** `EditorScreenShell` + `EditorPreviewFrame` + `SavePreviewStatus`; controls in `bottomControls` scroll; AWT busy/status |
| **iOS** | **No production EditorScreenShell** — DEBUG `editorScreenShellWitness` only. Production is SwiftUI `ScrollView` of discrete shared controls + preview |
| **Ownership** | UI: shared shells A+Desktop; iOS partial components. State: Android VM / Desktop locals+repos / iOS Workflow. Render: see §3.4 |
| **Status** | **shared root** Android+Desktop; iOS **candidate** (A3 full editor root) |
| **Next** | A3: replace SwiftUI scroll product surface with `EditorScreenShell` when enough controls are shared (**blocked for text field by S4d-338** if shell embeds `TextContentOption` sheet). A1: further thin Android wrapper. A2: already has shell |

### 3.4 Editor preview / watermark render

| Field | Content |
|---|---|
| **Surface + states** | Preview image; clamp drag offset; loading/error |
| **Events → owner** | Pan clamp offset → Android `MainViewModel`/`ImageInfo`; Desktop preview refresh; iOS re-render after config write |
| **commonMain production UI** | `EditorPreviewFrame`, `SavePreviewStatus` (status chrome). **Not** cell raster |
| **Android edges** | Native `WatermarkRenderer.build*Shader` + `compose` on Compose `Canvas` (`WaterMarkCanvas`); Bitmap decode/`BitmapUtils`/EXIF; **native text/icon/composition stay** (S4d-8/17/190) |
| **Desktop edges** | Skiko path: `composeTextCell`/`composeIconCell`/`composeOverBackground` via `DesktopWatermarkFlow` / decoder; preview temp file under `~/.easywatermark/preview/` |
| **iOS edges** | Production `IosWatermarkPreviewHost` + shared frame/status; PNG from `IosWatermarkRenderBridge` / Skiko; decode EXIF baked by Skia |
| **Ownership** | Geometry constants: commonMain `WatermarkGeometry`. Raster: Android native vs Desktop/iOS Skiko composer |
| **Status** | **shared component** (frame/status); render **platform edge** (by closed decision on Android) |
| **Next** | Do **not** route Android production through commonMain composer. A2/A3: only preview chrome alignment |

### 3.5 Editor controls (config)

| Control | commonMain | Android prod | Desktop prod | iOS prod | Status |
|---|---|---|---|---|---|
| Text content | `TextContentOption` | yes (`EditorScreen`) | yes | **SwiftUI `TextField`** (S4d-338 block on CMP) | iOS **candidate** blocked **S4d-338** |
| Degree / size / alpha / hGap / vGap | `SliderOption` | yes | yes | yes (slider hosts) | **shared component** (3 platforms) |
| Tile mode | `TileMode` option | yes | yes | yes | **shared component** |
| Typeface / paint style | options | yes | yes | yes | **shared component** |
| Text color | `TextColorOption` | yes (`ColorOption` wrapper) | yes (palette+hex) | yes (4-preset) | **shared component** |
| Icon mode | `IconWatermarkOption` / `IconOption` | yes | yes (Open icon) | yes + PhotosPicker edge | **shared component** + picker edge |

**Events:** change value → `WatermarkConfigEditor` / repo (all three) or Android VM wrappers.
**Persist:** commonMain `WaterMarkRepository` + platform DataStore creation.
**Next:** A3 text control only after owner Compose/Skiko align (**S4d-338**). A1: optional wrapper cleanup only.

### 3.6 Templates

| Field | Content |
|---|---|
| **Surface + states** | Sheet/list; empty; add/edit/use/delete |
| **commonMain production UI** | `EditorTemplateSheetHost` + `TemplateListSheet` strings model |
| **Android** | `EditorScreen` → `EditorTemplateSheetHost`; Room prepopulated DB; `TemplateEditor` via VM |
| **Desktop** | production `EditorTemplateSheetHost`; `TemplateEditor` + locale seed DB |
| **iOS** | **SwiftUI Templates section** + `IosTemplateBridge`/`TemplateEditor` — **not** shared sheet host in production |
| **Ownership** | Persist/domain shared; UI sheet shared A+Desktop; iOS UI candidate |
| **Status** | **shared component** A+Desktop; iOS **candidate** (A3) |
| **Next** | A3: host shared template sheet when text path allows (Use/Save still need text field UX; partial sheet possible without S4d-338 if no CMP text field). **S4d-338** only if sheet embeds focused CMP text |

### 3.7 Rendered output actions / export

| Field | Content |
|---|---|
| **Surface + states** | Idle / saving / saved / failed; share available or not |
| **commonMain production UI** | `SavedOutputActions` (primary/secondary); Android also `SaveExportSheetShell` + options/preview list; Desktop `SaveExportOptionsSection` + `SavedOutputActions` + `SavePreviewStatus` |
| **Android edges** | MediaStore / FileProvider; `SaveExportUiState` in `MainViewModel`; share intents; gallery view |
| **Desktop edges** | AWT open folder / clipboard path; format prefs via `OutputPrefsEditor`; real save paths under user dirs |
| **iOS edges** | Production `IosSavedOutputActionsHost`: Share=`UIActivityViewController`+temp URL; Save=Photos (in-memory PNG); independent enables (S4d-344) |
| **Ownership** | UI shared row/shells; IO always platform |
| **Status** | **shared component** (3 platforms for row or sheet pieces); export IO **platform edge** |
| **Next** | A1: keep sheet edges thin. A2 already consuming. A3 done for row (S4d-344). No A4 from this surface |

### 3.8 About / open source

| Field | Content |
|---|---|
| **commonMain** | `AboutScreenShell`, `OpenSourceScreen` |
| **Android** | production via Nav; `AboutViewModel` + `DynamicColorCapability` |
| **Desktop** | **none** production About root |
| **iOS** | DEBUG about witness only |
| **Status** | **shared root** Android; Desktop/iOS **candidate** if product wants parity screens |
| **Next** | Low priority A2/A3 after editor roots. Not A4 without second consumer of About-specific pure state |

### 3.9 System picker / share / save / permissions

| Field | Content |
|---|---|
| **commonMain** | none (by design) |
| **Android** | PickVisualMedia / multi-pick contracts; permissions; share-in `ACTION_SEND*`; MediaStore |
| **Desktop** | AWT FileDialog; drag/drop; Desktop.open; clipboard |
| **iOS** | PhotosPicker; PHPhotoLibrary add; UIActivityViewController; no gallery permission model same as Android |
| **Status** | **platform edge** everywhere |
| **Next** | Never migrate into product CMP. PHPicker grid **automation** residual is verification-only (S4d-57) |

### 3.10 Recovery / error routes

| Field | Content |
|---|---|
| **commonMain** | `RecoveryScreen` + strings edge |
| **Android** | `MyApp.recoveryMode` → `ComposeMainActivity` recovery setContent; clear data / telegram / email edges |
| **Desktop / iOS** | no production recovery UI |
| **Status** | **shared root** Android-only product; others N/A |
| **Next** | Phase B polish if needed; not A2/A3 unless product requires |

### 3.11 Headless / non-UI (not product screens)

| Surface | Notes |
|---|---|
| Desktop `--headless` | `DesktopWatermarkFlow` / Main witnesses — **not** product UI |
| Shared domain editors | Already multi-consumer (see §5) |

---

## 4. Platform root summary (ASCII)

```
  Android (ComposeMainActivity NavHost)
    LaunchScreen ──► LaunchScreenShell          [shared root]
    GalleryDialog ─► GalleryDialogShell…        [shared root]
    EditorScreen ──► EditorScreenShell + opts   [shared root + components]
    SaveExportSheet ► SaveExportSheetShell      [shared root]
    About/OpenSource/Recovery ► shared screens  [shared root]
    edges: perms, Uri/Coil, MediaStore, native WatermarkRenderer

  Desktop (Window root = product editor)
    EditorScreenShell + EditorPreviewFrame      [shared root]
    controls: TextContent/Sliders/Tile/…        [shared components]
    SaveExportOptions + SavedOutputActions      [shared components]
    EditorTemplateSheetHost                     [shared component]
    edges: AWT file/drop/save/folder, Skiko render, ~/.easywatermark
    NO gallery / launch / about production roots

  iOS (SwiftUI ContentView bring-up)
    LaunchScreenShell host                      [shared root]
    discrete CMP hosts: sliders/tile/style/…    [shared components]
    preview frame + SavedOutputActions          [shared components]
    TextField + Templates list                  [platform UI residual]
    PhotosPicker / UIActivity / Photos save     [platform edges]
    Skiko render bridges                        [platform edges]
    EditorScreenShell / Gallery / About         [DEBUG witnesses only]
```

---

## 5. A4 pure state/use-case candidates (§6.12)

Already extracted with **≥2 production consumers** (do not re-extract):

| Use-case | Consumers (production) |
|---|---|
| `WatermarkConfigEditor` | Android `MainViewModel`, Desktop window/flow, iOS `IosWatermarkConfigBridge` |
| `OutputPrefsEditor` | Android VM, Desktop window |
| `TemplateEditor` | Android VM, Desktop window, iOS `IosTemplateBridge` |

**New A4 candidates from this matrix:** **none yet.**

Reasons under strict §6.12:

- Remaining pure-looking Android `MainViewModel` methods are largely `UiState`/navigation or Android IO (`Uri`, `Bitmap`, MediaStore, compressor, native renderer) — S4d-191 still holds.
- No second-platform **production** consumer for a shared navigation/reducer, shared export state machine, or shared gallery selection model (Desktop/iOS acquisition differ).
- `AboutViewModel` still single-platform production consumer (Android only).
- DEBUG witnesses do not qualify.

**Do not invent a shared ViewModel** until A3 (or product decision) creates a real dual-platform consumer for a pure transition.

---

## 6. Residual A1 / A2 / A3 work (dependency order)

### A1 — Android wrapper thinning (parallel-safe)

1. Keep Nav + `MainViewModel` as root owner; only remove dead glue around already-shared shells.
2. Leave native renderer/composition, Uri/picker/share, DataStore migrations, Room asset open **as documented edges**.
3. Optional: further resource/string injection cleanup on About/Save sheet (behavior-preserving).

### A2 — Desktop shared screen root (largely done; residual polish)

1. Already: `EditorScreenShell`, preview frame, controls, templates host, output actions/options.
2. Residual candidates (product choice, not blockers for A5): Desktop launch empty-state, About root, true multi-image gallery parity.
3. Edges stay: AWT dialogs, drop, user dirs, Skiko render, packaging.

### A3 — iOS shared screen root (main residual)

1. **Unblocked now:** continue non-text shared components; optional shared **template sheet host** if it avoids focused CMP text field.
2. **Blocked by S4d-338 (lane-local):** replace SwiftUI watermark text with `TextContentOption` / any focused `OutlinedTextField` / modal text path; full `EditorScreenShell` if it embeds that text sheet.
3. **Product decision (not S4d-338):** production `GalleryDialogShell` / About only if multi-select gallery / about are required on iOS.
4. Keep PhotosPicker, Share, Save, font bundle, Skiko bridges as edges.
5. Do not treat PHPicker XCUITest grid automation as a product migration task.

### A4

- **No new pure extraction** until a residual control creates ≥2 production consumers for a pure transition (unlikely before A3 text/editor root).

### A5 Phase A integration gate (later)

- Shared CMP is route of record on all three **for product screens that exist on that platform**.
- Edges listed with reasons (this matrix + ADRs).
- Not pixel 1:1 (Phase B).

---

## 7. Closed decisions / invariants (must stay correct)

| Item | Status in matrix |
|---|---|
| Android native text/icon/composition | **edge** — do not A1 “fix” |
| Persisted DataStore/Room bytes | sacred; store creation platform plain functions |
| Uri / MediaStore / picker / share | Android edges |
| PHPicker grid automation | toolchain residual, not product failure |
| S4d-338 | blocks **iOS CMP text / full-root text path only**; other A1/A2/A3 lanes open |
| Phase A / B / 1:1 complete | **not claimed** |

---

## 8. Conclusions (required)

1. **Concrete residual order:** finish any A1 dead-glue → A2 optional empty/about only if desired → **A3 iOS editor root / text (S4d-338)** + optional templates sheet / gallery product decision → A4 only if dual consumers appear → A5 gate → Phase B.
2. **A4:** **no new qualified pure state/use-case candidate** under ≥2 production consumers; existing three editors already shared.
3. **Hard edges** (native renderer, persistence, Uri/media, PHPicker automation, S4d-338) recorded above.
4. **Not** Phase A complete; **not** Phase B; **not** Android v2.10.0 1:1.

---

## 9. Overclaim guard

- Shared shells on Android ≠ three-platform product identity (iOS still SwiftUI-assembled).
- Desktop shell ≠ full Android Nav graph.
- DEBUG witnesses ≠ production roots.
- S4d-344 output actions ≠ export MediaStore parity.

---

*End of S4d-345 A0 matrix*
