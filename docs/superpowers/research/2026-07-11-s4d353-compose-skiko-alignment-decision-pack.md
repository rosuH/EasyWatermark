# S4d-353 — 负责人决策包：iOS Compose/Skiko Phase A 门禁

**日期：** 2026-07-11
**类型：** 仅决策包 — **未接受、未推荐任何选项**
**并非** Phase A/B/parity 完成。不是实现简报。

**具有约束力的硬性规则：** [codex-goal-v2.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal-v2.md) §6（Android 原生 text/icon/composition 保持不变；持久化字节神圣不可擅自改；禁止静默 golden 重基线；新增依赖须负责人批准；consumer-first 且 ≥2 个生产消费者；**未经负责人批准不得重试 S4d-338 / 不得变更 Compose–Skiko**）；§7.3–7.4（阻塞 = iOS text/full-root 关键边；其他车道可切换，但 non-text 已耗尽）。未经负责人禁止：变更 Compose/Skiko 版本。

在负责人签署某一选项之前，Codex **不得**实现、 bump 版本、发明替代方案或重排计划。

---

## 1. 精确阻塞点

### 运行时（S4d-338）

在 iOS 上托管 commonMain `TextContentOption` 的生产尝试（随后**完全回滚**）。证据：`build/s4d338-text-xcuitest-r{2,3,4}.xcresult`；`findings.md` “iOS CMP text-input linker block”；`progress.md` S4d-338。iOS 27.0 XCUITest：

| 运行 | 触发 | 失败 |
|---|---|---|
| r2 | Material3 `ModalBottomSheet` | 缺失 `LocalKeyboardOverlapHeight` |
| r3 | Compose `Dialog` + `navigationBarsPadding()` | 缺失 `LocalSafeArea` |
| r4 | 绕过 insets；Compose 文本可见 | 聚焦 → `SkikoPlatformTextInputMethodRequest` 缺少 `unclippedTextOffsetInRoot` |

相关：S4d-321 `GalleryDialogShell` 默认 Scaffold insets → `LocalSafeArea` 崩溃（witness 仅使用显式 `WindowInsets()`）。**编译成功 ≠ iOS sheet/IME 安全。** non-text CMP 已可运行。

### 锁定版本（`gradle/libs.versions.toml`）

| 项 | 版本 |
|---|---|
| Kotlin | `2.3.20` |
| Compose Multiplatform | `1.11.1` |
| AndroidX Compose BOM | `2026.05.01` → Android 上 UI **1.11.2**（catalog 注释） |
| Material3（app catalog 钉死） | `1.4.0` |
| AGP | `8.13.2` |

`:shared` = CMP 插件 + `compose.runtime/ui/material3`。`:app` = 强制 BOM + `org.jetbrains.compose.*` → `androidx.compose.*` 替换。Skiko 为**传递依赖**（iOS/desktop CMP）；**无单独 catalog 钉死**。文本/IME 修复意味着须负责人批准的 **CMP（并很可能含 Kotlin）对齐**，而非仅 host 补丁。Desktop Skiko 必须留在 `:app` runtime 之外。

---

## 2. 受影响的生产界面

| 界面 | 共享 API | 当前生产形态 |
|---|---|---|
| 水印文本 | `TextContentOption`（sheet + `OutlinedTextField`） | SwiftUI `TextField` + Apply → `WatermarkWorkflow` / DataStore |
| 模板 | `TemplateListSheet` / host（sheet + `AlertDialog` + text） | SwiftUI + `IosTemplateBridge` — S4d-346 **直接接入 NO-GO** |

**未阻塞（已是 CMP 或系统边）：** launch、icon、sliders、tile/typeface/style、color swatches（`showCustomInput=false`）、preview、`SavedOutputActions`、PhotosPicker — 见 S4d-352 矩阵。

独立产品决策：iOS About/gallery/完整 editor root。本门禁**不**重开 Android 原生渲染器。

---

## 3. 为何安全的 non-text 工作已耗尽

S4d-352：所有具备安全 commonMain API 的生产 iOS **交互式 non-text** 控件均已有 iosMain host。剩余：pickers、captions/status、**text**、**templates**。A1 纯 Android wrapper 已关闭（S4d-351）。iOS 上进一步的 Phase A **控件**进展需要下方 A/B/C。

---

## 4. 互斥的负责人选项

### A — 对齐 Compose/Skiko（所需 Kotlin），使 S4d-338 API 在 iOS 上可用

解除 `ModalBottomSheet` / safe-area locals / 聚焦 `OutlinedTextField` 阻塞。
- **Android 渲染器 / 持久化：** 必须保持 native + 字节一致。
- **Goldens：** 若 Android Compose 血缘移动，**重跑**本地 strict FNV；重基线**仅**在负责人签字后。按需重跑 Desktop/iOS 感知测试。
- **依赖：** 对 §6.11 / S4d-338 禁令的具名例外；记录 pin + 回滚。
- **门禁：** 完整 multiplatform Gradle；iOS XCUITest 文本（若日后 drop-in 则含 templates）；Android assemble + non-strict 测试；已解锁路径的截图。
- **回滚：** 还原 catalog pins；在变绿前保留 SwiftUI。

### B — 永久显式原生例外（形式化现状）

iOS 水印 **text** + **templates UI** 继续用 SwiftUI + bridges；共享 text/sheet API 在重开前仍仅 Android+Desktop。
- **Android / goldens / 依赖：** 若仅政策则无变更。
- **门禁：** 现有 SwiftUI XCUITest 仍为证明。
- **Phase A：** 该边以**例外**关闭，并非“完整共享 UI”。
- **回滚：** 不适用；仅能通过新的负责人决策重开。

### C — 有意收窄的新替代（仅当要在无完整 IME 下增加共享）

新的 commonMain API **不使用** sheet/dialog/聚焦 text（例如仅列表的 templates；展示行 + 平台 text slot）。不是现有 sheet API 的 drop-in。需要 §6.12 双生产消费者或显式豁免。
- **风险：** 双 API 漂移（Android/Desktop 保留完整 sheet）。
- **依赖：** 优先不 bump；设计仍须负责人批准。
- **门禁：** 新 hosts + XCUITest；无 Android/Desktop sheet 回归。
- **回滚：** 删除新 API；保留 SwiftUI。

**非选项：** 无 IME 修复的静默 inset hack；把发明产品根 / `WatermarkModeActions` 当作进展；Android draw-swap；未批准的 golden 重基线。

---

## 5. 负责人必须决定

1. **A、B、C 或 defer**（附理由/日期）？
2. 若 **A：** 精确的 CMP/BOM/Kotlin 目标；接受完整 iOS text XCUITest + multiplatform 门禁；**仅当** hash 变化时是否授权 strict golden 重基线？
3. 若 **B：** 持久落点（ADR vs findings）；在未重开前停止安排 iOS CMP text/templates？
4. 若 **C：** 最小表面；第二平台消费者或豁免；Android/Desktop sheets 保持不变？
5. 确认在任一选择下 **Android 原生渲染器 + 持久化字节不受影响**。

---

## 6. Codex 不得

接受/实现 A–C；在 UI 切片中 bump Compose/Skiko/Kotlin；在当前 mix 上重试生产 iOS `TextContentOption` / `TemplateListSheet`；重基线 goldens 或触碰 DataStore/Room；发明产品根；push/merge；将本决策包当作产品代码。

---

## 7. 建议

**未接受任何选项。** 证据已可决策；残余 iOS Phase A 控件进展仅等待负责人选择。

**参考：** [codex-goal-v2.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal-v2.md) §6–7；`findings.md` / `progress.md` S4d-338；S4d-346/352 research；`libs.versions.toml`；`ContentView.swift`；`TextContentOption.kt`；`TemplateListSheet.kt`。
