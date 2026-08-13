# ProductThumb A/B: ImageIO thumbnail+repack vs Coil decoder size

**Date:** 2026-08-13  
**Baseline commit:** `740417a3` (then production A = ImageIO/AWT+repack)  
**Applied:** same day — Desktop/iOS JPEG/PNG production is now SourceFetch + Skia (A/B-B). iOS HEIC stays ImageIO via `IosHeifImageDecoder`. Android stays MediaStore.  
**Question:** ADR-0028 said we must sample in the Fetcher (ImageIO/AWT/MediaStore) because Coil `.size` is not a substitute. Was that measured?

## Arms

| Arm | What |
|-----|------|
| **A** | Production: `ProductThumbFetcher` → platform `decodeThumbnail(maxEdge=128)` → pixel pack → `ImageFetchResult` |
| **B** | `ProductThumb` → `SourceFetchResult(file)` → Coil Skia decoder + `.size(128)` `INEXACT` |
| **C** (iOS) | No ProductThumb: `.data("file://path")` built-in `FileUriFetcher` + same size |

Memory/disk cache **disabled** on every request (cold decode).

## Results (this machine)

### Desktop JVM — JPEG 3000×2000 → 128

`ProductThumbAbDecodeBenchTest`

Before apply (`740417a3`, A = ImageIO+repack):

| Arm | ok | median | out |
|-----|----|--------|-----|
| A product AWT/ImageIO+repack | yes | **15 ms** | 128×85 |
| B Coil `File` + decoder | yes | **4 ms** | 128×85 |

After apply (production A = SourceFetch + Skia, `isSampled=false`):

| Arm | ok | median | out |
|-----|----|--------|-----|
| A product Source+Skia | yes | **5 ms** | 128×85 |
| B Coil `File` + decoder | yes | **5 ms** | 128×85 |

### iOS Simulator — PNG 2400×1600 → 128

| Arm | ok | median | out |
|-----|----|--------|-----|
| A ImageIO+repack | yes | **12 ms** | 128×85 |
| B Source+Skia | yes | **8 ms** | 128×85 |
| C file:// builtin | yes | **8 ms** | 128×85 |

### iOS Simulator — HEIC 2400×1600 → 128 (ImageIO `public.heic` fixture)

After `IosHeifImageDecoder` (production A = `buildProductImageLoader`):

| Arm | ok | median | out |
|-----|----|--------|-----|
| A production (HEIF decoder + ImageIO thumb) | **yes** | **21 ms** | 128×84 |
| B Source+Skia only (no HEIF decoder) | **no** | — | `Failed to Image::makeFromEncoded` |
| C file:// builtin Skia | **no** | — | same Skia error |

PNG A is now also Source+Skia (Fetcher no longer ImageIO+repack): **8 ms**, same as B/C.

Matches `IosImageDecoder` comment: current Skiko **does not decode HEIC/HEIF**. Coil’s default decoder is that Skia path.

## What this does **not** prove

- **Android content URI** vs MediaStore (ADR’s Android worry) — still unmeasured.  
- Device Main-thread / LazyRow jank — isolated `ImageLoader.execute` only.

## Verdict

| Format | ADR-0028 “must sample in Fetcher” | Production after apply |
|--------|-----------------------------------|------------------------|
| JPEG/PNG file | **Over-strong** — Coil `.size` downsample works and is faster (no pack). | Desktop + iOS: `SourceFetchResult` + Skia. Desktop forces `isSampled=false`; do **not** re-bake EXIF (skiko already does). |
| **HEIC** (iPhone Photos) | **Confirmed** — Coil/Skia cannot load; ImageIO thumbnail is the working path. | iOS: `IosHeifImageDecoder` (policy-driven ImageIO thumb). |
| Android content URI | Unmeasured | Unchanged MediaStore Fetcher. |

## Commands

```bash
./gradlew :shared:desktopTest --tests me.rosuh.easywatermark.ui.image.ProductThumbAbDecodeBenchTest
./gradlew :shared:iosSimulatorArm64Test --tests me.rosuh.easywatermark.ui.image.IosProductThumbAbDecodeBenchTest
```

HEIC arm: `largeHeic_imageIoThumb_vs_coilSourceDecoder` (ImageIO encode `public.heic`; A succeeds, B/C fail Skia).
