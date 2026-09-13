# ADR-0032: Android splash then Launch fade are serial; iOS launch matches first-screen fill

**Status:** Accepted (owner 2026-08-26; iPhone 16 Pro Launch fill witness). **Amended 2026-09-13:** Android handshake reverted.  
**Context slice:** Cold start / system launch chrome vs process-first Launch reveal  
**Related:** ADR-0023, I3 MotionPolicy  
**Hub:** [cold-launch knowledge](../superpowers/research/2026-08-26-cold-launch-knowledge.md) (historical; Android hold is no longer product code)

## Context

Android 12+ shows a system `SplashScreen` (launcher icon) until first draw. Shared
`ColdLaunchReveal` fade+scale (`shellShortMs`, 240ms Full) used to start at first
`ProductShellHost` composition — parallel with splash dismiss. Debug builds often
finished the fade under the icon; release builds could show icon then fade, or a
late pop. There was no handshake.

iOS `UILaunchScreen` was an invalid nested empty dict, so the system used
`systemBackground` (not product olive). Compose then faded from `#262611`.

Desktop has no system splash; process-first fade stays as-is.

Lab numbers (emulator, 2026-08-25, drop first of 8): Android debug TTID p50 760 ms;
Android release TTID p50 168 ms. The user-facing Android release path was not a
1–2 s Koin/DataStore wait. The product bug was two clocks, plus iOS launch fill
mismatch. See the hub.

## Decision

1. **Android (2026-09-13):** first Compose frame is opaque Launch. No
   `installSplashScreen`, keep condition, exit listener, or host hold. The OS may
   still show a system splash until first draw. Starting-window /
   `windowSplashScreenBackground` stay olive `#262611`.
2. **iOS:** launch screen fill is `#262611` (`LaunchBackground` / `DesignEditorBg`).
   No icon. Fade stays process-first (no splash handshake).
3. **Desktop:** process-first fade. No Android splash APIs.
4. Do not retarget `md_theme_dark_background` (`#1D1B16`). No LaunchView springs.
   No branded iOS icon launch screen. Do not fade Android Launch from alpha 0.

The 2026-08-26 handshake (hold splash until `first_screen`, `remove()`, then 240 ms
fade) is withdrawn. It could leave Launch at alpha 0 when the exit listener never
ran (#431).

## Consequences

- **Positive:** iOS first paint matches the product fill. Owner confirmed the iOS path
  feels much faster on device (olive from the first pixel). Android launch matches 2.x
  (opaque first frame; no hold that can stick on black).
- **Trade-off:** Android 12+ still shows the OS splash (icon) until first draw — that
  is system chrome, not an app handshake. Android has no process-first Launch fade.
- **Amendment (2026-09-11, #424):** API 23–30 needed a `Theme.SplashScreen` starting
  theme while `installSplashScreen` + `OnExitAnimationListener` inflated
  `splash_screen_view` (`?attr/splashScreenIconSize`). `Theme.MyApp` alone crashed that
  inflation on Android 11. That theme existed only for the compat splash path.
- **Amendment (2026-09-13, #431):** The Android handshake is removed. Do not call
  `installSplashScreen`, do not keep the splash, do not set `OnExitAnimationListener`,
  do not host-hold Launch at alpha 0. First Android Compose frame is **opaque Launch**
  (2.x). The OS may still show a system splash until first draw; that is not app code.
  Olive `#262611` stays the starting-window / `windowSplashScreenBackground` fill.
  `Theme.MyApp.Splash` and `androidx.core:core-splashscreen` are gone. iOS / Desktop
  keep the process-first 240 ms fade. Do not recreate fade-from-alpha-0 on Android
  (splash would dismiss on the first transparent frame). `Theme.SplashScreen` is no
  longer required after dropping compat splash (#424 does not apply).
- **Revert path (historical):** the 2026-08-26 handshake (hold + keep condition + exit
  listener). Do not restore it. iOS plist can revert to an empty `UILaunchScreen` dict
  (that regresses to `systemBackground` — do not do this casually).
