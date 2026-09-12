# Agent guidance maintenance

[AGENTS.md](../../AGENTS.md) is the current working contract; [issue-tracker.md](issue-tracker.md) defines handoff and evidence locations. Keep durable rules there instead of duplicating them in every skill.

## OpenAI guidance review — 2026-09-05

Reviewed the [official latest-model guide](https://developers.openai.com/api/docs/guides/latest-model), which currently covers GPT-6 Astra. Its prompting guidance informs the project's rules for autonomous follow-through, skill precedence, concise communication, explicit delegation, and proportional verification. The user requested lead-agent orchestration with implementation delegated; substantial tasks follow that division while trivial tasks remain lightweight.

| Surface | Decision |
|---|---|
| `AGENTS.md` / `CLAUDE.md` | Update the canonical contract; preserve the `CLAUDE.md` symlink. |
| Historical mission files | Remove the obsolete root files; preserve their records in Git and point historical citations to a fixed revision. Current work follows `AGENTS.md`. |
| Project skill directories | Audited available skills; no project-owned model/agent orchestration skill needs migration. Preserve upstream Google skills and mirrors; apply current instruction precedence through `AGENTS.md`. |
| Agent configuration | No tracked Codex model configuration found. Preserve local and global settings; they are outside this repository guidance update. |
| Application/API | No API migration is part of this documentation change. Preserve the offline application contract and the user's configured model. |

For a future explicit API migration, re-read the current official guide and check model availability and request compatibility in the actual consumer. API features such as async tool calls and mid-turn steering require host implementation; documenting them does not enable them in an agent runtime. Avoid creating configuration or an orchestration framework solely to mirror the guide.

## Acceptance

For guidance-only edits, check the diff for whitespace, valid local links, symlink integrity, consistent instruction precedence, and unintended file changes. Delegate execution of relevant product checks when product behavior changes; retain required CI gates and applicable device evidence. A worker's success label alone does not establish acceptance.

## Prompt and skill review — 2026-09-12

Applied [Rethinking skills and prompts for GPT-6 Astra](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra): keep task-specific reading conditional, select skills by workflow, define completion, and avoid repeated approval for authorized local work. The contract retains project invariants, required checks, device evidence, and the owner's delegation preference across models.

Keep future guidance changes tied to an observed failure or durable project requirement. Remove redundant instructions instead of adding another procedural layer. Check that small edits stay small and that substantial tasks still reach their requested outcome; static review alone does not establish model behavior improvements.

When maintaining project-owned skills, keep descriptions short and specific about the trigger. Use the root skill as a router to relevant supporting docs and scripts; avoid duplicating the repository contract. This review changes guidance only, not skill packages or model settings.

For upstream Google Android skills, use `android update` and `android skills add --all --project=.` when refreshing them. Preserve upstream `SKILL.md` / `references/` and mirrors rather than hand-editing them.
