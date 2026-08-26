# 系统启动页 vs 进程首次淡入 — 时序调查（Android + iOS）

> **过期（产品代码）：** 本文冻结的是 **2026-08-26 改码前** 的仓库状态（当时没有 `installSplashScreen` / hold）。落地后的握手见 [ADR-0032](../../adr/0032-android-splash-then-launch-fade-serial.md) 和 [knowledge hub](./2026-08-26-cold-launch-knowledge.md)。实验室数字仍有效。

**日期：** 2026-08-26  
**范围：** 只调查，不改产品。Android 上实验室 **release TTID 168 ms** 并不慢；要查的是系统启动页和 240 ms 淡入谁先结束。本文同时写 iOS（没有 Android 那种图标闪屏，但是另一套启动页）。  
**相关基线：** [2026-08-25-cold-start-three-platform-bench.md](./2026-08-25-cold-start-three-platform-bench.md)（[中文](./2026-08-25-cold-start-three-platform-bench.zh.md)）

Android 12+ 冷/温启动有系统 `SplashScreen` Window。iOS / iPadOS 有 `UILaunchScreen`。Desktop 没有系统启动页。同一套 `ColdLaunchReveal`（240 ms）三端共用。

---

## 1. 问题

两套互不通信的时钟：

1. **系统闪屏**（Android 12+ `SplashScreen`，一层盖在 Activity 上的 Window）  
2. **进程首次 Launch 淡入**（`ColdLaunchReveal` + `ProductShellHost` 的 `graphicsLayer` alpha 0→1、scale 0.97→1，时长 `shellShortMs` = 240 ms）

需要回答：闪屏何时拆掉、淡入何时开跑、人实际看见什么、哪一段是测出来的、哪一段还是推断。

---

## 2. 官方：闪屏何时消失

来源：[Splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen)、[Launch time](https://developer.android.com/topic/performance/vitals/launch-time)、[SplashScreen compat API](https://developer.android.com/reference/kotlin/androidx/core/splashscreen/SplashScreen)。

冷启动或 Activity 尚未创建的温启动：

1. 系统用主题画出闪屏（默认是启动图标）。  
2. **应用画出第一帧时，闪屏被拆掉**，换成 Activity。  
   原文：*“The splash screen is dismissed as soon as your app draws its first frame.”*  
3. 热启动不显示这层闪屏。

第一帧仍算「画了」。alpha = 0 的 Compose 树只要提交了 DrawFrame，也算。要拖住闪屏，必须自己挂 `OnPreDrawListener` 返回 `false`，或 `SplashScreen.setKeepOnScreenCondition`。要接管拆掉瞬间，用 `setOnExitAnimationListener`，并在动画结束时 `remove()`。

官方还写：闪屏应在界面**视觉稳定**时再拆，不要露出未完成的界面。  
TTID = 第一帧上屏（Play vital）。TTFD = 调用 `Activity.reportFullyDrawn()` 之后的下一帧。**`reportFullyDrawn` 不延长闪屏。**

默认外观：若未设 `windowSplashScreenAnimatedIcon`，用启动器图标；窗口底若是纯色，用作闪屏底。compat 库 `androidx.core:core-splashscreen` 把同一套 API 接到 API 23。

---

## 3. 本仓库实际做了什么

在 `app/` 与 Gradle 里检索：`SplashScreen`、`installSplashScreen`、`core-splashscreen`、`windowSplashScreen*`、`windowBackground`、`setKeepOnScreenCondition`、`OnPreDrawListener`（启动路径）。**全部没有。**

| 项 | 现状 |
|---|---|
| 应用 / Activity 主题 | `@style/Theme.MyApp` → `Theme.BaseTheme` → `Theme.Material3.Dark.NoActionBar`（`AndroidManifest.xml`） |
| API 31 主题增量 | 只改了 window 动画和状态栏，**没有** `windowSplashScreen*`（`values-v31/themes.xml`） |
| 窗口 / 图标底 | `android:colorBackground` 与 adaptive icon background 都是 `#1D1B16`（`md_theme_dark_background`） |
| 闪屏图标 | 未覆盖 → 系统用 `ic_launcher`（`#1D1B16` + `ic_launcher_foreground`） |
| 拖住 / 自定义退出 | 无 |
| `reportFullyDrawn()` | `StartupTrace.fullyDrawn` → 首屏 **且** 240 ms 淡入结束之后（`MainActivity`）。这是 **TTFD**，不是闪屏。 |

因此 Android 12+ 冷启动**一定有**系统图标闪屏。应用既不定制它，也不知道它何时拆。

### 淡入自己的时钟

`ColdLaunchReveal.observeFirstBase`：本进程第一次 `ProductShellHost` 组合、且底路由是 Launch，才播一次。分享进 Editor、About 叠层、Editor→Launch 重挂，都不重播。这是对的，和闪屏无关。

`ProductShellHost`：

- `animateCold` 时 `Animatable` 初值 alpha = 0、scale = 0.97。  
- `LaunchedEffect(Unit)` **立刻**开 240 ms `FastOutSlowIn`，不等第一帧，更不等闪屏退出。  
- 外层 `Box` 的 chrome（橄榄底）**不在** `coldLayer` 上，始终不透明。淡的是里面的 Launch 树。  
- `MotionPolicy.Off`（系统动画缩放 = 0）时 `coldMs = 0`，没有淡入。

`BrandLogo` 的 mesh（2500 ms 循环）是另一条动画：等两帧才开。不要和 240 ms 淡入混为一谈。

没有 Android 主机信号把「闪屏已拆」传进 commonMain。

---

## 4. 两套时钟（已测 + 官方定义）

把官方「第一帧拆闪屏」叠到 2026-08-25 的打点上。进程内 0 = `MyApp.onCreate`。`am start -W` 的 TotalTime ≈ TTID ≈ 闪屏交权。

```
系统：  [======= 图标闪屏 Window =======] 第一帧 → 拆掉
本应用：          onCreate  Koin  setContent  shell  淡入 240ms        fully_drawn
                 |28ms|         |~170ms| |     |
                                              first_compose_frame
```

### Debug 模拟器（已测，n=7）

| 事件 | p50 | 相对谁 |
|---|---:|---|
| `app_create_end`（Koin+CMonet+Coil） | 28 ms | onCreate |
| `shell_composed`（淡入 **开跑**，alpha=0） | 219 ms | onCreate |
| `first_screen`（Launch 已布局，可能仍透明） | 298 ms | onCreate |
| `first_compose_frame`（≈ 官方「第一帧」） | 333 ms | onCreate |
| `cold_reveal_done` / `fully_drawn` | 667 ms | onCreate |
| OS TotalTime（TTID / 闪屏交权） | **760 ms** | `am start` |

`760 − 333 ≈ 427 ms`：onCreate 之前的 ART/bindApplication，和基线里「打点前约 460 ms」一致。

淡入从 219 跑到 667。第一帧在 333：

- 闪屏下面已经播了 **约 114 ms**（人看不见）。  
- 拆闪屏之后只剩 **约 126 ms**，而且已经过了 FastOutSlowIn 的前半。  
- 0.97→1 本身就很难察觉。

所以 debug 上「没有动画」**对得上**：先看很久的图标闪屏，再看到的 Launch 已经接近不透明。不是 `shouldPlay` 没触发。

### Release 模拟器（只测了 TTID）

TotalTime p50 **168 ms**（保留 n=4）。该包没有 `StartupTrace`，淡入落点未知。

168 ≪ 240。若官方规则仍适用（第一帧拆闪屏，TTID = 第一帧）：

- 闪屏大约在 168 ms 交权。  
- 淡入若在第一帧前几十毫秒才开始，交权后还会再跑 **约 200 ms**。  
- 人看到的是：**图标闪屏 → 橄榄底上 Logo/按钮从透明爬上来**。  
- 也可能看成「图标没了，首页晚弹一下」。0.97 缩放仍然容易被忽略，**alpha 0→1 在 release 上反而更可能被看见**。

这和 debug 相反。**「正式包没有动画」本轮不能成立，也还没被测到。**

`reportFullyDrawn` 在淡入结束后才调：release 的 TTFD 会被人为加上最多 240 ms，**闪屏不会因此多留**。

---

## 5. 人实际看见的画面（不是同一张图在淡）

闪屏：居中的 **adaptive 启动图标**（`#1D1B16` + `ic_launcher_foreground`）。  
交权后：同一块橄榄底，上面是 **Launch**（偏上的产品 Logo `ic_log_transparent`、中间选图、底部关于），从透明淡入。

底色连续，**图形不连续**：图标消失，另一套铬条出现。即使把两套时钟对齐，仍然是「系统图标 → 产品首页」，不是「同一枚 Logo 接着动」。

这和 iOS 不同。iOS `UILaunchScreen` 是空字典，先看到 SwiftUI 橄榄 `#262611`，再进 Compose，没有 Android 这种图标闪屏。

---

## 6. 根因（调查结论）

不是启动慢，是 **两套出现动画没有握手**：

1. 系统在 **第一帧** 拆闪屏（本仓库没有拖住）。  
2. 淡入在 **第一次组合** 就开始，第一帧时 Launch 按设计是透明的。  
3. 这和官方「视觉稳定再拆闪屏」相反：第一帧故意不稳定（alpha=0）。  
4. 用户观感由 **TTID 对 240 ms 的位置** 决定：慢构建（debug 760）盖住淡入；快构建（release 168）露出淡入或晚弹。

Koin / DataStore 不是这条因果链的一部分（debug 里 28 ms）。

---

## 7. 有效性

| 项 | 含义 |
|---|---|
| 拆闪屏 = 第一帧 | 官方文档。本仓库没有 listener 去改。 |
| debug 淡入被盖住 | 打点支持。 |
| release 会看见淡入 | **推断**。缺带探针的 release。 |
| 模拟器 ≠ Pixel | 真机 TTID 会变；竞态方向（盖住 vs 露出）仍由 TTID 对 240 ms 决定。 |
| API 23–30 | 没有 `SplashScreen` Window，只有 starting window。minSdk 23。这条竞态主要是 12+。 |
| 系统退出动画 | 未设 `setOnExitAnimationListener` 时，系统可能自己再播一段退出。与应用淡入重叠。未测。 |

---

## 8. 可选方向（未实现，未拍板）

都不需要先「把启动做快」。release 已经够快。

| | 做法 | 人看到什么 | 代价 |
|---|---|---|---|
| A. Android 取消进程淡入 | Launch 第一帧就 alpha=1。系统闪屏当出现。iOS/Desktop 可保留淡入 | 图标闪屏 → 静止首页 | 三端出现不一致；实现最小 |
| B. 第一帧就画实的，不要 alpha=0 | 不拖闪屏 | 图标 → 完整首页（可能叠系统退出动画） | 比 A 更硬；Android 上等于没淡入 |
| C. 拖住闪屏到视觉稳定 | `installSplashScreen` + `setKeepOnScreenCondition` 直到 Launch 已布局且 alpha=1；应用侧不再淡 | 图标多留一会儿 → 完整首页 | 拉长 TTID；要加 `core-splashscreen`（minSdk 23） |
| D. 闪屏退出 = 淡入 | 下面先画实 Launch；`setOnExitAnimationListener` 里淡掉闪屏 | 图标淡出，底下首页已在 | Android 专用；和 I3 0.97@240 要对齐 |
| E. 闪屏拆掉再开淡入 | 主机发「已退出」再让 `ColdLaunchReveal` 开跑 | release 上淡入完整可见 | 要新的 Android→shared 信号；debug 上闪屏后仍有 240 ms |

**不建议：** 把 `reportFullyDrawn` 再往后拖来「假装」闪屏还在——它不控制闪屏。  
**不建议：** 为了对齐闪屏去加 LaunchView 弹簧（ADR-0023）。

要拍板，缺的测量是：带 `StartupTrace` 的 **release/benchmark** 上 `first_compose_frame` / `fully_drawn` 相对 `am start -W`，最好再加闪屏 `exit` 打点。没有这个数，A/B（接受「闪屏就是出现」）和 D/E（要把淡入给人看）无法用数据选。

---

## 9. iOS：没有图标闪屏，但是有空白启动页

iOS **不是** Android 那条「图标闪屏盖住 / 露出淡入」的故事。系统启动页里没有 App 图标。淡入和启动页仍然没有握手，竞态方向和 Android debug **相反**。

### 9.1 官方：启动页何时换掉

来源：[HIG Launching](https://developer.apple.com/design/human-interface-guidelines/launching)、[UILaunchScreen](https://developer.apple.com/documentation/bundleresources/information-property-list/uilaunchscreen)、[UIColorName](https://developer.apple.com/documentation/bundleresources/information-property-list/uilaunchscreen/uicolorname)。

- 系统在进程起来的瞬间显示 launch screen，**第一屏准备好就立刻换掉**。目的是让启动显得快，不是品牌页。  
- HIG：启动页应和第一屏几乎一样，避免两屏之间闪一下。不要把 logo / 品牌当启动页，除非它本来就是第一屏的固定元素。  
- `UIColorName` 未设时，底色是 **`systemBackground`**，跟设备的浅色 / 深色走，**不是** 应用里 `.preferredColorScheme(.dark)`。

macOS / Desktop 不要求 launch screen。

### 9.2 本仓库实际做了什么

`iosApp/iosApp/Info.plist`：

```xml
<key>UILaunchScreen</key>
<dict>
    <key>UILaunchScreen</key>
    <dict/>
</dict>
```

外层 `UILaunchScreen` 合法。里层再套一个 `UILaunchScreen` 空字典 **不是** 合法子键（合法的是 `UIColorName`、`UIImageName`、栏配置）。等于 **没有设颜色、没有图** → 系统默认 `systemBackground`。

没有 `UILaunchStoryboardName`，没有 LaunchScreen.storyboard。

第一屏宿主（`ContentView`）：

- `.preferredColorScheme(.dark)`  
- SwiftUI 底 `#262611`（和 Compose `DesignEditorBg` 一致）  
- 上面嵌 `ComposeUIViewController`（`IosProductRootHost`）  
- `@StateObject` 创建 `IosProductRootBox` 时同步拉 Kotlin `defaultIosAppServices()`（DataStore / Session）

淡入仍是共享的 `ColdLaunchReveal`：第一次 `ProductShellHost` 组合就从 alpha 0 开跑 240 ms。iOS 没有「启动页已拆」信号。

### 9.3 人看见的三层（不是两层）

```
系统 UILaunchScreen     ContentView 橄榄底 + Compose(alpha 0→1)
[ systemBackground ] → [ #262611 ] → [ Logo / 选图 淡入 240ms ]
```

| 层 | 是什么 | 有没有图标 |
|---|---|---|
| 系统启动页 | `systemBackground`（浅色设备上接近白，深色上是系统深底，**不是** `#262611`） | 无 |
| SwiftUI 底 | `#262611` | 无 |
| Compose Launch | 同一块橄榄 + 产品铬条从透明上来 | 无系统图标；产品 Logo 在淡入里 |

Android 是「居中启动图标 → 产品首页」。iOS 是「系统空白底 → 橄榄 → 首页淡入」。  
浅色系统外观时，HIG 说的「两屏闪一下」在这里是 **白 → 橄榄**。应用强制深色，启动页不管这个。

### 9.4 已测时钟（iPhone 17 Pro 模拟器，墙钟 0 = Swift `t0`）

| 事件 | p50 |
|---|---:|
| `swift_compose_vc_ready`（Kotlin services + VC） | **82 ms** |
| `first_screen` | **153 ms** |
| `first_compose_frame` | Kotlin 97 ms / 墙钟约 170 ms |
| `fully_drawn`（含 240 ms 淡入） | **427 ms** |

启动页在第一帧交权，大约就在 Compose VC 就绪到首屏这一段（≤153 ms）。淡入从 `shell_composed` 起到 427 ms。

**启动页先没了，240 ms 淡入几乎整段都能看见。** 这和 Android debug（闪屏盖到 760 ms，淡入播完）相反，倒更像 Android release 的推断（TTID 168 ≪ 240），只是 iOS 交权时没有图标。

所以：

- 模拟器上「等一两秒」**不成立**（首屏 153 ms）。  
- 模拟器上「没有动画」**也不能用启动页盖住淡入来解释**；淡入应该能看见。若仍觉得没有，更像是 0.97 缩放太弱，或真机没测。  
- 真机 Debug / 第一次加载 `Shared.framework` 可能把 **空白 `systemBackground`** 拉长。那是「对着系统浅/深底等 K/N」，不是图标闪屏，也不是 240 ms 淡入。本轮真机 `3.0.0` 没有探针。

Kotlin `app_create_end` 经常是 0 ms：贵的是启动页下面那一段 **Swift t0 → 第一行 Kotlin**（模拟器约 76 ms）。

### 9.5 iOS 根因

| | Android 12+ | iOS |
|---|---|---|
| 系统层 | 图标 `SplashScreen` Window | 空白 `UILaunchScreen`（`systemBackground`） |
| 和第一屏像不像 | 底色接近橄榄，图是启动图标 | **不像**（浅色会白一下；深色也不是 `#262611`） |
| 相对 240 ms 淡入（已测构建） | debug：盖住；release：多半露出（未打点） | 模拟器：**露出** |
| 「等很久」 | debug 闪屏 760 ms；release 168 ms | 模拟器没有；真机若慢，慢在空白启动页下的 K/N |
| 握手 | 无 | 无 |

根因仍是「两套出现没有握手」，但 iOS 缺的不是图标时序，而是：

1. 启动页不是第一屏（违反 HIG）。  
2. 淡入在启动页拆掉之后才被人看见（模拟器上如此）。  
3. 真机「等很久」若存在，要测的是启动页停留时间，不是 240 ms。

### 9.6 iOS 可选方向（未实现）

| | 做法 | 人看到什么 |
|---|---|---|
| I1. 启动页改成 `#262611` | `UIColorName` 指向 asset 里的产品橄榄（修正 plist 里那层无效嵌套） | 系统底和 SwiftUI / Compose 底连续；浅色设备不再白一下 |
| I2. 启动页做成和 Launch 一样的静帧 | HIG 允许「第一屏固定元素」；不要单独做品牌闪屏 | 启动页 ≈ 静止首页，然后淡入或硬切 |
| I3. iOS 取消进程淡入 | 第一帧就 alpha=1；启动页（I1）当出现 | 橄榄 → 静止首页 |
| I4. 启动页拆掉再淡入 | 和 Android E 一样，要主机信号 | 模拟器上和现在差别不大（已经是拆完再淡） |

**不建议** 做成 Android 那种居中图标启动页（HIG 明确反对把启动页当品牌闪屏）。  
I1 不依赖再测，和淡入无关，只修「白/系统底 → 橄榄」的闪一下。

真机下一刀：Instruments 或现有 `-ewmStartupTrace`，看 `swift_app_init → first_screen` 墙钟，以及启动页是否明显长于 150 ms。

---

## 10. 来源

- https://developer.android.com/develop/ui/views/launch/splash-screen — 第一帧拆闪屏；默认可视元素；拖住 / 自定义退出  
- https://developer.android.com/topic/performance/vitals/launch-time — 冷启动 starting window；第一帧后系统换窗；TTID / TTFD  
- `androidx.core.splashscreen.SplashScreen` — `installSplashScreen`（`super.onCreate` 之前）、`setKeepOnScreenCondition`、`setOnExitAnimationListener`  
- https://developer.apple.com/design/human-interface-guidelines/launching — 启动页应接近第一屏；不是品牌页  
- https://developer.apple.com/documentation/bundleresources/information-property-list/uilaunchscreen/uicolorname — 未设颜色则为 `systemBackground`  
- 本仓库：`ColdLaunchReveal.kt`、`ProductShellHost.kt`、`MainActivity.kt`、`themes.xml`、`iosApp/Info.plist`、`ContentView.swift`、`iOSApp.swift`、`build/startup_bench/{android,ios}.json`
