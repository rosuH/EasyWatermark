# Issue tracking and task handoff

EasyWatermark does not keep a repository-local migration tracker or `.scratch` task backend.

## Sources of truth

| Source | Role |
|---|---|
| `codex-goal-v2.md` | Standing mission/process contract |
| `AGENTS.md`, `docs/CONTEXT.md`, `docs/adr/` | Current technical and architecture rules |
| Git history and PR #358 | Landed implementation and review state |
| ACSP (`~/.agent-cowork/sessions/easywatermark/`) | Temporary delegated-task handoff, result, verification, and review records |
| Verified artifacts outside the repository | Ephemeral execution evidence |

Do not recreate `.scratch`, `spec.md`, completed local issue archives, or `evidence/**` in the product branch.

## Conventions

- Start from the current user request, Git state, and the standing contracts above.
- For delegated work, use one bounded ACSP session and keep its result/verification outside the repository.
- Keep product/community GitHub Issues separate from internal migration orchestration. Do not create or mutate GitHub Issues for agent task tracking without explicit owner authorization.
- Do not reactivate root `task_plan.md`, `progress.md`, or `findings.md`; they are historical records only.
- Do not commit result/verification/diagnosis dumps, screenshots, logs, matrices, or benchmark-result JSON.
