# sim-use residual verification — 2026-08-10

Closes gaps from ACSP `20260809-235500--adaptive-editor-layout-ia` review residuals:

1. **Fold multi-display screencap** (was: bare `adb exec-out screencap -p` wrote warning text)
2. **Desktop window screenshots** (was: macOS `screencapture` / AX denied)

Tool: **sim-use 0.13.0** (`/opt/homebrew/bin/sim-use`).

---

## 1. Fold multi-display — **FIXED**

**Device:** `emulator-5556` · AVD `Pixel_9_Pro_Fold`  
**Displays:**

| HWC display id | Role | Size | State |
|---|---|---|---|
| `4619827259835644672` | Inner (display 0) | 2076×2152 | ON |
| `4619827551948147201` | Outer/cover (display 3) | 1080×2424 | OFF (in this session) |

### Why bare adb failed

```text
[Warning] Multiple displays were found, but no display id was specified!
```

`screencap -d 0` / `-d 3` also fail (logical ids). Need **SurfaceFlinger HWC ids** from:

```bash
adb -s emulator-5556 shell dumpsys SurfaceFlinger --display-id
```

### Working recipes

```bash
# A) sim-use (after one-time bridge install) — captures active/default display
sim-use android init --device emulator-5556
sim-use android screenshot --device emulator-5556 --output fold.png --json

# B) adb with HWC display id (inner)
adb -s emulator-5556 exec-out screencap -p -d 4619827259835644672 > fold-inner.png

# C) all active displays
adb -s emulator-5556 shell screencap -pa /sdcard/fold-all.png
# → fold-all_0.png, fold-all_1.png
```

### Evidence (this run)

| File | Result |
|---|---|
| `fold/sim-use-fold.png` | **OK** 2076×2152 — EasyWatermark editor on inner (supporting-pane A) |
| `fold/ewm-fold-launch.png` | **OK** Launch “Choose Images” |
| `fold/ewm-fold-inner-hwc.png` | **OK** HWC id path |
| `fold/fold-all_0-inner.png` / `fold-all_1-outer.png` | **OK** dual files from `-a` |
| bare `screencap -p` | still text warning (expected) |

**Note:** `sim-use screenshot` (top-level, non-`android`) hit AccessibilityService rate limit when called immediately after; use `sim-use android screenshot` with ~0.5s spacing, or `record-video` for loops.

---

## 2. Desktop (Compose JVM) — **capture OK after Screen Recording grant**

**sim-use scope:** iOS Simulator + Android only — does **not** target Compose Desktop windows.  
Desktop evidence uses host `screencapture -l <CGWindowID>` (Swift CoreGraphics to resolve window id by title `EasyWatermark — Desktop`).

### Permission (2026-08-10 retest)

| Check | Result |
|---|---|
| Before grant | `could not create image from display` |
| After grant (Herdr/Grok host) | `screencapture -x` **OK** |
| Window capture | **OK** — `desktop-default-800x600.png`, `desktop-1512x877.png` / `desktop-window.png` |
| AX resize for band morph | **OK** after Accessibility grant — `System Events` process `MainKt` (must `set frontmost` first; 0 windows until focused) |

### Evidence files

| File | Notes |
|---|---|
| `desktop/perm-smoke.png` | Full-display smoke after grant |
| `desktop/desktop-default-800x600.png` | Launch UI @ ~800×600 points |
| `desktop/desktop-window.png` | Larger window (~1512×877 points); Launch still (no image loaded) |
| `desktop/desktop-band-compact-390x700.png` | AX resize → 390×700 Launch |
| `desktop/desktop-band-medium-720x800.png` | AX resize → 720×800 Launch |
| `desktop/desktop-band-expanded-960x720.png` | AX resize → 960×720 Launch |
| `desktop/desktop-band-wide-1600x900.png` | Requested 1600×900; **clamped ~1472×869** by display (Expanded band, not ≥1440 C) |

### Editor verification (2026-08-10, autoOpen + AX resize only)

Safe path: `-PewmAutoOpen=a.png,b.png` + `-PewmW`/`-PewmH` (no FileDialog / no global click).

| File | Window | Observed layout |
|---|---|---|
| `desktop/editor-compact-390.png` | 390×700 | **Stack**: preview · filmstrip · bottom Content/Style/Layout |
| `desktop/editor-medium-720.png` | 720×800 | **Stack** (same skeleton, M1) |
| `desktop/editor-expanded-960.png` | 960×720 | **Supporting-pane A**: canvas+filmstrip \| inspector right (no left lib) |
| `desktop/editor-wide-max.png` | ~1470×857 (≥1440) | **Three-zone C**: session lib (2 images) \| canvas+filmstrip \| inspector |

autoOpen imported `verify-sample.png` + `verify-sample-b.png`. Screen width clamps ~1470 (C still active ≥1440).

### Recipe

```bash
# resolve CGWindowID
swift -e 'import CoreGraphics
let opts: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
if let info = CGWindowListCopyWindowInfo(opts, kCGNullWindowID) as? [[String: Any]] {
  for w in info {
    let name = w[kCGWindowName as String] as? String ?? ""
    if name.contains("EasyWatermark") {
      print(w[kCGWindowNumber as String] as? Int ?? -1)
    }
  }
}'
screencapture -x -l<ID> desktop.png
```

Optional size props (must reach **app** JVM, not only Gradle): `-Dewm.desktop.widthDp` / `heightDp` in `DesktopWindow.kt`.

---

## Summary

| Residual | Tool | Status |
|---|---|---|
| Fold multi-display screencap | **sim-use** (`android init` + `android screenshot`) or HWC `screencap -d` | **Verified OK** |
| Desktop Compose screenshots | **screencapture -l** (not sim-use) | **Verified OK** after Screen Recording grant |
