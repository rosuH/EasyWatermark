# Compose Migration Findings

## Current State Addendum (2026-06-16)

Treat this section as the current state. Older sections below preserve historical research and may describe files or risks that have since been resolved.

- S3 closure checkpoint: branch `feat/migrate_to_compose` was clean and synced with `origin/feat/migrate_to_compose` at `86fa73e` before this planning-doc reconciliation.
- View-to-Compose is functionally complete: `ComposeMainActivity` is the only Activity, Navigation Compose owns Launch/Gallery/Editor/About/OpenSource/recovery/share-in flows, and the legacy Activity/dialog/panel/adapter/base stack is gone.
- `EditorScreen` preview no longer uses an `AndroidView` bridge. The preview is a Compose `Canvas` calling `WatermarkRenderer.build*Shader` + `WatermarkRenderer.compose` on the native canvas.
- `WaterMarkImageView` and `ViewInfo` are deleted. Do not reintroduce either contract.
- `WatermarkGeometry` lives in `:shared/commonMain` and already drives preview/export cell sizing. `WatermarkRenderer` is still Android-only for actual composition: text drawing via `StaticLayout`, icon drawing, rotation, `BitmapShader`, REPEAT tiling, and CLAMP single-decal composition.
- The remaining C2 problem is therefore narrower than the original plan: move the composition/drawing model toward commonMain without destabilizing the shipped Android renderer.
- S3d removed the last orphaned gallery layout (`item_image_gallery.xml`) and `AsyncSquareFrameLayout`; only historical docs mention them now.
- Current verification expectations for renderer-adjacent work: run Android build/tests/goldens, visually inspect screenshots, grant `READ_MEDIA_IMAGES` on test devices, and prefer picker-based editor entry when validating preview behavior.
- S4d-1 proved that adding Compose graphics/text to `:shared/commonMain` for iOS requires `org.jetbrains.compose`; raw `androidx.compose.*:1.10.6` lacks iOS klibs. Applying `org.jetbrains.compose` only to `:shared` is not sufficient because `:app` then sees a split Android runtime graph and version skew.
- C4.3 design + implementation are accepted: `:app` keeps AndroidX Compose BOM at `2026.05.01` / core Compose `1.11.2`; `:shared` uses `org.jetbrains.compose` `1.11.1` with `compose.runtime` + `compose.ui` in `commonMain`; `:app` substitutes residual Android runtime `org.jetbrains.compose.*` requests to `androidx.compose.*:1.11.2`. This yields one Android runtime Compose lineage while still giving `:shared` desktop/iOS Compose klibs.
- C4.3 verification found no Compose-bump renderer/UI regression: strict renderer tests stayed green, paired production/debug screenshots covered Launch, Editor, Text modal, Save sheet, Template, About, and OpenSource. Remaining visible differences are pre-existing migration deltas (`bg-palette`, ADR-0015 editor chrome, share-in export-list count). Recovery screen was not re-captured for this build-config-only slice and remains a low-risk residual.

## Repository State

- The launcher entry is already Compose-based in `app/src/main/AndroidManifest.xml`, where `.ui.ComposeMainActivity` is the `MAIN` / `LAUNCHER` activity.
- Legacy `.ui.MainActivity` still exists and owns the `ACTION_SEND image/*` flow, so app entry is currently split.
- `app/src/main/java/me/rosuh/easywatermark/ui/ComposeMainActivity.kt` already hosts `LaunchScreen`, `GalleryDialog`, and `EditorScreen` via Navigation Compose.
- `app/src/main/java/me/rosuh/easywatermark/ui/EditorScreen.kt` is partially migrated but still uses `AndroidView` for `WaterMarkImageView`.
- `app/src/main/java/me/rosuh/easywatermark/ui/MainViewModel.kt` mixes `LiveData`, `StateFlow`, screen state, business state, and UI event handling.
- Legacy dialogs and panels remain in `ui/dialog/*` and `ui/panel/*`, especially text editing and save/export flows.
- In the current Compose path, `EditorTopBar` exposes save/about callbacks, but `ComposeMainActivity` does not wire `onShowSaveDialog` or `onGoAboutScreen` yet.
- The current Compose `add more images` affordance is functionally a replace flow, not an append flow. This matches the legacy behavior: both Compose `SystemPickerImageSelected` and legacy `MainActivity -> updateImageList()` end up calling `updateImageListInternal(...)`, and `WaterMarkRepository.updateImageList(...)` replaces the entire image list.
- `SaveImageBSDialogFragment` cannot be directly reused from `ComposeMainActivity`. It expects an AndroidX `FragmentManager`, but more importantly it casts `requireContext()` / `activity` to `MainActivity` to read image list, view info, and permission helpers. This makes save/export migration a real UI decoupling task, not a simple callback hookup.

## Most Important Risks

- Entry-path drift: normal launch and shared-image launch follow different activities.
- Navigation drift: Compose `NavHost` and `LaunchScreenUiState` both model screen flow.
- State inconsistency: `LiveData`, `StateFlow`, and mutable fields coexist in `MainViewModel`.
- Export regressions: save/export/compression remains tightly coupled to legacy activity/dialog APIs.
- Renderer regressions: `WaterMarkImageView` is stateful and should not be rewritten early.

## Recommended Migration Strategy

- Incremental, state-first migration.
- Unify entry and navigation ownership before rewriting more UI.
- Convert screen contracts to `UiState + typed intent` patterns.
- Migrate editor chrome before considering pure Compose rendering.
- Leave MediaStore queries, bitmap export, and custom rendering logic alone until the shell is stable.

## Official Guidance Used

- Android Developers migration strategy guidance recommends gradual migration for existing apps.
- Android Developers state guidance recommends hoisting state and keeping state owners outside leaf composables.
- Android Developers interoperability guidance supports temporarily embedding existing Views in Compose.

## CMP Readiness — Initial Dependency Triage (2026-06-12)

Project: `app` + `cmonet` (library) + benchmarks + `buildSrc`. Kotlin 2.3.20, AGP 8.13.2, Compose BOM 2026.03.01, 122 .kt files, 23 legacy layout XMLs, viewBinding still enabled.

- Already KMP-capable (version in catalog has KMP support): kotlinx-coroutines 1.10.2, Koin 4.2.1 (koin-core is KMP), Room 2.8.4 (KMP since 2.7), DataStore 1.2.1 (KMP since 1.1), Lifecycle/ViewModel 2.10.0 (KMP), Navigation Compose 2.9.7 (JetBrains multiplatform artifact exists).
- Needs replacement/upgrade for KMP: Coil 2.7.0 → Coil 3 (KMP); androidx Compose BOM → org.jetbrains.compose; kotlin-parcelize (Parcelable models) → kotlinx.serialization or androidMain-only.
- Android-only, dies with the View layer: appcompat, material (MDC), fragment-ktx, viewpager2, recyclerview, constraintlayout(View), asynclayoutinflater, viewBinding, runtime-livedata, Glide 5 (+ KSP compiler), colorpickerview (View lib), compressor (id.zelory).
- Android-only, needs expect/actual or per-platform impl: exifinterface, palette-ktx, accompanist-permissions, activity-compose (host), profileinstaller, MediaStore/ContentResolver flows, `cmonet` (Material You dynamic color — Android 12+ concept).

## Tooling Findings (2026-06-12)

- Official Android CLI 1.0 (stable 2026-05) — developer.android.com/tools/agents/android-cli; `android init` installs base android-cli skill; `android docs search` queries the Android Knowledge Base; `android skills` manages agent skills.
- Official Android skills repo: github.com/android/skills (Apr 2026) — includes XML→Compose migration, Navigation 3, AGP 9, R8, edge-to-edge skills following Now-in-Android practices.
- Local `android` on PATH is still the deprecated SDK tool (2 copies: /usr/local/bin, sdk/tools) — new CLI must be installed and PATH precedence checked.
- Context7 research sources picked: /jetbrains/kotlin-multiplatform-dev-docs (official KMP docs, 2470 snippets), /jetbrains/compose-multiplatform.
- Working memory (nowledge-mem): no CMP-related prior context; graph idle.

## Official KMP Migration Guidance (fetched 2026-06-12 via android docs / kb://)

- Android official path for EXISTING projects (kb://android/kotlin/multiplatform/migrate): add a "Kotlin Multiplatform Shared Module" (template in AS Meerkat+, AGP >= 8.8), Android app depends on it via `implementation(project(":shared"))`; iOS consumes a generated framework (iosArm64/iosSimulatorArm64/iosX64 binaries.framework, xcfName).
- iOS integration options (JetBrains): direct/local integration, SwiftPM, or CocoaPods; choose local direct embedding for a small app.
- Module configuration (kb://JetBrains/.../multiplatform-project-configuration): START WITH A SINGLE SHARED MODULE (officially recommended starting point; low cognitive load); umbrella-module + umbrella-framework only when feature modules emerge later.
- Android Knowledge Base (android docs) literally mirrors JetBrains kotlin-multiplatform-dev-docs — both vendors' guidance accessible offline via `android docs fetch kb://...`.

## Rendering Engine — First-Hand Read (2026-06-12)

- WaterMarkImageView (777 lines): watermark = offscreen Bitmap "cell" (text: StaticLayout + measureText, manual rotation-aware bounds via cos/sin; image: createScaledBitmap, canvas.rotate) wrapped in BitmapShader(tileMode, tileMode); onDraw fills drawable bounds with shader paint → REPEAT tiling free; CLAMP mode = single cell at offsetX/Y with drag + back-to-center ValueAnimator + pinch scale (ScaleGestureDetector). Palette for bg color. Decode via decodeSampledBitmapFromResource(contentResolver, inSample).
- EXPORT REUSES THE SAME ENGINE: MainViewModel.kt:329/352 calls WaterMarkImageView.buildTextBitmapShader/buildIconBitmapShader; encodes via Bitmap.compress (MainViewModel.kt:408,439); id.zelory Compressor at MainViewModel.kt:651 (separate compress flow, takes `activity`).
- CMP mapping is ~1:1: StaticLayout→TextMeasurer, BitmapShader(REPEAT)→ImageShader(TileMode.Repeated), Bitmap+Canvas offscreen→ImageBitmap()+Canvas(ImageBitmap), canvas.rotate→DrawScope.rotate, touch→pointerInput, ObjectAnimator→Animatable. Non-common pieces: sampled decode (per-platform), Bitmap.compress encode (per-platform/Skiko encodeToData), Palette (kmpalette/material-color-utilities or drop).

## Android CLI / Skills Status (2026-06-12)

- Installed android-cli 1.0.15498356 via `brew trust android/tap && brew install --cask android-cli` → /opt/homebrew/bin/android (wins PATH over deprecated tools).
- CLI skills registry: android-cli, verified-email, camera1-to-camerax, adaptive (Compose adaptive UI — relevant for desktop/foldables), navigation-3, display-glasses-with-jetpack-compose-glimmer, perfetto-trace-analysis, perfetto-sql, testing-setup. `jetpack-compose` NOT in CLI registry ("not found in downloaded DAC skills") — verify GitHub repo directly.
- Syntax note: `android skills add --agent=claude-code --project=. <skill>` (usage header says `install`, actual subcommand is `add`).

## CMP Plan Review Outcomes (2026-06-12, v1.0 → v1.1)

Gaps found by adversarial review and now fixed in the plan — keep these in mind during execution:
- About/OpenSource Activities were unscheduled but gate the removal of appcompat/material/palette/cmonet (now C1.4).
- Crash-recovery UI lives ONLY in legacy MainActivity (`activity_recovery`, `recoveryMode`); retiring MainActivity silently deletes the feature; handler is Android-only (now C1.6 + R16).
- Room `createFromAsset(ewm-db-ch.db / ewm-db-eng.db)` + locale switch has NO KMP path: KMP builder is path-based, no assets/ concept; needs compose-resources bundle + `createFromFile` + `exportSchema=true` (now D6/C3.4 + R15).
- Engine rewrite split into C2a (extract, zero behavior change, old View + export both delegate) and C2b (preview swap + image-space sizing) with golden harness built BEFORE C2a against the OLD engine (C1.7).
- Golden strategy is two-tier: strict same-platform old↔new; perceptual (SSIM-style) cross-platform with per-platform baseline sets — JVM-Skia text ≠ Android-Minikin text even with a bundled font.
- Compose lineage skew risk: don't keep androidx BOM in :app alongside org.jetbrains.compose in :shared without a dependency-graph spike (R13).
- `kspAndroid` is the AGP-9-plugin config name; on AGP 8.x the Android KSP config is plain `ksp`. Also need kspIosX64.
- scaleY is ALSO derived from MSCALE_X (MainViewModel.kt:323-324) — both axes wrong together; renderer must compute independently.
- SaveExportSheet's recent Bitmap.CompressFormat standardization is a deliberate stepping stone; swapped for app-owned ImageFormat in ONE move in C3.5 (mentor-loop continuity, not reversal).

## Learning-Oriented Sequencing

- Start with Compose shell patterns: navigation, lifecycle-aware state collection, launchers, dialogs, and bottom sheets.
- Then move to state modeling: immutable UI state, typed events, and screen boundaries.
- Then work on editor UI composition around the existing rendering view.
- Only after that evaluate more advanced topics like custom drawing or replacing the rendering engine.
