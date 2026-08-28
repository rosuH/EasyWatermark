# AGENTS.md

Guidance for agents working in this repository. `CLAUDE.md` is a symlink to this file.

This file is the always-on contract. Put ticket history, platform essays, and research notes elsewhere — update this file only when a durable agent rule changes.

## Product

EasyWatermark (`me.rosuh.easywatermark`) tiles text or image watermarks over photos so they cannot be reused. Fully offline; no tracking, stats, or crash SDKs. One Kotlin Multiplatform / Compose Multiplatform codebase ships Android, Desktop (JVM), and iOS.

Privacy that shapes code: Android needs no runtime permission on API 29+ (pre-29 storage). iOS pick needs no library read; save is add-only; optional Library Read is a photo-layer latch under a matching overlay (ADR-0029 + ADR-0033). Session and export stay path-first (ADR-0021). Export strips all EXIF (ADR-0009). Android ships via GitHub Releases, Google Play (paid, same code), F-Droid, and Coolapk.

## Modules

| Module | Role |
|---|---|
| `:shared` | Cross-platform domain, Room, session, render, Compose UI (`android` + `desktop` + iOS) |
| `:app` | Android shell: Activity, ports, MediaStore/decode/save, Koin |
| `:desktopApp` | Compose Desktop window + `--headless` CLI |
| `iosApp` | SwiftUI shell; `Shared.framework`; PHPicker / Photos / share |
| `:cmonet` | Android wallpaper Material You only, behind `DynamicColorCapability` |

`commonMain` is Kotlin + Compose with no Android types. Platform source sets own DataStore/Room builders, decode/encode, and system I/O.

## Read when

| When | Open |
|---|---|
| Domain words, invariants, retired terms | `docs/CONTEXT.md` |
| A design fork or “why is it this way” | `docs/adr/` |
| Issue / handoff / what not to recreate | `docs/agents/issue-tracker.md` |
| GitHub label names | `docs/agents/triage-labels.md` |
| Android / KMP API (not training data) | `android docs search '<query>'` then `android docs fetch` |

Do not start sessions from `task_plan.md`, `findings.md`, `progress.md`, or `docs/superpowers/research/`.

## Commands

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :shared:desktopTest
./gradlew :shared:iosSimulatorArm64Test          # macOS only
./gradlew :app:connectedDebugAndroidTest
./gradlew :desktopApp:run
./gradlew :desktopApp:run --args='--headless'
./gradlew :desktopApp:run -PewmAutoOpen=<abs image>
```

Debug `applicationId` is `me.rosuh.easywatermark.debug` (installs beside production). SDK: `Apps.compileSdk` 37, `targetSdk` 36, `minSdk` 23, JVM 17. No Spotless/ktlint — match existing style. PR CI: Ubuntu `assembleDebug` + `desktopTest` + non-strict `testDebugUnitTest`; macOS iOS job. `lintDebug` is fail-open. Do not add `WATERMARK_GOLDEN_STRICT=true` to PR CI (ADR-0010). Unsigned Desktop packaging is not a PR required check (ADR-0031).

## Rules

Pair every “don’t” with the replacement.

- **UI:** new product UI in `shared/commonMain/ui/`. Native UI only for app/window entry, pickers, share/save/permissions, capability glue, and renderer surfaces. Do not reintroduce `ViewInfo` or an `AndroidView` renderer.
- **Models:** keep `android.graphics.*` and `android.net.Uri` out of commonMain. Cross-platform identity is `MediaRef`. Android `Uri` stays only at picker/gallery/save/decode edges.
- **DataStore:** plain per-platform functions. Do not add a commonMain `expect`/`actual` store factory. Android stays on `PreferenceDataStoreFactory.create(produceFile, migrations)`.
- **Shared VM / IO:** do not extract a shared ViewModel, reducer, or IO `expect` without a named off-Android consumer or an owner decision.
- **Thumbs vs compose:** Coil 3 for gallery/filmstrip/save thumbs/icon/theme-seed (ADR-0028). Watermark preview and export decode stay on the pipeline, not Coil.
- **Preview:** editor main preview is a live two-layer overlay (ADR-0033): Source / iOS Library photo + tiled cell. Export still bakes. `PreviewImageRepository` keeps Source residency (ADR-0030). Slider ticks must not fully re-decode the focus source. Path change drops the previous live layers immediately (thumb / empty wait). Never paint Source or Library without a matching overlay. CLAMP commit persists offset, keeps live layers, and enqueues one non-draft overlay paint.
- **Render:** production path is `CommonWatermarkPipeline`. `:app` `WatermarkRenderer` is the measurement/golden oracle only. Text mode uses system-default fonts (ADR-0025) — no Noto in iOS or `desktopMain` resources.
- **Theme:** `DynamicColorCapability` for wallpaper only. Content editor theme is a separate path (ADR-0027). Do not call `CMonet` from Compose screens.
- **Editor layout:** dual-pane at **≥800 dp** via `editorLayoutClass` (ADR-0026). Route large-surface checks through `usesLargeScreenDialog`, never a raw width compare.
- **Motion:** `EwmMotionTokens` + `motionDurationMs`. Android cold Launch fade starts after splash exit (ADR-0032). Filmstrip switch is a hard cut.
- **i18n:** product strings/icons live in `shared/.../composeResources/`. Dual-write default EN to Weblate’s `app/src/main/res/values/` as well. Never hand-edit non-default locales. Do not put watermark fonts or Room seed DBs in composeResources.
- **iOS:** session holds Ready paths only. PhotoKit pixels never enter the pipeline, Session, or preview caches. Production framework is classic ObjC `Shared.framework` — do not migrate to Alpha Swift export. Prefer `internal` on implementation-only iosMain.
- **Desktop:** app data is OS-native (`DesktopAppPaths`). macOS export folder uses native AWT directory `FileDialog`, not Swing `JFileChooser`.
- **Deps:** stable-by-default; one catalog slice at a time; record rollback HEAD before a promotion.
- **Decisions:** new forks get an ADR (`docs/adr/`, Proposed until the owner signs). Milestone PRs update CONTEXT/ADR, or say “no doc impact”. Change this file only for durable agent rules.
- **Parity:** Android production v2.10.0 on `master` is the visual/behavior baseline. Verify renders by viewing screenshots, not byte sizes.
- **Machines:** do not shut down already-live Android or iOS simulators (standing order). Cap Gradle with `--max-workers=8`; `./gradlew --stop` when you started the daemon. Warn before long emulator+build load.

## Skills

Skills are the playbook. When a task matches one, open its `SKILL.md` and follow it before improvising. Name the skill in the plan. Official Google Android skills: refresh with `android update` and `android skills add --all --project=.` — do not hand-edit `SKILL.md` / `references/`.

Mirrored under `skills/`, `.claude/skills/`, and `.agents/skills/`. Compose / HotSwan skills live under `.agents/skills/`.

| Situation | Skill |
|---|---|
| XML → Compose parity | `migrate-xml-views-to-jetpack-compose` |
| System bars / IME / cutout | `edge-to-edge` |
| Nav / multi-pane scenes | `navigation-3` |
| Large-screen / foldable | `adaptive` |
| Test harness | `testing-setup` |
| Emulator, screenshot, docs KB | `android-cli` |
| R8 / keep rules | `r8-analyzer` |
| Jank / startup / traces | `android-profiler` → `perfetto-trace-analysis` |
| Play Data Safety | `play-policy-insights` |
| Recompose / stability | `auditing-compose-performance` |

Skip the catalog for pure domain/session/render work with no platform-skill match.

## Cursor Cloud

Headless Linux VM. iOS targets are out of scope. In-scope: `:app`, `:desktopApp`, `:shared` host tests.

- JDK 17 is the Gradle JVM (AGP 9). SDK is `~/android-sdk`; `local.properties` has `sdk.dir`.
- `compileSdk` 37 installs as `platforms;android-37.0` — not `platforms;android-37`.
- Desktop Skiko falls back to software GL; display is `DISPLAY=:1`.
- `:app:lintDebug` non-zero is informational. `:shared:commonPureTest` is not a CI gate (`ContentEditorThemeTest` needs Android `Bitmap`).
