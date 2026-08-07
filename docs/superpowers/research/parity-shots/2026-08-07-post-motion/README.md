# Post-motion / compress-drop / Slice D′ witness set (2026-08-07 → 2026-08-08)

## Code decisions under test
- **ADR-0022:** drop pre-compress recovery UI + Compressor dependency (intentional product shrink).
- **ADR-0023:** Launch↔Editor is shell route H-slide+fade under `MotionPolicy` (not LaunchView spring morph).
- Motion tokens wired into `AnimatedTransitionHost`, `EditorBottomControlsShell` option `AnimatedContent`, `GallerySelectedCountFab`.
- **iOS pick/HEIC:** host CEH + `IosImageDecoder` UIImage fallback for HEIC/HEIF (user-verified: multi-pick no longer blanks focused HEIC).

## Capture matrix (Slice D′)

| File | Platform | Route / state | Notes |
|---|---|---|---|
| `android-debug-launch-dprime.png` | emulator-5554 debug | Launch | cold start after reinstall |
| `android-debug-editor-fixture-dprime.png` | emulator-5554 debug | Editor | share-in fixture PNG; watermark tiles visible; filmstrip single |
| `android-debug-export-fixture-dprime.png` | emulator-5554 debug | Export sheet | JPEG/PNG + quality 80 + list 0/1 + thumb |
| `android-debug-export-dprime.png` | emulator-5554 debug | Export sheet multi | list **0/5** after multi pick (structural multi-export) |
| `android-debug-picker-open-dprime.png` | emulator-5554 debug | PhotoPicker | system picker open |
| `android-debug-about-dprime.png` | emulator-5554 debug | About | post-editor About |
| `android-debug-launch.png` | earlier same day | Launch | pre-D′ witness |
| `android-debug-about.png` | earlier | About | pre-D′ |
| `ios-sim-launch-dprime.png` | iPhone 17 Pro sim | Launch | after HEIC fix install |
| `ios-sim-editor-fixture-dprime.png` | iOS sim | Editor | `-uiTestFixtureImage` watermarked preview |
| `ios-sim-fixture-editor.png` | earlier | Editor | fixture witness |
| `ios-6to5-repro-000544.png` | iOS sim | Editor (pre-HEIC fix) | blank focused HEIC — historical bug shot |

## Residual (honest)

| Item | Status |
|---|---|
| Production APK side-by-side on same emulator | **Not this pass** — only `me.rosuh.easywatermark.debug` installed; no signed prod v2.10.0 APK in workspace |
| Android multi Editor with filled thumbs | Partial: multi export list 0/5 captured; system PhotoPicker multi re-entry hit Google login sheet (cloud) — use share-in / local fixtures for automation |
| iOS multi Editor + Export sheet | **Not automated** this pass — HEIC multi-pick verified live by owner; fixture covers single-image editor chrome |
| `animator_scale=0` / Reduce Motion matrix | **Open** |
| Desktop UI journey shots | **Open** (platform exception track) |

## How to extend
1. Install prod from GitHub Releases (`me.rosuh.easywatermark`) alongside debug.
2. Same multi local images via share-in / MediaStore (avoid Photopicker cloud path).
3. Capture reduced-motion: `adb shell settings put global animator_duration_scale 0` (+ transition/window scales).
