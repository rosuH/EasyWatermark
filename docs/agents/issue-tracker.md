# Issue tracking and task handoff

EasyWatermark does not keep a repository-local migration tracker or `.scratch` task backend.

## Sources of truth

| Source | Role |
|---|---|
| Current user request and `AGENTS.md` | Active scope, authorization, and agent workflow |
| `AGENTS.md`, `docs/CONTEXT.md`, `docs/adr/` | Current technical and architecture rules |
| Current Git history and relevant PR | Landed implementation and review state |
| Historical migration records in Git ([`codex-goal.md`](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md), [`codex-goal-v2.md`](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal-v2.md), [`task_plan.md`](https://github.com/rosuH/EasyWatermark/blob/dae43630/task_plan.md), [`progress.md`](https://github.com/rosuH/EasyWatermark/blob/dae43630/progress.md), [`findings.md`](https://github.com/rosuH/EasyWatermark/blob/dae43630/findings.md), [`docs/migration-log.md`](https://github.com/rosuH/EasyWatermark/blob/dae43630/docs/migration-log.md), [`docs/parity/`](https://github.com/rosuH/EasyWatermark/tree/dae43630/docs/parity), [`docs/superpowers/`](https://github.com/rosuH/EasyWatermark/tree/dae43630/docs/superpowers)) | Archived migration history retained in Git; not current instructions |
| Available subagent tools | Temporary delegated-task handoff, result, verification, and review records |
| Verified artifacts outside the repository | Ephemeral execution evidence |

Do not recreate `.scratch`, `spec.md`, `task_plan.md`, `progress.md`, `findings.md`, `docs/migration-log.md`, `docs/superpowers/`, completed local issue archives, or `evidence/**` in the product branch.

## Conventions

- Start from the current user request, Git state, and the standing contracts above.
- For delegated work, use the available subagent tools with bounded ownership and acceptance evidence. Keep result/verification artifacts outside the repository.
- Keep product/community GitHub Issues separate from internal orchestration. Do not create or mutate GitHub Issues for agent task tracking without explicit owner authorization.
- Do not commit result/verification/diagnosis dumps, screenshots, logs, matrices, or benchmark-result JSON.
