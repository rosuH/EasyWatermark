# EasyWatermark Android 增量编译方案（Jugg）

本文件是 EasyWatermark 的 **Android 日常编译合同**。Multica 团队实现、验证、部署 Android 改动时必须遵守。产品代码、Gradle 脚本、CI、Desktop、iOS 的权威构建仍是仓库现有 Gradle / Xcode 命令。

上游项目：[tencentmusic/jugg](https://github.com/tencentmusic/jugg)（MIT）。Wiki：[Jugg 文档](https://tencentmusic.github.io/jugg/zh/)。Skill：`jugg-android-dev-loop`（当前导入版本 1.0.29，日期 2026-09-12）。插件以 [Releases](https://github.com/tencentmusic/jugg/releases/latest) 为准（评估时最新稳定版为 3.4.2）。

## 1. 结论

采用 **旁路接入，不改工程构建脚本**。

| 决策 | 选择 | 原因 |
|---|---|---|
| 接入方式 | Android Studio 插件 + `jugg` CLI。不引入 Gradle plugin，不改 `build.gradle.kts` / `libs.versions.toml` / `settings.gradle.kts` | Jugg 的设计就是不改工程文件；改脚本会破坏「Gradle 仍是可信基线」这一前提 |
| 日常 Android 编译 | 烟测通过后，合格改动默认 `jugg --console=json compile`；需要设备/UI 时用 `deploy` | 避免每次小改都跑 `:app:assembleDebug` |
| 可信产物 | Gradle 完整构建仍是基线和对照。Jugg 与 Gradle 结果不一致时，以 Gradle 为准 | 上游明确：Jugg 不替代 AGP / Gradle |
| 仓库内 Skill | 不把上游 `jugg-android-dev-loop` 整包镜像进 `skills/` | 上游按版本更新；仓库只保留本叠加合同。Multica workspace 从 GitHub 导入 Skill |
| 强制生效条件 | **主机烟测通过之前，禁止把 Jugg 当成默认编译器。** 未通过时继续 `./gradlew --max-workers=8` | 当前工具链超出 Jugg 已验证范围，见 §3 |

烟测未通过时，本文件仍是后续实现必须遵守的目标合同，不是「现在已经在用 Jugg」。

## 2. Jugg 是什么

Jugg 复用最近一次 Gradle 产物（APK、classpath、资源表、生成代码），只编译本轮变化及其影响范围，再热重载 / 热修复 / 增量 APK / 重装到设备。日常入口是 IDE 里的 Jugg Run Configuration，或同一守护进程上的 CLI / MCP。

CLI 不是独立编译器。它连接本机 Android Studio 中 Jugg 插件的 HTTP JSON-RPC 服务（端口范围 `12320..12329`，路径 `/jugg-mcp`）。Android Studio 未打开本工程、插件未启动、或 CLI 找不到项目时，**立即回退 Gradle**，不要自建守护进程，也不要轮询不存在的端口。

Agent 优先用 CLI，不要直接配 MCP。CLI 已内置异步等待；MCP 的 `compile` / `deploy` / `gradle-build` / `instrument` 可能先返回 `jobId`，需要自己轮询 `get-compile-status`。同一任务禁止混用 CLI 和 MCP。

Jugg 官方能力覆盖 Java / Kotlin、Compose、KMP Android 目标、Compose Multiplatform 资源、`res` / assets / Manifest、ViewBinding / DataBinding、部分注解入口。EasyWatermark 用得到的子集见 §3 和 §5。

## 3. 与本仓库的适配

对照基线：隔离 checkout `master` `d47766cf`（评估时 HEAD）。产品模块：`:app` Android shell、`:shared` KMP + CMP、`:cmonet` 壁纸、`:desktopApp` / `iosApp` 非 Android。

### 3.1 工具链：超出已验证范围

| 项 | EasyWatermark | Jugg 文档已验证范围 | 判断 |
|---|---|---|---|
| AGP | 9.3.1 | 3.4–9.1（已验证含 9.1.2） | 超出。必须烟测 |
| Gradle | 9.7.0 | 5.4.1–9.2.1 | 超出。必须烟测 |
| Kotlin | 2.4.10 | 1.3–2.2 | 超出。必须烟测 |
| Isolated Projects | `org.gradle.isolated-projects=true` | 文档未写明 | 未知。烟测必须覆盖 |
| compileSdk | 37 | 文档写 target API 21–36 | 略超。记录实际行为 |
| minSdk | 23 | 设备 Android 8–16 | 运行设备需 Android 8+；minSdk 本身不是阻塞 |
| CMP | 1.12.0 | 声明支持 CMP 资源增量 | 概念匹配；版本组合未验证 |
| DI | Koin，Hilt 已移除 | 无 Hilt 增量限制 | 有利 |
| Room | `:shared` Room Gradle plugin + `:app` KSP Room | 不独立跑 Room / 一般 KSP2 processor | **改 Entity / DAO / schema 必须 Gradle** |
| Parcelize / Compose | 使用中 | 明确支持 | 合格改动可走增量 |

未验证不等于不能用。Jugg 文档写：未列入的中间版本通常可直接使用，遇到问题再报。对本仓库，**在烟测通过前不得把「通常可用」写成已兼容。**

### 3.2 适合 Jugg 的改动

- `:app` 或 `:shared` 的 Kotlin / Compose UI、业务逻辑（不改 Room 注解或 schema）
- `androidMain` / `commonMain` 的 expect/actual 方法体（不删文件、不改 source set）
- Compose Multiplatform `composeResources` 的新增或修改（不删除资源）
- Android `res/`、assets、简单 Manifest 修改
- 已有 `@Parcelize` 类型上的普通逻辑

### 3.3 必须走 Gradle 的改动

- `build.gradle.kts`、`settings.gradle.kts`、`gradle/libs.versions.toml`、`gradle.properties`、wrapper
- Room Entity / DAO / 数据库 schema / KSP 生成代码
- 删除 class、KMP 源文件、CMP 资源或 Manifest 节点
- 切分支、大批量跨模块修改、升级 AGP / Kotlin / Gradle / Compose
- unit test、`:shared:desktopTest`、iOS 测试、Desktop 运行
- 发布 APK / AAB、R8、正式签名
- CI（GitHub Actions）
- Headless / Cursor Cloud（无 Android Studio）
- `jugg status` 失败、端口连不上、或 `needFallback` / `NEEDS_GRADLE_BUILD`

Desktop 与 iOS 不在 Jugg 范围内。`:desktopApp:run` 与 iOS 框架构建仍用现有命令。

## 4. 主机前置条件

实施人员先完成这些步骤，再谈默认用 Jugg。

1. 安装 Android Studio（Bumblebee 至当前稳定版均可；以本机实际版本记录）。
2. 从 [Releases](https://github.com/tencentmusic/jugg/releases/latest) 安装 Jugg 插件。不要改 Gradle 来「接入」。
3. 用 **隔离 checkout** 打开 EasyWatermark，等待 Sync。禁止写入 `/Users/rosu/Coding/EasyWatermark` 原工作树。
4. 选择 Jugg Run Configuration，首次 Run 建立 Gradle 基线（完整构建 + 安装 debug APK）。debug `applicationId` 仍是 `me.rosuh.easywatermark.debug`。
5. 在 Search Everywhere 执行 `Install Jugg Skills`，勾选 CLI 到 PATH。Agent 侧也可使用 workspace 已导入的 `jugg-android-dev-loop`。
6. 终端执行 `jugg --console=json --project-dir <checkout> status`，确认能解析本工程。
7. 完成 §8 烟测清单并写进实施 issue。通过后，才把 Jugg 视为默认 Android 增量编译器。

设备约束与现有 Lead 规则相同：不得关闭已有模拟器、不得清用户数据；同一设备独占；重型 Gradle 由 Lead 串行安排；Gradle 使用 `--max-workers=8`。

## 5. 编译决策树（强制）

先完成 **全部** 源码修改，再触发 **一次** 编译。禁止每改一个文件就编译一次。

```text
改动是否只影响 Android 产品代码或资源？
  否（Desktop / iOS / 纯文档 / 纯测试）
    → 用 AGENTS.md 现有命令；不要调用 Jugg
  是
    → jugg 是否可用？（Android Studio 打开本 checkout，且
       `jugg --console=json status` 成功）
         否 → ./gradlew --max-workers=8 <对应任务>
         是 → 本轮改动是否属于 §3.3 必须 Gradle？
               是 → Gradle（可用 `jugg gradle-build` 或
                    `./gradlew --max-workers=8 :app:assembleDebug`）
               否 → 是否需要设备 / UI / Logcat / 运行态？
                     否 → jugg --console=json compile
                     是 → jugg --console=json deploy
```

解析 CLI 时同时看 `status`、`isCompileSuccess`、`isDeploySuccess`（若有）。协议成功不等于业务成功。`message` 含 `No pending file changes` 视为成功终态。

失败回退（与上游 skill 一致，本仓库叠加 Gradle 工人上限）：

1. 读 JSON `message` / `data.detail` 和 `build/jugg/log/compile_latest.log`。
2. 修源码后，对同一命令最多再试 3 次。每次必须是不同修复，禁止空重试。
3. 仍失败 → `jugg gradle-build` 或 `./gradlew --max-workers=8 :app:assembleDebug`。两者都是完整 Gradle；选一个跑完，不要并行。
4. 仍不清楚 → 停止并报告。不要删除整个 `build/`。需要保留 `build/jugg/log/` 与 `build/jugg/database/`。

`gradle-build` 是重操作。源码错误应先修代码，不要把完整构建当语法检查。

需要清空 App 数据时只用 `jugg clean-reinstall`，不要手动清数据或重装。手动清数据会丢掉增量部署状态。

## 6. 命令对照

在仓库根或显式 `--project-dir` 指向该根。Agent 使用 `--console=json` 或 skill 自带的 `python3 {SKILL_DIR}/scripts/jugg.py --console=json`。不要用 `--console=rich`。

| 目的 | 命令 | 何时 |
|---|---|---|
| 看增量是否就绪 | `jugg --console=json status` | 每次准备用 Jugg 之前；用户要求禁止 Gradle 回退时必做 |
| 只验证能编过 | `jugg --console=json compile` | 普通源码改动的默认 |
| 编过并上设备 | `jugg --console=json deploy` | 用户或验收要求看运行态 / UI |
| 部署后强制重启 | `jugg deploy --always-restart-app true` | 改了 Activity 声明、companion / static / 顶层声明、启动初始化 |
| 完整 Gradle 基线 | `jugg gradle-build` 或 `./gradlew --max-workers=8 :app:assembleDebug` | 回退、切分支后、工具链变化 |
| 单元测试 | `./gradlew --max-workers=8 :app:testDebugUnitTest` 等 | Jugg 不替代 |
| androidTest | 优先 `jugg instrument --source-path <file>`；不可用则 `:app:connectedDebugAndroidTest` | 目标切换常触发 Gradle 回退 |
| Desktop | `./gradlew --max-workers=8 :desktopApp:run` | 非 Jugg |
| 上一个编译还在跑 | `--if-compiling wait`（默认）或 `interrupt` | 禁止后台启动编译再 poll `status` |

编译类命令必须前台阻塞到进程退出。进程退出才是完成信号。

任务结束时按上游模板写两行结果（中文回复用中文模板）：

```
# Jugg 编译结果
场景=`{{仅编译|编译部署|Gradle回退|未使用Jugg}}`，命令=`{{jugg compile|jugg deploy|jugg gradle-build|./gradlew ...}}`
结果：`{{通过|失败|不确定}}`。编译结果=`{{成功|失败|未知}}`，部署结果=`{{成功|失败|跳过|未知}}`，验证结果=`{{跳过|轻量检查|不确定}}`。{{简短原因}}
```

未使用 Jugg 时，第一行写 `未使用Jugg` 并给出 Gradle 任务名。不要把 Gradle 成功写成 Jugg 成功。

## 7. 仓库与 Multica 落地

| 层 | 做什么 | 不做什么 |
|---|---|---|
| 本仓库 | 本文件 + `AGENTS.md` 路由 | 不 vendor Jugg 源码；不加 Jugg Gradle 依赖；不提交 `build/jugg/` |
| Multica workspace | 导入 `jugg-android-dev-loop`，绑定会改 Android 代码的 Agent | 不给 Reviewer 强行绑定（Reviewer 默认读 diff，不编包） |
| 运行主机 | 安装插件与 CLI，保持 Android Studio 打开本 checkout | 不在 CI runner 上装 Jugg 当门禁 |
| 产品验收 | 仍用 e2e-testmap / 本地 verify；Jugg 只加速编译部署 | 不把 `jugg compile` 成功当成 Human Confirm |

Skill 刷新：`multica skill refresh <skill-id>`。仓库合同与上游冲突时，**以本文件为准**（尤其是 `--max-workers=8`、设备独占、Room 必须 Gradle、烟测门闩）。

## 8. 烟测清单（实施人必须跑）

在隔离 checkout 上记录：插件版本、CLI 版本、Android Studio 版本、设备或模拟器、AGP / Gradle / Kotlin、Jugg 是否回退、耗时、日志路径。不要关已有模拟器。

1. `jugg --console=json status` 能看到本工程，且首次基线已建立。
2. 只改 `:shared` 里一个 Compose 文案或颜色，`jugg compile` 成功，且未无故 `fallback`。
3. 同上改动 `jugg deploy` 到 debug 包，界面可见变化。
4. 故意写一个 Kotlin 语法错误，`compile` 失败信息可定位；修复后第三次内通过。
5. 改 Room Entity 字段（或报告「本步用文档复述、未改生产 schema」）：确认走 Gradle，而不是静默用旧生成代码。
6. `./gradlew --max-workers=8 :app:testDebugUnitTest` 仍可用（Jugg 未破坏 Gradle）。
7. Isolated Projects 保持开启时，增量路径可完成；若 Jugg 要求关闭 Isolated Projects，**停止**，把冲突写进 issue，不要私自改 `gradle.properties`。

全部通过后，在实施 issue 注明「烟测通过」和 SHA。此后 Android 合格改动默认走 §5。任一项失败：保持 Gradle 为默认，issue 保持 blocked / backlog，不要改 AGENTS.md 把 Jugg 写成已生效默认。

## 9. 明确禁止

- 为了「接入 Jugg」修改任何 Gradle 脚本或版本目录。
- 把 Jugg 源码拷进本仓库当子模块或 vendored 工具链。
- 在 CI 中用 Jugg 替代 `assembleDebug` / 测试门禁。
- 无 Android Studio 时假装 Jugg 可用。
- 删除 `build/` 来「修复」增量。
- 用 Jugg 跑 Desktop / iOS / 发布包。
- 把 Jugg 热重载结果当成 R8 / release 行为。
- 与 Compose HotSwan 抢同一轮验证：Android 产品编译部署以本方案为准；HotSwan skill 仍只用于其自身工作流。
- 代签 Human Confirm。

## 10. 实施阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| A. 合同入库 | 本文件 + `AGENTS.md` 路由 | 本变更 |
| B. Workspace Skill | 导入并绑定 Lead / KMP / Client / ProductUX / QA | 评估当次完成 |
| C. 主机安装与烟测 | 插件、CLI、基线、§8 清单 | 待实施 issue；未授权不开工 |
| D. 默认切换 | 烟测通过后，合格 Android 改动强制走 Jugg | 依赖 C |
| E. 可选 | 用户声明 auto-run entry 后再启用 `flow_with_auto_run` | 默认关闭；禁止猜测入口方法 |

## 11. 关键决策

1. **旁路，不改 Gradle。** Jugg 的价值是跳过日常 Gradle 开销，同时保留 Gradle 产物。把 Jugg 写进构建脚本会让回退路径和 CI 同时变脏。
2. **CLI 优先于 MCP。** Agent 在终端里跑；CLI 已封装端口发现和等待。
3. **烟测门闩。** AGP 9.3.1 / Gradle 9.7.0 / Kotlin 2.4.10 / Isolated Projects 均超出或未出现在 Jugg 已验证表。未通过前 Gradle 仍是默认。
4. **Room / KSP 生成走 Gradle。** 上游不独立执行 Room 与一般 KSP2。方法体改动可以增量；注解和 schema 不行。
5. **一次编辑、一次编译。** 与上游 skill 强制规则一致，避免把增量队列打乱。
6. **不镜像上游 Skill 到 `skills/`。** 避免和 `android skills update` 以及 Multica `skill refresh` 三份漂移。

## 12. 待确认

- 本机 Android Studio 是否长期开着 EasyWatermark 隔离 checkout，从而让 Multica 本地 runtime 用得上 Jugg 守护进程。
- Isolated Projects 与 Jugg 是否冲突。冲突时默认保留 Isolated Projects，直到 owner 另作决定。
- 是否提供 auto-run entry 的完全限定方法名。未声明则不做该方法级运行验证。
