# Issue tracker: compact local tickets + ACSP

**Operational task backend for EasyWatermark migration work is local files + ACSP.**

Public GitHub Issues are **not** used for migration program/spec/tracer ops (owner-revoked for this purpose). Product/community issues on `rosuH/EasyWatermark` may still exist historically; **agents must not** run `gh issue create|comment|edit|close` (or equivalent) for migration planning or slice tracking.

## Lightweight operational source of truth

| Path | Role |
|------|------|
| `.scratch/easywatermark-kmp-cmp-migration/issues/13-roadmap-status.md` | Lightweight status page (six fields) |
| `.scratch/easywatermark-kmp-cmp-migration/issues/58-l0-integration-pr-landing-plan.md` | **Current slice plan** (L0 integration / PR landing) |
| `.scratch/easywatermark-kmp-cmp-migration/issues/13-post-audit-correctness-quality-roadmap.md` | Completed architecture capability spine + residual waivers |
| `codex-goal-v2.md` | Sole process/mission contract |
| ACSP (`~/.agent-cowork/sessions/easywatermark/`) | Active multi-agent handoff: task, review, result, verification |
| Verified artifacts **outside the repo** | Slice execution evidence |

There is **no** in-repo `spec.md`, completed issue archive (01–12, 14–57), or `evidence/**` backend. Do not recreate them on the product branch.

## Conventions

- **Status first:** open `13-roadmap-status.md` for `已完成 / 当前 / 下一步 / 阻塞 / 权威计划 / 最近验收 SHA`.
- **Current plan:** open the named slice plan (Issue 58 for L0).
- **Execute:** claim the ACSP session; follow `task.md` / `review.md`; prove with `result.md` / `verification.md`.
- **Update status / acceptance:** edit the status page and current plan after review (honest `BLOCKED_INTEGRATION_FAILURE` when publication is blocked).
- **Publish new work:** add a new numbered issue file only when the commander authorizes expansion. Do not restore the deleted evidence archive.
- **“Publish to the issue tracker”** means **write/update local compact ticket files** — **not** GitHub, and **not** screenshots/logs/result dumps in the repository.

## Forbidden operational actions

- `gh issue create` / `gh issue comment` / `gh issue edit` / `gh issue close` for migration tickets
- Reactivating root `task_plan.md` / `progress.md` / `findings.md` as execution workflow (those remain **historical evidence only**)
- Committing `evidence/**`, result/verification/diagnosis dumps, screenshots, logs, matrices, or benchmark result JSON into the repository
- Claiming ACSP is retired while authorized slices still use `~/.agent-cowork/sessions/easywatermark/`

## Local ticket template (reminder)

```markdown
# <NN> — <Ticket title>

**What to build:** end-to-end behaviour from the user/agent perspective.

**Blocked by:** numbers/titles, or "None — can start immediately".

**Status:** ready-for-agent | BLOCKED_INTEGRATION_FAILURE | …

- [ ] Acceptance criterion 1
- [ ] Acceptance criterion 2
```

## Triage vocabulary

Canonical roles (`ready-for-agent`, etc.) may appear as **Status** text in local tickets. Repo GitHub labels are optional and **must not** be invented for migration ops. See `docs/agents/triage-labels.md` for vocabulary only.
