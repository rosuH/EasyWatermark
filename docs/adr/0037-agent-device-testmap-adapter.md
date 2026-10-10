# ADR-0037：testmap agent 层改为 Agent Device

**Status:** Accepted — owner 2026-09-14: default runner Agent Device, P0–P4, no PR split  
**Date:** 2026-09-14  
**Owner scope:** 仅替换 change-to-verify 的 **agent 观察/点击适配器**；不改产品代码，不加 PR 动态门禁  
**Amends:** 历史 testmap 合同里「Android agent = Artemis」的绑定；不改 ADR-0031（选择脚本不是 CI 门禁）、ADR-0010（无 golden）、历史 ADR-0032 测试地图的 reuse 约束  
**Related:** `docs/testmap/README.md`、`docs/testmap/artemis-binding.md`  
**Amended 2026-09-18:** 地图落地为 **15 节点 / 29 边**（决策当日正文曾写 14/17，以 `docs/testmap/map.yaml` 为准）。

## Context

现有闭环是：`map.yaml`（落地 **15 节点 / 29 边**；决策当日草稿曾写 14/17）→ `e2e-select` → L0–L3 脚本 + Android Artemis agent → 文件检查 / 独立审查 / 人确认。Artemis 适配器在 `scripts/testmap_run.py` 的 `builder: artemis` 和 `docs/testing/artemis-cases.json`（按 edge id 1:1）。iOS agent 明确 blocked。Desktop 产品覆盖是 `:shared:desktopTest` L1，不是 agent。

2026-09-14 在本机用 **agent-device 0.21.2** 做过有界试跑：

- Android `emulator-5554` / `me.rosuh.easywatermark.debug`：Launch → 关于 → 返回 Launch
- iOS `iPhone 17 Pro Max` / `me.rosuh.easywatermark.ios`：Launch → 关于；Back 落到已有编辑器会话（环境状态，不是执行器失败）

Agent Device 的智力在 CLI 调用方，不在工具内。无人值守路径是 `.ad` 回放，不是自然语言 goal。这与 Artemis「模型看图自己点」不同。

历史合同否过「另起 Maestro / 点击引擎」。本决策把 Agent Device 当作 **已有地图上的执行器**，不是第二张拓扑，也不是 L0–L3 的替代。

## Decision

1. **只换 agent 层。** `map.yaml`、owners、L0/L1/L2/L3、console/CLI、drive 词汇、人确认、独立审查分层全部保留。
2. **新 builder `agent-device`。** 任务 id：`edge:<id>@android#agent` 与 `edge:<id>@ios#agent`。payload 按 edge id 1:1，禁止第二份地图。Guard 对失配失败。
3. **无人值守用 `.ad`（选择器，不用易过期 `@eN`）。** 探索仍可由 coding agent 发 CLI；稳定后 `open --save-script` 在 `close` 时写出脚本。Maestro YAML 只作为可选导出，不是事实源。
4. **drive 不因能点系统框而升级。** 选图器 / PHPicker / 分享面板 / FileDialog 仍是 `seam` 或 `none`。
5. **历史 Artemis 结果 provenance-bound。** 不改写 `result.json`、不涂绿 Add More、不把独立审查当人确认。
6. **Desktop 不迁。** Compose Desktop 没有 Agent Device 后端；继续 L1/`desktopTest`。
7. **CLI 钉版本。** 实施期钉本机已验证的 `agent-device@0.21.2`，不在 runner 里 `npx @latest`。
8. **设备选择必须显式。** Apple 用 `--udid`，Android 用 `--serial`。多个同名模拟器时 `--device` 不够（见 Agent Device `#2065`）。
9. **默认不做破坏性恢复。** 不用 `reinstall` / `settings clear-app-state` 当套件默认。Android 第一次 snapshot 会装 helper APK，这是工具行为，记入环境说明，不是产品安装。

默认 runner 是 Agent Device（`edge:<id>@android#agent` / `@ios#agent`）。P0–P3 的 ingest 分层保留。P4：`#artemis` 标 deprecated；`artemis-cases.json` 只读；历史 `build/artemis-suite/` 不删。CLI 仍可解析旧 `#artemis` 记录。回放/SDK completed 不是产品通过。细节见迁移计划。

## Consequences

- iOS in-app `real` 边（如 `launch-to-about`）第一次有 agent 执行器。
- 套件从「NL goal + 模型」变成「录制脚本 + 选择器」；UI 文案/无障碍标签变化会以 `REPLAY_DIVERGENCE` 失败，需修 `.ad` 而不是调 prompt。
- iOS 树含隐藏 `store-seed-*` 与叠层 Launch 按钮；脚本必须用可见 `label`/`role`/`id`。
- 设备上会留下 `com.callstack.agentdevice.snapshothelper` 与 `imehelper`；与已有 `com.artemis.helper` 并存直到 P4 清理说明。
- ColorOS 等会拦 `android:testOnly` helper（Agent Device `#2364`）；本仓库默认目标仍是专用模拟器。
- P4 停用的是 **默认入口**，不是删除历史 Artemis 证据或 `artemis-cases.json`。本切片不改 `testmap_run.py`；旧 `#artemis` 记录继续解析。
