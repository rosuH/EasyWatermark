# Post-motion industrial parity witness set (2026-08-07 → 2026-08-08)

**Branch:** `feat/migrate_to_compose`  
**Baseline prod:** EasyWatermark **v2.10.0** signed APK (`me.rosuh.easywatermark`) from GitHub Releases / local  
  `~/Downloads/EasyWatermark-2.10.0-21000-signed.apk`  
**Debug under test:** `me.rosuh.easywatermark.debug` (Compose product shell)  
**Devices:** `emulator-5554` (API arm64), iPhone 17 Pro sim `257EE76A-…`

## Code decisions already landed
| ADR / fix | Outcome |
|---|---|
| ADR-0022 | Drop pre-compress OOM recovery UI + Compressor dep |
| ADR-0023 | Launch↔Editor = shell H-slide+fade under MotionPolicy (not spring morph) |
| Motion tokens | AnimatedTransition / option panel / gallery FAB |
| iOS HEIC | `IosImageDecoder` UIImage fallback; multi-pick blank focus fixed (owner-verified) |

---

## Capture index

### A. Android prod ↔ debug (side-by-side, same emulator)

| Scene | Prod (v2.10.0) | Debug (compose) |
|---|---|---|
| Launch | `android-prod-launch.png` | `android-debug-launch-dprime.png` |
| Editor multi (3 fixtures) | `android-prod-editor-multi.png` | `android-debug-editor-multi.png` |
| Export sheet | `android-prod-export.png` | `android-debug-export-multi.png` / `android-debug-export-fixture-dprime.png` |
| Reduced-motion launch | `android-prod-rm-launch.png` | `android-debug-rm-launch.png` |
| Reduced-motion editor/export | — | `android-debug-rm-editor.png`, `android-debug-rm-export.png` |

Also: `android-debug-editor-fixture-dprime.png` (single share-in), picker open, about.

### B. iOS sim

| Scene | File |
|---|---|
| Launch | `ios-sim-launch-dprime.png` |
| Editor fixture (watermarked) | `ios-sim-editor-fixture-dprime.png` |
| Reduce Motion + fixture | `ios-sim-rm-editor-fixture.png` |
| Historical blank HEIC | `ios-6to5-repro-000544.png` (pre-fix) |

### C. Reduced motion method
```bash
adb shell settings put global animator_duration_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global window_animation_scale 0
# …captures…
# restore to 1 after
```
iOS: `defaults write com.apple.Accessibility ReduceMotionEnabled -bool true` in sim (restored false after).

---

## Side-by-side findings (visual, this pass)

These are **not** reopened P0 blockers — structure matches (Launch CTA, 3-tab editor, filmstrip multi, export format/quality). Residual **chrome / product copy** deltas vs prod:

| ID | Area | Prod | Debug (compose) | Severity |
|---|---|---|---|---|
| R1 | Launch logo tint | Material You / dynamic (pink-lilac on this AVD) | Static amber brand | P3 feel (dynamic on Android still via capability; may differ by wallpaper) |
| R2 | Editor top bar leading | **Brand logo** (no back) | **Back chevron** | **Owner 2026-08-08: keep back** (Compose route semantics) |
| R3 | Default watermark copy | zh: `👋图片仅供测试…` | Was stale DataStore `请勿转载…`; EN/zh resources already master; iOS/Desktop hardcodes → `config_default_water_mark_text` | **Fixed** (platform defaultTextProvider) |
| R4 | Content toggle icons | Classic T / sticker glyphs | Phosphor Regular set | Intentional (icon refresh commit) |
| R5 | Export sheet chrome | Partial peek, JPEG default on this run | Full sheet, PNG selected in fixture run | P3 sheet expansion / prefs |
| R6 | Reduced-motion | Launch still usable at scale 0 | Launch/editor/export chrome intact at scale 0 | **Pass** (no broken layout) |

**Aligned well:** multi filmstrip (3 thumbs), watermark tiling over fixtures, tab row (内容/样式/布局), save affordance, dark forced surfaces, export list count matches selection.

---

## Residual after this pass

| Item | Status |
|---|---|
| Prod side-by-side Launch / Editor multi / Export | **Done** |
| Android reduced-motion Launch+Editor+Export | **Done** (scales restored to 1) |
| iOS Reduce Motion fixture editor | **Done** |
| iOS multi + Export automation | Still manual (HEIC multi owner-OK; no sim tap tool) |
| Desktop journey shots | Open (separate platform track) |
| R2 top-bar logo-vs-back | **Closed — keep back** |
| R3 default text string unification | **Closed** — shared `config_default_water_mark_text` on Android/iOS/Desktop |
| R5 export sheet expansion vs peek | Optional polish |
| **M1–M10 micro-motion (2026-08-08)** | See ACSP `20260808-024249--motion-m1-m10-parity` / session `result.md`. Implemented: M1 select spring, M2 crossfade policy + iOS/Desktop fade, M4 caret gate, M6 check appear, M7 first reveal, M9 Desktop reduce-motion best-effort, M10 springs on micro. Residual: M3 filmstrip drag-delete (API exists, strip redesign needs owner design), M5 gallery scrollbar 2× (no custom scrollbar in CMP grid), M8 M3 PrimaryTabRow owns indicator |

---

## How to re-run quickly
1. `adb install -r ~/Downloads/EasyWatermark-2.10.0-21000-signed.apk`  
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`  
3. Share-in fixture:  
   `content://media/external/images/media/<id>` of `ewm-dprime-a.png`  
4. Multi: open PhotoPicker, tap 3 local cells, 完成 — avoid cloud paths that open Google login.
