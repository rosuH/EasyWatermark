# Research: Cross-platform dynamic color (wallpaper vs image palette)

**Date:** 2026-08-10  
**Branch context:** `feat/migrate_to_compose` vs production baseline `master` (v2.10.0)  
**Status:** Research complete — product decision locked in **ADR-0027** (Accepted 2026-08-11)  
**Related ADRs:** ADR-0007 (`DynamicColorCapability`), ADR-0014 (palette drop S4d-41; reopened via 0027), **ADR-0027** (wallpaper + content editor theme)

---

## TL;DR

EasyWatermark historically ran **two independent color systems** on Android. They are often conflated as “动态颜色” but use different APIs and product scopes:

| System | Source | What it recolors | Production status (`master`) | CMP branch status |
|---|---|---|---|---|
| **A. Material You / wallpaper dynamic color** | System wallpaper → OS seed → M3 tonal roles | **Whole app** theme (Material tokens) | Live via `:cmonet` + OEM allowlist + About force toggle | Live on Android via `DynamicColorCapability` → `:cmonet`; Desktop/iOS About toggle is sticky no-op |
| **B. Image palette editor chrome** | Open preview photo → `androidx.palette` swatches | **Editor chrome only** (bg animate, some text/panels) | Live on View stack | **Dropped** (dormant in Compose, then S4d-41 removed; ADR-0014 addendum) |

**Cross-platform truth:**

- **Wallpaper Material You is Android-only.** iOS has no public wallpaper-color API. Desktop JVM has no Compose Material You wallpaper path; OS accent is possible but not equivalent.
- **Image-derived tint is portable** on all three platforms from bytes the app already holds (privacy-aligned). Google’s own Material 3 names this *content-based color*, distinct from *user-generated (wallpaper) color*.
- If product wants “dynamic” delight on iOS/Desktop, implement **content-based chrome** (revive B deliberately), not fake wallpaper MY.

---

## 1. What production Android actually did

### 1.1 System A — Material You wallpaper (`:cmonet`)

**Flow (master + current Android):**

1. `MyApp` → `CMonet.init(application, apply = true)`.
2. If available: `DynamicColors.applyToActivitiesIfAvailable(application)` (Material Components).
3. Gate: `DynamicColors.isDynamicColorAvailable()` **AND** OEM/brand allowlist  
   (`samsung`, `google`, `sony`, `motorola`, …) **OR** user force flag  
   (`SharedPreferences` store `sp_water_mark_c_monet`, key `dynamic_color_force`).
4. Compose theme: `AppTheme(dynamicColor = capability.isAvailable())` →  
   `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` on API 31+.
5. About switch forces OEM bypass for non-allowlisted devices.

**Files:**

- `cmonet/.../CMonet.kt`, `MonetManufacturer.kt`
- `app/.../platform/AndroidDynamicColorCapability.kt`
- `app/.../ui/Theme.kt`
- `shared/.../platform/DynamicColorCapability.kt` (interface; Android only wired productively)

**Official pipeline (Android 12+):** OS samples wallpaper → extracts source color → five key colors (Primary/Secondary/Tertiary/Neutral/Neutral-variant) → 13-tone palettes → light/dark schemes. Apps consume tokens; they do **not** read wallpaper pixels.

Sources:

- [Enable dynamic colors (Views)](https://developer.android.com/develop/ui/views/theming/dynamic-colors)
- [Material 3 in Compose — dynamic color schemes](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic_color_schemes)
- [M3 dynamic color overview](https://m3.material.io/styles/color/dynamic) (wallpaper *and* content-based sources)

### 1.2 System B — Preview-image Palette (editor chrome)

**Flow (master View stack only):**

1. `WaterMarkImageView` decodes preview bitmap.
2. `Palette.Builder(bitmap).generate()` on `Dispatchers.Default`.
3. Callback `onBgReady(palette)` → `MainViewModel.updateColorPalette` → `MemorySettingRepo`.
4. `MainActivity` observes `colorPalette` →  
   `palette.bgColor(context)` / `titleTextColor(context)`.
5. `PaletteKtx.bgColor`: prefer `darkMuted` else `muted`, then  
   `MaterialColors.harmonize(swatch, md_theme_dark_background)` for brand-friendly tint.
6. Consumers: editor background animator, About activity accents, several panels (save sheet, tile mode, text display, BasePBFragment).

**Not** a full Material You scheme — cosmetic chrome only.

**CMP history:** Compose never re-wired generation/consumption. S4d-40 audit → S4d-41 owner-approved **drop** (parity-neutral because already inert). ADR-0014 addendum: reintroduction needs a **new feature ADR**, not silent restore. **kmpalette was never added.**

---

## 2. Android platform APIs (official)

### 2.1 Wallpaper / user-generated dynamic color

| API | Role |
|---|---|
| `com.google.android.material.color.DynamicColors` | Apply system dynamic theme to Activities |
| `DynamicColors.isDynamicColorAvailable()` | Device/OEM support probe |
| Compose `dynamicDarkColorScheme` / `dynamicLightColorScheme` | Read system-generated M3 `ColorScheme` |
| Framework `WallpaperColors` (API 27+) | Lower-level wallpaper color hints (widgets/system); product path prefers Material DynamicColors / Compose dynamic schemes |

### 2.2 Content / image palette

| API | Role |
|---|---|
| `androidx.palette:palette` / `palette-ktx` | Extract swatches (vibrant/muted/darkMuted/…) from `Bitmap` |
| Material Color Utilities (MCU) | Quantize + Score seed from image → `DynamicScheme` / tonal roles (content-based M3) |
| `MaterialColors.harmonize` | Pull extracted color toward brand seed |

Google’s design system explicitly separates:

- **User-generated color** — wallpaper / wallpaper-picker seed for system + apps  
- **Content-based color** — in-app media (album art, photo) for local UI  

EasyWatermark A = user-generated; B ≈ lightweight content-based (swatches, not full MCU scheme).

### 2.3 Content-based full M3 (modern alternative to Palette alone)

From [material-color-utilities](https://github.com/material-foundation/material-color-utilities):

1. Quantize image pixels  
2. `Score` ranks theme-suitable seeds  
3. `DynamicScheme` / `MaterialDynamicColors` produce accessible role colors  

This is what Music-style “theme from album” apps should use if they want **full** M3 roles from content, not just a muted bg.

---

## 3. iOS

### 3.1 Wallpaper Material You — **not available**

- No public API to read Home/Lock wallpaper or a wallpaper-derived system palette for third-party apps.
- Privacy: wallpaper often contains personal photos; Apple does not expose it.
- iOS 18 Home Screen icon tint is **system presentation only**, not an in-app theme seed API.
- Private APIs → App Store risk; do not use.

### 3.2 What iOS *does* provide

| Capability | API / pattern |
|---|---|
| Light/dark + semantic colors | `systemBackground`, `label`, Asset Catalog dynamic colors, `colorScheme` |
| App accent | Asset Catalog `AccentColor`, SwiftUI `.tint` |
| Frosted sampling of *on-screen* content | Materials (`.ultraThinMaterial` etc.) — samples drawn content behind chrome, not OS wallpaper |
| Average color of **owned** image | Core Image `CIAreaAverage` |
| Dominant palette of **owned** image | Manual downsample + k-means / quantization (no androidx.palette equivalent) |
| Music catalog art colors only | MusicKit `Artwork.backgroundColor` (not general Photos) |

### 3.3 Recommendation for iOS product

- Keep **system wallpaper dynamic color unavailable** (`DynamicColorCapability` false / static schemes). Correct platform behavior, not a bug.
- If delight is wanted: **content-based** tint from the open watermark photo only (local editor chrome), matching HIG music-style patterns and the app’s offline privacy model.
- Prefer shared seed math (MaterialKolor / MCU) over pure Swift-only average if Android/Desktop should match.

---

## 4. Desktop (Compose Multiplatform / JVM)

### 4.1 Material You wallpaper — **not provided by CMP**

- No `dynamicDarkColorScheme(LocalContext)` on Desktop.
- Official CMP Material3 samples: static `lightColorScheme` / `darkColorScheme` + `isSystemInDarkTheme()`.
- Community consensus: wallpaper Material You is Android-only.

### 4.2 OS accent / wallpaper (possible but not first-class)

| OS | Accent | Wallpaper path |
|---|---|---|
| Windows | Registry DWM `AccentColor` / `ColorizationColor` (ABGR) | Desktop wallpaper path / TranscodedWallpaper (fragile, multi-monitor) |
| macOS | AppKit `NSColor.controlAccentColor` | Desktop picture path (not theming model) |
| Linux | GNOME `accent-color` **named enum**; KDE different | Fragmented |

No JDK API. Needs JNA/native bridges. **Accent ≠ Android wallpaper tonal system.**

### 4.3 Image palette / seed schemes (portable)

| Library | Targets | Role |
|---|---|---|
| **[MaterialKolor](https://github.com/jordond/materialkolor)** `com.materialkolor:material-kolor` | Android, iOS, JVM, JS/Wasm | Seed color → full M3 `ColorScheme`; `ImageBitmap.themeColor(s)` for image seeds; KMP port of MCU |
| **kmpalette** | CMP | Direct androidx.palette port (swatches) — closer to historical System B |
| Raw Skia / `ImageBitmap` sampling | CMP | One average/dominant color without deps; reimplement contrast yourself |

### 4.4 Current EasyWatermark Desktop/iOS gap

Both hosts persist a **“Force Dynamic Color”** About preference (`DesktopDynamicColorPrefs` / `IosDynamicColorPrefs`) but **do not retheme** — sticky no-op relative to Android’s live MY path. Product honesty options: hide toggle, or wire it to content/accent seed schemes.

---

## 5. X / community signals (sampled)

- Material Design account historically: dynamic color from **wallpaper *or* content** — matches M3 dual-source model.  
- 9to5Google (2022): Material You dynamic color code open-sourced; **also coming to non-Android** via MCU (not via OS wallpaper).  
- App/dev posts: “Dynamic Themes” on Android almost always mean wallpaper MY (`dynamic*ColorScheme` / Material You), not image chrome.  
- Compose Multiplatform teaching content (e.g. Philipp Lackner-style guides): dynamic colors work on **Android**; **not** on iOS/Desktop out of the box.

Takeaway for product language: do not call Desktop/iOS static schemes “Material You.” Reserve that term for Android system wallpaper schemes; use “content tint” / “seed theme” elsewhere.

---

## 6. Capability model recommendation (architecture)

Do **not** overload `DynamicColorCapability` to mean both wallpaper and image tint.

```
DynamicColorCapability          // OS wallpaper / Material You (Android yes; iOS/Desktop false)
  isAvailable()
  isForcedSupport() / setForcedSupport()

ContentChromeTint (optional)    // NEW product feature if revived
  extractSeed(imageBytes | ImageBitmap) → Color
  optional: schemeFromSeed → ColorScheme roles for local chrome
```

| Platform | System A (wallpaper MY) | System B / content tint |
|---|---|---|
| Android | Keep `:cmonet` + `dynamic*ColorScheme` (parity) | Optional revive via shared MCU/MaterialKolor **or** kmpalette-like swatches |
| iOS | Always off | Shared seed from open photo only |
| Desktop | Off (or optional OS accent seed — separate product call) | Shared seed from open photo only |

**Android production parity:** keep System A unchanged unless owner approves algorithm drift (OEM allowlist, force SP key, forced-dark + dynamic token path).

**J4 dep rule:** if adding a library, one slice only — prefer **MaterialKolor alone** (covers seed schemes + image theme seed) over MaterialKolor + kmpalette unless swatch UX is required.

---

## 7. Product options matrix

| Option | Scope | Effort | Parity / risk | Notes |
|---|---|---|---|---|
| **0. Status quo** | Android MY live; image palette gone; Desktop/iOS toggle no-op | 0 | Android OK; toggle dishonest on other platforms | Document and/or hide non-Android toggle |
| **1. Honesty pass** | Hide/disable Force Dynamic Color on iOS/Desktop; copy clarifies Android-only | Low | No visual change | Recommended baseline hygiene |
| **2. Revive content chrome (B)** | Open photo → muted seed → editor bg/surface only | Medium | New feature ADR (ADR-0014) | Best cross-platform delight; privacy-safe; matches watermark domain |
| **3. Content full M3 scheme** | Photo seed → whole-app ColorScheme while editing | Medium–high | Can fight brand olive / forced-dark parity | Gate with preference; scope carefully |
| **4. Desktop OS accent seed** | Registry/AppKit → MaterialKolor scheme when forced | Medium | Not Android MY | Optional Desktop polish only |
| **5. Desktop wallpaper quantize** | Read wallpaper file → scheme | High | Fragile, multi-monitor, privacy surface | **Not recommended** |
| **6. iOS wallpaper** | — | — | Impossible officially | **Do not attempt** |

---

## 8. Suggested decision questions for owner

1. Is **Android wallpaper Material You** still a hard parity requirement for v3 CMP release? (Default: **yes** — keep A.)
2. Do we want **image-tinted editor chrome** back as a deliberate feature on **all** platforms? (Would reverse S4d-41 with a new ADR.)
3. Should About’s **Force Dynamic Color** on iOS/Desktop remain visible if it cannot mean Material You?
4. If content tint returns: **local chrome only** (historical B) vs **full M3 scheme** while editing?
5. Accept MaterialKolor (community, MCU-based) as the KMP color engine, or stay zero-dep with simple average color?

---

## 9. Source index

### Repo

- `cmonet/` — OEM gate + `DynamicColors.applyToActivitiesIfAvailable`
- `app/.../ui/Theme.kt` — Compose dynamic schemes
- `shared/.../platform/DynamicColorCapability.kt`
- `docs/adr/0007-platform-neutral-models.md` (capability status)
- `docs/adr/0014-parity-micro-decisions.md` (palette drop addendum)
- `master` View path: `WaterMarkImageView.generatePalette`, `PaletteKtx`, `MainActivity` observer

### Official

- Android dynamic colors: <https://developer.android.com/develop/ui/views/theming/dynamic-colors>
- Compose M3 dynamic schemes: <https://developer.android.com/develop/ui/compose/designsystems/material3>
- Palette API: <https://developer.android.com/develop/ui/views/graphics/palette-colors>
- M3 dynamic color: <https://m3.material.io/styles/color/dynamic>
- Material Color Utilities: <https://github.com/material-foundation/material-color-utilities>
- Apple HIG Color / CIAreaAverage / system colors (no wallpaper seed API)

### Community / CMP

- MaterialKolor: <https://github.com/jordond/materialkolor>
- kmpalette (androidx.palette KMP port)
- 9to5Google MCU open-source note (2022) — algorithms multi-platform; wallpaper still OS-bound on Android

---

## 10. Bottom line

| Goal | Correct approach |
|---|---|
| Match production Android Material You | Keep System A (`:cmonet` + `dynamic*ColorScheme`); Android-only capability |
| “Preview photo drives UI color” (historical B) | Content-based tint from **open image** via shared MCU/MaterialKolor or palette swatches — all platforms |
| Same wallpaper MY on iOS/Desktop | **Impossible / not worth it** |
| Honest multiplatform product | Separate capabilities; do not sell Desktop/iOS no-ops as Material You |

**Best cross-platform product story:**  
Android keeps true Material You from wallpaper; all platforms optionally share **content-based editor chrome** from the photo being watermarked — same privacy model (no wallpaper scraping), same domain (photo tool), and intentional rather than dormant wiring.
