# 13 — Post-audit correctness and quality architecture roadmap

**What to build:** Release-grade architecture closeout after the post-P1 audit: ownership, export honesty, reliability, performance budgets, UX/a11y, and CI/distribution — without retaining a local evidence archive in the product branch.

**Blocked by:** none (architecture Goal exit recorded as DONE_WITH_CONCERNS).

**Status:** completed under residual waivers (physical device labs, signed Desktop installers, multi-API instrumented farm, H3 hard CI SLOs, multi-scene Session).

## Completed capability spine (product tree)

| Stage | Outcome |
|---|---|
| A–C | Shared paint path, Desktop/iOS export cutover, C4 output contract |
| D | Typed export, cancellation, Android/iOS persistence honesty, host recovery UI |
| E | Session owns route/selection/offset; ownership fitness is source-checked |
| F | Desktop batch locality; typed editor events |
| G | Crash-atomic Desktop save; seed/schema hardening; fault matrix; iOS import memory lifecycle |
| H | Clamp-drag baseline/fix; Android benchmark/profile lane; copy reduction; budget proposal residual |
| I | Export recovery contract; adaptive layout; a11y/focus; motion policy; privacy confidence |
| J | Permanent iOS PR gate; Android backup/matrix lanes; Desktop paths/WebP honesty; dep policy; Shared.framework surface budget |
| K | Graduation **DONE_WITH_CONCERNS** (owner residual track optional, not Goal-blocking) |

## Residuals (explicit, not Goal-blocking)

- Physical a11y / multi-device labs
- Signed three-OS Desktop installers
- Multi-API instrumented Android farm
- H3 hard CI performance SLOs
- Multi-scene iOS Session design (single-scene is current release)

## Tracker policy after L0

Only this roadmap, `13-roadmap-status.md`, and `58-l0-integration-pr-landing-plan.md` remain under `.scratch/easywatermark-kmp-cmp-migration/issues/`. No `spec.md`, no `evidence/**`, no completed issue archive 14–57.
