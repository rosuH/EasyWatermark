# AGENTS.md

Guidance for agents working in this repository. `CLAUDE.md` is a symlink to this file.

Keep this always-on contract limited to durable project constraints. Put task history, detailed workflows, and research in linked docs; keep guidance useful across models.

## Working together

- **Complete the task:** derive completion from the requested outcome, including applicable checks and runtime inspection. Continue through failures caused by the change; a first implementation is not completion. Infer routine details, state material assumptions, and ask only when missing input changes scope, correctness, or authorization. Incorporate mid-task corrections without dropping unfinished work.
- **Delegate:** for substantial work, the lead owns scope and acceptance; subagents handle bounded reading, implementation, batch edits, and tests. Assign file ownership, constraints, and acceptance evidence; parallelize independent work and preserve others' edits. Keep trivial tasks local. If delegation is unavailable, report it and honor explicit role restrictions.
- **Verify:** inspect the current diff and relevant evidence, not worker status alone. Run affected checks and required gates; repeat only after relevant edits, failures, or unresolved concerns. Docs-only changes need static checks. UI/performance claims need applicable visual and physical-device evidence; mark missing evidence pending.
- **Communicate:** lead with the outcome, relevant changes, checks, and remaining limitations in concise, plain language.
- **Authorization:** requested local edits, checks, and fixes may proceed without repeated approval. Preserve unrelated work. Publishing, messaging, merging, and destructive cleanup require user authorization, which may already be present in the session. While awaiting missing input, continue independent authorized work.

## Product

EasyWatermark (`me.rosuh.easywatermark`) tiles text or image watermarks over photos so they cannot be reused. Fully offline; no tracking, stats, or crash SDKs. One Kotlin Multiplatform / Compose Multiplatform codebase ships Android, Desktop (JVM), and iOS.

Privacy that shapes code: Android needs no runtime permission on API 29+ (pre-29 storage). iOS pick needs no library read; save is add-only; optional Library Read is a photo-layer latch under a matching overlay (ADR-0029 + ADR-0033). Session and export stay path-first (ADR-0021). Export strips all EXIF (ADR-0009). Android ships via GitHub Releases, Google Play (paid, same code), and F-Droid. iOS ships on the App Store. Coolapk is delisted.

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

Load only the context needed for the task. Use these routes when relevant; a small edit does not require a full repository or documentation review.

| When | Open |
|---|---|
| Domain words, invariants, retired terms | `docs/CONTEXT.md` |
| A design fork or “why is it this way” | `docs/adr/` |
| Issue / handoff / what not to recreate | `docs/agents/issue-tracker.md` |
| Agent guidance maintenance / model migration scope | `docs/agents/workflow.md` |
| GitHub label names | `docs/agents/triage-labels.md` |
| Android / KMP API (not training data) | `android docs search '<query>'` then `android docs fetch` |

Start from the current request and relevant sources above. Historical mission files such as `task_plan.md`, `findings.md`, and `progress.md`, plus notes in `docs/superpowers/research/`, are evidence, not active instructions, unless the user resumes that work; reconcile it with the current request and this contract.

## Commands

Run from the repository root; choose commands for the task rather than running the list. Replace `/absolute/path/to/image.jpg` with an existing image.

```bash
./gradlew --max-workers=8 :app:assembleDebug
./gradlew --max-workers=8 :app:testDebugUnitTest
./gradlew --max-workers=8 :shared:desktopTest
./gradlew --max-workers=8 :shared:iosSimulatorArm64Test # macOS only
./gradlew --max-workers=8 :app:connectedDebugAndroidTest
./gradlew --max-workers=8 :desktopApp:run
./gradlew --max-workers=8 :desktopApp:run --args='--headless'
./gradlew --max-workers=8 :desktopApp:run '-PewmAutoOpen=/absolute/path/to/image.jpg'
```

Debug `applicationId` is `me.rosuh.easywatermark.debug` (installs beside production). SDK: `Apps.compileSdk` 37, `targetSdk` 36, `minSdk` 23, JVM 17. No Spotless/ktlint — match existing style. PR CI: Ubuntu `assembleDebug` + `desktopTest` + non-strict `testDebugUnitTest`; macOS iOS job. Docs/assets-only PRs still start `PR Checks` so the two required job names report success; Gradle/Xcode run only when a product path changes. `lintDebug` is fail-open. Do not add `WATERMARK_GOLDEN_STRICT=true` to PR CI (ADR-0010). Unsigned Desktop packaging is not a PR required check (ADR-0031).

## Rules

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
- **Store assets:** marketing config stays in this repo (`goldie/`, `goldie-play/`, `.argent/flows`). The engine is the [rosuH/goldie](https://github.com/rosuH/goldie) fork (`scripts/`, `studio/play/`). Do not patch `node_modules/goldie`. Framed/raw outputs stay untracked (`goldie/out/`, `goldie-play/out/`).
- **Decisions:** new forks get an ADR (`docs/adr/`, Proposed until the owner signs). Milestone PRs update CONTEXT/ADR, or say “no doc impact”. Change this file only for durable agent rules.
- **Parity:** Android production v2.10.0 on `master` is the visual/behavior baseline. Verify renders by viewing screenshots, not byte sizes.
- **Machines:** do not shut down already-live Android or iOS simulators (standing order). Cap Gradle with `--max-workers=8`; `./gradlew --stop` when you started the daemon. Warn before long emulator+build load.

## Skills

Use explicitly requested skills or skills whose workflow matches the task, not merely a related keyword. Read the selected `SKILL.md`, then only supporting files needed for that workflow; name the skill when first used. Explicit user instructions take precedence over skill guidelines, subject to system/developer constraints. If a skill blocks authorized work, link and quote the exact rule and distinguish it from your interpretation; otherwise continue.

Skills are mirrored under `skills/`, `.claude/skills/`, and `.agents/skills/`; read one copy. Compose / HotSwan skills live under `.agents/skills/`. For skill maintenance, see `docs/agents/workflow.md`.

| Situation | Skill |
|---|---|
| App Store / Play store assets | `goldie` (engine fork: https://github.com/rosuH/goldie) |
| XML → Compose parity | `migrate-xml-views-to-jetpack-compose` |
| System bars / IME / cutout | `edge-to-edge` |
| Nav / multi-pane scenes | `navigation-3` |
| Large-screen / foldable | `adaptive` |
| Create or change a test harness | `testing-setup` |
| Emulator, screenshot, docs KB | `android-cli` |
| R8 / keep rules | `r8-analyzer` |
| Jank / startup / traces | `android-profiler` → `perfetto-trace-analysis` |
| Play Data Safety | `play-policy-insights` |
| Recompose / stability | `auditing-compose-performance` |

## Cursor Cloud

Headless Linux VM. iOS targets are out of scope. In-scope: `:app`, `:desktopApp`, `:shared` host tests.

- JDK 17 is the Gradle JVM (AGP 9). SDK is `~/android-sdk`; `local.properties` has `sdk.dir`.
- `compileSdk` 37 installs as `platforms;android-37.0` — not `platforms;android-37`.
- Desktop Skiko falls back to software GL; display is `DISPLAY=:1`.
- `:app:lintDebug` non-zero is informational. `:shared:commonPureTest` is not a CI gate (`ContentEditorThemeTest` needs Android `Bitmap`).
