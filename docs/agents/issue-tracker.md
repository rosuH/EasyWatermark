# Issue tracking and task handoff

EasyWatermark does not keep a repository-local migration tracker or `.scratch` task backend.

## Sources of truth

| Source | Role |
|---|---|
| Current user request and `AGENTS.md` | Active scope, authorization, and agent workflow |
| [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md), [codex-goal-v2.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal-v2.md) | Historical migration missions retained in Git; not current instructions |
| `AGENTS.md`, `docs/CONTEXT.md`, `docs/adr/` | Current technical and architecture rules |
| Current Git history and relevant PR | Landed implementation and review state |
| Available subagent tools; ACSP when explicitly selected | Temporary delegated-task handoff, result, verification, and review records |
| Verified artifacts outside the repository | Ephemeral execution evidence |

Do not recreate `.scratch`, `spec.md`, completed local issue archives, or `evidence/**` in the product branch.

## Conventions

- Start from the current user request, Git state, and the standing contracts above.
- For delegated work, use the available subagent tools with bounded ownership and acceptance evidence. When the user selects ACSP, use its existing session protocol. Keep result/verification artifacts outside the repository.
- Keep product/community GitHub Issues separate from internal migration orchestration. Do not create or mutate GitHub Issues for agent task tracking without explicit owner authorization.
- Do not reactivate root `task_plan.md`, `progress.md`, or `findings.md`; they are historical records only.
- Do not commit result/verification/diagnosis dumps, screenshots, logs, matrices, or benchmark-result JSON.
