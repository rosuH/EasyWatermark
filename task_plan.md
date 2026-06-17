# Compose Migration Working Plan

## Goal

Create a file-based, PM-style migration plan that helps the developer incrementally move EasyWatermark from legacy Android Views/Fragments to Jetpack Compose while improving Compose skills and minimizing regression risk.

## Current Phase

Phase J - S4d-3 accepted; next is S4d-4 icon raster / image decode bootstrap for the commonMain renderer

## Phases

- [x] Phase 1 - Review repository state and identify the active Compose/View split
- [x] Phase 2 - Capture findings and migration constraints
- [x] Phase 3 - Write design spec and implementation plan files
- [x] Phase 4 - Review with the user and refine milestones/tasks
- [x] Phase 5 - Guided milestone execution loop through the View-to-Compose closure
- [x] Phase 6 - Run chat-driven execution/review loop through S3d cleanup
- [x] Phase I - Reconcile the CMP plan after S3a-S3d and design S4a renderer-commonization readiness
- [ ] Phase J - Execute S4 slices toward moving watermark composition into commonMain

## Key Decisions

- Use incremental migration, not a big-bang rewrite.
- Prioritize parity and consolidation before purity.
- View-to-Compose is now functionally complete: `ComposeMainActivity` is the sole Activity, the legacy Activity/dialog/panel/adapter/base stack is deleted, and `EditorScreen` renders preview through Compose `Canvas`.
- Do not reintroduce a `ViewInfo` or `AndroidView`-bridged renderer contract. `WaterMarkImageView` and `ViewInfo` are deleted; new rendering work goes through `WatermarkRenderer` / `:shared` commonMain.
- `WatermarkGeometry` is already commonMain and drives both preview and export cell sizing. The remaining C2 work is the actual cell composition/drawing rewrite into commonMain Compose graphics.
- Keep Android shippable at every step. Renderer changes require goldens plus visual screenshot inspection, not byte-size inference.
- Use project files as the shared source of truth for planning and iteration.
- Use a chat-driven workflow: I assign the next task in chat, the developer implements it, then I review and update records before moving on.

## Deliverables

- `task_plan.md`
- `findings.md`
- `progress.md`
- `docs/superpowers/specs/2026-04-18-compose-migration-design.md`
- `docs/superpowers/plans/2026-04-18-compose-migration-plan.md`

## Open Questions

- How should S4d-4 model icon/image raster input without pulling Android `Bitmap` or decode APIs into commonMain?
- Should S4d-4 only prove a synthetic `ImageBitmap`/platform-decoded icon cell, or also define the production image-decode boundary for Android/Desktop/iOS?
- Before production wiring, which bundled-font and Android-vs-commonMain pixel gates are mandatory for CJK/emoji parity?

## CMP Migration Planning (started 2026-06-12)

### Goal

Research and produce a phased, decision-complete plan to take EasyWatermark from single-platform Jetpack Compose (current branch `feat/migrate_to_compose`) to Compose Multiplatform (CMP), building on — not replacing — the in-flight View→Compose migration.

### Phases

- [x] Phase A — Restore context; verify branch state and existing plans
- [x] Phase B — Tooling: installed Android CLI 1.0.15498356 (brew, /opt/homebrew/bin/android); installed official skills into .claude/skills/: migrate-xml-views-to-jetpack-compose, adaptive, navigation-3 (no standalone "jetpack-compose" skill exists in registry; jetpack-compose-m3 is Wear-only)
- [x] Phase C — Codebase CMP-readiness audit via multi-agent workflow (13 agents, 783k tokens, 0 failures; bundle saved to `docs/superpowers/research/2026-06-12-cmp-readiness-audit.json`)
- [x] Phase D — Official guidance research (JetBrains KMP docs via android-CLI KB + Context7 + web; androidx KMP matrix; ecosystem replacements) — completed inside same workflow
- [x] Phase E — Synthesized phased CMP migration plan → `docs/superpowers/plans/2026-06-12-cmp-migration-plan.md` (phases C1–C6, decisions D1–D10)
- [x] Phase F — Adversarial review (fact-check sonnet: 29 claims, 4 wrong fixed; architecture opus: 16 findings, 6 major gaps closed) → plan revised to v1.1; awaiting developer answers to the 7 "Decisions Needed"

### Developer Goals (recorded 2026-06-13)

1. CMP + KMP as the end state.
2. UI fully aligned with the PRODUCTION release (v2.10.0 View UI on master is the parity baseline (branch versionName 2.9.6 is stale) — NOT the half-migrated Compose branch); audit the current gap first.
3. Elegant, best-practice, high-performance, stable.
4. Continuously accumulate context during implementation → AI-friendly repository (CLAUDE.md + docs/adr/ + docs/CONTEXT.md + committed .claude/skills/ + docs-with-code review gate).

- [x] Phase G — Execution kickoff DONE (2026-06-13): scaffolding shipped (CLAUDE.md, docs/CONTEXT.md, docs/adr/0001–0014 — only 0013 desktop-positioning still Proposed); UI-parity audit ran (8/8 screens, workflow wf_d279ab26-867) → backlog at docs/superpowers/research/2026-06-13-ui-parity-backlog.md
- [x] Phase H — C1 parity stream / View-to-Compose closure DONE: production-parity theme/text/filmstrip/save-sheet work landed; About/OpenSource/recovery/share-in migrated; legacy stack deleted; Compose Canvas preview shipped; `WaterMarkImageView`/`ViewInfo` retired; S3d orphan layout cleanup completed.
- [x] Phase I — S4/C2 remainder planning: reconcile the original C2a/C2b plan with the shipped S3a-S3d slices; design the next renderer-commonization task without regressing Android.
- [x] Phase J — C4/CMP-9547/Compose-lineage gate before adding Compose graphics/text dependencies to `:shared`.
- [x] Phase J.1 — C4.3 single Compose-lineage design gate (`:app` + `:shared`) before retrying commonMain renderer dependencies.
- [x] Phase J.2 — C4.3 implementation: land one clean Android Compose lineage (Option B first, Option A fallback) with strict golden + full UI parity gate.
- [x] Phase J.3 — S4d-2: first commonMain renderer implementation slice on top of the unified Compose lineage. Landed an offscreen `ImageBitmap` cell composer scaffold in `:shared/commonMain`, verified by `:shared:desktopTest`, and kept Android preview/export unwired.
- [x] Phase J.4 — S4d-3: add text raster / `TextMeasurer` bootstrap for the commonMain cell artifact, with explicit Android renderer parity gates before any production wiring. Accepted via ACSP session `20260617-075849--s4d3-text-raster-bootstrap`.
- [ ] Phase J.5 — S4d-4: add icon raster / image decode bootstrap for the commonMain cell artifact, still with no production preview/export wiring.

### Key Decisions (CMP)

- Model selection per task: haiku = mechanical inventories; sonnet = standard code analysis & doc research; fable (inherited) = graphics-core deep dive + final synthesis.
- CMP planning builds ON TOP of the existing View→Compose milestones; finishing the single-platform Compose shell remains a prerequisite stream.
- As of 2026-06-16, that single-platform Compose shell prerequisite is satisfied. The next meaningful CMP work is not more View cleanup; it is renderer commonization readiness and then C3 dependency de-Android-ization.
- S4d-1 proved commonMain Compose cannot be introduced safely until `:app` and `:shared` share one coherent Compose lineage. C4.3 is now implemented and accepted via Option B: AndroidX Compose BOM bumped to `2026.05.01` / core Compose `1.11.2`, `org.jetbrains.compose` `1.11.1` applied to `:shared`, and `:app` substitutes residual `org.jetbrains.compose.*` Android runtime requests to `androidx.compose.*:1.11.2`.

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| Local `android` command was the deprecated SDK tool, not the newer Android CLI described by the skill | 1 | Switched to repository inspection plus official Android documentation for migration guidance |
| (2026-06-12) Same deprecated `android` still on PATH (`/usr/local/bin/android`, `sdk/tools/android`) | 1 | New official Android CLI 1.0 exists (developer.android.com/tools/agents/android-cli); installing it this session |
