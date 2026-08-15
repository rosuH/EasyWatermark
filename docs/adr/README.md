# Architecture Decision Records

One file per decision. Statuses: **Accepted** (in force), **Proposed** (needs developer sign-off before the phase that depends on it), **Superseded**.

Decisions trace to the CMP migration plan (`docs/superpowers/plans/2026-06-12-cmp-migration-plan.md`, D1–D10) and its evidence bundle (`docs/superpowers/research/2026-06-12-cmp-readiness-audit.json`).

| # | Title | Status |
|---|---|---|
| [0001](0001-platform-targets-and-order.md) | Platform targets and order: Android → Desktop (validation) → iOS | Accepted |
| [0002](0002-single-shared-module-agp-hold.md) | Single `:shared` module; hold AGP 8.x with C4-gate re-check | Accepted |
| [0003](0003-navigation-stay-nav2.md) | Navigation: stay on Nav2 via JetBrains coordinate; defer Nav3 | Accepted |
| [0004](0004-rendering-engine-commonmain-rewrite.md) | Rendering engine: single commonMain rewrite, C2a/C2b split | Accepted; **production Android native hold partially superseded by [0018](0018-option-c2-common-raster-android-export.md)** |
| [0018](0018-option-c2-common-raster-android-export.md) | Option C2: common raster for Android export + unified preview | **Accepted** (owner 2026-07-12) |
| [0019](0019-cmp-compose-resources-i18n.md) | CMP composeResources for product UI strings + drawables | **Accepted** (Phases 0–3 landed 2026-07-12) |
| [0020](0020-ios-single-scene-release.md) | iOS current release is single-scene (process-wide Session) | **Accepted** (issue 14 B1, 2026-07-18) |
| [0005](0005-di-koin-interfaces.md) | DI: interfaces + Koin modules; expect/actual only at the edges | Accepted |
| [0006](0006-data-layer-kmp.md) | Data layer: Room/DataStore on KMP coordinates; prepopulated DB path | Accepted |
| [0007](0007-platform-neutral-models.md) | Platform-neutral model layer (TileMode, ImageFormat, MediaRef) | Accepted |
| [0008](0008-minsdk-23-hold.md) | minSdk stays 23 | Accepted |
| [0009](0009-exif-strip-is-a-feature.md) | EXIF stripping on export is a privacy feature | Accepted |
| [0010](0010-bundled-font-and-golden-strategy.md) | Bundled watermark font; two-tier golden strategy; sRGB pin | Accepted |
| [0011](0011-production-ui-parity-baseline.md) | Production v2.10.0 is the UI parity baseline | Accepted |
| [0012](0012-ai-friendly-docs-system.md) | AI-friendly repo: CLAUDE/CONTEXT/ADR + docs-with-code gate | Accepted |
| [0013](0013-desktop-positioning.md) | Desktop: validation target vs shipped product | **Proposed** |
| [0014](0014-parity-micro-decisions.md) | Parity micro-decisions: palette path superseded for product by [0027](0027-wallpaper-and-content-editor-theme.md); pinch off; quality snapping | Accepted (+ 0027 addendum) |
| [0015](0015-parity-vs-compose-idiom-tensions.md) | Parity-vs-Compose-idiom tensions: B (text-edit modal) implemented; A (back arrow) & C (segmented) kept with rationale | Accepted (revertable) |
| [0016](0016-mainactivity-integration-and-legacy-retirement.md) | MainActivity integration: ACTION_SEND→ComposeMainActivity, crash recovery→Compose, legacy Activity retirement | **Proposed** (design for final block) |
| [0025](0025-system-default-watermark-fonts.md) | System-default watermark Text fonts (drop production Noto) | **Accepted** (2026-08-09) |
| [0026](0026-adaptive-editor-layout-ia.md) | Adaptive editor layout IA (Supporting-pane; three-zone withdrawn) | **Accepted** (amended 2026-08-10) |
| [0027](0027-wallpaper-and-content-editor-theme.md) | Wallpaper dynamic color (Android, no OEM list) + Content editor theme (photo seed, all platforms) | **Accepted** (2026-08-11) |
| [0028](0028-coil-kmp-ui-image-loading.md) | Coil KMP for UI image loading; exclude watermark compose/export; MediaStore Fetcher on Android | **Accepted** (2026-08-11) |
| [0029](0029-ios-library-read-first-paint.md) | iOS Library Read; unwatermarked Library derivative first paint, then Watermarked preview | **Accepted** (owner 2026-08-15) |
