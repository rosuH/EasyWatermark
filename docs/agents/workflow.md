# Agent guidance maintenance

[AGENTS.md](../../AGENTS.md) is the current working contract; [issue-tracker.md](issue-tracker.md) defines handoff and evidence locations. Keep durable rules there instead of duplicating them in every skill.

Procedures are current. The log records what each review or refresh changed and why.

## Procedures

### Changing guidance

Keep guidance changes tied to an observed failure or durable project requirement. Remove redundant instructions instead of adding another procedural layer. Check that small edits stay small and that substantial tasks still reach their requested outcome; static review alone does not establish model behavior improvements. Change one behavior rule at a time where possible, so its effect stays attributable.

For a model or API migration, re-read the vendor's current guide and check model availability and request compatibility in the actual consumer. API features such as async tool calls and mid-turn steering require host implementation; documenting them does not enable them in an agent runtime. Avoid creating configuration or an orchestration framework solely to mirror a guide.

### Acceptance

For guidance-only edits, check the diff for whitespace, valid local links, symlink integrity, consistent instruction precedence, and unintended file changes. Delegate execution of relevant product checks when product behavior changes; retain required CI gates and applicable device evidence. A worker's success label alone does not establish acceptance.

### Project-owned skills

Keep descriptions short and specific about the trigger. Use the root skill as a router to relevant supporting docs and scripts; avoid duplicating the repository contract.

### Upstream skills

Google Android skills: refresh with `android update`, then `android skills update --all --project=. --agent=common,claude-code,openclaw`. Those agents are the three project mirrors (`.agents/skills`, `.claude/skills`, `skills/`). `update --all` refreshes installed skills only; install a new catalog id with `android skills add <id> --project=. --agent=common,claude-code,openclaw`. Do not omit `--agent`: a bare update also writes every detected agent, including home directories. Preserve upstream `SKILL.md` / `references/` and mirrors rather than hand-editing them. The CLI overlay does not delete files removed upstream; drop those leftovers after a refresh. Keep project-owned skills such as `e2e-testmap` and Compose / HotSwan skills.

Local patch, kept on every sync until upstream fixes the inference: remove Play Policy `group` / `groupId` / `GROUP` identity fallbacks in all three `orchestrator.py` mirrors. These are Maven coordinates, not Android application IDs; they can hide the real Manifest package, and `group = project.property(...)` is misread as `project.property`. Run `python3 scripts/test_android_skill_identity.py` after a refresh to verify Manifest fallback and explicit `applicationId` precedence.

Install a catalog skill only when the product uses its library. Not installed for that reason: `android-for-cars-parked-apps`, `appfunctions`, `camerax`, `display-glasses-with-jetpack-compose-glimmer`, `engage-sdk-integration`, `glance-canonical-layouts`, `leanback-to-compose-tv-migration`, `litert-runtime`, `media3-cast-integration`, `ml-kit-genai-prompt-api` (the product stays fully offline), `play-billing-library-version-upgrade`, `restore-credentials`, `verified-email`, `wear-compose-m3`, `wear-widgets`.

Routing: do not add a default `AGENTS.md` route for `android-permissions-security`; use it when the task is a manifest, permission, or IPC audit. Jank, startup, and trace work routes to `android-profiler` only; the Perfetto workflows live inside it.

skydoves/compose-performance-skills (Compose performance and HotSwan): the copy lives in `.agents/skills`, `.claude/skills` holds one symlink per skill, and they stay out of the `skills/` mirror.

## Log

### OpenAI guidance review — 2026-09-05

Reviewed the [official latest-model guide](https://developers.openai.com/api/docs/guides/latest-model), which currently covers GPT-6 Astra. Its prompting guidance informs the project's rules for autonomous follow-through, skill precedence, concise communication, explicit delegation, and proportional verification. The user requested lead-agent orchestration with implementation delegated; substantial tasks follow that division while trivial tasks remain lightweight.

| Surface | Decision |
|---|---|
| `AGENTS.md` / `CLAUDE.md` | Update the canonical contract; preserve the `CLAUDE.md` symlink. |
| Historical mission files | Remove the obsolete root files; preserve their records in Git and point historical citations to a fixed revision. Current work follows `AGENTS.md`. |
| Project skill directories | Audited available skills; no project-owned model/agent orchestration skill needs migration. Preserve upstream Google skills and mirrors; apply current instruction precedence through `AGENTS.md`. |
| Agent configuration | No tracked Codex model configuration found. Preserve local and global settings; they are outside this repository guidance update. |
| Application/API | No API migration is part of this documentation change. Preserve the offline application contract and the user's configured model. |

### Prompt and skill review — 2026-09-12

Applied [Rethinking skills and prompts for GPT-6 Astra](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra): keep task-specific reading conditional, select skills by workflow, define completion, and avoid repeated approval for authorized local work. The contract retains project invariants, required checks, device evidence, and the owner's delegation preference across models. This review changes guidance only, not skill packages or model settings.

### Official Android skills — 2026-10-02

Refreshed from [android/skills v1.0.13](https://github.com/android/skills/releases/tag/v1.0.13) with Android CLI `1.0.16486076`. Catalog addition: `android-permissions-security`. Dropped catalog leftovers the CLI left in place: `camera1-to-camerax`, `jetpack-compose-m3`, `perfetto-sql`, and `perfetto-trace-analysis`. First applied the Play Policy identity patch described under Upstream skills.

### Official Android skills — 2026-10-20

Refreshed from [android/skills v1.0.15](https://github.com/android/skills/releases/tag/v1.0.15) with Android CLI `1.0.16625370`. Catalog addition: `jetpack-compose-performance`. Dropped files v1.0.15 removed and the CLI left behind: Adaptive `references/android/develop/ui/compose/tooling/debug.md`, and Android Profiler `guiding_principles.md` plus `references/hints/`.

skydoves/compose-performance-skills at `1b32f817` (2026-06-16) already matched `.agents/skills` and `.claude/skills`. Added the three upstream skills that were missing from those mirrors: `choosing-derivedstateof`, `collecting-flows-safely`, and `configuring-r8-for-compose`.

v1.0.15 still infers an Android application id from Maven `group` / `groupId` / `GROUP`. Reapplied the local patch in all three `orchestrator.py` mirrors. `python3 scripts/test_android_skill_identity.py` passed 42 checks.

### Claude Fable 5.2 guidance review — 2026-11-27

Reviewed [Prompting Claude Fable 5.2](https://platform.claude.com/docs/en/build-with-claude/prompt-engineering/prompting-claude-fable-5-2) against `AGENTS.md`, `docs/agents/`, `eval/`, the `e2e-testmap` skill, and the installed skills.

| Guide topic | Decision |
|---|---|
| Subagents | The owner keeps lead orchestration with delegated implementation and set the tiers: Fable 5.2 leads and writes no product code, Opus 5.5 implements (four workstreams at once, one worker each), Sonnet 5.5 runs checks and other mechanical work. `AGENTS.md` names the model families, not versions. |
| Approvals | Replaced "publishing, messaging, merging, destructive cleanup" with named actions. Named the steps that need no approval, including messages between the lead and its workers. |
| Cautions | Machine rules state their reason and scope once, in `AGENTS.md`; four other files point there. "Warn before" now means say so and continue. Agents shut down the simulators they booted. Added one Gradle build at a time, because four parallel workstreams multiply build load. |
| Code ownership | Not added. The ownership sentence invites unrequested restructuring, which conflicts with the v2.10.0 parity baseline. Use it per task for a scoped refactor. |
| Finishing work, progress updates | Not added. Claude Code supplies both. Revisit if a turn ends with work still owed. |
| Report style | Deferred until a final report reads dense. Candidate: write for a reader who has not seen the tool calls or files. |
| Reasoning in responses, think-less lines, token budgets | None found in project guidance or installed skills. |
| Effort | No repository setting. Start at `high`; try `medium` for small and docs-only edits. |

Skills: removed 11 catalog skills whose libraries the product does not use (listed under Upstream skills) and fixed the `goldie` route, which named a skill that is not in the project mirrors. Converted the three skydoves skills added on 2026-10-20 to symlinks in `.claude/skills`, matching the other 23.

Open: `skills-lock.json` has no entries for those three skydoves skills. The owner has not decided on `agp-9-upgrade`, `migrate-xml-views-to-jetpack-compose`, `styles`, and the four HotSwan skills.

Out of scope: the Testmap AI controller prompts target Grok and Jev, not Claude.
