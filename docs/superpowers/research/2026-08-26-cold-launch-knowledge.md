# 冷启动串行：优化、改动、调研与经验

**日期：** 2026-08-26  
**分支：** `feat/migrate_to_compose`  
**状态：** 已落地。Owner 在 iPhone 16 Pro（`rosu的iPhone`，iOS 27）上确认启动观感明显变快。  
**决策：** [ADR-0032](../../adr/0032-android-splash-then-launch-fade-serial.md)（Accepted）

本文是这次冷启动工作的**入口**。数字、时序、计划原文仍在下面的专题稿里，不要把它们再抄一遍。

| 文档 | 角色 |
|---|---|
| [三端实测（中文）](./2026-08-25-cold-start-three-platform-bench.zh.md) / [EN](./2026-08-25-cold-start-three-platform-bench.md) | 改码前的实验室数字 |
| [系统启动页 vs 进程淡入](./2026-08-26-android-splash-vs-cold-reveal.md) | 改码前的时序调查（文首有过期说明） |
| [落地计划](./2026-08-26-splash-fade-serial-plan.md) | 当时锁定的实现规格（已标 shipped） |
| [ELI5](./2026-08-26-splash-vs-fade-eli5.html) | 两套时钟的可视化 |
| [ADR-0023](../../adr/0023-launch-editor-route-transition.md) | 禁止把 LaunchView 弹簧搬进冷启动 |

---

## 1. 用户看见的问题

口头感觉是「启动要一两秒」或「系统启动图挡很久，首页没有动画」。

实测后这两句话要拆开：

1. **Android release 并不慢。** 实验室模拟器 release 系统 TTID p50 **168 ms**。debug 是 **760 ms**（约 4.5 倍）。不要用 debug APK 判断用户等待。
2. **「没有动画」不是忘了写淡入。** 淡入一直在播。Android 12+ 系统闪屏和进程内 240 ms fade 是**两套互不通信的时钟**。debug 上 fade 多半在图标闪屏后面播完；release 上闪屏先拆，fade 才可能被看见，或被看成晚弹出。
3. **iOS 没有 Android 那种图标闪屏。** 空的 / 无效的 `UILaunchScreen` 会落到 `systemBackground`（设备浅色/深色，不是产品强制深色橄榄）。Compose 再从 `#262611` 淡入。人看到的是：系统底色 → 突然橄榄 → 再淡入控件。对齐底色之后，**第一块像素就已经是首页底**，所以真机上会觉得「快了很多」——即使 Kotlin/Compose 树还没画完。

Desktop 没有系统启动页。进程首次淡入保持原样。第一次出窗慢（实验室 `first_screen` p50 ~2.65 s）是首个 Compose 窗口 / Skia，不是 DataStore。本切片不修 Desktop 出窗。

---

## 2. 测出来的事实（改码前，2026-08-25）

实验室：Apple M5 Pro，macOS 27。脚本：`scripts/startup_bench.py`。每端 8 次冷启动，汇总丢掉第 1 次。原始 JSON 在本机 `build/startup_bench/`（不入库）。

| | Android debug 模拟器 API 36 | Android release 同机 | iOS 模拟器（Swift t0） | Desktop（`main()`） |
|---|---|---|---|---|
| 系统 TTID | 760 ms | 168 ms | — | — |
| 首屏 | 298 ms（`MyApp.onCreate`） | 无探针 | 153 ms | ~2652 ms |
| 绘制完成 | 667 ms | — | 427 ms | ~2765 ms |

要点：

- 进程内 `first_screen` 是 Launch **已经 layout**（logo / 选图 / 关于在树上）。此时 alpha 仍可以是 0。
- `fully_drawn` = 首屏 ∧ 淡入结束。Android 才调用 `Activity.reportFullyDrawn()`。**`reportFullyDrawn` 不延长闪屏。**
- Koin / DataStore 不是用户在等的那段。Android debug 税和系统闪屏交权才是。
- iOS 必须看 Swift 墙钟，不要只报 Kotlin `t_ms`（Kotlin 零点更晚）。
- 本轮没有 Android/iOS 真机探针数。2026-08-26 真机只做了观感确认，没有重跑 8 次 bench。

---

## 3. 决策（锁死，不要重开）

Owner 要的是**串行**，不是把 fade 删掉，也不是 iOS 上抄一份 Android 图标闪屏。

```
Android:  [图标闪屏] ──first_screen 或 skip──► splash.remove() ──► fade 0→1 @ 240ms ──► Launch 实底
iOS:      [橄榄启动页，无图标] ──进程起来──► 同色 SwiftUI 底 ──► 进程首次 fade（无握手）
Desktop:  无系统闪屏；进程首次 fade 立刻开
```

跳过 Android hold（第一帧就拆闪屏，不等 fade）当：

- Recovery
- 分享进入 / 底路由已是 Editor
- `MotionPolicy.Off`（系统动画缩放 = 0）
- 1500 ms 超时（`MainActivity` 须判断 `!isFinishing`）

其它硬约束：

- 淡入只播**本进程第一次**底路由是 Launch 的时候（`ColdLaunchReveal`）。Editor→Launch 重挂、About 叠层、分享进 Editor，都不重播。
- 握手放在 commonMain 对象上，**不要**为此加 `expect`/`actual`。
- 不要把 LaunchView 弹簧搬回来（ADR-0023）。
- 不要改 Android XML `md_theme_dark_background`（`#1D1B16`）。闪屏底用新色 `splash_screen_background` / iOS `LaunchBackground` = **`#262611`**（`DesignEditorBg`）。
- 不要给 iOS 启动页放图标（HIG：启动页应像第一屏，不是品牌闪屏）。
- Desktop **永远不要** `requestHostHold()`。

---

## 4. 落地对照

### 共享

| 件 | 做什么 |
|---|---|
| `ColdLaunchReveal` | `shouldPlay` / `observeFirstBase` 仍是进程一次性。新增 `requestHostHold` / `releaseHostHold` / `isHostHoldActive` / hold listener。`resetForTests()` 清 hold。 |
| `ProductShellHost` | `animateCold && hold` 时停在 alpha 0 / scale 0.97，**不**开 tween。`releaseHostHold()` → listener 把 `released=true` → 现有 `LaunchedEffect(released)` 开 240 ms `FastOutSlowIn`。淡完拆掉全屏 `graphicsLayer`，以免拖住 Launch↔Editor 短滑。 |
| `StartupTrace` | 门控探针。`first_screen` / `cold_reveal_done` / `fully_drawn`。`onFirstScreen()` **只生效一次**——`onGloballyPositioned` 会反复回调，listener 不能跟着反复拆闪屏。 |
| `LaunchScreen` | layout 后调 `StartupTrace.onFirstScreen()`。**禁止**在 Launch 首组上挂淡入（`RestrainedMotionSeamsTest`）。 |

### Android

- 依赖：`androidx.core:core-splashscreen` **1.2.0**（J4 单切片，稳定版，不写 alpha）。
- `MainActivity.onCreate`：**先** `installSplashScreen()`，再 `super.onCreate()`。
- `setKeepOnScreenCondition { keepSplash }`。Launch 将播 fade 时 `requestHostHold()`，并把 `firstScreenListener` 设成 `keepSplash = false`。
- `OnExitAnimationListener`：`remove()` 然后 `releaseHostHold()`。fade 从这里才开始。
- `onDestroy` 必须清掉 `firstScreenListener` / `fullyDrawnListener`。
- API 31+：`android:windowSplashScreenBackground` = `@color/splash_screen_background`。图标仍是默认 launcher。没有把主题 parent 改成 `Theme.SplashScreen`（API 23–30 仍走系统默认；那是后续，不是本切片回归）。

### iOS

- 应用 target `GENERATE_INFOPLIST_FILE=NO`，改的是手写 `iosApp/iosApp/Info.plist`。
- `UILaunchScreen` → `UIColorName = LaunchBackground`。**不要**嵌套空 `UILaunchScreen` dict（那会回落到 `systemBackground`）。
- `Assets.xcassets/LaunchBackground.colorset`：universal sRGB `#262611`。无 `UIImageName`。
- Swift 底 `ContentView.productBackground` 与 Compose `DesignEditorBg` 同色。fade 仍是进程首次，无 splash 握手。
- 模拟器第一次冷启动可以在橄榄底上停几秒才出 Compose；真机（2026-08-26 iPhone 16 Pro Debug）观感是立刻进入产品底。不要用模拟器第一次出树时间当「产品慢」。

### Desktop

- `Main.kt` / `DesktopWindow.kt` 只接 `StartupTrace`。不 hold。

### I3 同切片（冷启动旁边的运动）

大屏 `EwmContentDialog`、进程首次 Launch reveal：fade+scale 0.97 @ `shellShortMs`（240 ms）。开源页是共享 overlay，走 Launch↔Editor 短横滑+淡，**不是** About 的 0.75/0.5 封面。胶片切换保持硬切（`previewCrossfadeDurationMs = 0`）。

---

## 5. 探针与复测

正式包默认关。不要把 `WATERMARK_GOLDEN_STRICT` 或启动探针打进 PR CI。

| 平台 | 打开方式 | 日志 |
|---|---|---|
| Android | `adb shell setprop debug.ewm.startup_trace 1` 且 `adb shell setprop log.tag.EwmStartup DEBUG` | `EWM_STARTUP mark=<name> t_ms=<int>` |
| Desktop | `EWM_STARTUP_TRACE=1` 或 `-Dewm.startup.trace=true` | 同上，stdout |
| iOS | 启动参数 `-ewmStartupTrace` | Kotlin 行 + Swift 行（`clock=swift`） |

复跑实验室（**不要**擅自开关已经在用的模拟器）：

```bash
# Android：模拟器须已经在跑
./gradlew :app:assembleDebug --max-workers=8
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/startup_bench.py --platform android --iters 8 --out build/startup_bench

python3 scripts/startup_bench.py --platform desktop --iters 8 --out build/startup_bench

# iOS 模拟器（已 Booted）
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "platform=iOS Simulator,id=<UDID>" \
  -derivedDataPath build/ios_startup_dd CODE_SIGNING_ALLOWED=NO build
xcrun simctl install <UDID> \
  build/ios_startup_dd/Build/Products/Debug-iphonesimulator/iosApp.app
python3 scripts/startup_bench.py --platform ios --iters 8 --ios-udid <UDID> --out build/startup_bench
```

`CODE_SIGNING_ALLOWED=NO` **只能**给模拟器。真机必须签名。

---

## 6. 真机安装（2026-08-26 走过）

1. 关模拟器（仅当 owner 明确要求；站立命令默认不关正在用的 sim）：

```bash
export DEVELOPER_DIR=/Applications/Xcode-27.0.0-Beta.app/Contents/Developer
xcrun simctl shutdown all
killall Simulator 2>/dev/null || true
```

2. 确认配对真机：`xcrun devicectl list devices`。本实验室当时是 `rosu的iPhone`（iPhone 16 Pro，Developer Mode 开）。
3. 签名编 Debug（team 在 `iosApp.xcodeproj` 里：`DEVELOPMENT_TEAM = 85MZB3GP6S`）：

```bash
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "platform=iOS,id=<DEVICE_UDID>" \
  -derivedDataPath build/ios_device_dd \
  -allowProvisioningUpdates \
  DEVELOPMENT_TEAM=85MZB3GP6S \
  build
```

4. 安装并拉起：

```bash
xcrun devicectl device install app --device <COREDEVICE_ID> \
  build/ios_device_dd/Build/Products/Debug-iphoneos/iosApp.app
xcrun devicectl device process launch --device <COREDEVICE_ID> \
  me.rosuh.easywatermark.ios
```

`id=` 给 xcodebuild 用的是硬件 UDID；`devicectl --device` 用的是 Core Device identifier。两者不是同一个字符串。

---

## 7. 经验（下次不要再踩）

1. **先测再改动画。** 「一两秒 / 没有动画」在本仓库里一度是未测量的故事。三端数字出来之后，Android 用户路径的问题是**两套时钟并行**，不是 Koin 慢，也不是缺 240 ms fade。
2. **Debug ≠ 用户。** 把 760 ms debug TTID 当成 Play 体验会推错药。
3. **第一帧就算「画了」。** Compose 树 alpha=0 只要提交了 DrawFrame，Android 12+ 默认就会拆闪屏。要串行必须自己 `setKeepOnScreenCondition`，并在退出监听里 `remove()` 之后再放 fade。
4. **`installSplashScreen()` 必须在 `super.onCreate()` 之前。** 这是 compat 库的契约，不是风格问题。
5. **`onGloballyPositioned` 不是 once。** 闪屏 listener / `fully_drawn` 必须自己去重（`StartupTrace.onFirstScreen` 的 `firstScreen` 旗标）。
6. **iOS 空 `UILaunchScreen` 不是「没有启动页」。** 系统会填 `systemBackground`。手写 Info.plist（`GENERATE_INFOPLIST_FILE=NO`）时，嵌套空 dict 等于这个坑。
7. **启动页底色要对齐第一屏，不要对齐 Android XML `md_theme_dark_background`。** 共享 Compose 产品底是 `#262611`；Android XML 背景仍是 `#1D1B16`。混用会在闪屏交权时闪一下。
8. **不要为握手加 `expect`/`actual`。** 只有 Android 调 `requestHostHold()`。iOS/Desktop 不调，fade 立刻走。
9. **模拟器第一次 Compose 出树可以远慢于真机。** 用橄榄启动页验收时，看的是「第一块像素是不是 `#262611`、有没有图标」，不是「控件是否在 200 ms 内出现」。
10. **脏树里还有商店截图和 zh-CN 预填。** 冷启动切片不要 `git add -A`。`scripts/__pycache__/` 不要提交。

---

## 8. 刻意没做

- Android API 23–30 改 `Theme.SplashScreen` parent（pre-12 仍可能看到旧窗口底）。
- 带 `StartupTrace` 的 Android **release / benchmark** 重测（正式包当时没有探针）。
- Android / iOS 真机 8 次工业 bench；Perfetto / Instruments。
- Desktop 首窗 ~2.6 s。
- 胶片闪、Style→Layout 首击卡、BrandLogo mesh 循环（另一条 UX 线）。
- iOS 品牌图标启动页。
- 把 `md_theme_dark_background` 全局改成橄榄。

---

## 9. 回归清单

- [ ] Android 冷启动 Launch：闪屏在 `first_screen` 时还在；`releaseHostHold()` 之后才见 240 ms fade。
- [ ] Android 分享进 Editor / Recovery / 动画关：不 hold。
- [ ] iOS 冷启动：第一块像素 `#262611`，无启动图标，无系统白/浅底。
- [ ] Desktop：仍是进程首次 fade，无 hold。
- [ ] `./gradlew :shared:desktopTest --max-workers=8`
- [ ] `./gradlew :app:assembleDebug --max-workers=8`
