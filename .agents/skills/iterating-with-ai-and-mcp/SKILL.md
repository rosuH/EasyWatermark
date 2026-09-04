---
name: iterating-with-ai-and-mcp
description: Use this skill to drive Compose HotSwan from an AI agent (Claude Code, Cursor, any MCP client) so the agent can edit a Kotlin file, trigger a hot reload, capture a device screenshot, evaluate the result against a design intent, and iterate without a human in the loop. Covers the seven HotSwan MCP tools (hotswan_get_status, hotswan_reload, hotswan_take_screenshot, hotswan_start_snapshot, hotswan_stop_snapshot, hotswan_select_variant, hotswan_build_and_install), the canonical edit-reload-screenshot loop, snapshot-based rollback, and when to fall back to a full install for schema changes. Use when the developer says "get the AI to tune this screen until it matches a mock", asks "can the AI see what changed?" or "can the AI screenshot the device?", sets up a Claude Code or Cursor workflow that needs MCP tool access, or wants AI-driven UI iteration.
license: Apache-2.0. See LICENSE for complete terms.
metadata:
  author: Jaewoong Eum (skydoves)
  keywords:
  - jetpack-compose
  - performance
  - hot-reload
  - hotswan
  - mcp
  - ai-agent
  - claude-code
  - cursor
  - snapshot
---

# Iterating with AI and MCP: let the agent edit, reload, screenshot, and iterate

Compose HotSwan ships an embedded HTTP MCP server inside the IntelliJ plugin. Any MCP-compatible AI client (Claude Code, Cursor, any tool that speaks Model Context Protocol) can call its tools to drive the iteration loop. The agent edits a Kotlin file, calls `hotswan_reload`, captures a screenshot of the running device, evaluates the result against the design intent, and decides the next change. Cycle time is comparable to the human loop, a few seconds per iteration, so the agent can converge on a UI tweak without a human steering each step.

This skill teaches the canonical agent loop, the seven MCP tools (verbatim names), and the safety habits (status check first, snapshot wrapping for rollback, fallback to full install for schema changes) that keep the loop reliable.

## When to use this skill

- The developer wants AI-driven UI iteration: "tune this screen until it matches the mock", "let the agent pick a colour".
- The developer wants the AI to verify its own edits visually instead of guessing whether the change worked.
- The developer is wiring up a Claude Code or Cursor workflow that needs MCP tool access to a running app.
- The user asks "can the AI see what changed?", "can the AI screenshot the device?", "how does Claude Code drive HotSwan?".
- The user mentions "MCP server", "hotswan_reload", "hotswan_take_screenshot", "agent loop", or "snapshot rollback".

## When NOT to use this skill

- Pure code review without runtime feedback (no need for MCP tools at all).
- Release-grade visual regression testing. Use Macrobenchmark plus a screenshot diff harness, not HotSwan snapshots. Cross-link `../../measurement/generating-baseline-profiles/SKILL.md`.
- The change in question would force a full rebuild (parameter add, constructor change, new resource ID). Read `../understanding-hot-reload-limits/SKILL.md` first to classify the edit before reaching for the MCP loop.
- The reload keeps escalating to tier 2 or tier 3 and losing state. Fix that with `../preserving-state-across-reloads/SKILL.md` before adding an autonomous loop on top.

## Prerequisites

- Compose HotSwan installed and the IDE plugin active. Setup lives in `../setting-up-compose-hotswan/SKILL.md`.
- The target app is already running on a connected device or emulator with the HotSwan watcher state reported as `WATCHING`.
- An MCP-capable AI client (Claude Code, Cursor) with MCP server discovery enabled and pointed at the HotSwan plugin's HTTP MCP endpoint.

## MCP tools (verbatim names)

The HotSwan MCP server exposes exactly these seven tools. The agent **MUST NOT** invent additional tool names.

- `hotswan_get_status()`: returns device, app, and watcher state. Call once at the start of every loop to confirm the agent has a connected target and that the watcher is `WATCHING`.
- `hotswan_reload(filePaths)`: explicit reload trigger for the listed file paths. Returns the tier (1 / 2 / 3) that ran. The agent reads the tier to decide whether the previous edit kept the loop fast.
- `hotswan_take_screenshot()`: capture the current device screen. Returns image bytes or a path the agent can read back and inspect.
- `hotswan_start_snapshot()`: begin a snapshot session. After this call, HotSwan auto-captures a screenshot and source state after every reload, so the agent can roll back to any intermediate variant.
- `hotswan_stop_snapshot()`: end the current snapshot session and finalise the history.
- `hotswan_select_variant()`: pick a preferred snapshot from the recorded history. Used to roll the source code back to the chosen variant when the agent decides an earlier iteration was the best one.
- `hotswan_build_and_install()`: fall back to a full install. Used when the agent detects a schema change (new parameter, constructor change, new resource ID) that the hot reload pipeline cannot handle. Treat this as a fallback, not a default.

## Workflow

The canonical agent loop:

### 1. Check status before issuing any edit

Call `hotswan_get_status()`. If `watcher` is not `WATCHING`, surface the issue back to the human and stop. The reload tool will silently no-op if the watcher is not running and the agent will burn cycles wondering why nothing changed on screen.

### 2. Open a snapshot session

Call `hotswan_start_snapshot()` so the loop has a visual record. Each reload inside the session auto-captures, which lets the agent (or the human reviewing afterwards) compare iterations and roll back to any variant.

### 3. Edit the target file

Edit the Kotlin file using whatever file-edit tool the agent has. Keep the change inside one composable scope when possible so the reload stays in tier 1 (cross-link `../preserving-state-across-reloads/SKILL.md`).

### 4. Trigger reload and read the tier

Call `hotswan_reload(["app/src/main/kotlin/com/example/Foo.kt"])`. Read the returned tier. Tier 1 means the loop stayed fast; tier 2 or tier 3 means state was likely lost and the agent should expect to re-establish navigation or transient UI state before the next screenshot.

### 5. Screenshot and evaluate

Call `hotswan_take_screenshot()`. Compare the returned image to the design intent. If acceptable, exit the loop. If not, return to step 3 with a refined edit.

### 6. Fall back for schema changes

If the planned next edit changes a function signature, constructor, interface, or adds a new resource ID, call `hotswan_build_and_install()` to do a full install before continuing. Do not hammer `hotswan_reload` on a schema-violating edit; the reload tool will report failure and the loop will stall.

### 7. Close the loop

When the iteration is acceptable, call `hotswan_stop_snapshot()`. Optionally call `hotswan_select_variant()` to roll the source back to a preferred intermediate variant if the final state was not the best one.

## Patterns

### Pattern: skipping the status check

```text
// WRONG
1. Edit file
2. Call hotswan_reload
3. Wonder why nothing happened
// WRONG because: HotSwan needs the app running and the watcher in WATCHING state. If neither is true the reload silently no-ops. Always call hotswan_get_status first.
```

```text
// RIGHT
1. status = hotswan_get_status()
2. require(status.watcher == "WATCHING")
3. edit, reload, screenshot, iterate
```

### Pattern: reloading without a snapshot session

```text
// WRONG (for visual iteration)
edit -> reload -> screenshot -> discard -> repeat
// WRONG because: the agent loses the ability to roll back to a previous variant. Always wrap visual iteration loops in hotswan_start_snapshot / hotswan_stop_snapshot.
```

```text
// RIGHT
hotswan_start_snapshot()
repeat { edit; hotswan_reload([target]); hotswan_take_screenshot() }
hotswan_stop_snapshot()
```

### Pattern: schema change inside a tight loop

```text
// WRONG
Edit a composable to add a new parameter, then call hotswan_reload.
// WRONG because: parameter additions are a class-schema change. ART rejects the swap and hotswan_reload reports failure. The agent must detect the schema change first and call hotswan_build_and_install instead.
```

```text
// RIGHT
if (editChangesSchema(plannedEdit)) {
    applyEdit(target)
    hotswan_build_and_install()
} else {
    applyEdit(target)
    hotswan_reload([target])
}
```

Cross-link `../understanding-hot-reload-limits/SKILL.md` for the full list of schema-violating edits.

### Pattern: full canonical loop

```text
// RIGHT
status = hotswan_get_status()
require(status.watcher == "WATCHING")
hotswan_start_snapshot()
repeat {
    edit_file(target)
    result = hotswan_reload([target])
    screenshot = hotswan_take_screenshot()
    if (accepts(screenshot, intent)) break
}
hotswan_stop_snapshot()
```

The loop has exactly four moving parts: edit, reload, screenshot, evaluate. Everything else is bookkeeping (status check, snapshot wrapping, optional rollback).

## Mandatory rules

- **MUST** call `hotswan_get_status()` at the start of every loop and confirm the watcher state before issuing edits.
- **MUST** wrap visual iteration loops in `hotswan_start_snapshot()` and `hotswan_stop_snapshot()` so the agent can revert.
- **MUST NOT** invent MCP tool names. Only use the seven names listed in the MCP tools section: `hotswan_get_status`, `hotswan_reload`, `hotswan_take_screenshot`, `hotswan_start_snapshot`, `hotswan_stop_snapshot`, `hotswan_select_variant`, `hotswan_build_and_install`.
- **MUST NOT** call `hotswan_build_and_install()` inside a tight inner loop. It is a fallback for schema changes, not a default; using it as the default destroys the speed advantage of HotSwan.
- **PREFERRED:** combine with `../understanding-hot-reload-limits/SKILL.md` so the agent classifies the planned edit before reaching for `hotswan_reload`.
- **PREFERRED:** combine with `../preserving-state-across-reloads/SKILL.md` so the agent recognises when a reload escalated to tier 2 or tier 3 and loses transient UI state.

## Verification

- [ ] `hotswan_get_status()` returns `WATCHING` before the loop runs
- [ ] Each `hotswan_reload` call reports the tier (1, 2, or 3)
- [ ] `hotswan_take_screenshot()` returns image bytes the agent can inspect
- [ ] Snapshot history is non-empty after the loop and `hotswan_stop_snapshot()` finalises the session
- [ ] Schema-violating edits route through `hotswan_build_and_install()` instead of `hotswan_reload`

## References

- HotSwan MCP server docs: https://github.com/skydoves/compose-hotswan-web (under `/docs/mcp-server`)
- HotSwan agent skill examples: same repo, `/docs/agent-skill`
- HotSwan snapshot docs: same repo, `/docs/snapshot`
- HotSwan JetBrains plugin listing: https://plugins.jetbrains.com/plugin/30551-compose-hotswan/
- Model Context Protocol open standard: https://modelcontextprotocol.io
