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

If `http://127.0.0.1:8931/api/status` is up, POST the task so the watch pane follows. Queue `edge:<id>@android#agent` (the runner mirrors a supported `@ios#agent` twin and runs the two lanes in parallel). The pane shows Android, iOS, and Desktop side by side. Do not auto-open the native scrcpy window.

```bash
curl -sS -X POST http://127.0.0.1:8931/api/run \
  -H 'content-type: application/json' \
  -d '{"tasks":["edge:<id>@android#agent"],"device":"<serial-or-udid>"}'
```

Report run id, state, evidence dir. Do not mint Confirm. Skip a platform when `supported` is false.

## Mode B — pre-merge / pre-ship

Triggers: new requirement, product code change, “验证这次改动 / 出报告 / 合入前 / 上线前 / ready to merge”, or `/e2e-testmap` with a git range / current diff.

Do **not** skip select and run a favorite edge.

1. `scripts/e2e-select.sh` or `scripts/e2e-select.sh <range>`.
2. Docs-only → generator + guard only; no silent full map, no agent repeats.
3. Otherwise run suggested host scripts (warn before long emulator+build).
4. Agent edges: `scripts/e2e-verify.sh --change '<natural language of this change>' --run` (default 3 repeats, `EWM_AGENT_REPEATS`). Without `--run` it only writes the stub.
5. Look at console frames / `build/agent-device/` vs expected effect (change text + `copy.yaml` titles). Fill the UI heuristic in the generated `docs/testmap/runs/<ts>-<sha>-verify.md`.
6. Human Confirm on the console. Independent review is not Confirm.
7. L3 only if select suggested it or the change is perf. Numbers do not own pass/fail.
8. **Do not recommend merge or ship** if the report is missing, Confirm is missing where required, or any agent task is 0/N (unless the edge is a known product failure such as Add More).

Standing orders: do not shut down live emulators/simulators; `--serial` / `--udid`; debug package `me.rosuh.easywatermark.debug` / iOS `me.rosuh.easywatermark.ios`; Gradle `--max-workers=8`; `#artemis` only if the user names Artemis.

## Judge

- Process 0 / batch success / `review_required` is not a product pass.
- `drive: seam|none` stays that way even if the agent walked a system picker.
- `REPLAY_DIVERGENCE` / timeout / setup fail = **flake class**, not a UI-quality fail. Restore state then `replay --from` + `--plan-digest`; do not rewrite the digest.
- Photo Picker uses batch JSON (`find` + `first: true`); native `.ad` cannot pass `--first`.

## Out of scope

- Do not replace L0/L1/L2/L3 or Compose Desktop coverage with Agent Device.
- Do not add a PR CI gate (ADR-0031 / ADR-0010).
- Creating or changing the harness itself → `testing-setup`.
- Device boot/screenshot plumbing → `android-cli`.
- Unscripted explore → `docs/agents/e2e-walk.md`.
