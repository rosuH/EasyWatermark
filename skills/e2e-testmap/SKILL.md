---
name: e2e-testmap
description: >
  Run EasyWatermark change-to-verify paths (testmap + Agent Device).
  Use when the user says 跑测试, 跑链路, run e2e, testmap, Agent Device,
  pick-to-editor, 验证改动, 合入前, 上线前, 出报告, or /e2e-testmap.
  Default agent task is edge:<id>@android#agent or @ios#agent — not Artemis,
  not a GitHub required check.
---

# e2e-testmap

Name this skill when used. Map and payloads stay the source of truth: `docs/testmap/map.yaml`, `docs/testing/agent-device-cases.json`. Do not invent a second topology.

Two modes. **Mode B is the merge/ship gate for this skill.** Mode A is debug.

## Mode A — run one path

Triggers: a named edge (`pick-to-editor`, `launch-to-about`, …), “跑测试 / 跑链路” without a change range.

```bash
scripts/e2e-console.sh
scripts/e2e-run.sh edge:<id>@android#agent
scripts/e2e-run.sh edge:<id>@ios#agent
```

If `http://127.0.0.1:8931/api/status` is up, POST the task so the watch pane follows. Queue `edge:<id>@android#agent` (the runner mirrors a supported `@ios#agent` twin and runs the two lanes in parallel). The execution view shows the selected mobile lanes with their own steps. Desktop host checks remain available as tasks; their witness images are not device video. Do not auto-open the native scrcpy window.

```bash
curl -sS -X POST http://127.0.0.1:8931/api/run \
  -H 'content-type: application/json' \
  -d '{"tasks":["edge:<id>@android#agent"],"device":"<serial-or-udid>"}'
```

Report run id, state, evidence dir. Do not mint Confirm. Skip a platform when `supported` is false.

The CLI (`scripts/e2e-run.sh` / `scripts/testmap_run.py`) is the only runner. The console does not execute cases. `POST /api/run` starts that CLI and returns the run id. Stop signals the `pid` stored in the record.

## Run record

`docs/testmap/runs/<id>.json` is the interface between the CLI and the web UI. It is written atomically (temp file, then replace) before the first case starts, again after every step, and again when each case ends. Screenshots for that run live in `docs/testmap/runs/<id>/steps/`.

```json
{
  "id": "20260923T151925-337162c6",
  "git": {"sha": "7fc1364a", "dirty": true},
  "source": "manual",
  "pid": 12345,
  "state": "running",
  "tasks": [
    {
      "id": "edge:editor-style-then-export@android#agent",
      "edge": "editor-style-then-export",
      "platform": "android",
      "repeat": {"k": 1, "n": 1},
      "state": "running",
      "exit_code": null,
      "duration_s": null,
      "steps": [
        {"n": 1, "text": "open me.rosuh.easywatermark.debug", "state": "done", "duration_ms": 612, "shot": "android-1.png"}
      ]
    }
  ]
}
```

`source` is `manual` (a chosen task list), `select` (`e2e-select`), or `verify` (`e2e-verify.sh --run`). `repeat` is `k/N` for that row. Run and task states include `pending`, `running`, `passed`, `review_required`, `failed`, `skipped`, `stopped`, `uncovered`, and `interrupted`. Unknown states must stay visible and must not be treated as success. `shot` is a file name under `runs/<id>/steps/` for every executed step, including a failed step. When the capture itself fails, the step has `shot_error` instead of `shot`. A missing file is shown as「无截图」and is never filled from another step. `e2e-verify.sh --run` runs selected platforms sequentially, writes one record per platform whose rows are `1/N`, `2/N`, and keeps going after a case failure. The verify report combines their run IDs and evidence without dropping selected tasks or failures. Each case restarts its own app before the script runs, so it does not continue from the previous case's screen. `/api/status` reads this file (the running record, otherwise the latest). Restarting the console does not drop it.

## Mode B — pre-merge / pre-ship

Triggers: new requirement, product code change, “验证这次改动 / 出报告 / 合入前 / 上线前 / ready to merge”, or `/e2e-testmap` with a git range / current diff.

Do **not** skip select and run a favorite edge.

1. `scripts/e2e-select.sh` or `scripts/e2e-select.sh <range>`.
2. Docs-only → generator + guard only; no silent full map, no agent repeats.
3. Otherwise run suggested host scripts (warn before long emulator+build).
4. Agent edges: `scripts/e2e-verify.sh --change '<natural language of this change>' --run --android-device '<authorized-serial>' --ios-device '<authorized-udid>'` (default 3 repeats, `EWM_AGENT_REPEATS`). Supply a concrete device ID for every selected platform; omit the flag for an unselected platform. Missing, empty, or `auto` bindings are rejected before execution, and the existing device resolver rejects unknown or wrong-platform IDs. Formal runs no longer auto-select a connected phone. The existing runner receives each platform's exact selection and device, with `TESTMAP_NO_EXPAND=1`. Without `--run` it only writes the stub and needs no device bindings.
5. Look at console frames / `build/agent-device/` vs expected effect (change text + `copy.yaml` titles). Fill the UI heuristic in the generated `docs/testmap/runs/<ts>-<sha>-verify.md`.
6. Human Confirm on the console. Independent review is not Confirm.
7. L3 only if select suggested it or the change is perf. Numbers do not own pass/fail.
8. **Do not recommend merge or ship** if the report is missing, Confirm is missing where required, or any agent task is 0/N (unless the edge is a known product failure such as Add More).

Standing orders: do not shut down live emulators/simulators; `--serial` / `--udid`; debug package `me.rosuh.easywatermark.debug` / iOS `me.rosuh.easywatermark.ios`; Gradle `--max-workers=8`; `#artemis` only if the user names Artemis.

## Judge

- Process 0 / batch success / `review_required` is not a product pass.
- `drive: seam|none` stays that way even if the agent walked a system picker.
- `REPLAY_DIVERGENCE`, timeout, and setup failure are execution failures to investigate. Record the actual failure and distinguish environment, assertion, and product causes using evidence; do not automatically convert them to a product pass or blame the device. Use bounded single-case diagnosis before repeats. Preserve `--plan-digest` when resuming a replay.
- Photo Picker uses batch JSON (`find` + `first: true`); native `.ad` cannot pass `--first`.

## Out of scope

- Do not replace L0/L1/L2/L3 or Compose Desktop coverage with Agent Device.
- Do not add a PR CI gate (ADR-0031 / ADR-0010).
- Creating or changing the harness itself → `testing-setup`.
- Device boot/screenshot plumbing → `android-cli`.
- Unscripted explore → `docs/agents/e2e-walk.md`.

## Skill source

This file (`skills/e2e-testmap/SKILL.md`) is authoritative. The `.agents` and `.claude` entries link here. Edit only this source.
