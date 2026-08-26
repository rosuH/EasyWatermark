# 冷启动 / 首帧 / 首屏 — 三端性能测试报告

**日期：** 2026-08-25（上海时间晚间；JSON 内为 UTC）  
**分支：** `feat/migrate_to_compose` + 门控 `StartupTrace`  
**原始数据：** `build/startup_bench/startup_bench.json`（另有 `android.json`、`desktop.json`、`ios.json` 与逐次日志）  
**脚本：** `scripts/startup_bench.py`  
**英文原稿：** [2026-08-25-cold-start-three-platform-bench.md](./2026-08-25-cold-start-three-platform-bench.md)  
**本轮之后的落地：** [知识沉淀](./2026-08-26-cold-launch-knowledge.md) · [ADR-0032](../../adr/0032-android-splash-then-launch-fade-serial.md)

这是**实测基线**，不是估计。三端时钟和构建类型不同，**不能**用表头数字直接说「iOS 比 Android 快」。有效性见第 7 节。

---

## 1. 实验室

| | |
|---|---|
| 宿主机 | Apple M5 Pro，48 GiB，macOS 27.0（25A5416b），Darwin 27.0.0 arm64 |
| JDK | Homebrew OpenJDK 17.0.20（Desktop 走 `gradlew :desktopApp:run`） |
| Android | **模拟器** `sdk_gphone64_arm64`（ranchu，arm64-v8a），**API 36 / Android 16**，qemu=1。事先已在跑，本轮未关机。 |
| iOS | **模拟器** iPhone 17 Pro（`257EE76A-…`），iOS 27.0。配对真机 iPhone 16 Pro 上装的是商店/开发 `3.0.0`，**没有本探针**，本轮未重测。 |
| Desktop | 同一台宿主机，Compose Desktop 窗口，`EWM_STARTUP_TRACE=1`，5 秒后自动退出 |
| 次数 | 每端 8 次冷启动。汇总**丢掉第 1 次**（JIT / 类加载 / 模拟器日志挂接）。 |
| 间隔 | Android `am force-stop` + 2 s；Desktop 每次新 JVM（经 Gradle）；iOS `simctl terminate` + 1.5 s |

### 被测制品

| 平台 | 制品 | 说明 |
|---|---|---|
| Android debug | 刚装的 `me.rosuh.easywatermark.debug`（`:app:assembleDebug`） | 进程内打点 + `am start -W` |
| Android release | 机器上已有的 `me.rosuh.easywatermark` | **只有** `am start -W`（该包没有 `StartupTrace`） |
| Desktop | `:desktopApp:run` Debug | 从 `main()` 起的进程内打点 |
| iOS | `iosApp` Debug，`CODE_SIGNING_ALLOWED=NO`，DerivedData `build/ios_startup_dd` | Swift 时钟 + Kotlin 时钟 |

---

## 2. 指标词典

Kotlin 三端共用同一套标记名。`t_ms` 是 `TimeSource.Monotonic`，零点是进程内**第一次读到** `StartupTrace`。

| 名称 | 定义 |
|---|---|
| **系统 TTID** | Android `am start -W` 的 **TotalTime**（毫秒）。业界「到首次显示」：`startActivity` → Activity 第一帧上屏。本台 API 36 模拟器**没有**打印 `ThisTime`。 |
| **app_create_*** | Application / DataStore / Session 图搭完。 |
| **host_set_content** | `setContent` / `ComposeUIViewController` / Desktop `SwingWindow`。 |
| **first_compose_frame** | `ProductShellHost` 之后第一次 `withFrameNanos`。 |
| **first_screen** | `LaunchScreen` 完成布局（`onGloballyPositioned`）：Logo、选图、关于都已在树上。此时透明度仍可能为 0。 |
| **cold_reveal_done** | 进程首次淡入+缩放播完，**或**被跳过。 |
| **fully_drawn** | `first_screen` ∧ `cold_reveal_done`。Android 还会调 `Activity.reportFullyDrawn()`。 |
| **iOS 墙钟** | 统一日志上从 `swift_app_init`（Swift 可见的 `t0`）到同一标记。说 iOS「点图标到出屏」用这个，不要只用 Kotlin `t_ms`。 |

**本轮没测：** 选图按钮可点之后的 Time to Interactive（只证明它已组进树）。没有 Macrobenchmark `StartupTimingMetric` 的 `benchmark` 包。没有 Instruments / Perfetto。没有 Android / iOS 真机。

---

## 3. 方法

1. 门控探针：Android `debug.ewm.startup_trace=1` + `log.tag.EwmStartup`；Desktop `EWM_STARTUP_TRACE=1`；iOS 参数 `-ewmStartupTrace`。正式包默认关。  
2. 每次都是冷进程。  
3. 解析 `EWM_STARTUP mark=<name> t_ms=<int>`。  
4. 汇总：在**留下的 7 次**上算 n、min、**p50**、mean、p90、max、总体标准差。

复现：

```bash
# Android（模拟器已在跑）
./gradlew :app:assembleDebug --max-workers=8
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/startup_bench.py --platform android --iters 8 --out build/startup_bench

# Desktop
python3 scripts/startup_bench.py --platform desktop --iters 8 --out build/startup_bench

# iOS 模拟器（已启动的 iPhone 17 Pro）
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "platform=iOS Simulator,id=<UDID>" \
  -derivedDataPath build/ios_startup_dd CODE_SIGNING_ALLOWED=NO build
xcrun simctl install <UDID> build/ios_startup_dd/Build/Products/Debug-iphonesimulator/iosApp.app
python3 scripts/startup_bench.py --platform ios --iters 8 --ios-udid <UDID> --out build/startup_bench
```

---

## 4. 结果（未另注则为 p50）

### 4.1 头条（保留 n=7）

| 时钟 | Android debug | Android release | iOS 模拟器（墙钟） | Desktop 进程内 |
|---|---:|---:|---:|---:|
| 系统 TTID（`TotalTime`） | **760 ms** | **168 ms** | — | — |
| 首屏 | **298 ms**（从 `MyApp.onCreate` 起） | 未打点 | **153 ms**（从 Swift `t0` 起） | **2652 ms**（从 `main()` 起） |
| Compose 首帧 | 333 ms | — | Kotlin 97 ms / 墙钟约 170 ms | 2679 ms |
| 绘制完成（首屏 + 入场） | **667 ms** | — | **427 ms** 墙钟 | **2765 ms** |

Android debug 的 `TotalTime` p50 **760**，进程内 `first_screen` **298**：大约 **460 ms** 的 bindApplication / 系统闪屏 / ART 发生在进程内时钟之前或附近。240 ms 冷启动淡入（`fully_drawn − first_screen` 的 p50 约 369 ms，含排帧）**在 debug `TotalTime` 之前就结束了**。这台 debug 模拟器上，淡入是在**系统闪屏后面**播完的。

Android **release** `TotalTime` p50 **168 ms**（5 次丢掉第 1 次，剩 4 次）。该 APK 没有探针，无法把淡入放在同一根轴上。168 ms ≪ 240 ms：若第一帧上屏就拆掉闪屏，而 Launch 还在淡入，**release 才是 240 ms 淡入可能被看见——或被看成晚弹出——的构建**；debug 上看不到。

### 4.2 Android debug 进程内（毫秒，丢掉第 1 次）

| 标记 | min | p50 | mean | p90 | max | 标准差 |
|---|---:|---:|---:|---:|---:|---:|
| app_create_end | 26 | 28 | 28 | 30 | 30 | ~2 |
| shell_composed | 208 | 219 | 220 | 232 | 239 | — |
| first_screen | 291 | **298** | 301 | 312 | 314 | 8.7 |
| first_compose_frame | 320 | **333** | 332 | 342 | 346 | 8.8 |
| mesh_ready | 332 | 342 | 343 | 353 | 355 | — |
| cold_reveal_done | 616 | **667** | 669 | 709 | 717 | 31 |
| fully_drawn | 616 | **667** | 670 | 714 | 718 | 33 |
| 系统 TotalTime | 739 | **760** | 761 | 778 | 785 | 15 |

每次 `am start -W` 都是 `LaunchState: COLD`。这台模拟器的 `am` 输出没有 `ThisTime`。

### 4.3 Android release 仅系统 TTID（毫秒）

| | min | p50 | mean | max |
|---|---:|---:|---:|---:|
| TotalTime（保留 n=4） | 162 | **168** | 175 | 201 |

5 次里第 1 次是 223 ms（已丢掉）。**同一台模拟器上，debug 的 TotalTime 大约是 release 的 4.5 倍。**

### 4.4 iOS 模拟器

**不要拿 Kotlin `t_ms` 和 Swift `t_ms` 直接比。** Kotlin 零点从 `IosAppServices` 惰性初始化里第一次碰到 `StartupTrace` 算起，统一日志上大约比 Swift `t0` 晚 70–80 ms。

从 `swift_app_init` 起的墙钟（统一日志，丢掉第 1 次，n=7）：

| | min | p50 | mean | max |
|---|---:|---:|---:|---:|
| `swift_compose_vc_ready` | 70 | **82** | 81 | 87 |
| `first_screen` | 146 | **153** | 154 | 163 |
| `fully_drawn` / 入场结束 | 410 | **427** | 431 | 461 |

只看 Kotlin（从 services 标记起）：`first_screen` p50 **78 ms**，`fully_drawn` p50 **353 ms**。入场时长 ≈ 240 ms + 几帧（`fully_drawn − first_compose_frame` ≈ 256 ms）。

Kotlin 的 `app_create_end` 经常是 **0 ms**（和 start 同一毫秒）：K/N 起来之后 DataStore+Session 很便宜。贵的是 **Swift t0 → 第一行 Kotlin**（典型保留次大约 76 ms）。

### 4.5 Desktop（从 `main()` 起的进程内，丢掉第 1 次）

| 标记 | min | p50 | mean | p90 | max |
|---|---:|---:|---:|---:|---:|
| app_create_end（DataStore+Session+Room 种子） | 630 | **677** | 756 | 940 | 1262 |
| first_screen | 2527 | **2652** | 3370 | 4720 | 7341 |
| first_compose_frame | 2554 | **2679** | 3401 | 4761 | 7390 |
| fully_drawn | 2690 | **2765** | 3504 | 4840 | 7458 |

丢掉的第 1 次：`first_screen` **10686 ms**（Gradle + 首次 JIT）。留下的第 2 次仍是 **7341 ms**。第 3–8 次落在 **2.5–3.0 s**。`runner_wall_ms`（约 9–12 s）含 Gradle，**不能当 TTID**。

`first_screen − app_create_end` 的 p50 约 **2.0 s**：第一次 `application {}` 组树（Room `getAllTemplate`、`runBlocking` 读字符串、**12 次 `painterResource`**、窗口落地）发生在 Launch 布局之前。

---

## 5. 瀑布（典型保留次）

### Android debug（进程内 0 = `MyApp.onCreate`）

```
0        app_create_start
28       app_create_end          Koin + CMonet + Coil
50       host_set_content
219      shell_composed
298      first_screen            Launch 已布局（透明度可能仍为 0）
333      first_compose_frame
342      mesh_ready
667      fully_drawn             240 ms 入场 + 排帧
--- 系统 ---
760      TotalTime               Activity 第一帧 / 闪屏交权
```

### iOS 模拟器（墙钟 0 = Swift `EasyWatermarkApp.init`）

```
0        swift_app_init
82       swift_compose_vc_ready  窗口内同时起来 Kotlin services
153      first_screen
~170     first_compose_frame
427      fully_drawn             入场结束
```

系统 `UILaunchScreen` 是空字典；橄榄色 `#262611` 是 SwiftUI，然后才是 Compose。**没有** Android 那种图标闪屏。

### Desktop（0 = Kotlin `main`）

```
0        app_create_start
677      app_create_end          存储 + Session + Room 构建
~2.6 s   first_screen            图标资源 + 第一扇窗口
2.77 s   fully_drawn
```

---

## 6. 对「等很久、没有动画」的含义

1. **Android debug 模拟器：** 等待是真的（系统 TTID **760 ms**，±15 ms 很紧）。240 ms 淡入在大约 667 ms 结束，比 `TotalTime` 早约 **100 ms**。人看到的是：闪屏很久，然后首页已经静止。这和之前的竞态假设在**本实验室 debug 构建**上对得上。  
2. **Android release 模拟器：** 系统 TTID **168 ms**。该 APK **没有打点**。竞态很可能**反过来**：闪屏先没了，240 ms 淡入还在播。「正式包没有动画」是另一句话，需要带 `StartupTrace` 的 release/benchmark 重编再测。时序调查见 [2026-08-26-android-splash-vs-cold-reveal.md](./2026-08-26-android-splash-vs-cold-reveal.md)。  
3. **iOS 模拟器：** 首屏墙钟 **153 ms**。入场结束 **427 ms**。空启动页 + 橄榄底，不是图标闪屏。若「等很久」指的是 **iOS 真机** Debug/K/N，本模拟器数字只是**下限**，不是真机数。  
4. **Desktop：** 热 Gradle 之后，从 `main()` 到首屏大约 **2.6 s**。这确实是长等待；240 ms 淡入在这个预算里可以忽略。

---

## 7. 有效性 / 威胁

| 威胁 | 影响 |
|---|---|
| Debug vs release | **Android 上占主导。** Debug TotalTime 是 release 的 4.5 倍。不要把 760 ms 当成 Play 用户时间。 |
| 模拟器 / Simulator | 不是 Pixel，也不是 iPhone 16 Pro。CPU 是宿主机 M5 Pro；GPU/显示路径是虚拟的。 |
| iOS 两套时钟 | Kotlin `first_screen` 78 ms ≠ 墙钟 153 ms。对用户说「点一下」要用墙钟。 |
| Desktop Gradle | 进程内时钟从 JVM 起来之后才走；前两次是编译/JIT 离群。 |
| `fully_drawn` 含入场 | `fully_drawn` 包含 240 ms 动画。Launch 铬条的**第一批像素**是 `first_screen`，那时透明度可能还在升。 |
| 没有 Perfetto / Instruments | Android 打点前那 460 ms 里，分不清 bindApplication / RenderThread / 解码各占多少。 |
| iPhone 真机 | 机上有 `3.0.0`，但是**旧二进制**。本轮没启动。 |
| 缺少 `ThisTime` | 这台 API 36 的 `am start -W` 只给了 TotalTime / WaitTime。 |
| n=7 | 够做基线，不够当 SLO。Desktop 的 p90 不稳（留下的次数里有一次 7.3 s）。 |

---

## 8. 下一步（只测，不改产品）

1. 给 **Android `benchmark`/`release`** 打上 `StartupTrace`，在这台模拟器上重跑 `am start -W` + 打点，再到**真机**。  
2. 配对的 iPhone 16 Pro 上做一次 **Instruments Time Profiler + Swift t0**。  
3. Desktop：跑 **已安装** 的 `createDistributable` 二进制（不经 Gradle），得到从 Dock 点到像素的数。  
4. 可选：仓库里已有 `:macrobenchmark` `SampleStartupBenchmark`（10 次，release 包）。本轮没跑。

---

## 9. 探针（代码）

`StartupTrace` **默认关**，只有平台开关打开才记。标记走 `markOnce`。iOS actual 用 `NSLog`，方便 `devicectl` / `simctl log stream` 看见（与 `IosDevicePerfBench` 同一条规则）。
