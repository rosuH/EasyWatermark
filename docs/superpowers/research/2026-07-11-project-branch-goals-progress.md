# EasyWatermark — 项目 / 分支 / 目标 / 进度研究纪要

**Date:** 2026-07-11  
**Scope:** 仅基于仓库主源（`AGENTS.md` / [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) / `task_plan.md` / `progress.md` / `findings.md` / `docs/CONTEXT.md` / ADRs / CMP plan / git refs / PR #358）\
**Branch of record:** `feat/migrate_to_compose`  
**Audience:** 后续 agent / 协调者恢复上下文用；**不是** merge-ready 声明，也**不是** UI 1:1 验收报告。

---

## 1. Executive summary

**产品：** EasyWatermark（`me.rosuh.easywatermark`）是一款隐私优先的 Android 水印应用：在照片上平铺文字/图片水印，防止二次挪用。工程约束包括完全离线、零追踪/统计/崩溃 SDK、API 29+ 无需存储权限、导出剥离全部 EXIF。分发渠道：GitHub Releases、Google Play（付费、同码）、F-Droid、Coolapk；翻译由 Weblate（13 locales）维护。  
来源：`AGENTS.md` §What this app is；`README.md`；`docs/CONTEXT.md` §Invariants。

**当前位置（2026-07-11）：**

| 维度 | 状态 |
|---|---|
| View → Compose | **功能上已完成**（sole Activity = `ComposeMainActivity`；legacy View/Activity 栈已删） |
| KMP / CMP 代码迁移 | **进行中（Phase A）**：`:shared` 已存在并跨 Android/Desktop/iOS 编译；数据层与大量 shared CMP UI shells 已落地 |
| Desktop 产品流 | **功能级可用**（窗口、编辑、预览、保存、模板、打包证明），**非** v2.10.0 1:1 |
| iOS bring-up | **运行时证明 + 大量 shared 控件消费**；SwiftUI 仍为入口/系统 UI 胶水；**非** 1:1 |
| Android 1:1 像素还原 | **尚未开始（Phase B）**；生产 v2.10.0 仍是唯一视觉/行为真相源 |
| PR #358 | **Draft 集成检查点**，明确 **not merge-ready** |

**执行契约：** 自 2026-07-03 起由 [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) 主导流程（ACSP/cowork 退役）；切片号继续 `S4d-NNN`。\
来源：[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §0–§2；`AGENTS.md` §Current execution order (updated 2026-07-03)；`task_plan.md` §Current Phase。

**HEAD 语义（本地）：** 最近已接受切片为 **S4d-343**（恢复 Android 导出状态机）与 **S4d-342**（Desktop 消费 shared `EditorPreviewFrame` + 打包预览路径修复）；**S4d-254** Android 设备冒烟已于 2026-07-11 接受；**S4d-338**（iOS shared 文本输入）因 CMP 依赖/Skiko 对齐问题 **owner-blocked**。  
来源：`progress.md` §2026-07-11 S4d-338…S4d-343；`task_plan.md` §Current Phase；`.git/logs/HEAD` 末尾 commit messages。

---

## 2. Branch & VCS state

### 2.1 分支

| 项 | 值 | 证据 |
|---|---|---|
| 当前分支 | `feat/migrate_to_compose` | `.git/HEAD` → `refs/heads/feat/migrate_to_compose` |
| 上游跟踪 | `origin/feat/migrate_to_compose` | `.git/config` `[branch "feat/migrate_to_compose"]` |
| merge-base 提示 | `vscode-merge-base = origin/dev` | 同上 |
| 记录分支 | 与 [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) Owner 声明一致 | [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) 标题区 “Branch of record” |

### 2.2 HEAD 对比（研究时点）

| Ref | SHA (short) | 说明 |
|---|---|---|
| 本地 `feat/migrate_to_compose` | `fc94e936` | 最新 commit message：**Restore Android export state**（对应 S4d-343） |
| `origin/feat/migrate_to_compose` | `e54f7c4f` | 最新为 **Document iOS template bridge UI**（S4d-233 docs 一带） |
| `origin/HEAD` | → `origin/master` | 生产基线所在远程默认分支 |

**含义：** 本地相对 `origin/feat/migrate_to_compose` **明显领先**（大量 S4d-234…S4d-343 本地提交尚未推到该远程 tip；与 [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §6 “Never push … without explicit owner approval” 一致）。\
证据：`.git/refs/heads/feat/migrate_to_compose` vs `.git/refs/remotes/origin/feat/migrate_to_compose`；`.git/logs/HEAD` 末段。

### 2.3 最近本地提交主题（约末 30 条，`.git/logs/HEAD`）

工作流呈三条交织的 Phase A 线：

1. **Android wrapper / 依赖清理** — Remove dead ColorPickerView / Activity KTX / test deps；stale Compose catalog aliases；trim editor wrapper parameters；visibility/API hygiene（约 S4d-301…S4d-319）。
2. **iOS shared CMP 消费** — iOS launch/gallery/about host witnesses → gate behind test flag → production shared preview / tile / style / typeface / sliders / color / icon / launch screen（约 S4d-320…S4d-340）。
3. **Desktop shared shell + 设备证据** — Desktop 使用 shared shell/preview frame；记录 AndroMeld block → partial smoke → **Restore Android export state**（S4d-326…S4d-343）。

末段 commit 文案（从旧到新，摘自 `.git/logs/HEAD`）：

- `Use shared shell in Desktop window`
- `Use shared preview in iOS app` / `Frame iOS shared preview`
- `Use shared tile mode|text style|typeface|text size|rotation|opacity|h/v gap|text color on iOS`
- `Document iOS CMP text input block`（S4d-338）
- `Use shared icon watermark option on iOS` / `Use shared launch screen on iOS`
- `Record AndroMeld control socket block`
- `Use shared preview frame on Desktop`（S4d-342）
- `Record partial Android smoke evidence` → `Restore Android export state`（S4d-254 / S4d-343）

### 2.4 PR #358

- **标题：** Draft: Compose/KMP migration checkpoint, not merge-ready  
- **意图：** 大型 `feat/migrate_to_compose` 集成检查点；**在 1:1 parity 关闭前保持 Draft**  
- **自述限制：** 非 merge candidate；iOS 仍用 DEBUG fixture 绕过 PHPicker 单元格选择；PR 创建时未重跑 Android/真机 iOS 设备门禁；parity 仍有已知缺口  
- **与本地 tip 关系：** GitHub 页面上可见的较新 push 停在约 iOS template bridge docs（与 `origin` tip `e54f7c4` 一致）；**本地 S4d-234+ 工作大多尚未体现在该 PR tip**

来源：https://github.com/rosuH/EasyWatermark/pull/358 ；`AGENTS.md` / [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) / `task_plan.md` 均写 “PR #358 remains Draft”。

---

## 3. Project goals / migration target

### 3.1 双目标（[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §1）

1. **最大化 KMP + CMP 共享**  
   数据层（models / repos / DataStore / Room / use-cases）与 UI 层（screens / components / theme / state）尽量进入 `:shared` `commonMain`；仅在严格需要平台边时保留 native。
2. **1:1 工业级像素还原**  
   Android debug 必须对齐生产 **v2.10.0**（`me.rosuh.easywatermark`，`master` 构建）逐屏/逐态/手势；**不是**对齐本分支当前 Compose 草稿。Android 签收后再让 iOS/Desktop 对齐该 Android 基线，并显式记录平台例外。

### 3.2 工作顺序（硬顺序）

| Phase | 内容 | 状态 |
|---|---|---|
| **A** | 完成 release-grade KMP/CMP **代码**迁移；小切片、行为保持、字节级持久化不变 | **当前阶段** |
| **B** | 截图/录屏驱动 1:1 还原；**仅在 A 完成后**；勿把 parity polish 混进 A | **未开始** |

来源：[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §2；`task_plan.md` §Execution Order Update (2026-06-27)；`AGENTS.md` §Current execution order。

### 3.3 UI 路由原则（2026-06-30）

- **路线：** 从现有 Android Compose 屏迁移到 **shared CMP UI**。  
- **允许的 platform-native 边：** app/window 入口、picker/share/save/permission 系统 UI、平台能力胶水、必须 native/Skiko 的 renderer 表面。  
- **禁止：** 在 CMP 能承载时继续扩张长期 SwiftUI / Desktop-only 产品 UI。

来源：`AGENTS.md` §UI route of record；`docs/CONTEXT.md` 表项 **Shared CMP UI route**。

### 3.4 CMP 计划里程碑（原始 C1–C6）

计划文件：`docs/superpowers/plans/2026-06-12-cmp-migration-plan.md`。

| Phase | 原意图 | 相对现状（研究时点，粗映射） |
|---|---|---|
| C1 | Compose shell 完成 + CMP-shaped 选择 | 大体完成（sole Activity、typed routes、About/OpenSource/Recovery、黄金网基础） |
| C2a/C2b | 引擎抽取 + Canvas 预览 + image-space sizing | **完成**；Android 栅格/合成仍 native（闭决策） |
| C3 | 依赖去 Android 化 | **部分**（模型/MediaRef/ImageFormat/DataStore/Room 等已迁；Coil3/FileKit 等未全面按原表落地） |
| C4 | KMP 重构 + Desktop | **`:shared` + `:desktopApp` 已落地**；非完整 Android 级编辑器 1:1 |
| C5 | iOS bring-up | **已过 build/runtime 证明**；仍是 SwiftUI 胶水 + shared 控件，非完整 CMP 产品 UI |
| C6 | 加固 / 自适应 / 发布 | 未作为当前焦点；Desktop 公开分发仍 **ADR-0013 Proposed** |

### 3.5 Definition of Done（摘录，均未勾选）

[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §8 全部 checkbox 仍为未完成，包括：三平台 shared CMP 为路线真相、数据层 commonMain 化完成、Android 1:1 证据档案 owner 签收、PR #358 graduation 提案等。

---

## 4. Current architecture snapshot

### 4.1 模块

| 模块 | 角色 |
|---|---|
| `:app` | Android 壳：`ComposeMainActivity`、`MainViewModel`、`WatermarkRenderer`（native）、权限/picker/MediaStore/Coil 边、Koin DI |
| `:shared` | KMP：`androidTarget` + `jvm("desktop")` + `iosArm64` + `iosSimulatorArm64`；commonMain 域/引擎/UI shells；platform 边在 `androidMain`/`desktopMain`/`iosMain` |
| `:desktopApp` | Compose Desktop 入口 + `DesktopWindow` / headless 见证 |
| `iosApp/` | Xcode + SwiftUI 入口 + `Shared.framework`；`WatermarkWorkflow` 保留系统边 |
| `:cmonet` | Material You 动态色；经 `DynamicColorCapability` 暴露给 Compose（S4d-43） |
| `:baseBenchmarks` / `:macrobenchmark` | Android-only 性能 |

来源：`AGENTS.md` §Architecture；仓库目录结构；`shared/src/commonMain/...` 树。

### 4.2 数据流（平台中性核心已迁）

```
DataStore Preferences (WaterMarkRepository, UserConfigRepository — commonMain)
  + Room (Template / TemplateDao / AppDatabase / TemplateRepository — commonMain)
    → 共享 use-cases (WatermarkConfigEditor, OutputPrefsEditor, TemplateEditor)
      → Android: MainViewModel + Compose screens (仍 app-side 编排)
      → Desktop: DesktopWindow + editors over same repos
      → iOS: Swift WatermarkWorkflow + *Bridge suspend APIs（无 Flow 暴露给 Swift）
```

Store 创建是 **按平台 plain functions**（**无** `commonMain expect/actual createDataStore`）—— S4d-74/78/120 闭决策。  
来源：`docs/CONTEXT.md` 多行 domain 表；`AGENTS.md` conventions。

### 4.3 渲染引擎（产品核心）

| 路径 | 实现 | 决策 |
|---|---|---|
| Android 预览 + 导出 | `WatermarkRenderer.build*Shader` + `compose`（native Canvas / StaticLayout / BitmapShader） | 保持 native |
| 共享几何 / 常量 | `WatermarkGeometry`、`ICON_SCALE_REFERENCE_TEXT_SIZE` | 三平台单一真相 |
| Desktop/iOS 栅格 + 合成 | `WatermarkCellComposer.composeTextCell` / `composeIconCell` / `composeOverBackground` | Desktop/iOS only |
| 解码 | Android `BitmapUtils`；Desktop `DesktopImageDecoder`(+EXIF)；iOS Skia 已 bake EXIF | commonMain **无** decode API |

闭决策：S4d-8 图标、S4d-17 文字、S4d-190 合成 **均 No-Go Android draw-swap**。  
来源：`ADR-0004`（含 addenda）；`AGENTS.md` §Rendering engine；[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §5.1。

### 4.4 shared CMP UI 现状（文件系统）

`shared/src/commonMain/.../ui/` 已含大量 shells / options / theme / routes / save 组件，例如：

- Screens/shells：`LaunchScreenShell`、`GalleryDialogShell`、`EditorScreenShell`、`EditorPreviewFrame`、`EditorTopBarShell`、`EditorBottomControlsShell`、`AboutScreenShell`、`RecoveryScreen`、`OpenSourceScreen`、`TemplateListSheet` 等  
- Options：`TextContentOption`、`TextColorOption`、`TextTypefaceOption`、`TextPaintStyleOption`、`SliderOption`、`TileModeOption`、`IconWatermarkOption` 等  
- Save：`SaveExportSheetShell`、`SaveExportOptionsSection`、`SavePreviewStatus` 等  

Android app 侧仍保留薄包装：`LaunchScreen.kt`、`EditorScreen.kt`、`GalleryDialog.kt`、`SaveExportSheet.kt`、`AboutScreen.kt` + native `WaterMarkCanvas` / Coil / 权限。  
证据：`shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/**`；`app/src/main/java/me/rosuh/easywatermark/ui/**`。

---

## 5. Progress by workstream

### 5.1 View → Compose（Android）

**状态：功能完成（2026-06-13）**

- Sole Activity：`ComposeMainActivity`（launcher + Nav Compose + share-in + recovery）  
- 删除 legacy：`MainActivity` / `AboutActivity` / `OpenSourceActivity` + dialog/panel/adapter/base 等 39 `.kt`（ADR-0016）  
- 预览：Compose `Canvas`（`WaterMarkCanvas`）+ `WatermarkRenderer`；`WaterMarkImageView` / `ViewInfo` 已删（S3c-2/3）  
- 主题/部分 parity 早期修复（forced dark、filmstrip 等）见 `docs/superpowers/research/2026-06-13-ui-parity-backlog.md` —— **那是中间基线，非 Phase B 终态**

来源：`AGENTS.md` §1 View→Compose；`docs/adr/0016-mainactivity-integration-and-legacy-retirement.md`。

### 5.2 KMP foundations / 数据层

**大量已落地（S4d-50…S4d-98 一带及后续）：**

- 模型：`WaterMark`、`MediaRef`、`ImageInfo`、`ImageFormat`、`FuncType`/`WatermarkConfigChange`、`UserPreferences`…  
- Repos：`UserConfigRepository`、`WaterMarkRepository`、`TemplateRepository` 均 commonMain  
- Room KMP + 平台 builders（Android 预置 DB 兼容模式；Desktop/iOS BundledSQLite + seed）  
- Use-cases：`WatermarkConfigEditor`、`OutputPrefsEditor`、`TemplateEditor`  
- `MainViewModel` **业务 IO 共享抽取** 被 S4d-191 判定 **NO-GO/defer**（无第二消费者则不造 shared VM）

来源：`AGENTS.md` 长 Current state 列表；`docs/CONTEXT.md`；`progress.md` 2026-06-27…28 段。

### 5.3 Desktop

**功能产品流大体完成（仍非 1:1）：**

- 渲染管道：text/icon cell → composeOverRealImage → JPEG/PNG encode；EXIF bake  
- 窗口：打开图/图标、编辑控件、reactive preview、Save/Save as、share 替代、drag/drop 多文件、Open multi-select、模板 CRUD + 本地化 seed  
- 持久化：`~/.easywatermark`（config/prefs/templates/icons/preview）；输出到 `~/Pictures` 或 app output  
- 打包：`createDistributable` + CI `desktop_packaging.yml`（unsigned app image；**无** 签名/公证/installer）  
- **Lane 2：** Desktop 已消费多类 shared CMP controls（Save options、TileMode、Typeface、Style、Sliders、TextContent、TextColor、Icon option、`EditorScreenShell`、`EditorPreviewFrame` 等；S4d-278…S4d-342）

来源：`AGENTS.md` Desktop 段落；`progress.md` S4d-119…S4d-229、S4d-278…、S4d-342；`docs/CONTEXT.md` Desktop 表项。

### 5.4 iOS

**C5 运行时 + 功能路径证明；CMP 控件正在替换 bring-up UI：**

| 能力 | 状态 | 切片/证据 |
|---|---|---|
| `Shared.framework` + 字体包 | 已证明 | S4d-55 |
| 渲染/保存/分享（fixture 缝） | 已证明 | S4d-58 |
| 水印编辑字段 + icon 模式 | 功能完成 | S4d-102…S4d-118 |
| Templates Room/seed/Swift UI + XCUITest | 已证明 | S4d-231…S4d-233、S4d-253 |
| Shared CMP host witnesses | 测试门控 | S4d-320…S4d-325 |
| Production shared consumers | 多项已接 | preview frame、tile/style/typeface、sliders、color、icon、launch shell（S4d-327…S4d-340） |
| Shared 文本输入（`TextContentOption`） | **blocked** | S4d-338（`LocalKeyboardOverlapHeight` / `LocalSafeArea` / `unclippedTextOffsetInRoot`） |
| 真实 PHPicker 网格选中自动化 | **未证明** | S4d-57 工具链限制，非产品失败 |

来源：`progress.md` 2026-06-26…07-11；`findings.md` 顶部 S4d-321…S4d-338；`task_plan.md` §Current Phase。

### 5.5 Android parity

- **真相源：** 生产 v2.10.0（ADR-0011）  
- **早期审计：** `docs/superpowers/research/2026-06-13-ui-parity-backlog.md`（主题/filmstrip/文本 sheet 等中间修复）  
- **密度疑云：** S4d-209…212 **关闭**，非回归（仅 ~1.24× 文本尺寸漂移可接受）  
- **设备冒烟：** S4d-254 **已接受**（2026-07-11 AndroMeld：Launch→Gallery→Editor→调参→导出→相册→分享）—— **不是** 像素 1:1 签收  
- **S4d-343：** 恢复导出 sheet 的 `0/n → saving → n/n`、Share、View in gallery 状态机（CMP 迁移中丢失的 v2.10 行为，**仍属 Phase A 行为恢复**）  
- **Phase B 正式截图矩阵：** 未启动

来源：`docs/adr/0011-production-ui-parity-baseline.md`；`progress.md` S4d-254/S4d-343；`AGENTS.md` density 段。

### 5.6 Shared CMP UI lane（Phase A 当前主线）

两段式：

1. **Lane 1（S4d-255…S4d-277…）：** 把 Android Compose 布局壳抽到 commonMain（Launch/Gallery/Editor/About/Save…），Android 保留权限/Coil/native canvas 边。  
2. **Lane 2（S4d-278+）：** Desktop / iOS **真实消费** shared shells（非仅 DEBUG witness）。

**截至 S4d-343 的结论：** 共享壳与控件面已很大；平台入口/系统 UI/Android renderer 仍薄包装；iOS 文本 modal 与依赖对齐是明确阻塞；完整跨平台 “同一 EditorScreen 状态机” 尚未完成。

---

## 6. What's done vs not done

### 6.1 已完成（勿再宣称未做）

- [x] View→Compose 功能闭环 + legacy 删除  
- [x] `:shared` KMP 多目标编译；geometry + Desktop/iOS renderer 路径  
- [x] Android native 渲染策略（text/icon/composition）闭决策落地  
- [x] 核心 DataStore/Room/repos/use-cases commonMain  
- [x] Desktop 端到端 open→edit→render→save + 用户目录持久化 + 打包证明  
- [x] iOS 端到端 fixture 渲染/导出/分享 + templates UI 测试 + 多 shared 控件  
- [x] Android 模拟器 AndroMeld 冒烟（S4d-254）+ 导出状态机恢复（S4d-343）  
- [x] 过程切换到 Codex 直做 + 本地按切片提交（[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md)）

### 6.2 未完成 / 勿过度宣称

- [ ] **Phase B** Android v2.10.0 逐屏 1:1 像素/录屏验收  
- [ ] iOS/Desktop 对齐已签收 Android 基线  
- [ ] Shared CMP 成为三平台 **唯一** 产品 UI（iOS 仍大量 SwiftUI 编排；Android `MainViewModel` 仍重）  
- [ ] iOS shared `TextContentOption` / 聚焦文本字段（S4d-338 blocked）  
- [ ] 真实 PHPicker 网格选中自动化  
- [ ] `MainViewModel` 整体迁 shared（S4d-191 NO-GO）  
- [ ] Android 生产渲染改走 commonMain raster（禁止默认重开）  
- [ ] Desktop 是否公开分发（ADR-0013 Proposed）  
- [ ] PR #358 merge-ready / graduation  
- [ ] [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §8 DoD 全绿

### 6.3 测试门禁（近期 progress 复现数字）

反复出现的健康快照（单次切片证明，非永恒）：

- `:shared:desktopTest` ≈ **132/0**  
- `:shared:iosSimulatorArm64Test` ≈ **101/0**  
- `:app:testDebugUnitTest` ≈ **53/0**  
- iOS `iosAppUITests` 在 2026-07-11 多轮达 **17–18/0**（含新 shared 控件测试）

Strict FNV watermark goldens：**本地/钉扎环境**；**故意不进** GitHub PR CI（S4d-171/172）。  
来源：`progress.md` 各 proof 行；[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §4.1 / §5.10。

---

## 7. Active / most recent work（HEAD 在做什么）

| 切片 | 日期 | 结论 |
|---|---|---|
| **S4d-343** | 2026-07-11 | **HEAD 主题：** 恢复 Android 导出状态机（progress、Share、View in gallery）；shared sheet 只收 display flags |
| **S4d-342** | 2026-07-11 | Desktop `EditorScreenShell` 预览槽消费 `EditorPreviewFrame`；修复 packaged `.app` 预览临时路径 → `~/.easywatermark/preview/` |
| **S4d-254** | 2026-07-11 | AndroMeld 模拟器冒烟 **接受**（非 1:1） |
| **S4d-340** | 2026-07-11 | iOS 生产入口改用 shared `LaunchScreenShell` |
| **S4d-339** | 2026-07-11 | iOS 生产 icon 选项改用 shared `IconWatermarkOption` |
| **S4d-338** | 2026-07-11 | iOS shared 文本控件 **blocked**（CMP/Skiko 依赖对齐需 owner） |
| **S4d-326…337** | 2026-07-03…11 | Desktop shell 消费 + iOS 系列 shared controls（preview/tile/style/typeface/sliders/gaps/color） |

本地 tip SHA：`fc94e936`（Restore Android export state）。  
远程 tip SHA：`e54f7c4f`（仍停在更早的 iOS template docs）。

---

## 8. Known blockers / closed decisions（do-not-do）

### 8.1 当前阻塞 / owner 门

| 项 | 说明 | 来源 |
|---|---|---|
| **S4d-338** | iOS 上 `TextContentOption` / `ModalBottomSheet` / focused `OutlinedTextField` 在现有 Compose 依赖混合下 `IrLinkageError`；**禁止**无 owner 决策就升依赖或重试 | `progress.md`、`task_plan.md`、`findings.md` |
| **Compose/Skiko 版本错位** | `LocalSafeArea`、`LocalKeyboardOverlapHeight` 等（S4d-321 已见） | `findings.md` S4d-321…S4d-338 |
| **PHPicker 网格自动化** | Xcode 27 / iOS 27 上 XCUITest 无法点选系统 picker 单元格 | S4d-57；`AGENTS.md` |
| **Push / merge** | 无 owner 明确批准不得 push/merge/rebase | [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §6 |
| **ADR-0013 Desktop 是否上架** | Proposed，未决 | `docs/adr/0013-desktop-positioning.md` |

### 8.2 闭决策清单（默认勿重开）

摘自 [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §5 与 `AGENTS.md` conventions：

1. Android **text / icon / composition** 生产路径保持 native（S4d-17 / S4d-8 / S4d-190）  
2. 禁止恢复 `ViewInfo` / `AndroidView` 桥接预览（S3c-3）  
3. 禁止 `commonMain expect/actual createDataStore`；平台 plain 工厂（S4d-74/78/120）  
4. 禁止在 `:shared` 使用 compose-resources（CMP-9547 隔离）  
5. **持久化字节神圣**：DataStore keys、Room schema v1、seed DB、storage ids  
6. 故意保留的 Android `Uri` 边不要“顺便修掉”  
7. Dynamic color 经 `DynamicColorCapability`；`:cmonet` 吸收需 owner  
8. 非默认 `strings.xml` 归 Weblate  
9. 隐私契约：离线、无追踪 SDK、导出剥 EXIF（ADR-0009）  
10. Strict FNV golden **不进** GitHub PR Checks  
11. 新依赖 owner-gated；优先 stdlib/JDK/系统框架  
12. **不** 在无消费者时发明 shared ViewModel / IO expect（S4d-191）

---

## 9. Recommended next slices

按 `task_plan.md` §Current Phase “Next” 与 `progress.md` 末段、[codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) §7 综合（**Phase A only**）：

1. **继续 Phase A 真实 shared 消费者**  
   - iOS：优先**不**需要 CMP 文本聚焦的剩余表面（模板列表若可在无 `OutlinedTextField` 依赖下推进则评估；否则等 S4d-338 owner 决策）  
   - Desktop：其余 native 控制若已有 shared shell 形状则可替换；保持 AWT/IO 在边  
   - Android：继续收薄 wrappers；导出/分享边已由 S4d-343 修过，勿再丢状态机  

2. **S4d-338 停放**  
   - 需 owner 明确 CMP/Skiko 版本对齐目标；在此之前不要重试 focused text field  

3. **不要启动 Phase B**  
   - 除非 Phase A 的 “shared CMP 为三平台路线真相 + 数据层完成” 更接近 [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) DoD\

4. **Parked Phase B 债务（记录，勿现在做）**  
   - RecoveryScreen 精细 parity（曾标 S4d-207）  
   - Editor baseline delta pack（曾标 S4d-208 / S4d-209 UI deltas）  
   - 完整 `docs/parity/` 证据档案  

5. **VCS**  
   - 本地已大幅领先 `origin`；任何 push 需 owner 授权后更新 PR #358 Draft tip  

6. **设备纪律**  
   - 重冒烟时优先 AndroMeld；确认 `READ_MEDIA_IMAGES`；查看截图而非文件大小；重负载后 `./gradlew --stop`  

---

## 10. Key ADRs index（迁移相关）

| ADR | 主题 | 状态（研究时点） |
|---|---|---|
| 0001 | 平台顺序 Android → Desktop → iOS | Accepted |
| 0002 | 单 `:shared`；AGP 8.x hold | Accepted |
| 0003 | 留 Nav2 | Accepted |
| 0004 | 渲染 commonMain 重写 + Android native 保留 addenda | Accepted（多处修订） |
| 0005 | DI：接口 + Koin | Accepted |
| 0006 | Data layer KMP | Accepted |
| 0007 | 平台中性模型 | Accepted |
| 0008 | minSdk 23 hold | Accepted |
| 0009 | EXIF strip 是功能 | Accepted |
| 0010 | 捆绑字体 + 双层 golden | Accepted |
| 0011 | 生产 v2.10.0 UI baseline | Accepted |
| 0012 | AI-friendly docs | Accepted |
| 0013 | Desktop 是否上架 | **Proposed** |
| 0014 | Parity micro-decisions | Accepted |
| 0015 | Parity vs Compose idiom | Accepted (revertable) |
| 0016 | MainActivity 整合 + legacy 退休 | Implemented / 设计时 Proposed，实现已落地 |

来源：`docs/adr/README.md` 与各 ADR 文件头。

---

## 11. Source map（本报告主要引用）

| 源 | 用途 |
|---|---|
| `AGENTS.md` | 产品定义、当前状态地图、架构、禁令 |
| [codex-goal.md](https://github.com/rosuH/EasyWatermark/blob/aab7f6e5258f5b9137b136f07a68c190d2d05780/codex-goal.md) | 任务/流程/DoD/门禁/闭决策 |
| `task_plan.md` | Current Phase、执行顺序、下一步 |
| `progress.md` | 切片级已验证证据（尤其 2026-07-03…07-11） |
| `findings.md` | 工程教训（iOS CMP 陷阱、slider 边界等） |
| `docs/CONTEXT.md` | 域词汇与不变量 |
| `docs/adr/*` | 架构决策 |
| `docs/superpowers/plans/2026-06-12-cmp-migration-plan.md` | C1–C6 原始蓝图 |
| `docs/superpowers/research/2026-06-13-ui-parity-backlog.md` | 早期 UI 审计（非终态） |
| `.git/HEAD` / refs / logs | 分支与本地 tip 证据 |
| GitHub PR #358 | Draft 检查点公开描述 |

---

## 12. Overclaim guard（给读者）

- **不要说** “KMP/CMP 迁移完成” 或 “三平台已 1:1”。  
- **不要说** “PR #358 可合并”。  
- **不要说** “Android 已像素对齐 v2.10.0”——仅有冒烟 + 部分行为恢复。  
- **不要说** “iOS 已是完整 CMP 产品 UI”——大量 SwiftUI 与系统边仍在；文本控件 blocked。  
- **Desktop** 是 release-grade **功能** 与打包证明，**不是** 上架产品（ADR-0013 未决）。  
- `task_plan.md` 顶部历史段落中仍混有 “S4d-254 blocked” 的旧措辞；**以 `progress.md` 2026-07-11 “S4d-254 accepted” 与本地 HEAD 为准** 覆盖该过时句。

---

*End of research note.*
