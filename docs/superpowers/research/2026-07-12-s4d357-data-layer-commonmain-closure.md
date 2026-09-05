# S4d-357 — Phase A data-layer commonMain closure audit (read-only)

**Date:** 2026-07-12
**Type:** source-cited inventory / A4 readiness — **no product code**
**Status:** accepted docs audit (evidence revised for path precision). **Not** Phase A complete; **not** §9 DoD.

**Question:** Is the Phase A **data layer** (repos, editors, central models/rules) closed in `commonMain`, and does any residual qualify for further pure extraction under [codex-goal-v2.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal-v2.md) §6.12?

**Verdict: data-layer consumer surface is closed for pure extraction.** Repos + three editors + central models/rules already live in `shared/commonMain` with named multi-platform production consumers. **A4 extraction is NO-GO now** — no residual candidate meets §6.12 (two named production platforms + platform-neutral I/O). **Does not** prove end-to-end persisted-byte identity, final all-platform product integration, Phase A complete, or §9 DoD.

**Permission:** inventory only. **No** move of native renderer, `Uri`/`Bitmap`, or platform store/DB factories.

---

## 1. commonMain data layer (source)

### 1.1 Repositories

| Type | Path | Role |
|---|---|---|
| `WaterMarkRepository` | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/data/repo/WaterMarkRepository.kt` | DataStore Preferences consumer for watermark config; in-memory `imageInfoMap` over `MediaRef` |
| `UserConfigRepository` | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/data/repo/UserConfigRepository.kt` | Output format + compress level prefs |
| `TemplateRepository` | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/data/repo/TemplateRepository.kt` | Room DAO façade for templates |

### 1.2 Editors (use-cases)

| Type | Path | Role |
|---|---|---|
| `WatermarkConfigEditor` | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/domain/WatermarkConfigEditor.kt` | Suspend `update*` over `WaterMarkRepository` (+ clamps via rules) |
| `OutputPrefsEditor` | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/domain/OutputPrefsEditor.kt` | `save(format, level)` over `UserConfigRepository` |
| `TemplateEditor` | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/domain/TemplateEditor.kt` | add/update/delete + `isDaoNull()` over `TemplateRepository` |

### 1.3 Central models / rules (non-exhaustive but authoritative set)

Directory: `shared/src/commonMain/kotlin/me/rosuh/easywatermark/data/model/`

- Config/domain: `WaterMark.kt`, `WatermarkMode.kt`, `WatermarkTileMode.kt`, `TextTypeface.kt`, `TextPaintStyle.kt`, `WatermarkConfigChange.kt`, `FuncType.kt`, `WatermarkConfigRules.kt`
- Identity/IO-neutral: `MediaRef.kt`, `ImageInfo.kt`, `ImageFormat.kt`, `UserPreferences.kt`, `JobState.kt`, `Result.kt`
- Entity / Room: `data/model/entity/Template.kt`, `data/db/AppDatabase.kt`, `data/db/dao/TemplateDao.kt`, `data/db/DateConverter.kt` (same `shared/src/commonMain/kotlin/me/rosuh/easywatermark/` tree)

Geometry constants used with config (not a repo): `shared/src/commonMain/kotlin/me/rosuh/easywatermark/render/WatermarkGeometry.kt`.

### 1.4 Named real production consumers (verified)

**Android**

| Consumer | Path | Uses |
|---|---|---|
| Koin wiring | `app/src/main/java/me/rosuh/easywatermark/di/RepositoryModule.kt`, `app/src/main/java/me/rosuh/easywatermark/di/DataStoreModule.kt`, `app/src/main/java/me/rosuh/easywatermark/di/AppModule.kt` | Constructs all three repos; injects Android store/DB edges |
| `MainViewModel` | `app/src/main/java/me/rosuh/easywatermark/ui/MainViewModel.kt` | Injects repos; owns `WatermarkConfigEditor`, `OutputPrefsEditor`, `TemplateEditor` |
| `AboutViewModel` | `app/src/main/java/me/rosuh/easywatermark/ui/about/AboutViewModel.kt` | `WaterMarkRepository` for bounds / related prefs |
| Editor UI constants | `app/src/main/java/me/rosuh/easywatermark/ui/EditorScreen.kt` | `WaterMarkRepository.MAX_*` ranges |

**Desktop**

| Consumer | Path | Uses |
|---|---|---|
| `DesktopWindow` | `desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWindow.kt` | `WatermarkConfigEditor`, `OutputPrefsEditor`, `TemplateRepository`/`TemplateEditor`, `WatermarkConfigRules` |
| `DesktopWatermarkFlow` | `desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/DesktopWatermarkFlow.kt` | Builds/uses `WaterMarkRepository`, `UserConfigRepository`, `WatermarkConfigEditor` |
| Headless / entry | `desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/Main.kt` | `UserConfigRepository`, `WaterMarkRepository`, editors, `TemplateRepository`/`TemplateEditor` witnesses |

**iOS**

| Consumer | Path | Uses |
|---|---|---|
| `IosWatermarkConfigBridge` | `shared/src/iosMain/kotlin/me/rosuh/easywatermark/data/repo/IosWatermarkConfigBridge.kt` | `WaterMarkRepository` + `WatermarkConfigEditor` |
| `IosUserConfigBridge` | `shared/src/iosMain/kotlin/me/rosuh/easywatermark/data/repo/IosUserConfigBridge.kt` | `UserConfigRepository` |
| `IosTemplateBridge` | `shared/src/iosMain/kotlin/me/rosuh/easywatermark/data/repo/IosTemplateBridge.kt` | `TemplateRepository` + `TemplateEditor` |
| `WatermarkWorkflow` | `iosApp/iosApp/WatermarkWorkflow.swift` | Production Swift path over bridges |
| `ContentView` | `iosApp/iosApp/ContentView.swift` | Production UI calling workflow (text/degree/tile/alpha/color/size/gaps/typeface/style/icon/templates) |

**§6.12 note:** the **three editors already have ≥2 production platforms**. Further A4 “extract the same thing again” is not available; new pure extraction needs a **new** dual-consumed platform-neutral rule not yet shared.

---

## 2. Retained platform edges (path + reason)

| Edge | Path(s) | Why retained |
|---|---|---|
| Android DataStore factory | `shared/src/androidMain/kotlin/me/rosuh/easywatermark/data/datastore/CreateDataStore.android.kt` (`createPreferencesDataStore`); wired by `app/src/main/java/me/rosuh/easywatermark/di/DataStoreModule.kt` | `Context` + `SharedPreferencesMigration`; byte-faithful legacy prefs; **not** a commonMain `expect` |
| common okio path helper | `shared/src/commonMain/kotlin/me/rosuh/easywatermark/data/datastore/CreateDataStore.kt` | Shared factory **API** (`createPreferencesDataStore(producePath)`, driver-free `createDataStore(storage)`), not Android creation path |
| Desktop DataStore | `shared/src/desktopMain/kotlin/me/rosuh/easywatermark/data/datastore/CreateDataStore.desktop.kt` | `createUserConfigDataStore` / `createWaterMarkDataStore`; platform FS |
| iOS DataStore | `shared/src/iosMain/kotlin/me/rosuh/easywatermark/data/datastore/CreateDataStore.ios.kt` | `NSDocumentDirectory` + okio path |
| Android Room builder | `shared/src/androidMain/kotlin/me/rosuh/easywatermark/data/db/TemplateDatabaseBuilder.android.kt` | Framework SupportSQLite + `createFromAsset` seeds |
| Desktop Room builder / seed | `shared/src/desktopMain/kotlin/me/rosuh/easywatermark/data/db/TemplateDatabaseBuilder.desktop.kt`, `shared/src/desktopMain/kotlin/me/rosuh/easywatermark/data/db/TemplateDatabaseSeeds.kt` | `BundledSQLiteDriver` + resource seed copy |
| iOS Room builder | `shared/src/iosMain/kotlin/me/rosuh/easywatermark/data/db/TemplateDatabaseBuilder.ios.kt` | Bundled driver + NSBundle seed path |
| Desktop icon persistence | `shared/src/desktopMain/kotlin/me/rosuh/easywatermark/data/repo/DesktopIconPersistence.kt` | App-private icon file copy/prune |
| iOS icon persistence | `shared/src/iosMain/kotlin/me/rosuh/easywatermark/data/repo/IosIconPersistence.kt` | Helper-owned icon files under documents dir |
| iOS Swift bridges | `shared/src/iosMain/kotlin/me/rosuh/easywatermark/data/repo/IosWatermarkConfigBridge.kt`, `IosUserConfigBridge.kt`, `IosTemplateBridge.kt`; `shared/src/iosMain/kotlin/me/rosuh/easywatermark/render/IosWatermarkRenderBridge.kt` | Suspend/value edges; no public Swift `Flow` |
| Android tile/SDK mapper | `app/src/main/java/me/rosuh/easywatermark/utils/ktx/TileModeExt.kt` | Injected into `WaterMarkRepository` for pre-12 DECAL id 3 → REPEAT |
| Android Bitmap / Uri IO | `app/src/main/java/me/rosuh/easywatermark/utils/bitmap/BitmapUtils.kt`, `app/src/main/java/me/rosuh/easywatermark/utils/bitmap/BitmapCache.kt`; deliberate `Uri` in `Action` / gallery / save paths | Decode/export/MediaStore; **not** commonMain |
| Android DI / resource UI / state | `app/src/main/java/me/rosuh/easywatermark/di/*`, `app/src/main/java/me/rosuh/easywatermark/data/model/FuncTitleModel.kt`, ViewModels, `Action` | Koin + resource ids + platform IO |

---

## 3. Non-candidates (do not extract “for purity”)

| Item | Path | Finding |
|---|---|---|
| `TextMeasureEnv` / `WatermarkTextMeasurer` | `app/src/main/java/me/rosuh/easywatermark/render/TextMeasureEnv.kt`, `app/src/main/java/me/rosuh/easywatermark/render/AndroidTextMeasureEnv.kt`; product use via `app/src/main/java/me/rosuh/easywatermark/render/WatermarkRenderer.kt` | **Android native text measurement seam only.** Moving it would reopen Android text production / raster decisions (S4d-17 native stay; ADR-0004 family). **Must not reopen.** CommonMain raster env is separate `shared/src/commonMain/kotlin/me/rosuh/easywatermark/render/TextRasterEnv.kt` (Desktop/iOS). |
| `MemorySettingRepo` | Removed post-L0 from the Android repo/DI/ViewModel constructor surface | It was an empty class with no members or behavior and no consumer. Removing the dead wiring is behavior-neutral; it is not an extraction candidate. |
| `Action` (declared in `LaunchScreen.kt`) | `app/src/main/java/me/rosuh/easywatermark/ui/LaunchScreen.kt` `sealed class Action`; **live** dispatch: `app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt` → `MainViewModel.process` | **Not** “unused”: all current sealed variants have `process` branches. **Not** a commonMain candidate: carries `Uri`, `ContentResolver`, `FuncTitleModel`, gallery `Image`. At most future **dead-branch** cleanup if a variant loses producers — **not** A4 pure extraction. |

---

## 4. A4 verdict ([codex-goal-v2](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal-v2.md) §6.12)

**NO-GO for new pure A4 extraction now.**

Reasons:

1. Repos + editors + central config models/rules **already** commonMain with **named Android + Desktop + iOS** production consumers.
2. Remaining residuals are **platform factories**, **bridges**, **icon FS**, **Bitmap/Uri**, **DI/resource UI**, or **renderer-native** — none are platform-neutral pure state transitions lacking a shared home.
3. §6.12 requires **≥2 named production platforms** and **no platform types** in the extracted I/O. Nothing residual satisfies that without inventing consumers or reopening closed edges.
4. Tests / DEBUG witnesses / theoretical callers **do not count**.

**Reopen A4 only when** a real dual-platform production consumer appears for a **new** pure rule/state transition not already shared.

---

## 5. Explicit limits (non-claims)

This audit **does not** prove:

- End-to-end **persisted-byte** identity across Android/Desktop/iOS stores
- Final **all-platform product integration** or 1:1 UI
- **Phase A complete**
- **§9 Definition of Done** / PR #358 merge readiness

It also **does not** authorize:

- Android native renderer draw-swap
- Moving `Uri`/`Bitmap`/`ContentResolver` into commonMain
- Collapsing platform DataStore/Room factories into a single `expect/actual`

---

## 6. Relation to other open gates

| Gate | Status |
|---|---|
| S4d-353 Compose/Skiko owner A/B/C/defer | Open (iOS text/templates UI) |
| S4d-348/350/354/356 AndroMeld visual About/SaveExport | NOT VERIFIED (mirror control) |
| S4d-357 data-layer A4 | **NO-GO** (this note; accepted docs audit only) |

---

## 7. Verification

| Check | Result |
|---|---|
| Source citations | Exact repo-relative paths grepped/read in tree |
| Product code / deps / Compose/Skiko | Untouched |
| Protected `docs/superpowers/research/2026-07-11-project-branch-goals-progress.md` | Not edited |
| S4d-344 paths | Not edited |
| Tests run | **None claimed** |
