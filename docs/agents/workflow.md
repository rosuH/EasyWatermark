# Agent guidance maintenance

[AGENTS.md](../../AGENTS.md) is the current working contract; [issue-tracker.md](issue-tracker.md) defines handoff and evidence locations. Keep durable rules there instead of duplicating them in every skill.

## OpenAI guidance review — 2026-09-05

Reviewed the [official latest-model guide](https://developers.openai.com/api/docs/guides/latest-model), which currently covers GPT-6 Astra. Its prompting guidance informs the project's rules for autonomous follow-through, skill precedence, concise communication, explicit delegation, and proportional verification. The user requested lead-agent orchestration with implementation delegated; substantial tasks follow that division while trivial tasks remain lightweight.

| Surface | Decision |
|---|---|
| `AGENTS.md` / `CLAUDE.md` | Update the canonical contract; preserve the `CLAUDE.md` symlink. |
| Historical `codex-goal*.md` | Label as history so old single-agent, Herdr, or ACSP mandates do not silently override current work. Preserve the mission records. |
| Project skill directories | Audited available skills; no project-owned model/agent orchestration skill needs migration. Preserve upstream Google skills and mirrors; apply current instruction precedence through `AGENTS.md`. |
| Agent configuration | No tracked Codex model configuration found. The ignored `.claude/settings.local.json` contains local permissions, not model selection; leave local permissions and global settings intact. |
| Application/API | No API migration is part of this documentation change. Preserve the offline application contract and the user's configured model. |

For a future explicit API migration, re-read the current official guide and check model availability and request compatibility in the actual consumer. API features such as async tool calls and mid-turn steering require host implementation; documenting them does not enable them in an agent runtime. Avoid creating configuration or an orchestration framework solely to mirror the guide.

## Acceptance

For guidance-only edits, check the diff for whitespace, valid local links, symlink integrity, consistent instruction precedence, and unintended file changes. Delegate execution of relevant product checks when product behavior changes; retain required CI gates and applicable device evidence. A worker's success label alone does not establish acceptance.
