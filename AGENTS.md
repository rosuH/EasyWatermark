# AGENTS.md

This file provides guidance to agents when working with code in this repository. `CLAUDE.md` is a symlink to this file for Claude Code compatibility.

## What this app is

EasyWatermark (`me.rosuh.easywatermark`) — a privacy-focused watermark app that tiles text/image watermarks over photos so they can't be repurposed. Privacy promises that shape engineering decisions: fully offline, zero tracking/stats/crash SDKs, no permissions needed on API 29+ (pre-29 needs storage permission). One Kotlin Multiplatform / Compose Multiplatform codebase ships Android, Desktop (JVM), and iOS. Android is distributed via GitHub Releases, Google Play (paid, same code), F-Droid, Coolapk.

## Architecture

### Module graph

- `:shared` — KMP library (`androidTarget()` + `jvm("desktop")` + `iosArm64()` + `iosSimulatorArm64()`). Owns everything cross-platform: domain models, repositories, Room, editor use-cases, the session state machine, the render engine, and the shared Compose Multiplatform UI.
- `:app` — Android app. Thin platform shell over `:shared`: sole Activity, Android ports, native renderer, MediaStore/decode/save IO, Koin DI.
- `:desktopApp` — Compose Desktop app. Embeds the shared UI shell; `--headless` CLI path for automation.
- `iosApp` — Xcode/SwiftUI shell. Links the dynamic `Shared.framework`, hosts the shared Compose UI, owns PHPicker/Photos/share-sheet system edges.
- `:cmonet` — Material You dynamic-color gate, Android-only, behind `DynamicColorCapability` (ADR-0007). `:baseBenchmarks` / `:macrobenchmark` — perf. `buildSrc` — build constants.

### Layering inside `:shared`

`commonMain` is pure Kotlin + Compose, no Android types:

- `data/model/` — `WaterMark`, `WatermarkMode`, `WatermarkTileMode`, `TextTypeface`, `TextPaintStyle`, `ImageInfo`, `MediaRef`, `UserPreferences`, `FuncType`, `WatermarkConfigRules` (pure config normalization: clamps/conversions/mode transitions).
- `data/repo/` + `data/datastore/` — `WaterMarkRepository` / `UserConfigRepository` (DataStore Preferences) + store-creation helpers.
- `data/db/` — Room KMP: `Template` entity, `AppDatabase`, `TemplateDao`, `TemplateRepository`. Locale-seeded template DBs (`ewm-db-ch.db` / `ewm-db-eng.db`).
- `domain/` — editor use-cases over the repos: `WatermarkConfigEditor`, `OutputPrefsEditor`, `TemplateEditor`.
- `session/` — product state machine: `WatermarkSessionViewModel` + `SessionReducer` + `AppIntent`, with platform capabilities injected as ports (`ExportPipelinePort`, `MediaLibraryPort`). ADR-0017.
- `render/` — engine: `WatermarkGeometry` (image-space sizing: `REF_WIDTH` 1000, gap/diagonal/rotated-AABB math), `WatermarkCellComposer` (`composeTextCell` / `composeIconCell` / `composeOverBackground`), and product composition entry `CommonWatermarkPipeline` (optional last `FontFamily? = null` for Text mode; omitted/`null` = default resolver path; Image mode ignores family). Decode, font file load, encode, and system I/O stay platform edges. Text rastering via `TextRasterEnv` (FontFamily.Resolver + Density) and `TextMeasurer`/`MultiParagraph`.
- `ui/` — shared Compose Multiplatform UI: `Routes` (typed `@Serializable` nav), `ProductShellNav`/`ProductShellHost`, `LaunchScreen`, `EditorScreen` (+ option controls, template sheet, gallery dialog, save/export sheet), `about/`, `theme/`. Strings/drawables via `composeResources`.

Platform source sets supply the edges:

- `androidMain` — byte-faithful DataStore creation (`createPreferencesDataStore(context, name)` with `SharedPreferencesMigration`), Room builder in compatibility mode (framework SupportSQLite + `createFromAsset` locale seeds, no `sqlite-bundled`).
- `desktopMain` — DataStore/Room builders under `~/.easywatermark` (`BundledSQLiteDriver`, locale-aware seed unpack), `DesktopImageDecoder` (AWT `ImageIO` + manual EXIF bake), `DesktopWatermarkComposer`/`DesktopWatermarkTextRenderer` (bundled Noto Latin+CJK via Skiko byte-`Font`), `DesktopExportPipelinePort`, `DesktopIconPersistence`, `DesktopSaveDecision`.
- `iosMain` — DataStore/Room builders under `NSDocumentDirectory` (seeded), `IosImageDecoder` (Skia decode — already bakes EXIF, never re-rotate), `IosTextRasterEnv`/`IosFontLoader` (NSBundle font bytes → Skiko), `IosWatermarkRenderer`, `IosExportPipelinePort`, Swift-facing bridges (`IosWatermarkRenderBridge`, `IosWatermarkConfigBridge`, `IosUserConfigBridge`, `IosTemplateBridge`), `IosSharedComposeHost`/`IosProductRootHost`.

### Runtime wiring

- **Android:** `ComposeMainActivity` (sole `ComponentActivity`) → shared nav/UI (`ui/Routes`, `EditorScreen` …) via Android shells (`ui/AndroidEditorScreen.kt`, `ui/AndroidLaunchScreen.kt`). `MainViewModel` extends the shared session and injects `AndroidExportPipelinePort` + `AndroidMediaLibraryPort`; Koin (`di/AppModule`, `di/RepositoryModule`, `di/DataStoreModule`) wires repos and injected Android edges (default-text provider, SDK-gated tile-id mapper, logger). One-off watermark-icon selection launches `PickVisualMedia` without broad media permission, copies bytes through `AndroidIconPersistence` to internal `filesDir/watermark_icons`, then awaits the shared config commit before deleting the prior app-owned icon. Cold editor entry reads `waterMarkRepo.waterMark.first()`; `MyApp` must not reset the persisted watermark mode during process startup.
- **Desktop:** `desktopApp/Main.kt` + `DesktopWindow.kt` embed the shared Compose shell with `DesktopExportPipelinePort`; `--args='--headless'` runs the bounded open→render→save spine (`DesktopWatermarkFlow`) without a window.
- **iOS:** `iosApp/WatermarkWorkflow.swift` retains one instance of each Kotlin bridge; `ContentView.swift` hosts the shared Compose UI via `IosSharedComposeHost` and keeps PHPicker/save/share in Swift. A `#if DEBUG` UI-test fixture seam (`-uiTestFixtureImage`) drives XCUITest because real PHPicker grid cells are not addressable on the current toolchain. **Current release is single-scene** (`UIApplicationSupportsMultipleScenes=false`, ADR-0020): `IosAppServices` / `defaultIosAppServices()` owns one process-wide Session (route/selection/export/temp); multi-window needs a separately approved scene-scoped Session design.

### Rendering pipeline

Two cell-raster paths share one geometry core (`WatermarkGeometry`) and one tiling semantic (REPEAT grid / CLAMP decal at fractional offset):

- **Native Android** (`:app/render/WatermarkRenderer`): legacy `StaticLayout` text / `BitmapShader` oracle for dual-path measurement and historical goldens — **not** the production path.
- **CommonMain raster** (`CommonWatermarkPipeline` + `WatermarkCellComposer` primitives): Android via `AndroidCommonRaster`. Desktop via `composeRealImage` + `DesktopRenderRequest` (C2). iOS Preview via `IosPreviewRaster` (max-edge 720, no final encode) and Final Export via `IosFinalRenderSpine` + `IosRenderRequest` (full-res JPEG/PNG, explicit sRGB, frozen offset; C3 / issue 22). Platform edges retain decode/encode/I/O. Never claim byte-parity with legacy native goldens; rebaseline per `docs/adr/0010-c2-golden-policy-delta.md`.

EXIF policy: Android decode uses `ExifInterface(InputStream)` on API 23+ and bakes all eight EXIF orientations (including mirrored 2/4/5/7) into pixels in `utils/bitmap/BitmapUtils.kt`; sampled bounds swap only for orientations 5–8. MediaStore rotation is a best-effort fallback only when EXIF is absent or invalid. Desktop decodes via AWT and bakes orientation manually; iOS Skia decode bakes it implicitly. Export strips all EXIF metadata — deliberate privacy feature (ADR-0009).

### Storage & model invariants

- Persisted bytes are compatibility-critical: DataStore store names/keys, `WatermarkTileMode.storageId` (mirrors `Shader.TileMode` ordinals; pre-Android-12 stored DECAL id 3 reads back as REPEAT — Android-only mapper in `TileModeExt.kt`), `TextTypeface`/`TextPaintStyle` `serializeKey()` values, Room schema v1 (`exportSchema=true`, committed under `shared/schemas/me.rosuh.easywatermark.data.db.AppDatabase/1.json`).
- Keep `android.graphics.*`, `android.net.Uri`, repo-nested types out of commonMain models. Android render types live at the edge: `utils/ktx/TileModeExt.kt`, `TextStyleExt.kt`, `MediaRefExt.kt`, `ImageFormatExt.kt`.
- `MediaRef` (`@JvmInline value class`) is the cross-platform reference type (`WaterMark.iconUri`, `ImageInfo.uri`, `imageInfoMap` keys). Deliberate Android `Uri` edges that stay (do NOT "fix"): gallery `Image.uri`, `Action.SystemPickerImageSelected.uriList`, `SaveExportSheet.imageUris`, picker contracts, `BitmapUtils`/`BitmapCache`/`FileUtils` decode signatures.
- Newly picked Android watermark icons persist an app-owned `${applicationId}.fileprovider/watermark_icons/...` `MediaRef`; legacy/external icon refs remain readable but are never deleted by the app-owned cleanup path. The picker `Uri` stays inside the Android host until the private copy succeeds.

## Code navigation

- `app/src/main/java/me/rosuh/easywatermark/`
  - `ComposeMainActivity.kt` — sole Activity: launcher, share-in, crash-recovery gate. `MyApp.kt` — app init (Koin, CMonet).
  - `ui/` — `MainViewModel.kt` (Android session edge), `AndroidEditorScreen.kt` / `AndroidLaunchScreen.kt` (Android shells over shared UI), `compose/` (Android-only controls: gallery dialog, MediaStore thumbnails), `about/AboutViewModel.kt`, `Theme.kt`.
  - `render/` — `WatermarkRenderer.kt` (native measurement oracle), `AndroidCommonRaster.kt` (production common-raster edge), `TextMeasureEnv.kt`.
  - `session/` — `AndroidExportPipelinePort.kt`, `AndroidMediaLibraryPort.kt`. `platform/` — `AndroidDynamicColorCapability`, `AndroidIconPersistence`, `AndroidIconSelectionCoordinator`.
  - `di/` — Koin modules. `utils/bitmap/` — decode (`BitmapUtils`), `BitmapCache`. `utils/ktx/` — Android edge mappers.
- `shared/src/commonMain/kotlin/me/rosuh/easywatermark/` — `data/model|repo|datastore|db`, `domain/`, `session/`, `render/`, `ui/` (see §Layering).
- `shared/src/{androidMain,desktopMain,iosMain}/kotlin/...` — platform store/Room builders, decoders, raster envs, export ports, iOS Swift bridges.
- `shared/src/commonMain/composeResources/` — shared strings (`values(-*)/strings.xml`) and product drawables.
- `desktopApp/src/main/kotlin/me/rosuh/easywatermark/desktop/` — `Main.kt` (entry + headless), `DesktopWindow.kt` (window UI), `DesktopWatermarkFlow.kt` (save spine).
- `iosApp/iosApp/` — `iOSApp.swift`, `ContentView.swift`, `WatermarkWorkflow.swift` (bridge retention + state), `KotlinInterop.swift`, `ImageExport.swift`; `iosAppUITests/` — XCUITest fixture-seam tests.
- `buildSrc/src/main/kotlin/Apps.kt` — compileSdk/targetSdk 36, minSdk 23, JVM toolchain 17.
- Tests: `app/src/test/` (Robolectric unit/goldens), `app/src/androidTest/` (instrumented goldens), `shared/src/commonTest|desktopTest|iosTest/`.

## Strings / i18n / product drawables

- **Product UI labels:** `stringResource(Res.string.*)` / `FuncType.label()` from `shared/src/commonMain/composeResources/values(-*)/strings.xml`.
- **Product UI icons/logo:** `painterResource(Res.drawable.*)` / `SharedProductDrawables` / `FuncType.iconPainter()` from `composeResources/drawable/` (not hand-drawn `SharedActionIcons`, not BrandLogo expect/actual).
- **Weblate (until post-`master` retarget):** still owns `app/src/main/res/values-*/strings.xml`.
- **Agents adding keys:** dual-write default EN to both string trees. Never hand-edit non-default locales.
- **Packaging:** `:shared` `android { androidResources { enable = true } }`; do **not** substitute `org.jetbrains.compose.components.*` to AndroidX in `:app`.
- **Do not** put watermark fonts or Room seed DBs into composeResources (existing platform boundaries).
- **Non-Compose string reads:** use `sharedString(Res.string.*)` (Toast, Intent chooser, DataStore default text). Prefer `stringResource` inside Composables.
- **Still Android-local (OK):** Material theme color attrs (`ContextExtension` / `R.color` / `R.attr`); launcher mipmaps; unused legacy XML drawables in `app/res` until cleaned.

## Commands

- Build debug: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/`. Debug applicationId is `me.rosuh.easywatermark.debug`, so it installs alongside the production app — useful for side-by-side parity checks.
- Unit tests: `./gradlew :app:testDebugUnitTest`; shared: `./gradlew :shared:desktopTest`; iOS: `:shared:iosSimulatorArm64Test`. Instrumented: `./gradlew :app:connectedDebugAndroidTest`.
- Desktop: `./gradlew :desktopApp:run` (window), `:desktopApp:run --args='--headless'` (automation), `:desktopApp:createDistributable` (unsigned app image; needs Corretto/Zulu, NOT Homebrew OpenJDK, CMP#3107). **J3:** app-data is OS-native (`DesktopAppPaths`) with legacy `~/.easywatermark` copy-forward; chooser formats via `DesktopImageFormats` (WebP only if ImageIO can decode). Unsigned `createDistributable` is **not** a signed three-OS release (DMG/MSI/DEB + signing residual).
- No linter is wired up (spotless/ktlint blocks in root `build.gradle.kts` are commented out). Match existing style by hand.
- Release builds are minified (R8, `proguard-rules.pro` + `coroutines.pro`); CI (`.github/workflows/pr_pre_check.yml`) on PRs: Ubuntu `build` (`:app:assembleDebug` + `:shared:desktopTest` + non-strict `:app:testDebugUnitTest` + J2 backup-policy structural check; lintDebug fail-open) and permanent macOS `ios` job (J1: `:shared:iosSimulatorArm64Test` + `iosApp` generic iOS Simulator `xcodebuild`, `CODE_SIGNING_ALLOWED=NO`). Release: `assembleRelease` + baseline-prof presence check; physical witness residual.
- **Dependency qualification (J4):** stable-by-default; prerelease only with a named reason; **one upgrade slice at a time** (never bulk CMP+Material+Nav+DataStore). Record rollback HEAD before any catalog promotion.
- **iOS framework surface (J5):** classic ObjC dynamic `Shared.framework` only — do **not** migrate production to Alpha Swift export. Prefer `internal` for implementation-only iosMain; public growth requires review.
- Android CLI 1.0 is installed (`android`): `android docs search '<query>'` queries an offline KB mirroring developer.android.com AND the JetBrains KMP docs (then `android docs fetch kb://...`); `android emulator list/start`, `android screenshot`, `android layout` (UI tree as JSON — faster than screenshots), `android run`.

## Conventions for agents

- **Docs-with-code gate:** every milestone PR ships its context delta (ADR / CONTEXT.md / AGENTS.md updates) or states "no doc impact" in the PR description.
- **Parity source of truth:** the Android production release v2.10.0 (built from `master`). Android debug aligns to it first, then Desktop/iOS align to that Android baseline with explicit platform exceptions. Per-layout migrations follow the 10-step skill in `skills/migrate-xml-views-to-jetpack-compose/` (screenshot baseline → migrate → visual diff → delete XML).
- **CMP-first UI:** new product UI goes in `shared/commonMain/ui/`. Platform-native UI only for app/window entry, picker/share/save/permission system UI, capability glue, and renderer surfaces. No `ViewInfo` / `AndroidView`-bridged renderer.
- **No shared-ViewModel/reducer/IO `expect` extraction without a named real off-Android consumer or an explicit owner decision.**
- **DataStore creation is plain per-platform functions — never a commonMain `expect`/`actual`.** Android creation stays byte-faithful (`PreferenceDataStoreFactory.create(produceFile, migrations)`), and does not route through the common helper.
- **No direct `CMonet` in migrated Compose consumers** — use `DynamicColorCapability`. Absorbing `:cmonet` is an owner-gated follow-up (ADR-0007).
- **Golden gates:** the strict pinned-environment FNV gate (`WATERMARK_GOLDEN_STRICT=true`) is local-only — never re-add it to PR CI (ADR-0010). Verify renders by VIEWING screenshots, not byte sizes.
- **Decision forks get an ADR** in `docs/adr/` (use the `grill-with-docs` flow); status `Proposed` until the developer signs off.
- When unsure about an Android/KMP API, prefer `android docs search` over training data.
- **Clean up heavy processes you start:** automated emulator sessions boot with `-no-window` when interaction isn't needed; stop Gradle with `./gradlew --stop`; cap automated builds with `--max-workers=8`. **Do not shut down already-live Android or iOS simulators** used for ongoing migration work unless the owner explicitly orders it (standing order 2026-07-11). Sustained emulator+build load has frozen this machine's input devices before — warn the developer before kicking off long heavy local automation.

## Agent skills

### Use skills proactively (required stance)

Skills are not optional reference shelves — they are the preferred playbooks for matching work. **Load and follow the relevant skill before improvising.**

1. **Match → open → follow.** At task start (or as soon as the task shape is clear), pick the best skill from the catalogs below, **read its `SKILL.md`**, and execute its workflow/checklists. Do not recreate a migration, edge-to-edge, Navigation 3, testing harness, R8, or CameraX plan from training data when a skill already owns that path.
2. **Read references when the skill points at them.** Many Android skills ship `references/` (recipes, release notes, migration tables). Open those files instead of guessing API surface.
3. **Prefer skill + offline docs over memory.** For Android/KMP APIs: skill guidance first, then `android docs search` / `android docs fetch` (see `android-cli`). Training data is the fallback, not the primary source.
4. **Compose perf has a skill path too.** Jank, skip/recompose mysteries, stability, lazy scroll, baseline profiles, HotSwan → load the matching Compose skill before ad-hoc Layout Inspector thrashing.
5. **Name the skill in your plan.** When a task maps to a skill, say which skill you are following (e.g. “following `migrate-xml-views-to-jetpack-compose` step 4”) so reviews can check skill fidelity.
6. **Skip only when truly off-catalog.** Pure domain/session/render work with no platform-skill match, or an owner-explicit shortcut, may skip. If unsure, open the closest skill and use what applies.
7. **Refresh, do not hand-edit upstream skills.** Official Google Android skills are managed by the `android` CLI. Update with `android update` (CLI) and `android skills add --all --project=.` (or a named skill). Do not patch `SKILL.md` / `references/` by hand unless the owner asks for a repo-local fork.

**Install layout:** Google Android skills are mirrored under `skills/`, `.claude/skills/`, and `.agents/skills/` (same content after `android skills add`). Compose performance / HotSwan skills live under `.agents/skills/` (and are symlinked from `.claude/skills/`). Any of these roots is fine to open; prefer the path your agent already resolved.

### Compose skills (`.agents/skills/`)

- **Performance audit & diagnosis:** `auditing-compose-performance` (four-phase audit orchestration), `debugging-recompositions` (Layout Inspector counts), `diagnosing-compose-stability` (compiler reports), `understanding-stability-inference`, `tracing-recompositions-at-runtime`, `visualizing-recomposition-cascades`.
- **Fixes & idioms:** `stabilizing-compose-types`, `deferring-state-reads`, `ordering-modifier-chains`, `using-efficient-effects`, `using-strong-skipping-correctly`, `migrating-to-modifier-node`, `avoiding-subcomposition-pitfalls`.
- **Lazy layouts:** `optimizing-lazy-layouts`, `configuring-lazy-prefetch`.
- **Measurement & CI:** `generating-baseline-profiles`, `testing-compose-in-release-mode`, `enforcing-stability-in-ci`, `using-stability-analyzer-ide-plugin`.
- **Hot reload (Compose HotSwan):** `setting-up-compose-hotswan`, `iterating-with-ai-and-mcp`, `preserving-state-across-reloads`, `understanding-hot-reload-limits`.

### Android skills (`skills/` · also `.claude/skills/` · `.agents/skills/`)

- **Daily drivers:** `android-cli` (`android` CLI + offline docs KB), `migrate-xml-views-to-jetpack-compose` (10-step parity migration), `edge-to-edge`, `navigation-3`, `testing-setup`, `styles` (experimental Compose Styles API).
- **Build & perf:** `agp-9-upgrade` (**not** for KMP modules), `r8-analyzer`, `perfetto-trace-analysis`, `perfetto-sql`.
- **Wear / TV / media (when relevant):** `wear-compose-m3`, `jetpack-compose-m3` (Wear Material3), `leanback-to-compose-tv-migration`, `media3-cast-integration`.
- **Platform / Play / security:** `camerax`, `camera1-to-camerax`, `adaptive`, `appfunctions`, `android-intent-security`, `engage-sdk-integration`, `play-billing-library-version-upgrade`, `play-policy-insights`, `verified-email`, `display-glasses-with-jetpack-compose-glimmer`.

**High-value triggers for this repo (open the skill early):**

| Situation | Skill |
|---|---|
| XML → Compose layout / parity migration | `migrate-xml-views-to-jetpack-compose` |
| System bars, IME, cutout, obscured UI | `edge-to-edge` |
| Nav graph, back stack, deep links, multi-pane scenes | `navigation-3` |
| Adaptive / large-screen / foldable layout | `adaptive` |
| Unit / UI / screenshot / e2e harness setup | `testing-setup` |
| Emulator, screenshots, layout tree, docs KB | `android-cli` |
| R8 / keep rules / size | `r8-analyzer` |
| Trace jank or latency | `perfetto-trace-analysis` (+ `perfetto-sql`) |
| Intent export / redirection / PendingIntent | `android-intent-security` |
| Play policy / Data Safety audit | `play-policy-insights` |
| Scroll jank / recompose / stability | Compose skills above (`auditing-compose-performance` entry point) |

### Ops pointers

- **Issue tracking:** there is no repository-local `.scratch` tracker. Use the current user request, Git/PR state, `codex-goal-v2.md`, and bounded ACSP handoffs as described in `docs/agents/issue-tracker.md`. No repository evidence archive.
- **Triage labels:** `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix` (`docs/agents/triage-labels.md`).
- **Domain docs:** vocabulary in `docs/CONTEXT.md`, decisions in `docs/adr/` (`docs/agents/domain.md`).
- **Migration history:** high-level notes in `docs/migration-log.md`; `task_plan.md` / `findings.md` / `progress.md` at repo root are historical evidence only — do not read at session start, do not update.
