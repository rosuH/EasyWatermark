# Architecture Decision Records

One file per decision. Statuses: **Accepted** (in force), **Proposed** (needs developer sign-off before the phase that depends on it), **Superseded**.

Early KMP/CMP decisions trace to the archived CMP migration plan ([`2026-06-12-cmp-migration-plan.md`](https://github.com/rosuH/EasyWatermark/blob/dae43630/docs/superpowers/plans/2026-06-12-cmp-migration-plan.md), D1–D10) and its evidence bundle ([`2026-06-12-cmp-readiness-audit.json`](https://github.com/rosuH/EasyWatermark/blob/dae43630/docs/superpowers/research/2026-06-12-cmp-readiness-audit.json)) in Git history.

| # | Title | Status |
|---|---|---|
| [0001](0001-platform-targets-and-order.md) | Platform targets and order: Android → Desktop (validation) → iOS | Accepted |
| [0002](0002-single-shared-module-agp-hold.md) | Single `:shared` module; hold AGP 8.x with C4-gate re-check | Accepted |
| [0003](0003-navigation-stay-nav2.md) | Navigation: stay on Nav2 via JetBrains coordinate; defer Nav3 | Accepted |
| [0004](0004-rendering-engine-commonmain-rewrite.md) | Rendering engine: single commonMain rewrite, C2a/C2b split | Accepted; **production Android native hold partially superseded by [0018](0018-option-c2-common-raster-android-export.md)** |
| [0005](0005-di-koin-interfaces.md) | DI: interfaces + Koin modules; expect/actual only at the edges | Accepted |
| [0006](0006-data-layer-kmp.md) | Data layer: Room/DataStore on KMP coordinates; prepopulated DB path | Accepted |
| [0007](0007-platform-neutral-models.md) | Platform-neutral model layer (TileMode, ImageFormat, MediaRef) | Accepted |
| [0008](0008-minsdk-23-hold.md) | minSdk stays 23 | Accepted |
| [0009](0009-exif-strip-is-a-feature.md) | EXIF stripping on export is a privacy feature | Accepted |
| [0010](0010-bundled-font-and-golden-strategy.md) | Bundled watermark font; two-tier golden strategy; sRGB pin | Accepted (production font scope superseded by [0025](0025-system-default-watermark-fonts.md)) |
| [0010-c2](0010-c2-golden-policy-delta.md) | Golden policy delta under Option C2 ([0018](0018-option-c2-common-raster-android-export.md)) | Accepted with ADR-0018 |
| [0011](0011-production-ui-parity-baseline.md) | Production v2.10.0 is the UI parity baseline | Accepted |
| [0012](0012-ai-friendly-docs-system.md) | AI-friendly repo: CLAUDE/CONTEXT/ADR + docs-with-code gate | Accepted (session-memory files retired to Git history) |
| [0013](0013-desktop-positioning.md) | Desktop: validation target vs shipped product | **Proposed** |
| [0014](0014-parity-micro-decisions.md) | Parity micro-decisions: palette path superseded by [0027](0027-wallpaper-and-content-editor-theme.md); pinch-off superseded by [0040](0040-preview-gesture-targets.md); quality snapping | Accepted (+ 0027, 0040) |
| [0015](0015-parity-vs-compose-idiom-tensions.md) | Parity-vs-Compose-idiom tensions: B (text-edit modal) implemented; A (back arrow) & C (segmented) kept with rationale | Accepted (revertable) |
| [0016](0016-mainactivity-integration-and-legacy-retirement.md) | Sole Activity owns ACTION_SEND + crash recovery; later renamed back to `MainActivity` for v2.10.0 component-name compatibility | **Implemented** (amended 2026-08-16) |
| [0017](0017-shared-session-viewmodel-cmp-first.md) | Shared session ViewModel + ports + CMP-first UI | **Accepted** (2026-07-12) |
| [0018](0018-option-c2-common-raster-android-export.md) | Option C2: common raster for Android export + unified preview | **Accepted** (owner 2026-07-12; editor preview amended by [0033](0033-live-overlay-preview.md)) |
| [0019](0019-cmp-compose-resources-i18n.md) | CMP composeResources for product UI strings + drawables | **Accepted** (Phases 0–3 landed 2026-07-12) |
| [0020](0020-ios-single-scene-release.md) | iOS current release is single-scene (process-wide Session) | **Accepted** (2026-07-18) |
| [0021](0021-ios-progressive-photo-import.md) | iOS progressive photo import (path-first slots) | **Accepted** (2026-08-07) |
| [0022](0022-drop-pre-compress-recovery-ui.md) | Drop pre-compress recovery UI | **Accepted** (2026-08-07) |
| [0023](0023-launch-editor-route-transition.md) | Launch↔Editor uses product-shell route transitions | **Accepted** (2026-08-07) |
| [0024](0024-room3-hold-no-migration.md) | Room 3 — hold (no migration this cycle) | **Proposed** (2026-08-09) |
| [0025](0025-system-default-watermark-fonts.md) | System-default watermark Text fonts (drop production Noto) | **Accepted** (2026-08-09) |
| [0026](0026-adaptive-editor-layout-ia.md) | Adaptive editor layout IA (Supporting-pane; three-zone withdrawn) | **Accepted** (amended 2026-08-10, 2026-08-16) |
| [0027](0027-wallpaper-and-content-editor-theme.md) | Wallpaper dynamic color (Android, no OEM list) + Content editor theme (photo seed, all platforms) | **Accepted** (2026-08-11) |
| [0028](0028-coil-kmp-ui-image-loading.md) | Coil KMP for UI image loading; exclude watermark compose/export; MediaStore Fetcher on Android | **Accepted** (2026-08-11) |
| [0029](0029-ios-library-read-first-paint.md) | iOS Library Read; Library is photo-layer only under a matching overlay (ADR-0033) | **Accepted** (owner 2026-08-15; amended 2026-08-26) |
| [0030](0030-cross-platform-preview-working-set.md) | Cross-platform `PreviewImageRepository`; decode stays on the platform edge; PhotoKit not in the repo | **Proposed** |
| [0031](0031-desktop-packaging-ci-not-pr-required.md) | Desktop packaging CI: weekly/master-push + Build packages button; not a PR required check; no third-party path classifiers | **Proposed** |
| [0032](0032-android-splash-then-launch-fade-serial.md) | Android splash then Launch fade are serial; iOS launch fill matches first screen (`#262611`, no icon) | **Accepted** (owner 2026-08-26; amended 2026-09-13) |
| [0033](0033-live-overlay-preview.md) | Editor preview is a live two-layer overlay; export still bakes | **Proposed** — Implemented, pending device sign-off |
| [0034](0034-one-click-android-play-ship.md) | Ship release: CREATE = GitHub (Android + unsigned Desktop) + Play; PLAY = resume Play | **Proposed** |
| [0035](0035-custom-watermark-fonts.md) | 三端自定义水印字体：系统字体、目录导入与共享渲染 | **Accepted** (owner 2026-09-05) |
| [0036](0036-pin-r8-pg-map-id.md) | Next GitHub APK pins R8 pg-map-id to 64 zeros before signing and keeps pre-stripped AndroidX `.so` files untouched (#443); Play AAB/mapping not pinned | **Proposed** |
| [0037](0037-agent-device-testmap-adapter.md) | testmap agent 层改为 Agent Device；L0–L3/地图保留；Artemis 历史 / P4 deprecated | **Accepted** (owner 2026-09-14: default runner Agent Device, P0–P4, no PR split) |
| [0038](0038-testmap-continuous-routes.md) | Testmap continuous routes beside independent cases; one setup/session/restore and attributable steps | **Proposed** |
| [0039](0039-testmap-android-model-directed-journey.md) | Testmap Android model-directed journey as an evidence-gated runner adapter | **Proposed** |
| [0040](0040-preview-gesture-targets.md) | Preview gesture targets and watermark editing | **Accepted** (owner 2026-10-18) |
| [0041](0041-reusable-sticker-asset-library.md) | Reusable sticker asset library | **Proposed** — owner-approved asset-only sticker direction, pending verification sign-off |
