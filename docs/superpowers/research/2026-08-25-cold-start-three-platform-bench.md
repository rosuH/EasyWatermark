# Cold start, first frame, first screen — three-platform bench

**Date:** 2026-08-25 (Asia/Shanghai evening; UTC timestamps in JSON)  
**Branch:** `feat/migrate_to_compose` + gated `StartupTrace`  
**Raw data:** `build/startup_bench/startup_bench.json` (`android.json`, `desktop.json`, `ios.json`, per-iter logs)  
**Harness:** `scripts/startup_bench.py`  
**中文报告：** [2026-08-25-cold-start-three-platform-bench.zh.md](./2026-08-25-cold-start-three-platform-bench.zh.md)  
**What shipped after this bench:** [2026-08-26-cold-launch-knowledge.md](./2026-08-26-cold-launch-knowledge.md) · [ADR-0032](../../adr/0032-android-splash-then-launch-fade-serial.md)

This is a **measured baseline**, not a guess. Clocks and build types are not the same across OS — do not rank “iOS is faster than Android” from the headline numbers alone. Read §Validity.

---

## 1. Lab

| | |
|---|---|
| Host | Apple M5 Pro, 48 GiB, macOS 27.0 (25A5416b), Darwin 27.0.0 arm64 |
| JDK | Homebrew OpenJDK 17.0.20 (Desktop `gradlew :desktopApp:run`) |
| Android device | **Emulator** `sdk_gphone64_arm64` (ranchu, arm64-v8a), **API 36 / Android 16**, qemu=1. Already running; not stopped. |
| iOS device | **Simulator** iPhone 17 Pro (`257EE76A-…`), iOS 27.0. Physical iPhone 16 Pro is paired and has store/dev `3.0.0` **without this probe** — not re-measured. |
| Desktop | Same host, Compose Desktop window, `EWM_STARTUP_TRACE=1`, auto-exit 5 s |
| Iterations | 8 cold starts / platform. **Drop first** for summaries (JIT / classload / sim log attach). |
| Inter-run | Android `am force-stop` + 2 s; Desktop new JVM via Gradle; iOS `simctl terminate` + 1.5 s |

### Build under test

| Platform | Artifact | Notes |
|---|---|---|
| Android debug | `me.rosuh.easywatermark.debug` just installed from `:app:assembleDebug` | In-app marks + `am start -W` |
| Android release | already-installed `me.rosuh.easywatermark` | **`am start -W` only** (no `StartupTrace` in that APK) |
| Desktop | `:desktopApp:run` Debug | In-app marks from `main()` |
| iOS | `iosApp` Debug, `CODE_SIGNING_ALLOWED=NO`, DerivedData `build/ios_startup_dd` | Swift clock + Kotlin clock |

---

## 2. Metric dictionary

Same mark names on all Kotlin hosts. `t_ms` is `TimeSource.Monotonic` from the **first in-process `StartupTrace` read**.

| Name | Definition |
|---|---|
| **OS TTID** | Android `am start -W` **TotalTime** (ms). Industry “time to initial display”: startActivity → first displayed Activity frame. API 36 on this emulator did **not** print `ThisTime`. |
| **app_create_*** | Application / DataStore / Session graph. |
| **host_set_content** | `setContent` / `ComposeUIViewController` / Desktop `SwingWindow`. |
| **first_compose_frame** | First `withFrameNanos` after `ProductShellHost`. |
| **first_screen** | `LaunchScreen` laid out (`onGloballyPositioned`): logo + Choose Images + About in the tree. |
| **cold_reveal_done** | Process-first fade+scale finished **or skipped**. |
| **fully_drawn** | `first_screen` ∧ `cold_reveal_done`. Android also calls `Activity.reportFullyDrawn()`. |
| **iOS wall** | Unified-log timestamps from `swift_app_init` (process-visible Swift `t0`) to the same mark. Use this for iOS “tap → screen”, not Kotlin `t_ms` alone. |

**Not measured here:** Time to Interactive after the pick button is hittable (we only prove it is composed). No Macrobenchmark `StartupTimingMetric` on a `benchmark` APK. No Instruments / Perfetto traces. No physical iPhone / physical Android.

---

## 3. Procedure

1. Gated probe: Android `debug.ewm.startup_trace=1` + `log.tag.EwmStartup`; Desktop `EWM_STARTUP_TRACE=1`; iOS argv `-ewmStartupTrace`. Off in normal production.  
2. Cold process each iter.  
3. Parse `EWM_STARTUP mark=<name> t_ms=<int>`.  
4. Summaries: n, min, **p50**, mean, p90, max, population stdev on the **7 kept** runs.

Reproduce:

```bash
# Android (emulator already up)
./gradlew :app:assembleDebug --max-workers=8
adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/startup_bench.py --platform android --iters 8 --out build/startup_bench

# Desktop
python3 scripts/startup_bench.py --platform desktop --iters 8 --out build/startup_bench

# iOS Simulator (booted iPhone 17 Pro)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
  -destination "platform=iOS Simulator,id=<UDID>" \
  -derivedDataPath build/ios_startup_dd CODE_SIGNING_ALLOWED=NO build
xcrun simctl install <UDID> build/ios_startup_dd/Build/Products/Debug-iphonesimulator/iosApp.app
python3 scripts/startup_bench.py --platform ios --iters 8 --ios-udid <UDID> --out build/startup_bench
```

---

## 4. Results (p50 unless noted)

### 4.1 Headline (kept n=7)

| Clock | Android debug | Android release | iOS sim (wall) | Desktop in-process |
|---|---:|---:|---:|---:|
| OS TTID (`TotalTime`) | **760 ms** | **168 ms** | — | — |
| First screen | **298 ms** (after `MyApp.onCreate`) | not instrumented | **153 ms** (after Swift `t0`) | **2652 ms** (after `main()`) |
| First Compose frame | 333 ms | — | 97 ms Kotlin / ~170 ms wall | 2679 ms |
| Fully drawn (screen + reveal) | **667 ms** | — | **427 ms** wall | **2765 ms** |

Android debug `TotalTime` p50 **760** vs first_screen **298**: ~**460 ms** of bindApplication / splash / ART sits **before or around** the in-app clock. The 240 ms cold reveal (`fully_drawn − first_screen` ≈ 369 ms p50, includes frame scheduling) **finishes before** debug `TotalTime`. On this debug emulator the fade runs **under the system splash**.

Android **release** `TotalTime` p50 **168 ms** (n=4 after drop-first of 5). That APK has no probe, so we cannot place the reveal on the same axis. 168 ms ≪ 240 ms: if first draw dismisses splash while Launch is still fading, **release is the build where a 240 ms fade can actually be seen** — or seen as a late pop — unlike debug.

### 4.2 Android debug in-app (ms, drop-first)

| Mark | min | p50 | mean | p90 | max | stdev |
|---|---:|---:|---:|---:|---:|---:|
| app_create_end | 26 | 28 | 28 | 30 | 30 | ~2 |
| shell_composed | 208 | 219 | 220 | 232 | 239 | — |
| first_screen | 291 | **298** | 301 | 312 | 314 | 8.7 |
| first_compose_frame | 320 | **333** | 332 | 342 | 346 | 8.8 |
| mesh_ready | 332 | 342 | 343 | 353 | 355 | — |
| cold_reveal_done | 616 | **667** | 669 | 709 | 717 | 31 |
| fully_drawn | 616 | **667** | 670 | 714 | 718 | 33 |
| OS TotalTime | 739 | **760** | 761 | 778 | 785 | 15 |

`LaunchState: COLD` on every `am start -W`. `ThisTime` absent in this emulator’s `am` output.

### 4.3 Android release OS TTID only (ms)

| | min | p50 | mean | max |
|---|---:|---:|---:|---:|
| TotalTime (n=4 kept) | 162 | **168** | 175 | 201 |

First of 5 was 223 ms (dropped). **Debug TotalTime is ~4.5× release** on the same emulator.

### 4.4 iOS Simulator

**Do not compare Kotlin `t_ms` to Swift `t_ms`.** Kotlin epoch starts at first `StartupTrace` in `IosAppServices` lazy init (~70–80 ms after Swift `t0` on the unified log).

Wall time from `swift_app_init` (unified log, drop-first n=7):

| | min | p50 | mean | max |
|---|---:|---:|---:|---:|
| `swift_compose_vc_ready` | 70 | **82** | 81 | 87 |
| `first_screen` | 146 | **153** | 154 | 163 |
| `fully_drawn` / reveal done | 410 | **427** | 431 | 461 |

Kotlin-only (after services mark): `first_screen` p50 **78 ms**, `fully_drawn` p50 **353 ms**. Reveal duration ≈ 240 ms + a few frames (`fully_drawn − first_compose_frame` ≈ 256 ms).

`app_create_end` Kotlin often **0 ms** (same millisecond as start): DataStore+Session after K/N is up is cheap. The expensive pre-Kotlin slice is **Swift t0 → first Kotlin line** (~76 ms on a typical kept run).

### 4.5 Desktop (in-process from `main()`, drop-first)

| Mark | min | p50 | mean | p90 | max |
|---|---:|---:|---:|---:|---:|
| app_create_end (DataStore+Session+Room seed) | 630 | **677** | 756 | 940 | 1262 |
| first_screen | 2527 | **2652** | 3370 | 4720 | 7341 |
| first_compose_frame | 2554 | **2679** | 3401 | 4761 | 7390 |
| fully_drawn | 2690 | **2765** | 3504 | 4840 | 7458 |

Dropped first run: `first_screen` **10686 ms** (Gradle + first JIT). Kept run #2 still **7341 ms**. Runs 3–8 sit at **2.5–3.0 s**. `runner_wall_ms` (~9–12 s) includes Gradle; **do not use it as TTID**.

Gap `first_screen − app_create_end` ≈ **2.0 s** p50: first `application {}` composition (Room `getAllTemplate`, `runBlocking` strings, **12 `painterResource`**, window realize) **before** Launch layout.

---

## 5. Waterfall (typical kept run)

### Android debug (in-app 0 = `MyApp.onCreate`)

```
0        app_create_start
28       app_create_end          Koin + CMonet + Coil
50       host_set_content
219      shell_composed
298      first_screen            Launch laid out (may still be alpha 0)
333      first_compose_frame
342      mesh_ready
667      fully_drawn             240 ms reveal + schedule
--- OS ---
760      TotalTime               first Activity frame / splash handoff
```

### iOS sim (wall 0 = Swift `EasyWatermarkApp.init`)

```
0        swift_app_init
82       swift_compose_vc_ready  + Kotlin services in that window
153      first_screen
~170     first_compose_frame
427      fully_drawn             reveal
```

System `UILaunchScreen` is an empty dict; olive `#262611` is SwiftUI, then Compose. No Android-style icon splash.

### Desktop (0 = Kotlin `main`)

```
0        app_create_start
677      app_create_end          stores + Session + Room builder
~2.6 s   first_screen            painters + first window
2.77 s   fully_drawn
```

---

## 6. What this does to the “no animation + long wait” report

1. **Debug Android emulator:** wait is real (**760 ms** OS TTID, tight ±15 ms). The 240 ms fade **completes ~100 ms before** `TotalTime`. User can see a long splash and then a **static** Launch. That matches the earlier race hypothesis **on this lab debug build**.  
2. **Release Android emulator:** OS TTID **168 ms**. We did **not** instrument that APK. The race likely **reverses**: splash is gone before a 240 ms fade ends. “No animation” on a Play/release build is a **different** claim and needs a `StartupTrace` release/benchmark rebuild.  
3. **iOS Simulator:** first screen **153 ms** wall. Reveal ends **427 ms**. Empty launch screen + olive, not an icon splash. If the owner’s “long wait” was iOS **device** Debug/K/N, this sim number is a **lower bound**, not a device number.  
4. **Desktop:** first screen **~2.6 s** after `main()` on a warm Gradle daemon. That **is** a long wait; the fade is a rounding error on that budget.

---

## 7. Validity / threats

| Threat | Impact |
|---|---|
| Debug vs release | **Dominant on Android.** Debug TotalTime 4.5× release. Do not quote 760 ms as user-facing Play time. |
| Emulator / Simulator | Not a Pixel / not an iPhone 16 Pro. CPU is host M5 Pro; GPU/display path is virtual. |
| Two iOS clocks | Kotlin `first_screen` 78 ms ≠ wall 153 ms. Report wall for “user tap”. |
| Desktop Gradle | In-app clock starts after JVM launch; first two runs are compile/JIT outliers. |
| Reveal in `fully_drawn` | `fully_drawn` includes 240 ms motion. First *pixels of Launch chrome* are `first_screen`, which can be while alpha is still rising. |
| No Perfetto / Instruments | Cannot name bindApplication vs RenderThread vs decode inside the 460 ms Android pre-mark gap. |
| Physical iPhone | App present (`3.0.0`) but **old binary**. Not launched in this bench. |
| `ThisTime` missing | API 36 `am start -W` here only gave TotalTime/WaitTime. |
| n=7 | Enough for a baseline, not an SLO. p90 on Desktop is unstable (one 7.3 s kept run). |

---

## 8. What to do next (measurement, not product)

1. Rebuild **Android `benchmark`/`release`** with `StartupTrace` and repeat `am start -W` + marks on this emulator, then a **physical** phone.  
2. One **Instruments Time Profiler + Swift t0** pass on the paired iPhone 16 Pro.  
3. Desktop: run the **installed** `createDistributable` binary (no Gradle) for a dock-to-pixel number.  
4. Optional: `:macrobenchmark` `SampleStartupBenchmark` (10 iters, `TARGET_PACKAGE` release) — already in-tree, not run this session.

---

## 9. Probe (code)

`StartupTrace` is **off** unless the platform flag is set. Marks are `markOnce`. iOS actual uses `NSLog` so `devicectl` / `simctl log stream` can see lines (same rule as `IosDevicePerfBench`).
