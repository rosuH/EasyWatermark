# 12 — Post-P1 architecture refactor plan

## Goal

在不改变 Android v2.10.0 产品基线、持久化字节和平台系统边界的前提下，按可独立验收的小切片完成 Candidate 1 之后的架构收口；任何时刻都只有一个 active slice，前一切片没有证据就不进入下一切片。

## Status

- **Current baseline:** Candidate 1 / P1 Desktop render-save spine 已实现并提交于 `a1ac0de5`。
- **Current planning phase:** **complete**（2026-07-18）；本计划为唯一 sequential roadmap 源，已按 HEAD 交叉检查。
- **Implementation of any product slice (including P1.C1–P6):** **not authorized by this plan alone.** 必须由 owner **另开授权** 后才能改产品代码；本文件只规定顺序与验收，不视为开工令。
- **Next planned slice when separately authorized:** **P1.C1**（P1 closeout 第一步）；P1 closeout 完成并记证据后才进入 P2。

## Scope map

| Stage | Architecture outcome | Status |
|---|---|---|
| P1 closeout | 关闭 Candidate 1 的剩余人工验收与小清理 | planned |
| P2 | `CommonWatermarkPipeline` 独占 shared paint 知识 | planned |
| P3 | typed export outcome；Session 统一 progress / result ownership | planned |
| P4 | Product Session 分 slice 成为 route / transient selection / offset snapshot 单一 owner | planned |
| P5 | 折叠 Desktop Open / Drop batch orchestration | planned |
| P6 | 删除 Shared Editor 的 `FuncType + Any` translation chain | planned after P4 |
| Observation | Android preview internal seam | observation only；没有持续测试摩擦不得实施 |

## Global guardrails

- 每个 slice 单独 review、单独验证、可独立停止；禁止一次跨 P2/P3/P4 修改。
- Android production v2.10.0 是行为基线；Desktop/iOS 向该基线对齐，平台例外必须显式记录。
- `WatermarkRenderer` 保留为 Android golden / performance / delta oracle；不得重新进入产品运行路径。
- 不新增无真实 off-platform consumer 的 `expect/actual`、port、wrapper 或 shared ViewModel seam。
- 不改 DataStore store/key、Room v1 schema、`WatermarkTileMode.storageId`、typeface/paint serialize key。
- 平台 shell 继续拥有 picker、share sheet、save dialog、permission、decode/encode 和文件系统目标策略。
- 自动化 build/test 不能代替真实 UI、保存路径与视觉验收；证据必须标明属于哪一层。
- 每个 milestone 必须同步 ADR / CONTEXT / AGENTS，或明确写 `no doc impact`。

## Execution plan

```text
P1 closeout → P2 common paint → P3 typed export → P4 Session single owner
                                                     ├→ P5 Desktop batch locality
                                                     └→ P6 typed Shared Editor
```

P5 与 P6 技术上可以在 P4 后独立进行，但本计划仍坚持同一时间只有一个 active slice；默认先做范围较小的 P5，再做 P6。

### P1 closeout — 关闭 Candidate 1

**目标：** 不再改变 spine 设计，只完成接口、测试归属与真实运行验收。

#### P1.C1 — 删除 compatibility-only surface

- 删除 `DesktopWatermarkFlow.runSaveFlow` 完全未使用的 `editor: WatermarkConfigEditor` 参数并更新所有 caller。
- 修正 `DesktopSaveDecision`、`DesktopExportPipelinePortTest` 等仍描述“未来 cutover”的过期 KDoc。
- 不修改 `DesktopRenderSaveSpine` 的 exact-target contract、输出命名策略或 Session API。

**完成证据：** 编译通过；`rg` 不再找到保留参数说明；无产品行为 diff。

#### P1.C2 — 测试归位

- `DesktopRenderSaveSpineTest` 独占 Text/Image、JPEG/PNG、REPEAT/CLAMP、alpha、missing icon、exact target 的 render/write contract。
- `DesktopExportPipelinePortTest` 只保留 source validation、unique destination、typed/result mapping、dimension mapping，以及最多一条端到端 happy path。
- 删除的只能是重复 contract test，不能降低上述矩阵覆盖。

**完成证据：** focused Desktop tests 与完整 `:shared:desktopTest` 通过；测试名能直接说明 owner。

#### P1.C3 — 四种 destination 真实验收

- Preview：只写 app-private temp，不改变 last real save。
- Save As：精确写用户选择路径，不偷偷 unique-rename。
- Open / Drop：写稳定 output dir，冲突时生成唯一文件名，批量顺序保持。
- Headless：fixture、输出路径与退出行为保持确定。
- 使用受支持的 Zulu/Corretto JDK 补跑 `:desktopApp:createDistributable`；Homebrew JDK 的 vendor rejection 不能记成产品失败。

**最小命令：**

```bash
./gradlew :shared:desktopTest :desktopApp:compileKotlin --max-workers=8
./gradlew :desktopApp:run --args='--headless' --max-workers=8
./gradlew :desktopApp:createDistributable --max-workers=8
git diff --check
```

**停止条件：** 如果清理要求修改 `ExportPipelinePort`、Session state 或 preview/real-save 含义，立即停止并转到 P3/P4，不夹入 P1。

**P1 checkpoint：** cleanup 与验收记录独立提交；确认后再推送。P2 不得混进同一 commit/未验收 diff。

---

### P2 — `CommonWatermarkPipeline` 独占 shared paint

**目标：** Android、Desktop、iOS 的正式公共光栅路径只让 adapter 准备 decode、font env、icon 与 encode/write；Text/Image 选择、config→cell、tile normalization、offset、alpha-once 全部由 `CommonWatermarkPipeline` 负责。

#### P2.0 — 固化 paint contract

- 在 `CommonWatermarkPipelineComposeTest` 建立完整 config matrix：英文、CJK、多行、Fill/Stroke、四种 typeface、Text/Icon、REPEAT/CLAMP、0/partial/full alpha、CLAMP offset。
- 明确 perceptual common-raster policy：不得宣称与 Android native oracle byte parity。
- 保存三端代表性视觉 witness；文件大小或 hash 不能代替查看图片。

#### P2.1 — 深化 common API

- 为 `CommonWatermarkPipeline` 增加平台准备好的可选 `FontFamily` 输入；默认值保持 Android 当前行为，Desktop/iOS 显式传 bundled Latin+CJK family。
- 保持输入为已解码 `ImageBitmap + WaterMark + TextRasterEnv + icon? + offset`；不接收 path、bytes、`Context`、`NSData` 或 `File`。
- `WatermarkCellComposer` 继续作为 pipeline 内部 primitive，不再由 product adapter 直接编排。

#### P2.2 — Desktop cutover

- `DesktopRenderSaveSpine` / `DesktopWatermarkComposer` 只负责 ImageIO decode、bundled font、pipeline 调用、JPEG/PNG encode 与 exact-target write。
- 旧 `composeOverRealImage` / `composeIconOverRealImage` 如果仍有测试或 headless consumer，应先改为委托 pipeline；consumer 迁完再删除，不保留第二份 mapping。
- 保留 EXIF bake、JPEG alpha flatten、quality 与 destination policy。

#### P2.3 — iOS preview/export cutover

- `IosPreviewRaster`：thumbnail decode + bundled font/icon decode 后调用 pipeline；保留 preview max-edge 和 benchmark marks。
- `IosWatermarkRenderer` / `IosWatermarkRenderBridge` / `IosExportPipelinePort`：decode/encode/temporary-file 仍在 iOS edge，paint mapping 委托 pipeline。
- Preview 与 export 必须走同一 paint rules，但仍保留不同 decode budget 与是否 encode/write 的语义。

#### P2.4 — 三端验证与文档

- `rg` 证明正式 Desktop/iOS adapter 不再直接组合 `composeTextCell / composeIconCell / composeOverBackground`；测试 helper 与 Android native oracle 例外必须逐项解释。
- 更新 ADR-0018/CONTEXT/AGENTS 的“正式路径 vs oracle”说明，避免旧 KDoc 继续声称平台手工 mapping。

**最小自动化：**

```bash
./gradlew :shared:desktopTest --max-workers=8
./gradlew :shared:iosSimulatorArm64Test --max-workers=8
./gradlew :app:testDebugUnitTest --max-workers=8
./gradlew :app:assembleDebug :desktopApp:compileKotlin --max-workers=8
git diff --check
```

**人工 gate：** Android、Desktop、iPhone 各查看 Text/CJK/multiline/Icon 与 REPEAT/CLAMP；确认 alpha 只应用一次、CLAMP offset 正确、字体没有回退。

**停止条件：** common API 一旦需要平台 path/encode/system API，或 cutover 迫使 native oracle 回到产品路径，立即停止；那说明边界放错了。

---

### P3 — typed export outcome 与单一 progress owner

**目标：** adapter 返回同一种 typed output；不再修改输入 `ImageInfo`。Session 独占 per-item outcome、batch progress 与 host-facing projection；host 不再 cast `Result<*>`、重复计数或手工标记 finished。

#### P3.0 — characterization first

- 固化 success、partial failure、all failure、empty batch、cancel、retry 与 exception 的当前行为。
- 明确旧 `completedCount` 是 success count；设计评审后选择：保持兼容名称/语义，或显式拆成 `processedCount / successCount / failureCount`。必须同时更新 UI 文案测试。
- 固化 `ExportErrorCodes` 与 Android pre-Q/Q+ 保存行为。

#### P3.1 — typed adapter output

- 新建纯 commonMain value object，例如 `ExportedMedia(ref, width, height)`；port 返回 `Result<ExportedMedia>`（或等价的 sealed typed outcome）。
- Android/Desktop/iOS adapter 只构造 outcome；禁止写 `imageInfo.width/height/result/jobState`。
- 不按平台拆出三个浅 port；继续保留现有一个 seam + 三个真实 adapter。

#### P3.2 — Session-owned item state

- Session 把 typed outcome 投影为一致的 per-item state 与 aggregate `ExportJobState`。
- 将 `Result<*>` + `JobState` 两个可产生矛盾的字段收敛为 typed export item state；host 能直接取得成功 output ref、尺寸和失败信息。
- cancellation 必须留下确定状态，不得让 `isSaving=true` 或 item `Ing` 永久悬挂。

#### P3.3 — hosts cut over one by one

1. Android：分享/打开图库直接消费 typed outputs；删除 result-data mapper/cast 与 host recount。
2. Desktop：Open/Drop/save sheet 直接消费 typed outputs；删除 `markExportFinished` escape hatch。
3. iOS：Photos save/share 直接消费 typed outputs；删除 `sheetExportFinished` 与 result cast mirror。

每个平台切换后都必须能独立编译和测试；不得在同一未验证 diff 中同时切三端。

#### P3.4 — 删除 legacy mutation surface

- 零 caller 后删除 `ImageInfo.result: Result<*>?`、旧 export-only `JobState`，或把仍有非导出 consumer 的部分迁到明确 typed field。
- 更新 port/session KDoc 和 ADR-0017 当前实现图。

**最小自动化（可复制执行）：**

```bash
# Session export ownership + cancel/partial (new tests must exist under these packages after P3)
./gradlew :shared:desktopTest \
  --tests 'me.rosuh.easywatermark.session.*' \
  --tests 'me.rosuh.easywatermark.session.OffsetExportOrderingTest' \
  --max-workers=8

# Desktop export adapter (typed outcome, no ImageInfo mutation after cutover)
./gradlew :shared:desktopTest \
  --tests 'me.rosuh.easywatermark.session.DesktopExportPipelinePortTest' \
  --max-workers=8

# Android unit: export port / share mapping / session-facing progress if present
./gradlew :app:testDebugUnitTest \
  --tests 'me.rosuh.easywatermark.session.*' \
  --tests 'me.rosuh.easywatermark.ui.ExportShareUriMappingTest' \
  --max-workers=8

# iOS shared tests (export bridge / port)
./gradlew :shared:iosSimulatorArm64Test --max-workers=8

# Compile gates
./gradlew :app:compileDebugKotlin :desktopApp:compileKotlin \
  :shared:compileKotlinIosSimulatorArm64 --max-workers=8

git diff --check
./gradlew --stop
```

**关键断言（测试必须覆盖，不可只有 happy path）：**

| 场景 | 断言方向 |
|------|----------|
| success batch | typed success outcomes；`ExportJobState` finished；host 无需 cast `Result<*>` |
| partial failure | 失败项有 code/message；成功项仍有 output ref；`completedCount`/进度语义与设计一致（success vs processed 在 characterization 中钉死） |
| all failure | finished + 无 success outputs；UI 可重试 |
| empty batch | no hang；`isSaving` 不为 true 永久悬挂 |
| cancel mid-batch | 取消后 item 非永久 `Ing`；`isSaving=false` 或明确 cancelled 态 |
| retry after cancel/fail | 可再次 export；状态从确定 idle 出发 |
| adapter non-mutation | port 不写 `imageInfo.width/height/result/jobState`（单元/静态断言） |

**人工 gate：** 三端分别验证单张/多张、部分失败、取消后重试、保存后 share/open-gallery；进度数字和按钮状态必须与实际输出一致。

**停止条件：** picker、Photos、MediaStore、FileDialog 或 share sheet 进入 common Session 时立即停止；Session 拥有 outcome/progress，不拥有平台系统 UI。

---

### P4 — Product Session single owner

**目标：** route、transient image selection/current image、offset 与 export snapshot 的产品规则只在 Session/Reducer；Repository 回到 watermark config persistence；host 只发送系统 callback/用户 intent 并观察 state。

#### P4.0 — ownership inventory 与 race characterization

- 为 EnterEditor → SelectCurrent → ApplyOffset → Export、late sync、delete current、append/replace picker、Back/About return 建立 reducer/session tests。
- 列出 `WaterMarkRepository.imageInfoList/selectedImage/updateImageList/select/updateOffset` 的所有真实 caller，Android delete/compress 不得遗漏。
- **Characterization only（迁出前）：** 可跑现有
  `./gradlew :shared:desktopTest --tests 'me.rosuh.easywatermark.data.repo.WaterMarkOffsetUpdateTest' --max-workers=8`
  以钉死当前 repo offset identity 行为；**不得**作为 P4 最终 gate（P4.3 会删除该 ownership）。

#### P4.1 — route/back/return-route 归 Session

- **复用 / 扩展 / 原子替换** 现有 `LaunchScreenState.uiState: LaunchScreenUiState`（`Launch` / `GalleryDialog` / `Editor` 已在 `shared/.../ui/LaunchScreenUiState.kt`），由 reducer 成为唯一 product-route owner。
- **禁止** 新增平行的 `ProductRoute` / `currentRoute` / 第二套 route enum 与 `LaunchScreenUiState` 并存；About 与 return-route 应扩展 **同一** uiState 模型（或同 snapshot 上的明确 companion 字段），不得第三套状态。
- `ProductShellNav` 只保留 transition animation / pure helpers；按 Android → Desktop → iOS 顺序删除 host 的 `productRoute/aboutReturnRoute` mirror；每端切换后独立验收返回键、About 往返和外部 picker 回流。
- reducer 已接管并三端 host 删除 mirror 后，不得再出现 host 本地 route 与 session uiState 双写。
- `showSaveSheet`、系统 picker/share sheet、OpenSource overlay 等纯 presentation/system state 默认留在 host；只有出现跨端产品规则证据才升级到 Session。

#### P4.2 — 删除 host selection mirrors

- Desktop 删除 `selectedSessionImage` fallback；Android/iOS editor/filmstrip 只读 Session current/selected state。
- host 不得再依赖“先 dispatch，再手工写 local selected/route”的顺序。

#### P4.3 — transient selection/offset 从 Repository 迁出

- 为 Session 增加 typed remove/clear/select/offset intents/API，先迁移 `MainViewModel` 的 delete/compress/current-image consumer。
- Export 只从一次冻结的 Session snapshot 读取，不再 repo-first/session-fallback/request-object 三路猜测。
- consumer 清零后删除 Repository 的 `_imageMapFlow/_selectedImage` 与对应方法；offset race/identity tests 移到 Session owner。
- **必建** `shared/src/desktopTest/.../session/SessionOffsetIdentityTest.kt`：承接原 `WaterMarkOffsetUpdateTest` 的 list/selected/committed identity 与 pure CAS/same-offset/missing-URI 矩阵，但驱动 **Session** API（非 `WaterMarkRepository.updateOffset`）。删除旧 repo 测试前，该测试必须已绿。
- `WaterMarkRepository` 最终只保留持久化 `WaterMark` config 与兼容关键字节。

#### P4.4 — 简化同步机制

- 删除只为双 owner 存在的 `SyncCurrentImage` collector、merge/CAS compatibility logic 与 ordering KDoc；只删已经失去 consumer 的部分。
- 保留 `Mutex` 的真实 reducer/effect 串行职责；不得把它换成 JVM `synchronized`。

**最小自动化 — P4 最终 gate（可复制执行；offset/list ownership 已在 Session）：**

```bash
# Reducer: route/uiState transitions, selection, back/return (LaunchScreenUiState only)
./gradlew :shared:desktopTest \
  --tests 'me.rosuh.easywatermark.session.SessionReducerTest' \
  --max-workers=8

# Session-owned offset/list identity + export ordering (P4.3 必建 SessionOffsetIdentityTest)
./gradlew :shared:desktopTest \
  --tests 'me.rosuh.easywatermark.session.OffsetExportOrderingTest' \
  --tests 'me.rosuh.easywatermark.session.WatermarkSessionViewModelTest' \
  --tests 'me.rosuh.easywatermark.session.SessionOffsetIdentityTest' \
  --max-workers=8

# Android unit (picker/share-in / MainViewModel edges if present)
./gradlew :app:testDebugUnitTest --max-workers=8

# Compile three shells
./gradlew :app:compileDebugKotlin :desktopApp:compileKotlin \
  :shared:compileKotlinIosSimulatorArm64 --max-workers=8

# Static FAIL if third route owner / host route mirrors remain (exit 1 on any match)
if rg -n "ProductRoute|currentRoute|aboutReturnRoute" \
    shared/src app/src desktopApp/src iosApp \
    --glob '!**/build/**'; then
  echo "FAIL: residual parallel route owner or host route mirror" >&2
  exit 1
fi

# Static FAIL if Repository transient image/offset API or product callers remain after P4.3
if rg -n "fun updateOffset|fun updateImageList|fun select\(|_imageMapFlow|_selectedImage|imageInfoList|selectedImage" \
    shared/src/commonMain/kotlin/me/rosuh/easywatermark/data/repo/WaterMarkRepository.kt; then
  echo "FAIL: WaterMarkRepository still exposes transient image/offset state" >&2
  exit 1
fi
if rg -n "waterMarkRepo\.(updateOffset|updateImageList|select|imageInfoList|selectedImage)|WaterMarkRepository\.(updateOffset|updateImageList)" \
    shared/src app/src desktopApp/src iosApp \
    --glob '!**/build/**' --glob '!**/*Test*' --glob '!**/*test*'; then
  echo "FAIL: product callers still use Repository transient image/offset API" >&2
  exit 1
fi
# Old repo offset contract test must be gone or rewritten as session test (not left green against deleted API)
if rg -n "WaterMarkOffsetUpdateTest|repo\.updateOffset" \
    shared/src/desktopTest --glob '*.kt'; then
  echo "FAIL: legacy WaterMarkOffsetUpdateTest still present; migrate/delete under session ownership" >&2
  exit 1
fi

git diff --check
./gradlew --stop
```

**关键断言：**

| 场景 | 断言方向 |
|------|----------|
| EnterEditor / NavigateBack | 仅 `LaunchScreenState.uiState` 变化；host 无平行 route mirror |
| About open/return | return-route 恢复到进入前 product surface（Launch 或 Editor）；无第三套 route state |
| SelectCurrent + ApplyOffset → Export | **Session-owned** snapshot 含最新 offset；list/selected 同 URI 同 identity |
| delete current / clear | selection 与 cur 一致；**无** repo+session 双 owner |
| late Sync / picker append | 不丢 offset；不回写 stale host route |
| static route | 无 `ProductRoute`/`currentRoute`/`aboutReturnRoute` 匹配（`rg` 非零 → gate fail） |
| static repo | Repository 无 transient list/offset API；生产 caller 清零；无遗留 `WaterMarkOffsetUpdateTest` |

**人工 gate：** 三端逐项验证 picker/追加图片、filmstrip 切换、CLAMP 拖动后立即导出、删除当前图片、Back、About 往返、保存后返回；iPhone/Android 必须使用真实 picker 回流至少一次。

**停止条件：** 不做一次性三端 state rewrite；任何 slice 同时触及 route + selection + export outcome 就拆分。持久化水印配置不能搬进 Session 内存模型。若实现引入平行 route 类型 → **reject**，先收敛到 `LaunchScreenUiState`。

---

### P5 — Desktop Open / Drop batch locality

**前置：** P4 完成后重新检查重复是否仍存在；如果 P4 已自然消除重复，本阶段直接记为 `no-op`，不为了完成编号造 module。

#### P5.1 — 复用一条 internal batch function

- 将现有 `openImageFilesBatch` 收紧/重命名为 Open 与 Drop 都能调用的 window-local batch function。
- FileDialog 只提供 picked files；Drop 只做 busy/supported-file adapter；共同 function 拥有 EnterEditor、Session export、结果汇总、last real save、route/selection intent 与一次 preview refresh。
- 继续复用 `DesktopSaveDecision.supportedImageFiles`；默认不新增 port、class 或 shared abstraction。

**完成证据：** `DesktopWindow.kt` 只剩一份 `ImageInfo/GalleryImage → session → summarize` call graph；Open 与 multi-file Drop 的 success/partial failure/busy/unsupported 行为通过手工验收。

**停止条件：** 如果复用必须把 AWT DragAndDrop/FileDialog 类型推进 shared 层，停止并保持 window-local。

---

### P6 — Shared Editor typed event seam

**前置：** P4 Session API 稳定；否则 typed UI event 会被迫同时追逐 state ownership 变化。

#### P6.0 — typed control events

- 将 `EditorScreen` / `EditorBottomControls` callback 从 `(FuncType, Any)` 改为 `(WatermarkConfigChange) -> Unit`。
- Text/Icon/Color/Alpha/Degree/TextSize/Typeface/TileMode/Gap 控件在值产生处构造 typed change；gap rounding 等现有行为保持。
- `FuncType` 可继续承担 option identity、label/icon 与 stable key；目标是删除 raw value transport，不是为删类型而删类型。

#### P6.1 — 三端 adapter cutover

- Android 直接 `applyConfig(change)`；删除 `FuncTitleModel → Action.WaterMarkChange → WatermarkConfigChange.from` dispatch chain。
- Desktop/iOS 直接把 typed change 交给 Session/editor API；picker 仍在平台边缘转换成 `MediaRef`。
- 零 caller 后删除 `WatermarkConfigChange.from(type, value: Any)` 与只服务该链的 legacy Action/KDoc/tests。

**完成证据：**

```bash
rg -n "onConfigChange:.*Any|WatermarkConfigChange\.from|Action\.WaterMarkChange" shared/src app/src desktopApp/src
```

结果应为零（历史文档/明确测试 fixture 例外需解释）；`WatermarkConfigChangeTest` 改为 typed event/rounding/Session dispatch contract，而非测试错误 cast。

**人工 gate：** 三端逐个操作所有 editor controls，尤其 Icon↔Text mode、gap rounding、alpha、tile mode、typeface/style 与 template apply；预览和下一次导出必须使用同一新配置。

**停止条件：** 不把 Android `Uri`、resource id 或 picker contract 放进 typed event；平台 payload 先在 edge 转为 `MediaRef`/pure model。

---

### Observation-only — 不自动排期

- **Android preview external port：** `WaterMarkCanvas` 当前已有小接口 + 大实现，只有一个真实 Android consumer。只有持续出现至少两个独立的测试/替换痛点，并经 owner 确认，才提出 internal seam；禁止先造 external port。
- **MediaLibrary 跨平台扩张：** Desktop/iOS 使用系统 picker，当前没有共同 off-platform library behavior；保持现状。
- **吸收 `:cmonet`：** 继续受 ADR-0007 owner gate 约束，不属于本计划。

## Verification and handoff rules

每个 slice 完成时，在本文件追加一条 evidence log，至少包含：commit/diff、运行的精确命令、通过/失败数、人工查看的设备与场景、未完成 gate。必须在最终源码修改后重新跑证据。

每个 stage 的退出条件：

- focused tests 绿；受影响 target compile 绿；`git diff --check` 绿。
- 真实 runtime/视觉/保存路径 gate 已跑，或明确写成未完成，不能用 build 冒充。
- 没有跨入下一 stage 的 scope；没有新增无 consumer abstraction。
- 文档同步完成或写明 `no doc impact`。
- review 无 P0/P1 finding 后，才把下一 slice 标成 active。

## Decisions

| Decision | Rationale |
|---|---|
| Candidate 1 视为 implemented baseline，而不是后续待办 | HEAD `a1ac0de5` 已完成两 caller 到同一 spine 的切换 |
| 后续全部改造只维护本文件 | 避免 HTML、handoff 与多个 issue 各自形成互相漂移的路线图 |
| 计划与执行分离 | 用户当前要求“写 plan”，不等于授权开始产品代码重构 |

## Evidence and progress log

- 2026-07-18: 创建 canonical plan；开始对 Candidate 2–6 与当前源代码做交叉检查。
- 2026-07-18 / P2 source finding: `CommonWatermarkPipeline.compose` 已拥有 Text/Image 分支、tile 归一化、offset 和单次 alpha；但 `DesktopWatermarkComposer`、`IosPreviewRaster`、`IosWatermarkRenderer` 仍直接组合 `WatermarkCellComposer`，因此 P2 是扩大现有 deep module 的使用面，不是创建新 abstraction。
- 2026-07-18 / P2 boundary: Desktop/iOS 必须继续负责 bytes/path decode、字体资源准备和 encode/write；shared pipeline 只接受已解码 `ImageBitmap`、`WaterMark`、`TextRasterEnv`、可选 icon 与 offset。
- 2026-07-18 / P3 source finding: `ExportPipelinePort.exportOne` 当前返回 `Result<MediaRef>`，同时三端 adapter 都直接修改传入 `ImageInfo.width/height`；Session 随后再写 `ImageInfo.result/jobState` 并统计 success。这让 adapter 输出与 Session presentation ownership 混在可变对象上。
- 2026-07-18 / P3 target seam: port 应返回包含 output ref 与最终尺寸的 typed outcome；adapter 不再修改 `ImageInfo`，Session 是唯一把 outcome 投影到 `ImageInfo`、`JobState` 与 `ExportJobState` 的地方。错误码兼容必须在这一阶段保留。
- 2026-07-18 / P4 source finding: `SessionReducer` 已拥有 Launch/Editor、gallery selection 与 current-image transition；但 Android、Desktop、iOS host 仍各自维护 `productRoute/aboutReturnRoute/showSaveSheet`，Desktop 还维护 `selectedSessionImage` mirror，并可直接调用 `markExportFinished`。
- 2026-07-18 / P4 sequencing: ownership 必须按 `route → host selection mirrors → Repository transient selection/offset` 三个独立 slice 收回。Export outcome/progress 已由 P3 负责；`showSaveSheet` 等纯 presentation state 默认留在 host。
- 2026-07-18 / P5 source finding: Desktop Drop 分支逐行复制了 `openImageFilesBatch` 的 selection mapping、Session entry/export、结果汇总、last output 与 preview refresh。优先方案是让 Open 与 Drop 调用同一个 window-local batch function；不新建跨模块 coordinator。
- 2026-07-18 / P6 source finding: typed `WatermarkConfigChange` 已存在，但 `EditorScreen.onConfigChange(FuncType, Any)` → `AndroidEditorScreen` → `FuncTitleModel` → legacy `Action.WaterMarkChange` → `WatermarkConfigChange.from` 仍保留 raw `Any` 链。P6 应让 shared controls 直接发 typed change，并删除 translator；必须等 P4 ownership 稳定后再做。
- 2026-07-18 / P2 API constraint: Desktop/iOS 当前通过 `TextStyle.fontFamily` 注入 bundled Latin+CJK，而 `TextRasterEnv` 只包含 resolver/density/direction。P2 扩展 `CommonWatermarkPipeline` 时必须显式接收可选 `FontFamily`（Android 可用默认值；Desktop/iOS 传 bundled family），否则“统一 paint”会造成字体行为回退。
- 2026-07-18 / test inventory: 已有 common pipeline、Desktop/iOS renderer、三端 export port、Session reducer、ProductShellNav 与 config translator 测试可作为迁移落点；P2–P6 应把合同测试移动到新的 owner，而不是只保留旧 adapter 内部测试。
- 2026-07-18 / P3 host leakage: Android、Desktop、iOS host 都会从 `ImageInfo.result: Result<*>?` 向下 cast `MediaRef`，并各自重新计算完成数；iOS 另有 `sheetExportFinished`，Desktop 还可直接 `markExportFinished`。P3 不应只改 port 泛型，必须同时提供 host 可直接观察的 typed output/progress，才能真正关闭 seam。
- 2026-07-18 / P3 compatibility: `completedCount` 当前实际表示 success count，而不是 processed count。P3 必须先用 characterization tests 固化 UI 语义，再决定是保留名称/行为，还是显式拆成 `processed/succeeded/failed`；禁止静默改变进度文案。
- 2026-07-18 / P4 repository duplication: `WaterMarkRepository` 同时持有 transient `_imageMapFlow/_selectedImage`，Session 又持有 `LaunchScreenState.selectedImageList/curImageInfo`，并用 merge/CAS 与 identity tests 维持一致。P4 的最终删除目标是让 repository 回到持久化 watermark config；但必须先迁移 Android delete/compress 等真实 consumer，不能直接删 repo state。
- 2026-07-18 / P1 closeout: core spine 已完成；剩余代码清理是删除 `runSaveFlow` 未使用的 `editor` 参数、把 render contract 留在 `DesktopRenderSaveSpineTest`、收窄 `DesktopExportPipelinePortTest` 到 adapter contract，并修正过期 KDoc。它们和真实窗口/packaging 验收一起组成 closeout，不重新打开 render design。
- 2026-07-18: source cross-check 与计划编写完成；implementation 仍未开始；next planned slice when separately authorized = P1.C1。
- 2026-07-18 / planning-goal acceptance: 本文件满足 post-P1 roadmap AC（单序列 P1→P2→P3→P4 后 P5/P6、全局 guardrails、next planned = P1 closeout、P2–P6 仅 outcome/stop、证据规则与 Observation-only 延期项齐全）。HEAD `a1ac0de5` 交叉检查：`DesktopRenderSaveSpine` 存在且 Flow/Port 委托；P1.C1 积压 = 未用 `runSaveFlow(editor=…)` 参数 + `DesktopExportPipelinePortTest` 仍写 “after P1.1–P1.2 cutover”；P2 残差 = Desktop composer 仍直接 `WatermarkCellComposer`；P3 残差 = port `Result<MediaRef>` + 写 `ImageInfo.width/height`。**不**在本 goal 改产品代码。
- 2026-07-18 / review fix (DONE_WITH_CONCERNS): (1) Status 授权改为 “Next planned slice when separately authorized: P1.C1”，消除 plan 不授权 vs 已授权 矛盾；(2) P4.1 明确复用/扩展 `LaunchScreenUiState`，禁止平行 `ProductRoute/currentRoute`；(3) P3/P4 补齐可复制 Gradle 命令、目标测试类与 partial/cancel/retry/路由恢复 关键断言，含 `./gradlew --stop`。
- 2026-07-18 / review fix 2: P4 最终 gate 去掉 `WaterMarkOffsetUpdateTest`（仅 P4.0 characterization）；最终改跑 Session-owned tests + 静态清零 Repository transient API/caller。`rg` gate 改为匹配即 `exit 1`（不再 `|| true`）。
- 2026-07-18 / review fix 3: `SessionOffsetIdentityTest` 钉死为 P4.3 **必建**；最终 gate 强制 `--tests` 该名；删除“可选/等价覆盖”表述。

## Errors / constraints encountered

| Item | Resolution |
|---|---|
| 根目录 planning trio 是历史材料，仓库规则禁止更新 | 使用 local-only migration tracker 下的单一 plan，并内嵌 findings/progress |
