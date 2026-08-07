# Post-motion / compress-drop witness set (2026-08-07)

## Changes under test
- ADR-0022: drop pre-compress recovery UI + Compressor dependency
- ADR-0023: Launch↔Editor route transition intentional (shell H-slide+fade)
- MotionPolicy wired into AnimatedTransitionHost, EditorBottomControlsShell option AnimatedContent, GallerySelectedCountFab

## Captures
| File | Platform | Notes |
|---|---|---|
| android-debug-launch.png | emulator-5554 | debug launch idle |
| android-debug-gallery-or-picker.png | emulator-5554 | after 选择图片 |
| android-debug-about.png | emulator-5554 | About route |
| ios-sim-launch.png | iPhone 17 Pro sim | launch idle |

## Not captured this pass
- Editor with real media (needs photo selection)
- Export sheet
- animator_scale=0 reduced-motion matrix
- Production APK side-by-side (debug only)
