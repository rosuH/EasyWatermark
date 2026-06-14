# Compose Multiplatform (CMP) Migration Plan

**Date:** 2026-06-12 (v1.1 — revised after two-agent adversarial review)
**Status:** Ready for developer review
**Builds on:** `docs/superpowers/specs/2026-04-18-compose-migration-design.md` and `docs/superpowers/plans/2026-04-18-compose-migration-plan.md` (the in-flight View→Compose migration, milestones M0–M6)
**Evidence base:** `docs/superpowers/research/2026-06-12-cmp-readiness-audit.json` (13-agent audit + research bundle: 8 codebase dimensions, 5 official-docs research tracks, with source URLs)

---

## Goal

Take EasyWatermark from a single-platform Jetpack Compose app to a Compose Multiplatform app running on **Android + Desktop (JVM) + iOS**, sharing UI, state, data, and — critically — the watermark rendering engine in `commonMain`, while never breaking the shipping Android app.

## Product Framing

- The watermark engine is the product. Everything else (picking, saving, sharing) is platform plumbing.
- The View→Compose migration in flight is not a detour — done with CMP-shaped choices, it **is** phase one of the CMP migration.
- Desktop is the cheap second platform (no permission model, trivial file IO, fast dev loop) and validates the engine portability before the expensive iOS bring-up. Whether desktop ships publicly (packaging, signing, notarization) is a separate decision — see Decisions Needed #7.
- This is a long haul for a solo part-time developer: C1+C2+C3 is realistically the bulk of the work, measured in months of evenings, not weeks. The plan is sliced so Android stays releasable the whole way.
- Web/Wasm is explicitly out of scope for this plan (revisit after iOS ships).

## Non-Goals

- No visual redesign.
- No Navigation 3 adoption in this plan (see Decision D3).
- No AGP 9 upgrade during the restructure (see Decision D2 and Risk R8).
- No feature additions beyond what platform parity demands.

---

## Verified Version Baseline (as of 2026-06-12)

> Note: two research agents disagreed on the CMP "production baseline" (1.10.3 vs 1.11.x). Resolution: target the **latest stable 1.11.x** (1.11.1 at research time; concurrent iOS rendering default since 1.11.0); re-verify the exact version when C4 starts.

| Component | Today | CMP target | Note |
|---|---|---|---|
| Kotlin | 2.3.20 | keep | Compatible with current CMP per JetBrains compat docs ("latest CMP is always compatible with latest Kotlin") |
| AGP | 8.13.2 | **hold on 8.x** | `kotlin { androidTarget {} }` works; AGP 9 forces `com.android.kotlin.multiplatform.library`, currently bitten by CMP-9547 (compose resources not packaged into APK). Re-check at the C4 gate (D2) |
| Compose | androidx BOM 2026.03.01 | org.jetbrains.compose **1.11.x** | iOS stable since CMP 1.8.0 |
| compileSdk / targetSdk | 36 | keep | |
| minSdk | 23 | keep (see D8) | pre-Q save path lives entirely in the Android actual; does not pollute common code |
| Room | 2.8.4 | keep coordinates | Native KMP artifact; per-target KSP (see D6); do NOT take Room 3 alpha (`androidx.room3`) |
| DataStore | `datastore-preferences` 1.2.1 | **rename to** `datastore-preferences-core` 1.2.1 in commonMain | non-core artifact stays androidMain; expect `createDataStore()` per platform |
| Lifecycle/ViewModel | 2.10.0 | keep coordinates in commonMain | KMP-stable; verify at execution that `lifecycle-runtime-ktx`→`lifecycle-runtime` merge covers our usage (audit agents disagreed on a detail) |
| Navigation | androidx navigation-compose **2.9.7** | `org.jetbrains.androidx.navigation:navigation-compose:**2.9.2**` | JetBrains mirror lags androidx (2.9.2 < 2.9.7) — a deliberate, slight downgrade; no API breakage expected for our NavHost usage, but test at swap time |
| Koin | 4.2.1 | keep | + `koin-compose-viewmodel` in commonMain; `koin-android` stays androidMain |
| Coil | `io.coil-kt:*` 2.7.0 | **Coil 3: `io.coil-kt.coil3:coil-compose` + `io.coil-kt.coil3:coil-network-ktor3`** (group ID changes!) | Also replaces Glide 5 entirely; drop `coil-svg` (see C3.1) |

**Library replacement map** (full rationale in the audit JSON, `research.ecosystem`):

| Need | Today | CMP answer | Maturity |
|---|---|---|---|
| Image loading | Coil 2 + Glide 5 | Coil 3 — `io.coil-kt.coil3:coil-compose` + `coil-network-ktor3` | stable |
| SVG decode | coil-svg | **drop** — icons are compiled vector drawables → compose-resources `ImageVector`, no runtime SVG needed; if one appears, gate on the known iOS Skia SVG parser crash | n/a |
| Image picking | `PickVisualMedia` | FileKit (`io.github.vinceglb:filekit-dialogs-compose`) — wraps PickVisualMedia / PHPicker / NSOpenPanel | stable |
| Save to photo library | MediaStore insert | thin expect/actual `PhotoLibraryStore` (MediaStore / PHPhotoLibrary / file dialog) — FileKit does not cover PHPhotoLibrary | manual, ~50 LOC/platform |
| Permissions | accompanist-permissions | thin expect/actual (recommended) or moko-permissions `permissions-gallery` | stable |
| EXIF orientation read | androidx.exifinterface | Ashampoo Kim (`com.ashampoo:kim`) or per-platform actual; Coil 3 auto-applies orientation for preview | beta (only maintained KMP EXIF lib) |
| Source pre-compression | id.zelory:compressor | delete; reimplement as decode→downscale→encode on the engine's own expect/actuals (~20 LOC) | manual |
| Palette (bg color) | androidx.palette | kmpalette `com.kmpalette:kmpalette-core:3.1.0` (direct port) — or drop the feature | stable |
| Color picker UI | skydoves colorpickerview (View) | skydoves **colorpicker-compose** 1.2.0 (same vendor, full KMP) | stable |
| Share sheet | ACTION_SEND + FileProvider | expect/actual (`UIActivityViewController` on iOS — iPad needs popover anchor; "Reveal in Finder" on Desktop) | manual |
| Parcelable nav args | kotlin-parcelize | `@Serializable` routes (kotlinx.serialization); Parcelize may remain androidMain-only | stable |
| Clipboard / open URL / haptics | platform APIs | `LocalClipboardManager` / `LocalUriHandler` / `LocalHapticFeedback` in commonMain (iOS haptics may no-op on some types) | stable |
| Dynamic color | :cmonet module | delete from Compose path; expect/actual `isDynamicColorAvailable()` (Android actual keeps OEM allowlist; iOS/Desktop return false); static `LightColorScheme`/`DarkColorScheme` fallback already exists in Theme.kt | manual |
| Crash recovery | `MyApp.catchException` + recovery UI in legacy `MainActivity` | port recovery screen to Compose; crash handler becomes a platform capability (Android keeps current handler; iOS/Desktop no-op or native hooks) | manual (see C1.6) |

---

## Key Decisions

### D1 — Platform order: Android → Desktop → iOS
Desktop (JVM target) comes online with the KMP restructure almost for free, has no permission model, and gives a fast loop to validate the shared engine. iOS follows with its own dedicated phase (photos stack, memory, fonts). Rationale: every "hard" CMP risk in the audit (memory, photo library, share-in) is iOS-specific; don't let it block proving the architecture. In this plan desktop is a **validation target**; public desktop distribution is Decision #7.

### D2 — Module shape: single shared module + thin entry points; module-plugin choice is a C4-gate decision
Per the official JetBrains config guidance and the Android KMP template: one `:shared` KMP module (commonMain + androidMain + iosMain + desktopMain) consumed by a thin `:app` (Android, `com.android.application`), `:desktopApp`, and `iosApp/` (Xcode). Start single-module; umbrella modules only if/when it grows.

**Module plugin:** today the choice is `com.android.library` + `kotlin-multiplatform` on AGP 8.13.2 (works with Kotlin 2.3.20), because AGP 9's `com.android.kotlin.multiplatform.library` is bitten by CMP-9547 (compose resources missing from APK). But AGP 10 removes the legacy path on a ~H2-2026+ horizon, which would force a second module-plugin migration. **Therefore: re-verify CMP-9547 immediately before C4 and pick the plugin ONCE** — if fixed, build `:shared` directly on the AGP 9 stack and fold the AGP upgrade into C4; if not, ship on AGP 8.x and schedule the plugin migration as its own later task (Risk R8/R17).

### D3 — Navigation: stay on Nav2, JetBrains coordinate, @Serializable routes
`org.jetbrains.androidx.navigation:navigation-compose:2.9.2` in commonMain is API-compatible with today's navigation-compose 2.9.7 usage (note the mirror lags androidx — slight version downgrade, test at swap). Navigation 3's runtime is KMP-stable but its UI artifact (`NavDisplay`) is Android-only with the CMP mirror at 1.0.0-alpha06; Google's own migration guide describes Nav3 as an atomic rewrite, deep links unsupported. Revisit Nav3 after the CMP mirror stabilizes — the official `navigation-3` skill is already installed in `.claude/skills/` for that day. Prerequisite work now: typed `@Serializable` route classes (also kills Parcelize-in-common).

### D4 — Rendering engine: rewrite once in commonMain compose-ui graphics — extracted first, swapped second
This is the central decision, and the audit settles the old plan's open question M6 ("Rendering Evaluation"). The fable deep-dive found:

- The portable core is small (~400–500 LOC): two cell builders (`buildTextBitmapShader`/`buildIconBitmapShader`, companion functions in `WaterMarkImageView`) + one composition rule + one scale rule.
- Every drawing primitive maps ~1:1 to common APIs: `StaticLayout`→`TextMeasurer`, `BitmapShader(REPEAT)`→`ImageShader(TileMode.Repeated)` in a `ShaderBrush`, offscreen `Bitmap+Canvas`→`ImageBitmap(w,h)+Canvas+CanvasDrawScope`, `canvas.rotate`→`DrawScope.rotate`, touch→`pointerInput`, animators→`Animatable`. Only decode/encode/orientation/storage need expect/actual. (Caveat: a "headless" `TextMeasurer` still needs `createFontFamilyResolver(context)` on Android — the engine bootstrap is per-platform even though the engine is common.)
- The rewrite retires two real design debts: composition logic duplicated between `WaterMarkImageView.onDraw` and `MainViewModel.generateImage`, and the `ViewInfo` coupling where **export scale = 1/preview-view MSCALE_X — and `scaleY` is also (incorrectly) derived from MSCALE_X** (`MainViewModel.kt:323-324`); the new renderer must compute both axes independently. On a resizable Desktop window the view-coupled scale is a correctness bug, not a quirk.
- **Sequencing honors the old plan's safety rule.** The old plan said "do not rewrite WaterMarkImageView first" because of regression risk. We keep that spirit by splitting:
  - **C2a — extract, zero behavior change:** create `WatermarkRenderer` and make BOTH the existing `WaterMarkImageView` AND `generateImage` delegate to it. No AndroidView swap, no sizing change, nothing visible. Golden tests pin old↔new equality on the same platform.
  - **C2b — swap and re-spec:** replace the AndroidView preview with a Compose `Canvas`, move to image-space sizing (config migration), delete `WaterMarkImageView`/`WaterMarkShader`/`ViewInfo`.
  - The **golden harness is built BEFORE C2a, against the current engine** (see C1.7) — the safety net must exist and be trusted before the thing it protects changes.
- **Deliberate behavior changes requiring sign-off (C2b):** watermark sizing moves from view-px to image-space units; persisted `textSize` needs a one-time config migration. Icon scaling moves from nearest-neighbor (`filter=false`) to explicit `FilterQuality` (pin `None` for parity or accept smoother output and re-baseline).
- **Effort honesty:** C2 as a whole (harness + extraction + parity debugging + gesture rewrite + config migration) is the long pole — multi-week for a part-time solo dev, not "days". Golden-parity debugging is iterative; the C2a/C2b split exists precisely so each step is a reviewable, shippable PR in the mentor loop.

**Golden test strategy (precision matters here):**
1. **Regression goldens (strict):** old-engine vs new-engine on the SAME platform (Android↔Android) — near-pixel-exact tolerance. This is the C2 gate.
2. **Cross-platform consistency (perceptual):** Android vs JVM vs iOS — per-platform baseline sets compared with perceptual metrics (e.g., SSIM / max-channel-delta), looser thresholds inside text-glyph regions, because Minikin (Android) and Skia Paragraph (JVM/iOS) shape text differently even with a bundled font. C4's "goldens green on JVM" means the JVM baseline set passes — it is NOT a proxy for Android correctness.

### D5 — DI: interfaces + Koin, expect/actual only at the edges
Per official expect/actual guidance: platform capabilities (decode, encode, photo store, share, permissions, dynamic color, crash handling) are **interfaces in commonMain** bound in per-platform Koin modules; `expect val platformModule: Module` is the only expect/actual wiring. ViewModels come from `koin-compose-viewmodel`'s `koinViewModel()` in commonMain. Caveat: validate `koinViewModel()` lifecycle/clearing on iOS early in C5 (`ViewModelStoreOwner` provision through `ComposeUIViewController` is a known sharp edge).

### D6 — Data layer to commonMain with stable coordinates
Room 2.8.4: `@ConstructedBy` + expect constructor object, `BundledSQLiteDriver`, suspend-only DAOs, no `withTransaction` in common code. KSP configurations: plain `ksp` covers Android on AGP 8.x, plus `kspIosArm64`, `kspIosSimulatorArm64`, `kspIosX64`, `kspJvm` (under AGP 9's KMP-library plugin the Android one becomes `kspAndroid`). iOS DB path via NSFileManager.

**Prepopulated template DBs are a first-class task, not a footnote:** today `AppModule` uses `createFromAsset(isCh ? "ewm-db-ch.db" : "ewm-db-eng.db")` — an Android-`assets/`-only API requiring a Context-based builder. The KMP builder is path-based. Plan: bundle both `.db` files via compose-resources (or per-platform resources), add `expect fun prepopulatedDbPath(locale): String` that copies the bundled DB to a writable path, switch to `createFromFile(path)`, set `exportSchema = true` and commit the schema (Room validates prepackaged DBs against it), and pick a common locale-detection API. (Risk R15.)

DataStore: `datastore-preferences-core` in commonMain (a **rename** from the current non-core artifact) + expect `createDataStore()` (OkioStorage on iOS). `Template.creationDate: java.util.Date` → `kotlinx.datetime.Instant`; drop `Parcelable` from the entity.

### D7 — Platform-neutral model layer FIRST
The audit found android types leaking into the domain: `WaterMark.tileMode: Shader.TileMode` (persisted as **android enum ordinal** in DataStore), `WaterMark.iconUri`/`ImageInfo.uri: android.net.Uri`, `UserPreferences.outputFormat: Bitmap.CompressFormat`, `ViewInfo: Matrix+ScaleType`, `FuncTitleModel` carrying resource-ID Ints. Introduce app-owned `TileMode` and `ImageFormat` enums with **explicit ordinal-compatible mappers** (cross-enum ordinal equality is fragile), a `MediaRef` value class for image identity, and delete `ViewInfo` with the renderer swap (C2b).

**Mentor-loop continuity note:** the recent task that standardized `SaveExportSheet` on `Bitmap.CompressFormat` (progress.md 2026-05-19) was the right Android-only stepping stone — it unified the type end-to-end. C3 deliberately swaps that unified type for the app-owned `ImageFormat` in ONE move (sheet + `UserPreferences` + `UserConfigRepository` together). This is the planned second beat of the same lesson ("now we abstract the platform type we deliberately coupled to"), not a reversal; the sheet's format type changes once, not twice.

### D8 — minSdk stays 23 (for now)
The pre-Q MediaStore path lives entirely inside the Android `PhotoLibraryStore` actual, so it does not block or pollute common code. Raising minSdk to 29 (deleting that path + the WRITE_EXTERNAL_STORAGE manifest entry) is a product decision to take separately with install-base data.

### D9 — EXIF stripping is a product policy, document it
Today's export bakes orientation into pixels and strips all other metadata (GPS/date/camera). For a privacy app this is plausibly a **feature**. Decision: keep strip-by-default and write it down (README/FAQ); beware iOS `PHPhotoLibrary` save paths that can silently re-attach metadata — the iOS actual must verify output metadata matches policy.

### D10 — Text rendering determinism: bundle a font
Cross-platform text is not pixel-identical (Android Minikin vs Skia Paragraph elsewhere; no Roboto on iOS; the **default watermark text starts with an emoji** — stored as `"👋 DO NOT REDISTRIBUTE"` in `WaterMark.kt:34`, i.e. 👋; app ships 13 locales incl. zh-rCN/zh-rTW/ja). Bundle one font via compose resources for the watermark; golden tolerance per D4's two-tier strategy; pin decode-to-sRGB policy to avoid P3 drift on iPhone photos.

---

## Milestone Board (extends the M0–M6 board)

| Phase | Priority | Outcome | Ships? |
|---|---|---|---|
| C1 Compose shell completion, CMP-shaped | P1 | M1–M5 work lands with CMP-compatible choices; About/OpenSource/recovery migrated; golden harness built against the OLD engine; CI extended | Android releases throughout |
| C2a Engine extraction (zero behavior change) | P0 (keystone) | `WatermarkRenderer` exists; old View + export both delegate to it; strict goldens green | Android release |
| C2b Preview swap + re-spec | P0 | Compose `Canvas` preview; image-space sizing + config migration; `WaterMarkImageView`/`ViewInfo` deleted | Android release |
| C3 Dependency de-Android-ization | P1 | Coil 3, colorpicker-compose, kmpalette, Room/DataStore KMP idioms, model layer platform-neutral, prepopulated-DB path — still a one-module Android app | Android releases per swap |
| C4 KMP restructure + Desktop | P1 | `:shared` module; `:app` thin; `jvm()` target + `:desktopApp` runs the real editor; BOM-skew spike resolved; benchmarks/CI updated | Internal desktop build |
| C5 iOS bring-up | P2 | iosApp + actuals (decode/encode/photos/share/permissions), fonts, memory hardening | TestFlight alpha |
| C6 Hardening, adaptive, release | P2 | Cross-platform perceptual diff suite; adaptive layouts; R8/shrink audit; desktop packaging (if Decision #7 = ship); revisit Nav3 + AGP 9 | All platforms |

Each phase leaves the Android app shippable. C1–C3 happen **before any KMP module exists**: this keeps the chat-driven mentor loop intact (every step is a small reviewable PR — including inside C2, thanks to the a/b split) and avoids a long-lived restructure branch.

---

### C1 — Compose shell completion, CMP-shaped (≈ existing M1–M5, re-scoped)

1. **State consolidation (M2) gets a precise kill-list** — the audit enumerated every LiveData/dual-state problem in `MainViewModel` (`archDi.stateIssues` in the audit JSON): `waterMark` LiveData duplicate of `waterMarkFlow`; `saveResult`/`compressedResult`/`saveProcess` (mixed `.value`/`.postValue`); `imageList` bundling `autoScroll`; `galleryPickedImageList` mirrored in two holders; `selectedImage` LiveData bridge; vestigial `saveImageUri`; init-block manual re-emission → `combine(...).stateIn(...)`; `Action.WaterMarkChange(Any)` → typed intents. Exit: zero LiveData imports in `MainViewModel`.
2. **Routes become `@Serializable` data objects/classes now** (works on Nav2 today, required for the JetBrains coordinate and iOS later).
3. **Panel migrations (M4) follow the official `migrate-xml-views-to-jetpack-compose` skill** (installed at `.claude/skills/`): per-layout 10-step loop with baseline screenshots, minimal theming, mandatory previews.
4. **Migrate `AboutActivity` + `OpenSourceActivity` to Compose screens.** They are launcher-reachable, ViewBinding-based, and hard-depend on appcompat/material/palette/cmonet — the exact libraries C3/C4 want to delete. They are static info screens: cheap, ideal Compose practice. Removal of palette/cmonet/appcompat/material is **gated** on this task.
5. Share-in (ACTION_SEND) consolidation per M1 decision; fix the existing gap: manifest lacks `ACTION_SEND_MULTIPLE` (single-image share-in only today).
6. **Port the crash-recovery screen to Compose.** Today `MyApp.catchException` + the recovery UI live in legacy `MainActivity` (`activity_recovery` layout, `recoveryMode`, `KEY_STACK_TRACE`); retiring `MainActivity` without this silently deletes the crash-loop self-heal feature. Crash handling becomes a platform capability (Android keeps `Thread.setDefaultUncaughtExceptionHandler`; iOS/Desktop get no-op or native hooks later).
7. **Build the golden-image harness against the CURRENT engine.** Corpus: multiline, emoji 👋, CJK, 315°, both tile modes, icon mode, JPEG+PNG, hGap/vGap extremes, per-locale samples. Baselines captured from today's export path; harness proven trustworthy BEFORE C2a relies on it. This supersedes/extends the old M0 smoke matrix and runs in CI from day one.
8. **CI:** extend `pr_pre_check.yml` beyond `:app:assembleDebug` — run unit tests + the golden JVM/Robolectric path on every PR; keep `release.yml`/`publish.yml` Android-only for now (they hardcode `:app`, which stays correct until C4 — revisit there).
9. Do not build new UI against `ViewInfo`/`AndroidView` contracts — C2b deletes them.

### C2a — Engine extraction, zero behavior change (keystone, part 1)

**Files (new):** `ui/render/WatermarkRenderer.kt` (cell build + composite + export rasterize), platform-neutral model types it needs (D7 subset: `TileMode`, text style enums).
**Hard precondition:** C1.7 golden harness green on the current engine.

1. Platform-neutral `TileMode`/`ImageFormat` enums + explicit ordinal-compatible mappers + DataStore migration for the persisted android-ordinal (Risk R6). (The `textSize` semantic change is NOT here — that's C2b.)
2. `WatermarkRenderer` in pure `androidx.compose.ui.graphics`/`ui.text`:
   - `buildCell(config, textMeasurer | iconBitmap, scale): ImageBitmap` — text via `TextMeasurer.measure` (TextLayoutResult gives max line width + line metrics; decide preserve-or-fix the `indexOf` duplicate-line latent bug, document either way), icon via `drawImage` with pinned `FilterQuality.None` (parity first).
   - `composite(drawScope, photoSize, cell, tileMode, offset)` — `ShaderBrush(ImageShader(cell, Repeated))` for tile; single-cell draw at fractional offset for CLAMP; reproduce exact conventions: rotation-AABB (w·cos+h·sin), gap = size·(g/100+1), icon diagonal cell, tile origin at photo top-left.
   - Offscreen export rasterizer: `ImageBitmap(w,h)` + `Canvas` + `CanvasDrawScope`; headless `TextMeasurer` constructed per platform (Android needs `createFontFamilyResolver(context)` — engine bootstrap is androidMain even though the engine is common). Do NOT use `GraphicsLayer.toImageBitmap()` (layout-sized, wrong tool for full-res).
3. Delegate BOTH existing paths to it: `WaterMarkImageView` keeps its View shell but its cell-building/composition calls the renderer; `MainViewModel.generateImage` builds cells via the renderer (decode/encode still behind `ImageCodec`/`PhotoLibraryStore` interfaces with Android impls). Fix the `scaleY`-from-`MSCALE_X` bug only if goldens prove it invisible, else preserve+document until C2b.
4. Strict goldens: new-export ≍ old-export, near-pixel-exact, same platform. Delete dead code the audit found (`BitmapUtils.generateMatrix`, inBitmap helpers; don't port `interChangeSize`'s 90/180 latent bug).

### C2b — Preview swap + re-spec (keystone, part 2)

1. Swap `EditorScreen`'s `AndroidView` → `WatermarkPreview` Compose `Canvas` (pointerInput drag + snap-back `Animatable`; decide whether to resurrect the commented-out pinch-to-scale).
2. Image-space watermark sizing: export scale computed purely from source-image dimensions; **one-time config migration for persisted `textSize`** (today it's view-px at preview scale, density-dependent); product sign-off + changelog entry (Decision #2).
3. Export memory fix: decode straight into the mutable target (drop the full-size `.copy`) — smaller OOM surface on Android, survivable on iOS later.
4. Delete `WaterMarkImageView`, `WaterMarkShader`, `ViewInfo`; un-block export-before-ViewInfo guard in `ComposeMainActivity`.
5. Re-baseline goldens where behavior deliberately changed; everything else stays strict.

### C3 — Dependency de-Android-ization (each step independently shippable)

1. Coil 2 → Coil 3 (`io.coil-kt.coil3` group). Delete Glide + AppGlideModule with the last legacy adapters. **Drop `coil-svg`** — vector icons go through compose-resources, no runtime SVG path remains (if one is found, gate on the iOS Skia SVG crash before C5).
2. colorpickerview → colorpicker-compose 1.2.0 (same vendor).
3. androidx.palette → kmpalette (or drop the bg-palette feature — product call, it is cosmetic). Gated on C1.4 (About uses palette too).
4. Room KMP idioms in place: suspend-only DAOs, `@ConstructedBy`, `BundledSQLiteDriver`, no `withTransaction`. **Prepopulated DB path per D6**: compose-resources bundling + `createFromFile` + `exportSchema=true` + locale expect. DataStore → `-core` artifact rename + `createDataStore()` factory. `java.util.Date`/`SimpleDateFormat`/`MessageDigest` → kotlinx.datetime/okio hashing.
5. `Bitmap.CompressFormat` → app-owned `ImageFormat` in ONE move across sheet + `UserPreferences` + `UserConfigRepository` (see D7 mentor-loop note).
6. Koin modules reshaped: `commonModule` (repos, viewmodels, renderer) + `platformModule` (codec, photo store, share, permissions, dynamic color, crash).
7. Delete `:cmonet` from the Compose path (expect/actual capability per audit). Note: Compose `AppTheme`'s `dynamicColor` parameter defaults to `false` and is never overridden at the call site — wire the real toggle through the new capability. Gated on C1.4 (About hosts the toggle today).
8. id.zelory:compressor → renderer-based decode-downscale-encode; delete dependency.

### C4 — KMP restructure + Desktop target

0. **Gate decision (D2):** re-verify CMP-9547 / AGP 9 status; pick the module plugin once.
1. Create `:shared` with `kotlin-multiplatform` (+ android library plugin per gate), `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose`; targets: `androidTarget()`, `jvm("desktop")`, `iosArm64()`, `iosSimulatorArm64()`.
2. Move code: commonMain gets renderer, models, repos, DB, DataStore, ViewModels (platform use-cases behind interfaces), navigation, Compose screens; androidMain gets actuals + glue; `:app` shrinks to manifest + `ComposeMainActivity` + Android actuals wiring.
3. **Compose lineage spike (Risk R13):** after `:shared` exists, source ALL Compose from `org.jetbrains.compose` (its Gradle plugin redirects to the right androidx artifacts on Android) and drop the androidx Compose BOM from `:app` — keep the BOM only if a dependency-graph spike (`./gradlew :app:dependencies`) proves single-version resolution of compose-runtime. Pin the Compose compiler plugin = Kotlin version across all modules.
4. Resources → compose resources: ~98 strings × 13 locales, 36 vector drawables (8 raster as-is); **add the bundled watermark font (D10)**; themes/mipmaps/filepaths stay in `:app`; bundle the two template `.db` files (D6). No plurals in use today — keep it that way or verify CMP support first.
5. `:desktopApp`: `Window` entry, FileKit dialogs, file-drop onto window as share-in analogue, "Reveal in Finder/Explorer" as share-out. DataStore/Room file locations: per-OS app-data dirs (define in the desktop actuals).
6. **Benchmarks:** `:macrobenchmark` (`com.android.test`, `targetProjectPath=":app"`, `benchmark` buildType) and `:baseBenchmarks` stay Android-only against `:app`; update variant matching after `:app` thins; align their compileSdk (34 → 36) or accept the mismatch consciously.
7. **CI:** PR check now builds `:shared` all targets' metadata + runs jvmTest goldens (per-platform baseline sets per D4); desktop run job optional.
8. **R8/shrink audit:** `:app` keeps minify+shrink; add/verify consumer keep rules for kotlinx.serialization (routes), Koin, Room KMP, Coil 3; smoke-test a minified release build, not just debug (Risk R18).

Exit: full edit→export loop on macOS/Windows/Linux; JVM golden baseline set green; Android golden set unchanged.

### C5 — iOS bring-up

1. `iosApp/` Xcode project; **Direct Integration or Local SwiftPM** (no CocoaPods need found); `ComposeUIViewController` entry; Koin init from `@main`; **validate `koinViewModel()` lifecycle/clearing early** (D5 caveat).
2. Actuals: `ImageCodec` (decode: `CGImageSourceCreateThumbnailAtIndex`-bounded for preview — Skiko has **no inSampleSize**, a naive port decodes full-res; encode: Skiko `Image.encodeToData(JPEG/PNG/WEBP, quality)`), `PhotoLibraryStore` (PHPhotoLibrary `performChanges`, `NSPhotoLibraryAddUsageDescription`, limited-library mode), picker via FileKit (PHPicker — permission-free; copy picked data into sandbox immediately, security-scoped URLs don't outlive the callback), share (`UIActivityViewController` + iPad popover anchor), permissions (addOnly is least-invasive), crash hook (D5/C1.6 capability).
3. Memory hardening: cap/tile export for ≥48MP; never hold full-res + copy simultaneously (done in C2b); explicit release after encode (K/N tracing GC is lazy; KT-61763); memory-warning hook.
4. Font/text parity pass: iOS golden baseline set captured; perceptual cross-platform diffs vs Android per D4 strategy; emoji/CJK/RTL checks; sRGB pin verified against P3 photos.
5. Share-in (iOS Share Extension) is explicitly **post-MVP**.

Exit: TestFlight alpha — pick → edit → export → share on a real device.

### C6 — Hardening, adaptive, release

Cross-platform perceptual diff suite in CI (JVM cheap path + Android instrumented + iOS on-demand); adaptive layouts for desktop windows/tablets/foldables (official `adaptive` skill installed; window-size-class available in CMP); performance pass (zoom preview on large photos — consider panpf/zoomimage if needed; iOS concurrent rendering default on 1.11); **desktop packaging if Decision #7 = ship** (`compose.desktop.nativeDistributions` dmg/msi/deb, desktop icon set, macOS signing/notarization — non-trivial); R8 release-shrink periodic check; store assets + release trains; **revisit AGP 9/10 module plugin (R8/R17) and Nav3 (CMP mirror stability) here**.

---

## Risk Register

| # | Risk | Mitigation |
|---|---|---|
| R1 | Text shaping/font parity across platforms (emoji default text, 13 locales) | Bundled font (D10); two-tier golden strategy (D4): strict same-platform, perceptual cross-platform; tolerance thresholds; accept documented deltas |
| R2 | iOS memory on large photos (no catchable OOM; Skiko lacks subsampled decode) | Bounded decode actual; no full-res copy; cap/tile export; memory-warning hooks; test with 48MP corpus |
| R3 | Color-space drift (P3 iPhone photos vs sRGB) | Pin decode-to-sRGB policy in `ImageCodec` contract; assert in goldens |
| R4 | Behavior changes from image-space sizing + FilterQuality (C2b) | One-time config migration + product sign-off + golden re-baseline; changelog entry |
| R5 | JPEG/WebP encode semantics differ (libjpeg vs Skia; PNG ignores quality; JPEG alpha→black) | Encode-matrix test per platform/format/quality; keep "quality snapped to 20s" rule consciously |
| R6 | TileMode persisted as android enum ordinal | App-owned enum + explicit mapper + DataStore migration (C2a.1) |
| R7 | Picker URI lifetime (Android process death; iOS callback-scoped URLs) | Copy to app cache on selection (fixes both platforms) |
| R8 | AGP 9 forces KMP-library plugin; CMP-9547 resource packaging bug | Hold AGP 8.x now; **C4-gate re-check picks the plugin once** (D2) |
| R9 | Compose compiler 2.0.x stability-inference bug in multiplatform modules (excess recomposition) | Profile after C4; strong-skipping + stability config file if needed |
| R10 | navigation-compose in maintenance mode; JetBrains mirror lags androidx (2.9.2 vs 2.9.7) | Acceptable for plan horizon; test at coordinate swap; Nav3 re-evaluation gate in C6 |
| R11 | `ACTION_SEND_MULTIPLE` missing today (single-image share-in only) | Fix in C1.5 while consolidating entry |
| R12 | Kim (EXIF lib) is beta; HEIC write unsupported; coil-svg iOS Skia crash | Only orientation-read needed (Coil 3 covers preview); drop coil-svg (C3.1); per-platform actual fallback |
| R13 | Compose lineage skew: androidx BOM in `:app` vs org.jetbrains.compose in `:shared` (duplicate classes / NoSuchMethodError) | C4.3 spike; prefer single lineage (org.jetbrains.compose everywhere) post-restructure; dependency-graph verification |
| R14 | Golden baselines conflated across platforms (JVM-Skia ≠ Android-Minikin text) | Two-tier strategy in D4; per-platform baseline sets; perceptual metrics cross-platform |
| R15 | Prepopulated `createFromAsset` template DBs (+ locale switch) have no KMP path as written | D6/C3.4 task: compose-resources bundle + `createFromFile` + `exportSchema=true` + locale expect |
| R16 | Crash-recovery UI lives in legacy `MainActivity`; retiring it silently deletes the feature; handler is Android-only | C1.6: port recovery to Compose; crash capability via platform interface |
| R17 | AGP 10 removes legacy library plugin (~H2 2026+) before C6 reaches the migration | D2 C4-gate decision; if AGP 9 fixed by then, adopt at C4 and avoid double migration |
| R18 | R8/minify breaks serialization/Koin/Room/Coil at runtime only in release builds | C4.8 keep-rule audit + minified-release smoke test per release train |
| R19 | Benchmark modules break when `:app` thins (variant matching, targetProjectPath) | C4.6 explicit update; keep Android-only |

## Decisions Needed From the Developer (before C2a starts)

1. **Platform order confirm** — Desktop-as-validation before iOS (D1)?
2. **Rendering behavior changes** (C2b): image-space sizing + config migration; icon FilterQuality pin-vs-accept; resurrect pinch-to-scale?
3. **EXIF policy** (D9): confirm strip-all-metadata as documented feature.
4. **Palette feature**: port via kmpalette or drop (cosmetic background color only; also used by About — see C1.4).
5. **minSdk** (D8): keep 23 (recommended for now) or raise to 29 with install-base data.
6. **C2 split confirm** (D4): C2a extraction-first sequencing, golden harness as hard precondition (C1.7).
7. **Desktop: validation-only or shipped product?** Shipping adds packaging/signing/notarization/icon work in C6.

## Working Style (unchanged)

The chat-driven mentor loop from the in-flight migration continues: tasks assigned in chat → developer implements → review → planning files updated. The C2a/C2b split exists so even the keystone decomposes into mentor-loop-sized, independently shippable PRs. C1.7 goldens become the new smoke-matrix backbone. One milestone per PR; Android stays releasable at every step. Expect C1–C3 to take months of part-time work — that is the plan working, not slipping.

## Tooling

- Official Android CLI 1.0 installed (`/opt/homebrew/bin/android`): use `android docs search/fetch` for any API question (the KB mirrors JetBrains KMP docs); `android skills list` for more skills.
- Installed project skills (`.claude/skills/`): `migrate-xml-views-to-jetpack-compose` (C1.3 panels), `navigation-3` (C6 re-evaluation), `adaptive` (C6).
- Audit/research bundle: `docs/superpowers/research/2026-06-12-cmp-readiness-audit.json` — per-dependency classifications, per-flow IO strategies, full source URLs.

## Execution Toolkit & Knowledge System (v1.2, 2026-06-13)

Developer goals recorded: (1) CMP+KMP; (2) **UI fully aligned with the production release** — the v2.10.0 View UI on master is the visual/behavioral parity baseline, not the half-migrated Compose branch; (3) elegant / best-practice / performant / stable; (4) **accumulate context continuously → AI-friendly repository**.

### Goal 2 amendment — production-UI parity

- New C1 task (**C1.10 — UI-parity audit**): build a screenshot matrix of production v2.10.0 (release build / master) vs the current Compose screens, per screen × theme × a sample of locales, using the android CLI (emulator + `android screenshot` / `android layout`). Output: a parity backlog ranking every visual/behavioral deviation. Production is the source of truth; the current Compose screens are treated as drafts to be corrected.
- The `migrate-xml-views-to-jetpack-compose` skill's Step 4 (baseline screenshot) institutionalizes this per layout going forward; C1.10 covers screens already migrated before this rule existed.
- Two parity layers, two baselines: engine output goldens (C1.7, from production export path) and screen-UI screenshots (C1.10, from production UI).

### Tool → task mapping

| Work unit | Tool | Serves |
|---|---|---|
| Each XML/panel/About migration | `migrate-xml-views-to-jetpack-compose` skill (10-step, screenshot baseline) | G2, G3 |
| Renderer C2a/C2b | golden harness + TDD discipline (`tdd` skill) | G3 |
| Each PR / milestone exit | `/code-review` (high effort) + `simplify` pass; docs-with-code gate (below) | G3, G4 |
| Behavior validation per task | `verify` / `run` + android CLI (screenshot/layout/journeys) | G2, G3 |
| Decision points (7 pending; C4 gate; any new fork) | `grill-with-docs` → mints ADR + updates CONTEXT.md inline | G4 |
| Periodic architecture review (post-C3, pre-C4) | `improve-codebase-architecture` (consumes CONTEXT.md + ADRs) | G3, G4 |
| Research / audits / adversarial verification / cross-platform sweeps | **Workflow** (multi-agent; like the 13-agent readiness audit) | breadth |
| Batch migration of similar panels AFTER the pattern is proven by hand (optional) | **Workflow** with worktree isolation + per-item adversarial review + screenshot check; developer reviews PRs | speed |
| Session continuity | `planning-with-files` (active) | G4 |
| Distill stabilized procedures into reusable project skills (golden-run, parity-check) | `skill-creator` | G4 |

Division of labor: skills = how each unit of work is done correctly; workflows = breadth and verification at phase boundaries. Routine implementation stays small-PR; workflows are the heavy artillery, not the assembly line — except for explicitly chosen mechanical batches.

### Goal 4 scaffolding (bootstrap before C1 starts)

```
CLAUDE.md                  ← /init then curate: build/test/run commands, architecture map, conventions, doc pointers
docs/CONTEXT.md            ← domain glossary/concept map (watermark cell, tile mode, image-space sizing, MediaRef, …)
docs/adr/NNNN-*.md         ← one ADR per decision: D1–D10 + the 7 pending answers; grill-with-docs maintains
docs/superpowers/…         ← planning/research artifacts (in place)
.claude/skills/            ← official skills (commit them); project-specific skills added as patterns stabilize
task_plan/findings/progress.md ← session memory (in place)
```

**Docs-with-code gate:** every milestone PR ships its context delta (ADR/CONTEXT/CLAUDE.md updates) or states "no doc impact" explicitly — enforced in the review checklist. CI stays a trustworthy verifier surface (goldens + tests green = agents can rely on it).

## Review Log

- v1.0 → v1.1 (2026-06-12): two-agent adversarial review applied. Fact-check (29 claims): fixed `kspAndroid`→`ksp` on AGP 8.x, added `kspIosX64`, Coil 3 group ID, datastore `-core` rename callout, scaleY/MSCALE_X bug note, dynamicColor default-param wording, headless TextMeasurer Context caveat, CMP version-conflict resolution note, navigation 2.9.2<2.9.7 gap. Architecture review (16 findings): C2 split into C2a/C2b with goldens-first (F1/F16); About/OpenSource scheduled + gating (F2); effort re-labeled honestly (F3); crash-recovery ported + capability (F4); prepopulated Room DB task (F5); CompressFormat mentor-loop continuity note (F6); benchmarks (F7), CI (F8), desktop packaging decision (F9), coil-svg drop (F10), BOM lineage spike (F11), two-tier golden strategy (F12), AGP plugin C4-gate (F13), R8 audit (F14), koinViewModel iOS caveat (F15).
