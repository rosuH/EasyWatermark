# ADR-0012: AI-friendly repository — CLAUDE.md + CONTEXT.md + ADRs + docs-with-code gate

**Status:** Accepted (2026-06-13) · **Plan ref:** Goal 4, plan v1.2 toolkit section

## Context
Developer goal: accumulate context continuously so any agent (or future human) can be productive without re-deriving decisions. The repo had no CLAUDE.md and no decision log.

## Decision
- `CLAUDE.md` (root): commands, big-picture architecture, migration state, agent conventions.
- `docs/CONTEXT.md`: domain glossary + invariants — the shared vocabulary.
- `docs/adr/`: one record per decision; `grill-with-docs` maintains them at decision points; Proposed → Accepted requires developer sign-off.
- `task_plan.md`/`findings.md`/`progress.md`: session memory (planning-with-files), already in force.
- `.claude/skills/` committed to git so installed skills travel with the repo; stabilized procedures get distilled into project skills (skill-creator).
- **Docs-with-code gate:** every milestone PR ships its context delta or states "no doc impact".

## Consequences
- Reviews check docs alongside code; drift in CLAUDE.md/CONTEXT.md is treated as a bug.
- CI must stay a trustworthy verifier surface (goldens + tests green = agents may rely on it).
