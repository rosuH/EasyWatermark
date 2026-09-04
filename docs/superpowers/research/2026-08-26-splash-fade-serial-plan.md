# Plan: 系统启动页与进程淡入串行 + iOS 启动页对齐第一屏

**Date:** 2026-08-26  
**Branch:** `feat/migrate_to_compose`  
**Status:** shipped 2026-08-26 (see [knowledge hub](./2026-08-26-cold-launch-knowledge.md) and [ADR-0032](../../adr/0032-android-splash-then-launch-fade-serial.md))  
**Evidence:** [bench](./2026-08-25-cold-start-three-platform-bench.zh.md), [timing](./2026-08-26-android-splash-vs-cold-reveal.md)

## Mission

让「系统挡布」和「首页 240 ms 淡入」变成**串行**（安卓），并让 iOS 启动页底色等于第一屏橄榄，不放图标。

## Decision (locked)

Owner expected serial. Current code is parallel: fade starts at first `ProductShellHost` composition; Android 12+ splash dismisses on first frame.

1. **Android:** hold system splash until Launch is laid out (`first_screen`) **or** we will not play the fade. Then dismiss splash. **Then** start the 240 ms fade.  
2. **iOS:** do **not** copy the Android icon splash. Set launch background to `#262611` (`DesignEditorBg`). Fade stays process-first (no splash handshake).  
3. **Desktop:** unchanged process-first fade (no system splash).  
4. No LaunchView springs (ADR-0023). No `expect`/`actual` for this. No branded iOS icon launch screen (Apple HIG).

## Android sequence (target)

```
[icon splash] ──first_screen or skip──► splash remove ──► fade 0→1 @ 240ms ──► Launch solid
```

Skip hold (dismiss splash on first frame, no fade wait) when:

- Recovery screen  
- Share-in / first base route is Editor  
- `MotionPolicy.Off` (no fade)  
- 1500 ms safety timeout  

## Shared API (no expect)

`ColdLaunchReveal`:

- Keep `shouldPlay` / `observeFirstBase` / `resetForTests`.  
- Add host hold:
  - `fun requestHostHold()` — Android calls once before setContent when it will install splash and may play fade.  
  - `fun releaseHostHold()` — Android splash `OnExitAnimationListener` after `remove()`.  
  - `fun isHostHoldActive(): Boolean` — `ProductShellHost` waits if `observeFirstBase` said play **and** hold is active.  
- `resetForTests()` clears hold.

`ProductShellHost`:

- If `animateCold && ColdLaunchReveal.isHostHoldActive()`: keep alpha 0 / scale 0.97, **do not** start the tween until hold releases. Use `snapshotFlow` / `LaunchedEffect` that observes hold, or a `mutableStateOf` the Android host flips via `releaseHostHold()` that also ticks a `mutableState` the shell reads.  
- Simplest reliable seam: `ColdLaunchReveal.hostHoldState: MutableState<Boolean>` is awkward in non-Compose. Prefer:

```kotlin
// ColdLaunchReveal
private var hold: Boolean = false
private var holdListener: (() -> Unit)? = null
fun requestHostHold() { hold = true }
fun releaseHostHold() { hold = false; holdListener?.invoke() }
fun isHostHoldActive() = hold
internal fun setHoldListener(l: (() -> Unit)?) { holdListener = l }
```

`ProductShellHost` registers listener in `DisposableEffect` and `var released by remember { mutableStateOf(!isHostHoldActive()) }`. When listener fires, `released = true`, then existing `LaunchedEffect(released)` runs the tween.

Desktop/iOS never call `requestHostHold()` → fade starts immediately (today).

Do **not** one-shot the fade on `LaunchScreen` first composition (`RestrainedMotionSeamsTest`).

## Android host

1. Catalog: `androidx.core:core-splashscreen` **latest stable** (not alpha). Own version key, J4 one slice. `implementation(libs.androidx.core.splashscreen)` next to `core-ktx`.  
2. `MainActivity.onCreate`: `installSplashScreen()` **before** `super.onCreate()`.  
3. `setKeepOnScreenCondition { keepSplash }`.  
4. `setOnExitAnimationListener { view -> view.remove(); ColdLaunchReveal.releaseHostHold() }`.  
5. `requestHostHold()` only when we expect a Launch fade (not recovery). Share-in Editor: do not request hold; `keepSplash = false` immediately.  
6. `StartupTrace.onFirstScreen` already exists — also flip `keepSplash = false` so splash can exit, then the exit listener releases hold (fade starts).  
7. Timeout 1500 ms → `keepSplash = false`.  
8. Recovery: never hold.  
9. Theme API 31+: `android:windowSplashScreenBackground` = `#262611` (new color resource, do **not** retarget all `md_theme_dark_background` `#1D1B16`). Icon stays default launcher icon.

`reportFullyDrawn` stays at fade end (TTFD). It must not keep the splash.

## iOS

`iosApp/iosApp/Info.plist` (`GENERATE_INFOPLIST_FILE = NO` for the app target):

```xml
<key>UILaunchScreen</key>
<dict>
    <key>UIColorName</key>
    <string>LaunchBackground</string>
</dict>
```

Remove the invalid nested `UILaunchScreen` empty dict.

Add `iosApp/iosApp/Assets.xcassets/LaunchBackground.colorset`:

- universal sRGB `#262611` (same as `DesignEditorBg` / `ContentView.productBackground`)  
- Appearances: any (forced-dark app; one color is enough)

No `UIImageName`. No icon.

## Docs

- ADR **0032** Proposed: Android splash and cold reveal are serial; iOS launch screen matches first-screen fill, not a brand splash.  
- One line in `AGENTS.md` I3 motion: Android cold Launch fade starts after splash exit.  
- Do not rewrite the bench report.

## Tests

- `ProductShellNavTest`: hold active → `shouldPlay` still true; release clears hold.  
- `RestrainedMotionSeamsTest`: still forbids fade on `LaunchScreen`; host still uses `observeFirstBase`; allow new hold APIs.  
- `MotionPolicyTest` unchanged.  
- Run: `./gradlew :shared:desktopTest --max-workers=8` (or the named tests if faster and green).  
- `./gradlew :app:assembleDebug --max-workers=8` after splashscreen + theme.  
- iOS: no required `xcodebuild` this slice (plist + colorset). Do not boot simulators.

## Boundaries

**In:** shared hold seam, Android splash install/keep/exit, iOS launch color, ADR-0032, AGENTS I3 one line, tests above.

**Out:** Desktop dock-to-pixel, release StartupTrace bench, Perfetto, filmstrip, BrandLogo About freeze, Style/Layout warmup, store screenshots, `git add -A`, commit, LaunchView springs, iOS icon splash, changing `md_theme_dark_background` globally, shutting down any live emulator/simulator.

**Dirty tree:** only touch files for this slice. Unrelated fastlane/store edits stay untouched.

## DoD

- Android Launch cold start: splash still up at `first_screen`; fade `LaunchedEffect` starts only after `releaseHostHold()`.  
- Android Editor-first / recovery: splash not held.  
- iOS launch screen color is `#262611`, no launch image.  
- Desktop fade still process-first.  
- Tests listed green.  
- ADR-0032 + AGENTS I3 line present.
