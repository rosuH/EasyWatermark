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

## Optional AI SDK hybrid (2026-10-18)

10. **Replay 仍是总默认，AI SDK 只做显式探索。** `TESTMAP_AGENT_DEVICE_MODE=ai-sdk` 才启动 Node harness，AI SDK 内默认使用通用 hybrid。Magpie `cursor/grok-4.7@500k` 规划路线及处理合法低置信度接管；Jev 通过 TypeSafe HTTP API 的 `jev-latest` 选择候选，记录实际返回的模型版本。宿主持有 app/device 生命周期。AI 模式不进 PR CI，完成 tool loop 最多记 `executed_review_required`。
11. **显式 benchmark 限定两条 Android 路径。** AI 模式下，`TESTMAP_AGENT_DEVICE_PILOT_ARM` 对 `launch-to-about` 接受 `jev|grok|hybrid`，对 `pick-to-editor` 接受 `grok|hybrid`。About pilot 使用原生 action 节点和 Launch → About → Launch 顺序检查，刷新后必须明确 `selected=false` 才点击；独立 Jev arm 使用官方 HTTP Choice 与固定 confidence gate，低置信度或缺状态拒绝操作。Pick pilot 使用自己的选图、编辑、PNG 导出及文件检查约束，不继承 About 的通用 `selected=false` 点击要求。对比保留运行计划、模型真实版本、逐步证据与成功样本耗时，不能外推到一般 UI 探索。
12. **两条 benchmark 的 hybrid arm 均让 Grok 规划、Jev 选择、合法低 confidence 交回 Grok。** 接管使用刷新后的快照、候选项和当前计划；最终操作仍受各路径的状态与顺序检查约束。Grok 的 2–4 个导航步骤限制仅属于 About pilot，不适用于 Pick pilot。显式故障注入仅支持 About hybrid，用于验证接管分支，保留原始响应并单独标注，不更改 replay 默认。

13. **AI SDK 默认使用通用 hybrid，replay 保持总默认。** 按 owner 本次要求，hybrid 从专用 pilot 扩展到现有清单支持的全部 Android / iOS 绑定。Grok 根据原脚本、setup、verify 和限制保留整条路线概览，每批规划 2–12 步，累计最多 80 步，后续批次使用最新观察和完成历史；Jev 选择操作目标。原值通过 `remember` 读取后保存，用于需要恢复的字段。合法 Choice 的置信度低于 `0.9` 时交回 Grok，使用当前快照和计划历史；已知目标交回控件选择，低置信度 `none` 则重规划剩余路线。刷新后目标必须唯一；未提供的原生状态保留为未知。返回、键盘、等待、断言及失败源图恢复由宿主执行。恢复只接受当前运行的失败导出证据与宿主持有的状态文件。未支持的产品路径和系统选图器可见性限制保持原样。接线覆盖不等于全部设备验收；结果仍最多为 `executed_review_required`。原有两条 Android benchmark pilot 保留为显式对照入口，不作为通用执行器的性能结论。

### Optional harness 的证据与复现边界

操作约束以 [Testmap README](../testmap/README.md) 为准。Jev key 只从环境或 gitignored 的 `build/agent-device/jev-pilot.env` 读取。非法 Choice、缺失必需状态及 provider 错误停止执行；合法低置信度可以由 Grok 接管，不等于取消刷新后目标唯一性、原生状态或视觉图像哈希约束。

Android 使用独立 MediaStore PNG 文件 oracle，仅清理本轮拥有的源图/导出并恢复偏好；iOS 使用独立 PhotoKit 原始 PNG 资源 oracle，必须匹配真实 app `ewm_out_*` 哈希，再检查像素与持久化 marker。像素变化和 marker 字节不证明逐字渲染正确。iOS PHPicker 原生 AX 缺少 grid 时，仅专用空库 opt-in 可由 Grok 根据截图生成候选，Jev 选择，低置信度接管附带当前截图；visual `selected` 保持未知，点击前必须通过未变的裁剪图像哈希重绑定。这不使用产品 fixture seam，也不扩展到个人图库或真机验收。

iOS 环境仍由维护者预置：只登记本次新建的专用模拟器，确认空 active Photos 库、PhotoKit helper 的真实 full read/write UI 授权，以及 clone 后安装注册和容器归属。不得改元数据冒充拥有个人或预存模拟器；本轮不新增 provision CLI，已有运行设备和 seed 已销毁，旧 UDID 不能复用。现有运行入口见 README，不是环境创建命令。清理先回读确认偏好恢复，再重验所有 active Photos 资产归属，最后仅销毁所有权匹配的专用模拟器及数据目录（含 Recently Deleted）；未知资产或恢复失败阻止销毁。此范围作为受限实验收尾，不替代独立审查、人确认或全部 supported 绑定的设备验收。

## Consequences

- iOS in-app `real` 边（如 `launch-to-about`）第一次有 agent 执行器。
- 套件从「NL goal + 模型」变成「录制脚本 + 选择器」；UI 文案/无障碍标签变化会以 `REPLAY_DIVERGENCE` 失败，需修 `.ad` 而不是调 prompt。
- iOS 的 `store-seed-*` 入口需显式启用。editor fixture setup 在文件准备完成后以 `-storeCaptureHooks` 启动应用；`.ad` 用不带 `--relaunch` 的 `open` 连接已有进程。0.21.2 的 `.ad open` 不支持 CLI 的 `--launch-args`。普通启动不显示这些入口。
- 设备上会留下 `com.callstack.agentdevice.snapshothelper` 与 `imehelper`；与已有 `com.artemis.helper` 并存直到 P4 清理说明。
- ColorOS 等会拦 `android:testOnly` helper（Agent Device `#2364`）；本仓库默认目标仍是专用模拟器。
- P4 停用的是 **默认入口**，不是删除历史 Artemis 证据或 `artemis-cases.json`。本切片不改 `testmap_run.py`；旧 `#artemis` 记录继续解析。
