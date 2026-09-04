# Research: Full CMP product UI for iOS + Desktop (renderer edges only)

**Date:** 2026-07-12  
**Goal (owner):** iOS and Desktop product UI live in **Compose Multiplatform**; only **necessary** platform rendering and system UI stay native.  
**Android baseline:** v2.10.0 product behavior + owner-signed parity archive (tickets 07/08).  
**Related:** ADR-0017 (session VM), ADR-0004 (renderer split), exception registry `docs/parity/v2.10.0/alignment/ios-desktop-exception-registry.md`, AGENTS “UI route of record”.

---

## 1. Target architecture

```text
                    ┌──────────────────────────────────────┐
                    │  shared/commonMain                   │
                    │  ProductApp (CMP)                    │
                    │  ├── Navigation (Launch/Gallery?/    │
                    │  │   Editor/About?/Export sheet)     │
                    │  ├── Screens = existing *Shell +     │
                    │  │   option composables              │
                    │  ├── WatermarkSessionViewModel       │
                    │  └── strings/painters via params or  │
                    │      future multiplatform resources  │
                    └───────────────┬──────────────────────┘
                                    │ inject slots
              ┌─────────────────────┼─────────────────────┐
              ▼                     ▼                     ▼
         Android               Desktop                  iOS
      Activity/Nav2          Window application     UIViewController
      native preview         Skiko preview          Skiko preview
      MediaStore gallery     FileDialog/DnD         PHPicker
      MediaStore export      FS save/share          Photos/Share
```

**Necessary non-CMP (keep forever or long-term):**

| Edge | Why |
|------|-----|
| Watermark **raster** (Android native / Desktop·iOS Skiko) | ADR-0004 closed; not byte-unified |
| Image **decode/encode** | Platform codecs / EXIF policy |
| System **pickers** | PHPicker, Android Photo Picker, AWT FileDialog |
| System **share / save-to-photos / MediaStore** | OS APIs |
| App **entry** | `ComponentActivity`, Compose Desktop `Window`, iOS `App` + one `UIViewController` host |
| Permissions | Android storage / iOS Photos limited access |

**Must move into CMP (today partially or fully outside):**

- Product chrome: Launch, Editor layout, option tabs, text/style/layout controls, templates sheet, export options sheet  
- Navigation between product screens (where product has those screens)  
- Binding UI to **session VM** (not parallel `@Published` / `mutableStateOf` business state)  
- Preview **frame** (shared); pixels from platform decoder  

---

## 2. Current state (evidence-based)

### 2.1 Shared CMP already exists (strong foundation)

| Area | commonMain assets |
|------|-------------------|
| Shells | `LaunchScreenShell`, `GalleryDialogShell`+grid/FAB, `EditorScreenShell`, top/bottom/tab/carousel/control frames, `EditorPreviewFrame`, `EditorPhotoStrip`, `EditorTemplateSheetHost` |
| Options | `TextContentOption`, `TextColorOption`, `SliderOption`, `TileModeOption`, `TextTypefaceOption`, `TextPaintStyleOption`, `IconWatermarkOption`, `WatermarkModeActions` |
| Save | `SaveExportSheetShell`, options section, preview box, command/actions rows |
| About | `AboutScreenShell`, `OpenSourceScreen` |
| Theme | `AppTheme` / colors |
| Routes | `@Serializable` Launch/Gallery/Editor/About/OpenSource |
| Session | `WatermarkSessionViewModel` + ports (logic layer) |

**Strings strategy today:** shells take `*Strings` / `Painter` at the edge — **no compose-resources** (avoids CMP-9547 history). Full CMP UI can keep this pattern longer, or adopt multiplatform resources later.

### 2.2 Android (source of product truth)

| Piece | Location | CMP? |
|-------|----------|------|
| Nav graph | `ComposeMainActivity` NavHost | Jetpack Navigation (not yet multiplatform nav host) |
| Launch / Gallery / Editor / About | Activity + app wrappers | Screens mostly shared shells + Android slots |
| Preview | `WaterMarkCanvas` → native `WatermarkRenderer` | Platform raster slot |
| Gallery data | `MediaLibraryPort` | Port + session |
| Export | session + `AndroidExportPipelinePort` | Port |

Android is closest to the target: **shells shared, edges thin**.

### 2.3 Desktop — **shell-on, product chrome still Desktop-local**

| Metric | Approx. |
|--------|---------|
| `DesktopWindow.kt` | **~1290 LOC** single file |
| Uses shared | `EditorScreenShell`, several option composables, save options section, session for **Open/drop export only** |
| Still Desktop-local | Long vertical control list, labels, busy/status, templates chrome, dual config state (`mutableStateOf` + direct `WatermarkConfigEditor`) |
| Missing product routes | Launch, Gallery, About (registry **E01/E02/E04** — previously “exception”) |
| Preview | Skiko-decoded `ImageBitmap` from `runSaveFlow` temp file |
| Entry | `Window` + drag-and-drop + AWT dialogs |

**Gap:** Desktop is “CMP shell + bespoke bottom panel”, not “Android-parity editor product on CMP”. Config path does **not** primarily use `session.applyConfig`.

### 2.4 iOS — **hybrid: one CMP editor host + large Swift product frame**

| Metric | Approx. |
|--------|---------|
| `IosSharedComposeHost.kt` | **~1227 LOC** (hosts + many per-control hosts + production `IosEditorScreenHost`) |
| `ContentView.swift` + `WatermarkWorkflow.swift` | **~1230 LOC** Swift |
| Production CMP | `IosLaunchScreenHost`, `IosEditorScreenHost` (options column inside CMP) |
| Still SwiftUI | PhotosPicker (photo + icon), Templates list/save/delete, status/save lines, Share presentation, DEBUG witnesses |
| State | Workflow `@Published` mirrors of every watermark field + bridge; session mainly for **export** |
| Entry | SwiftUI `App` → `UIViewControllerRepresentable` wrappers |

**Gap:** Product UX is still **Swift-owned orchestration** with CMP as embedded editor; not a single CMP app graph driven by session.

---

## 3. Target product surface map (what “full CMP UI” means)

Align to Android signed behaviors, with **explicit product decisions** for former exceptions:

| Screen / flow | Android today | Full CMP target iOS | Full CMP target Desktop | Remains non-CMP |
|---------------|---------------|---------------------|-------------------------|-----------------|
| Launch | CMP shell + logo edge | CMP Launch in root graph | **Decision:** add Launch **or** keep editor-only window (E01 reopen) | Logo animation may stay platform |
| Gallery | In-app dialog + system picker secondary | **Decision:** keep PHPicker-only (E02) **or** invent gallery (owner) | Keep FileDialog/DnD (E02) | Picker UI |
| Editor | Full EditorScreen | One CMP root = EditorScreenShell + **shared bottom controls** like Android | Same | Preview raster |
| Options (Content/Style/Layout) | Android `BottomView` + shared frames | Move option wiring into shared composable fed by session | Collapse Desktop scroll list into same shared bottom | — |
| Text sheet / templates | Shared + Android resources | Templates inside CMP sheet (drop SwiftUI strip) | Already partial CMP templates | — |
| Export sheet | Shared shell + Android Uri thumbs | Shared shell + ImageBitmap thumbs | Shared shell | Save/share buttons invoke ports |
| About | Shared shell + Android links | **Decision:** add route or keep absent (E04) | Same | URL open edge |
| Recovery / crash | Shared RecoveryScreen | Optional | Optional | Mail intent Android-only |

**Critical owner decisions (block “1:1 three app” claims if deferred):**

1. **Desktop Launch/About** — invent for matrix vs accept editor-window product forever.  
2. **iOS/Desktop in-app gallery** — invent vs system-pick-only (current policy).  
3. **Multi-image filmstrip** on iOS/Desktop (E06) — product yes/no.  
4. **Localization** — hard-coded EN strings at edge vs multiplatform resources (Weblate already for Android `strings.xml`).

---

## 4. Workstreams required (deep breakdown)

### WS-A — Single product UI module composition (commonMain)

**Deliverable:** `ProductApp` / `AppNavHost` composable in commonMain that platforms call.

| Task | Detail | Depends |
|------|--------|---------|
| A1 | Multiplatform navigation host | JetBrains Navigation / Navigation3-CMP or hand-rolled state nav on `LaunchScreenUiState` / typed routes already in `Routes.kt` |
| A2 | Wire screens: LaunchShell, Editor **full**, optional About, export sheet | Existing shells |
| A3 | **Shared Editor bottom controls** extracted from Android `EditorScreen.BottomView`/`OptionControl` | Highest-value UI move; today Android-only orchestration |
| A4 | Shared filmstrip optional (`showPhotoStrip`) driven by session selection | Session already has selection |
| A5 | Preview slot API: `PreviewSlot(state) -> PlatformPreview` | Keep raster out of commonMain |
| A6 | Theme + density consistency | `AppTheme` already shared |

**Non-goal in A:** byte-identical Skiko vs Android preview.

### WS-B — Session becomes the only UI state owner

| Task | Detail |
|------|--------|
| B1 | Desktop: all Apply/slider/mode → `session.applyConfig` / `applyTextStyle`; drop dual `WatermarkConfigEditor` as primary path |
| B2 | iOS: remove `@Published` watermark field mirrors; CMP collects `session.launchScreenUiStateFlow` / repo flows |
| B3 | Templates: UI always → `TemplateEditor` via session or thin shared use-case wrapper |
| B4 | Export sheet progress always ← `exportJobState` |
| B5 | Effects channel for “OpenSystemPicker”, “Share(path)”, “SaveToPhotos” so CMP UI never imports UIKit/AWT |

**Without B, full CMP UI still forks business behavior.**

### WS-C — Platform hosts become thin

#### Desktop

| Task | Detail |
|------|--------|
| C-D1 | `Window { ProductApp(session, desktopPorts) }` — delete ~1k LOC of bespoke bottom UI over time |
| C-D2 | Ports: File open/save/drop → `MediaRef` list; `DesktopExportPipelinePort`; share substitute |
| C-D3 | Preview: decode export/preview bytes to `ImageBitmap` in desktopMain slot |
| C-D4 | Keep `--headless` as non-UI witness (ok to skip ProductApp) |

#### iOS

| Task | Detail |
|------|--------|
| C-I1 | **One** `ComposeUIViewController { ProductApp(...) }` as production root (retire multi-host swarm for product; keep DEBUG witnesses optional) |
| C-I2 | Swift only: `App`, present PHPicker / `UIActivityViewController` / Photos save when session emits effects |
| C-I3 | Delete SwiftUI Templates strip and status chrome once CMP hosts them |
| C-I4 | XCUITest: retarget accessibility to CMP `testTag`s; keep fixture seam for PHPicker residual (E14) |
| C-I5 | Shrink `WatermarkWorkflow` → thin effect executor or merge into `IosAppServices` |

### WS-D — Resources, a11y, localization

| Task | Detail |
|------|--------|
| D1 | Inventory all `*Strings` / labels used by shells |
| D2 | Short term: English literals in Desktop/iOS edges (current pattern) |
| D3 | Medium: multiplatform resources or shared string table generated from Weblate (policy decision; compose-resources historically avoided) |
| D4 | Preserve XCUITest / accessibility identifiers during iOS migration |

### WS-E — Rendering slots only (do not “fix” into CMP)

| Platform | Preview implementation |
|----------|------------------------|
| Android | Keep `WaterMarkCanvas` + `WatermarkRenderer` |
| Desktop | Skiko decode of session/preview pipeline output **or** live compose-to-bitmap via existing Desktop composer (performance: don’t full re-export every frame) |
| iOS | Decode PNG from session export / lightweight preview render via `IosImageDecoder` |

**Performance gates (carry ADR-0017):**

- No full export on every slider tick; debounce / preview-quality path  
- Heavy work off UI dispatcher  
- Android export/preview algorithm wrap-not-rewrite  

### WS-F — Product / policy re-decisions

Documented exceptions that **block** “UI is CMP and product-complete vs Android”:

| ID | Decision needed |
|----|-----------------|
| E01 | Desktop Launch: add or permanent editor-only |
| E02 | Off-Android gallery: system-only forever vs shared gallery shell with empty/local data |
| E04 | About on iOS/Desktop: add or permanent absence |
| E06 | Multi-image strip off-Android |
| E08 | Templates UI: must live in CMP (capability exists) |

---

## 5. Suggested program phases (UI migration)

Each phase shippable; Android must stay green.

| Phase | Name | Outcome | Rough effort |
|-------|------|---------|--------------|
| **U0** | Owner policy pack | Resolve E01/E02/E04/E06; accept “CMP UI ≠ pixel 1:1 Skiko” | 1–2 days |
| **U1** | Shared Editor controls extraction | Android `BottomView`/`OptionControl` → commonMain composable; Android becomes thin binder | 1–2 weeks |
| **U2** | Desktop ProductApp v1 | Window hosts shared Editor graph + session.applyConfig; delete majority of local control Column | 1–2 weeks |
| **U3** | iOS ProductApp v1 | Single Compose root; Swift only pickers/share/save; delete SwiftUI templates/status | 2–3 weeks |
| **U4** | Nav + optional Launch/About | If U0 says yes: shared nav graph on Desktop/iOS | 1 week |
| **U5** | Export sheet + filmstrip policy | Shared export UI; multi-image if E06 accepted | 1 week |
| **U6** | Strings / polish / XCUITest retarget | a11y, localization strategy, regression | ongoing |

**Order rationale:** U1 unlocks both Desktop and iOS; U2/U3 can parallelize after U1; session work (B1/B2) should ride with U2/U3.

---

## 6. Technical risks

| Risk | Mitigation |
|------|------------|
| Navigation multiplatform maturity | Prefer session-driven screen state (`LaunchScreenUiState`) before full Nav3-CMP if tooling painful |
| compose-resources / Weblate | Keep string params; don’t block UI move on resources |
| iOS XCUITest breakage | Migrate identifiers deliberately; keep fixture seam |
| Performance of reactive preview | Explicit preview refresh policy (Android-like), not per-frame export |
| Scope explosion (“pixel 1:1”) | Track visual parity as **Android-first** then soft align; no CJK byte claims |
| Dual state regressions | Feature flag or slice-by-control: each control switches to session then delete mirror |

---

## 7. Success criteria (definition of “UI migrated”)

**Must all be true:**

1. Desktop production window’s **visible product controls** are commonMain composables (not a long Desktop-only Column of ad-hoc controls).  
2. iOS production UI root is **one** Compose tree; SwiftUI ≤ entry + system sheets.  
3. Config/nav/export UI events go to **`WatermarkSessionViewModel`** (or shared editors it owns), not parallel workflow fields.  
4. Preview/export **pixels** still via platform ports/renderers.  
5. Android behavior/regression gates green (assemble, unit, skill visual checks for touched screens).  
6. Owner-accepted exceptions list updated (only system/render edges remain).

**Explicitly not required:**

- Pixel-identical preview vs Android native renderer  
- In-app gallery on iOS/Desktop unless E02 reopened  
- Play/App Store packaging parity  

---

## 8. Immediate next implementation slice (if owner says go)

**U1 — Shared editor controls extraction (Android-preserving):**

1. Map Android `EditorScreen` `BottomView` + `OptionControl` branches → single `EditorBottomControls` in commonMain taking session callbacks + string/painter bags.  
2. Android `EditorScreen` becomes shell + native preview + resource edge.  
3. No Desktop/iOS product change required yet; unblocks U2/U3.  
4. Gates: `:app:assembleDebug`, unit tests, optional screenshot of Style/Color tab vs production.

---

## 9. Summary

| Layer | Status toward “CMP UI only” |
|-------|-----------------------------|
| Shared **shells & options** | Already ~70% of building blocks |
| Shared **session logic** | Ready enough to drive UI |
| Desktop **product UI** | Shell only; **~1.3k LOC local chrome** still to delete/replace |
| iOS **product UI** | Editor host CMP; **Swift still owns frame + templates + pickers** |
| Platform **render** | Correctly remaining native/Skiko |

**To reach the goal:** not more model migration — **(1) extract Android editor controls into commonMain, (2) put Desktop/iOS behind `ProductApp` + session, (3) shrink Swift/Desktop chrome to system edges, (4) settle product exceptions E01/E02/E04/E06.**

---

*End of research*
