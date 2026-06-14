# Compose Migration Working Plan

## Goal

Create a file-based, PM-style migration plan that helps the developer incrementally move EasyWatermark from legacy Android Views/Fragments to Jetpack Compose while improving Compose skills and minimizing regression risk.

## Current Phase

Phase 5 - Guided milestone execution and review loop

## Phases

- [x] Phase 1 - Review repository state and identify the active Compose/View split
- [x] Phase 2 - Capture findings and migration constraints
- [x] Phase 3 - Write design spec and implementation plan files
- [x] Phase 4 - Review with the user and refine milestones/tasks
- [ ] Phase 5 - Expand the selected milestone into coding checklists
- [ ] Phase 6 - Run chat-driven execution/review loop milestone by milestone

## Key Decisions

- Use incremental migration, not a big-bang rewrite.
- Prioritize parity and consolidation before purity.
- Keep `WaterMarkImageView` bridged through `AndroidView` until entry, navigation, and editor state are stable.
- Keep legacy `MainActivity` alive as a compatibility path until Compose fully covers `ACTION_SEND`.
- Use project files as the shared source of truth for planning and iteration.
- Use a chat-driven workflow: I assign the next task in chat, the developer implements it, then I review and update records before moving on.

## Deliverables

- `task_plan.md`
- `findings.md`
- `progress.md`
- `docs/superpowers/specs/2026-04-18-compose-migration-design.md`
- `docs/superpowers/plans/2026-04-18-compose-migration-plan.md`

## Open Questions

- Should `MainActivity` become a thin trampoline, or should share-intent handling move directly into `ComposeMainActivity`?
- How much automated regression coverage is realistic before migration starts?
- How far should Milestone 0 go before we switch to Milestone 1?

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
- [ ] Phase H — C1 parity stream execution: work order = theme tokens (P0-A) → save-sheet quality-default bug → filmstrip (P0-B) → text-edit parity sheet → top bar/spacing/style polish → save-sheet remainder; plus the 4 verify items in the backlog

### Key Decisions (CMP)

- Model selection per task: haiku = mechanical inventories; sonnet = standard code analysis & doc research; fable (inherited) = graphics-core deep dive + final synthesis.
- CMP planning builds ON TOP of the existing View→Compose milestones; finishing the single-platform Compose shell remains a prerequisite stream.

## Errors Encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| Local `android` command was the deprecated SDK tool, not the newer Android CLI described by the skill | 1 | Switched to repository inspection plus official Android documentation for migration guidance |
| (2026-06-12) Same deprecated `android` still on PATH (`/usr/local/bin/android`, `sdk/tools/android`) | 1 | New official Android CLI 1.0 exists (developer.android.com/tools/agents/android-cli); installing it this session |
