# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

EasyWatermark (`me.rosuh.easywatermark`) — a privacy-focused Android app that tiles text/image watermarks over photos so they can't be repurposed. Privacy promises that shape engineering decisions: fully offline, zero tracking/stats/crash SDKs, no permissions needed on API 29+ (pre-29 needs storage permission). Distributed via GitHub Releases, Google Play (paid, same code), F-Droid, Coolapk. Translations come from Weblate (13 locales) — don't hand-edit non-default `strings.xml`.

## Current state: View→Compose done, KMP foundations landed — read this first

1. **View→Compose migration** — **functionally COMPLETE** as of 2026-06-13 (branch `feat/compose-about-share-parity`, PR #377 → `feat/migrate_to_compose`). `ComposeMainActivity` (a `ComponentActivity`) is the **sole** Activity: launcher + Navigation Compose (LaunchScreen → GalleryDialog → EditorScreen), `ACTION_SEND`/`ACTION_SEND_MULTIPLE` share-in, and the crash-recovery screen (`RecoveryScreen.kt`, gated on `MyApp.recoveryMode`). The legacy stack — `MainActivity`, `AboutActivity`, `OpenSourceActivity`, all `ui/dialog/*`, `ui/panel/*`, `ui/adapter/*`, `ui/base/*`, and the `LaunchView`/edge-effect widgets — was **deleted** (ADR-0016, 39 .kt files). `EditorScreen` still embeds the retained `WaterMarkImageView` via `AndroidView` — the one surviving legacy View, slated for the commonMain renderer (ADR-0004). Remaining hygiene: orphaned legacy layout XMLs (`activity_*`/`dialog_*`/`item_*`) are unreferenced (R8-stripped), pending a cleanup pass.
2. **Compose Multiplatform / KMP migration** (phases C1–C6): `docs/superpowers/plans/2026-06-12-cmp-migration-plan.md`. Decisions in `docs/adr/`; domain vocabulary in `docs/CONTEXT.md`; dependency-level KMP classifications in `docs/superpowers/research/2026-06-12-cmp-readiness-audit.json`. **Foundations now landed (2026-06-13), not just planned:**
   - **`:shared` KMP module EXISTS, compiles for all 3 platforms, and RUNS on 2** — `kotlin-multiplatform` with `androidTarget()` + `jvm("desktop")` + `iosArm64()` + `iosSimulatorArm64()`; pure-Kotlin only (no Compose/Android resources yet → sidesteps CMP-9547). `commonMain` holds platform-neutral domain types `ImageFormat`/`Result`/`JobState` **and the engine geometry core `render/WatermarkGeometry`** (gap/diagonal/rotated-cell-AABB math, faithfully extracted from `WaterMarkImageView`, unit-tested in `commonTest`). Compiles for Android + JVM/desktop + iOS; the same code **runs** on Android (`:app`) and Desktop (`:desktopApp` — `./gradlew :desktopApp:run` executes the shared engine core). `commonTest` runs on every PR (CI). Add platform-neutral domain/engine code here.
   - **NOT yet wired / still ahead:** `WatermarkGeometry` is verified-correct but additive — NOT yet driving the live Android renderer (the C2a swap is gated behind the **golden image harness, C1.7**, since it touches the product core). `:desktopApp` is a runnable scaffold, not the Compose Desktop editor (C4). iosApp bring-up (PHPicker, photo store, memory, Compose UI) is C5. The commonMain Compose renderer + UI is C2/C4.
   - Typed `@Serializable` Navigation routes (`ui/Routes.kt`); `ImageFormat` replaces `Bitmap.CompressFormat` in the model (encode mapping at the edge, `utils/ktx/ImageFormatExt`); `AboutViewModel` is StateFlow (C1.1 started).
   - **NOT yet done** (the bulk — months of work): full `MainViewModel` LiveData→StateFlow (entangled with un-migrated **compress** + **bg-palette** features — see parity backlog; migrate, don't delete); the **golden test harness (C1.7)** that gates the engine rewrite (C2); de-Android-izing remaining deps (Coil3/Room/DataStore/TileMode — C3); Desktop app + iOS (C4/C5). `tileMode` is deliberately NOT neutralized yet (feeds the live `BitmapShader` render path — golden-gated).

Session memory (planning-with-files): `task_plan.md`, `findings.md`, `progress.md` at repo root — read them at session start, update them as you work.

**Parity rule:** the visual/behavioral source of truth is the latest production release (v2.10.0, built from `master`), NOT this branch's current Compose screens. Per-layout migrations follow the 10-step skill in `.claude/skills/migrate-xml-views-to-jetpack-compose/` (screenshot baseline → migrate → visual diff → delete XML).

## Commands

- Build debug: `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/`. Debug applicationId is `me.rosuh.easywatermark.debug`, so it installs alongside the production app — useful for side-by-side parity checks.
- Unit tests: `./gradlew :app:testDebugUnitTest` (only stub tests exist today; the golden/screenshot harness is planned work — see plan C1.7). Instrumented: `./gradlew :app:connectedDebugAndroidTest`.
- No linter is wired up (spotless/ktlint blocks in root `build.gradle.kts` are commented out). Match existing style by hand.
- SDK constants live in `buildSrc` (`Apps.kt`): compileSdk/targetSdk 36, minSdk 23. JVM toolchain 17.
- Release builds are minified (R8, `proguard-rules.pro` + `coroutines.pro`); CI (`.github/workflows/`) runs `:app:assembleDebug` on PRs, signing + Play upload on release.
- Android CLI 1.0 is installed (`android`): `android docs search '<query>'` queries an offline KB that mirrors developer.android.com AND the JetBrains KMP docs (then `android docs fetch kb://...`); `android emulator list/start`, `android screenshot`, `android layout` (UI tree as JSON — faster than screenshots for UI debugging), `android run`.

## Architecture (big picture)

- Modules: `:app` (everything), `:cmonet` (Material You dynamic-color gate, Android-only, scheduled for replacement — ADR-0007), `:baseBenchmarks`/`:macrobenchmark` (Android-only perf), `buildSrc`.
- **Data flow:** DataStore Preferences (watermark config via `WaterMarkRepository`, user prefs via `UserConfigRepository`) + Room (`Template` entity; **prepopulated DBs** `assets/ewm-db-ch.db`/`ewm-db-eng.db` selected by locale in `AppModule`) → Koin DI (`di/`) → `MainViewModel` (large; LiveData+StateFlow currently mixed, being consolidated to StateFlow-only — kill-list in plan C1.1) → both UI stacks.
- **Rendering engine (the product core):** `WaterMarkImageView`'s companion builds a watermark "cell" offscreen (text via StaticLayout, icon via scaled bitmap, rotated), wraps it in a `BitmapShader` — `REPEAT` tiles it across the photo, `CLAMP` ("decal") draws one draggable instance at a fractional offset. Preview composites in `onDraw`; **export reuses the same cell builders** in `MainViewModel.generateImage` but duplicates the composition and derives export scale from the preview view's matrix (`ViewInfo`, `1/MSCALE_X` — known debt; both axes use MSCALE_X). The CMP plan replaces all of this with one commonMain renderer (ADR-0004).
- **Gotchas encoded in data:** `WaterMark.tileMode` is persisted in DataStore as an **android `Shader.TileMode` enum ordinal**; `Uri`/`Bitmap.CompressFormat` leak into models — platform-neutral replacements are planned (ADR-0007); don't extend these patterns.
- Image IO: decode via `BitmapFactory` + inSampleSize with EXIF rotation baked in (`utils/bitmap/BitmapUtils.kt`); save via MediaStore `IS_PENDING` (API ≥29) or pre-Q file path; export deliberately strips all EXIF metadata (privacy feature — ADR-0009).

## Conventions for agents

- **Docs-with-code gate:** every milestone PR ships its context delta (ADR / CONTEXT.md / CLAUDE.md updates) or states "no doc impact" in the PR description.
- Do not build new UI against `ViewInfo` / `AndroidView` contracts — both are scheduled for deletion (plan C2).
- Decision forks get an ADR in `docs/adr/` (use the `grill-with-docs` flow); status `Proposed` until the developer signs off.
- When unsure about an Android/KMP API, prefer `android docs search` over training data.
- **Clean up heavy processes you start:** automated emulator sessions boot with `-no-window` when interaction isn't needed, and end with `adb emu kill` + `./gradlew --stop`; cap automated builds with `--max-workers=8`. Sustained emulator+build load has frozen this machine's input devices before — warn the developer before kicking off long heavy local automation.
