# ADR-0032: Android splash then Launch fade are serial; iOS launch matches first-screen fill

**Status:** Accepted (owner 2026-08-26; iPhone 16 Pro Launch fill witness)  
**Context slice:** Cold start / system launch chrome vs process-first Launch reveal  
**Related:** ADR-0023, I3 MotionPolicy  
**Hub:** [cold-launch knowledge](../superpowers/research/2026-08-26-cold-launch-knowledge.md)

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

1. **Android:** hold system splash until Launch `first_screen` (or a skip case). Then
   `remove()` the splash, **then** start the 240ms fade. Handshake is a commonMain
   `ColdLaunchReveal` host hold (no `expect`/`actual`). Skip hold for recovery,
   share-in / Editor-first, `MotionPolicy.Off`, and a 1500ms timeout.
2. **iOS:** launch screen fill is `#262611` (`LaunchBackground` / `DesignEditorBg`).
   No icon. Fade stays process-first (no splash handshake).
3. **Desktop:** unchanged. Never calls `requestHostHold()`.
4. Do not retarget `md_theme_dark_background` (`#1D1B16`). Splash uses a new
   `#262611` color. No LaunchView springs. No branded iOS icon launch screen.

## Consequences

- **Positive:** serial Android splash → Launch; iOS first paint matches the product fill.
  Owner confirmed the iOS path feels much faster on device (olive from the first pixel).
- **Trade-off:** Android TTFD (`reportFullyDrawn`) stays at fade end; splash no longer
  dismisses on first Compose frame. API 23–30 still uses the default splash theme
  parent (not `Theme.SplashScreen`).
- **Revert path:** drop hold + splash keep condition; iOS plist can revert to an empty
  `UILaunchScreen` dict (that regresses to `systemBackground` — do not do this casually).
