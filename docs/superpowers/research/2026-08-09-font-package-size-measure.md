# Package size measure — system-default fonts (ADR-0025) + cleanup

**Date:** 2026-08-09  
**Commits:** `90e22505` (system-default fonts) + follow-up cleanup (dead `IosFontLoader` / `bundledFontFamily`)  
**Branch:** `feat/migrate_to_compose`

## Method

| Artifact | How measured | Notes |
|----------|--------------|--------|
| Production font **payload** | `git cat-file -s de76b2cb:<path>` | Exact bytes at pre-ADR parent |
| Desktop product JAR | `shared-desktop.jar` inside `createDistributable` app | No Noto entries after change |
| Desktop distributable | `du -sh` of `EasyWatermark.app` after `:desktopApp:createDistributable` | Unsigned; Homebrew JDK needed `-Pcompose.desktop.packaging.checkJdkVendor=false` once |
| iOS Shared release binary | `:shared:linkReleaseFrameworkIosArm64` → `Shared.framework/Shared` | Kotlin/Native only — fonts were never *inside* the framework (they were app Copy Bundle Resources) |
| iOS app bundle | Debug rebuild `xcodebuild` iphonesimulator, `CODE_SIGNING_ALLOWED=NO` | Not App Thinning Size Report / ASC; Noto presence is the decisive signal |

**Not claimed:** App Store thinned IPA download size, signed multi-OS Desktop DMG, Play AAB (Android never shipped production Noto).

---

## 1. Exact production payload removed

| Path (at `de76b2cb`) | Bytes | MiB |
|----------------------|------:|----:|
| `iosApp/.../Fonts/NotoSansSC-Regular.otf` | 8 331 336 | 7.95 |
| `iosApp/.../Fonts/NotoSans-Regular.ttf` | 616 116 | 0.59 |
| OFL license texts (iOS) | ~8.7 KB | ~0.01 |
| **iOS subtotal** | **~8 956 148** | **~8.54** |
| `desktopMain/resources/fonts/NotoSansSC-Regular.otf` | 8 331 336 | 7.95 |
| `desktopMain/resources/fonts/NotoSans-Regular.ttf` | 616 116 | 0.59 |
| OFL (desktopMain) | ~8.7 KB | ~0.01 |
| **Desktop product subtotal** | **~8 956 148** | **~8.54** |
| **Combined product payload** | **~17.9 MB** | **~17.08 MiB** |

**After:** both production paths absent (`Fonts/` dirs removed).  
**Test-only residual (not in product):** `desktopTest/resources/fonts` ~8.6 M, `androidTest/assets/fonts` ~8.6 M.

---

## 2. Desktop distributable (unsigned app image)

| State | `EasyWatermark.app` | `Contents/app` | `shared-desktop-*.jar` | Noto in jar? |
|-------|--------------------:|---------------:|-----------------------:|:-------------|
| Pre-rebuild cache (Jul 25 tree, fonts still packaged) | **139 M** | 71 M | **8.6 M** | yes (historical) |
| **After ADR-0025** (`createDistributable` 2026-08-09) | **127 M** | 64 M | **1.5 M** | **no** |

- **App image Δ ≈ −12 M** on-disk (includes JBR/runtime packaging variance; not only fonts).  
- **shared-desktop.jar Δ ≈ −7.1 M** (8.6 M → 1.5 M) — matches removing ~8.54 MiB uncompressed fonts (ZIP may store fonts poorly compressed).  
- Fresh `shared/build/libs/shared-desktop.jar` = **1.5 M**, no `Noto` / `.otf` / `.ttf` entries.

---

## 3. iOS

| Artifact | Size | Noto? |
|----------|-----:|:------|
| `linkReleaseFrameworkIosArm64` `Shared.framework` | **51 M** total / **50 M** binary | N/A (never held Noto files) |
| Stale **Debug-iphoneos** `iosApp.app` (Jul 15, pre-ADR) | **85 M** | **yes** (7.9 M SC + 602 K Latin at app root) |
| **Debug-iphonesimulator** `iosApp.app` (rebuilt 2026-08-09) | **79 M** | **no** |

- Fonts were **app bundle resources**, not inside `Shared.framework`. Removing them does not shrink the K/N binary much; it shrinks the **`.app` payload** by ~8.54 MiB when rebuilt.  
- Cross-SDK Debug sizes (iphoneos vs simulator) are not a perfect A/B; the hard proof is **zero Noto files** in the new app + exact git object sizes of removed resources.  
- **App Thinning Size Report / ASC** not run (no Ad Hoc export this session). Expected download/install savings ≈ **−8.5 MiB** on thinned variants once re-exported.

---

## 4. Cleanup follow-up (dead code)

Removed from product tree:

- `IosFontLoader.kt` (entire NSBundle Noto loader)
- `IosTextRasterEnv.bundledFontFamily(...)`
- Renamed test → `IosSystemDefaultFontTest` (system default + resolver cache)

Docs: ADR-0025 consequence line, AGENTS.md, iosApp/README.md, related KDoc.

**Size impact of cleanup:** negligible (code-only; no asset change beyond ADR-0025).

---

## 5. Bottom line

| Platform | Controllable font win | Measured product signal |
|----------|----------------------|-------------------------|
| **iOS** | **−8.54 MiB** payload | New Debug sim `.app` has **no** Noto; old device Debug had full Noto |
| **Desktop** | **−8.54 MiB** product classpath | `shared-desktop.jar` **8.6 M → 1.5 M**; app image **139 M → 127 M** |
| **Android Play** | **0** (already system default) | Test Noto remains under androidTest only |

Cleanup of dead iOS loaders is hygiene after ADR-0025; size story is the payload removal above.
