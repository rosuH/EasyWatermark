# Architecture Decision Records

One file per decision. Statuses: **Accepted** (in force), **Proposed** (needs developer sign-off before the phase that depends on it), **Superseded**.

Decisions trace to the CMP migration plan (`docs/superpowers/plans/2026-06-12-cmp-migration-plan.md`, D1–D10) and its evidence bundle (`docs/superpowers/research/2026-06-12-cmp-readiness-audit.json`).

| # | Title | Status |
|---|---|---|
| [0001](0001-platform-targets-and-order.md) | Platform targets and order: Android → Desktop (validation) → iOS | Accepted |
| [0002](0002-single-shared-module-agp-hold.md) | Single `:shared` module; hold AGP 8.x with C4-gate re-check | Accepted |
| [0003](0003-navigation-stay-nav2.md) | Navigation: stay on Nav2 via JetBrains coordinate; defer Nav3 | Accepted |
| [0004](0004-rendering-engine-commonmain-rewrite.md) | Rendering engine: single commonMain rewrite, C2a/C2b split | Accepted (image-space sizing portion: Proposed) |
| [0005](0005-di-koin-interfaces.md) | DI: interfaces + Koin modules; expect/actual only at the edges | Accepted |
| [0006](0006-data-layer-kmp.md) | Data layer: Room/DataStore on KMP coordinates; prepopulated DB path | Accepted |
| [0007](0007-platform-neutral-models.md) | Platform-neutral model layer (TileMode, ImageFormat, MediaRef) | Accepted |
| [0008](0008-minsdk-23-hold.md) | minSdk stays 23 | Accepted |
| [0009](0009-exif-strip-is-a-feature.md) | EXIF stripping on export is a privacy feature | Accepted |
| [0010](0010-bundled-font-and-golden-strategy.md) | Bundled watermark font; two-tier golden strategy; sRGB pin | Accepted |
| [0011](0011-production-ui-parity-baseline.md) | Production v2.10.0 is the UI parity baseline | Accepted |
| [0012](0012-ai-friendly-docs-system.md) | AI-friendly repo: CLAUDE/CONTEXT/ADR + docs-with-code gate | Accepted |
| [0013](0013-desktop-positioning.md) | Desktop: validation target vs shipped product | **Proposed** |
| [0014](0014-parity-micro-decisions.md) | Parity micro-decisions: palette kept, pinch stays off, quality snapping kept | Accepted |
| [0015](0015-parity-vs-compose-idiom-tensions.md) | Parity-vs-Compose-idiom tensions: B (text-edit modal) implemented; A (back arrow) & C (segmented) kept with rationale | Accepted (revertable) |
| [0016](0016-mainactivity-integration-and-legacy-retirement.md) | MainActivity integration: ACTION_SEND→ComposeMainActivity, crash recovery→Compose, legacy Activity retirement | **Proposed** (design for final block) |
