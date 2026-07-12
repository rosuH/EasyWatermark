# Issue tracker: local Matt Pocock tickets

**Operational task backend for EasyWatermark migration work is local files only.**

Public GitHub Issues are **not** used for migration program/spec/tracer ops (owner-revoked for this purpose). Product/community issues on `rosuH/EasyWatermark` may still exist historically; **agents must not** run `gh issue create|comment|edit|close` (or equivalent) for migration planning or slice tracking.

## Source of truth

| Path | Role |
|------|------|
| `.scratch/easywatermark-kmp-cmp-migration/spec.md` | Program specification (to-spec local backend) |
| `.scratch/easywatermark-kmp-cmp-migration/issues/*.md` | Tracer-bullet tickets (to-tickets local backend), numbered `01`… in dependency order |
| `codex-goal-v2.md` | Sole process/mission contract |
| Accepted worker brief + verified artifacts | Slice execution evidence |

## Conventions

- **List frontier:** tickets whose **Blocked by** are all done (or `None — can start immediately`).
- **Read a ticket:** open the corresponding `.md` under `.scratch/easywatermark-kmp-cmp-migration/issues/`.
- **Update status / acceptance:** edit that ticket file (checkbox status, brief outcome notes). Prefer small, clear edits after Codex accepts a slice.
- **Publish new work:** add a new numbered issue file + link it from `spec.md` ticket index when Codex authorizes to-tickets expansion.
- **“Publish to the issue tracker”** (Matt skills) means **write/update local files under `.scratch/<feature-slug>/`** — **not** GitHub.

## Forbidden operational actions

- `gh issue create` / `gh issue comment` / `gh issue edit` / `gh issue close` for migration tickets
- Reactivating root `task_plan.md` / `progress.md` / `findings.md` as execution workflow (those remain **historical evidence only**)

## Local ticket template (reminder)

```markdown
# <NN> — <Ticket title>

**What to build:** end-to-end behaviour from the user/agent perspective.

**Blocked by:** numbers/titles, or "None — can start immediately".

**Status:** ready-for-agent

- [ ] Acceptance criterion 1
- [ ] Acceptance criterion 2
```

## Triage vocabulary

Canonical roles (`ready-for-agent`, etc.) may appear as **Status** text in local tickets. Repo GitHub labels are optional and **must not** be invented for migration ops. See `docs/agents/triage-labels.md` for vocabulary only.
