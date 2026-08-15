# Skia / Skiko / CMP / Coil image-decode paths vs iOS filmstrip latency (2026-08-15)

Research answer to the six questions in the brief, plus a measured root cause for the device fact
that started it: **`io128` ≈ 180–200 ms and `kCGImageSourceSubsampleFactor` does not help at 128 px.**

No production code changed. All Kotlin/C++/GN claims are read out of primary sources (skia-pack build
args, Skia `BUILD.gn` / `SkCodec.cpp`, skiko sources jar + skiko C++, CMP `ui-graphics` sources jar,
Coil 3.5.0 sources jars). All ImageIO numbers come from a throwaway Swift probe (§2) — labelled as
macOS, not device.

---

## Executive verdict

1. **The bottleneck is not the handoff, not Coil, not Skia, and not the thumbnail API. It is the
   HEIC file layout.** Camera-originated HEIC stores the photo as an **HEVC tiled grid** — the two
   real-world files probed here are each **63 × 512×512 HEVC tiles per grid** (126 colour tile streams
   plus 40 gray auxiliary tiles). ImageIO must decode *every tile* no matter how small the requested
   output is. A single-frame HEIC of comparable size decodes in **25–30 ms**; the tiled ones take
   **165–250 ms**. That is a per-tile cost, not a per-pixel cost (§3).
2. **`kCGImageSourceSubsampleFactor` is honoured for HEIF and still buys nothing.** Proven both ways:
   `CGImageSourceCreateImageAtIndex` with factor 2/4/8 returns correctly reduced dimensions (so the
   key is not being silently dropped), and the wall time is unchanged or worse. HEVC has no cheap
   partial decode; the reduction happens *after* the tiles are decoded. JPEG is the opposite — DCT
   scaling makes factor 8 genuinely cheaper (25 ms → 9.5 ms) (§2.2).
3. **The same content as JPEG costs ~10 ms at 128 px instead of ~200 ms — a ~20× gap.** And a 256 px
   JPEG *sidecar* re-reads at 128 px in **0.2–0.3 ms**. Encoding a derived thumbnail once is worth
   roughly **700×** on every subsequent read (§2.3).
4. **`QLThumbnailGenerator` is cheaper cold *and* has a free persistent OS-owned cache** —
   91–182 ms cold, **0.8–2.1 ms warm**, no permission, works on plain file URLs (§2.3).
5. **Skia has no HEIF decoder at all**, on any platform, in mainline. Android gets HEIF by bolting
   `AImageDecoder` on through `SkImageGenerator` (`skia_use_ndk_images`, `is_android` only). skiko
   exposes **no `ImageGenerator` binding**, so the Android trick is not reproducible from Kotlin (§4.1, §4.4).
6. **Skiko cannot wrap an `MTLTexture`.** `BackendTexture` has `makeGL` and nothing else, in both the
   Kotlin API and skiko's C++. So CVPixelBuffer / IOSurface / Metal zero-copy is closed at the
   binding layer (§4.5).
7. **Compose on iOS cannot display a `CGImage` without a Skia raster.** `SkiaBackedCanvas.drawImage`
   calls `ImageBitmap.asSkiaBitmap()`, which *throws* for anything that is not a
   `SkiaBackedImageBitmap`. The only escape is `androidx.compose.ui.interop.UIKitView` hosting a
   `UIImageView` per cell, which the repo's own CMP-first convention rules out (§4.3).
8. **Coil is not in the way and does not need to be left.** `ImageFetchResult` is Coil's documented
   way for a `Fetcher` to return an already-decoded `coil3.Image` and skip the decoder chain entirely
   — `BitmapFetcher` in `commonMain` already does exactly this. A PHImageManager- or
   QuickLook-backed Fetcher is idiomatic Coil (§4.2).
9. **The PHImageManager path is the best possible latency and the worst possible fit.** Photos keeps
   pre-rendered derivatives, so it never decodes the HEIC — but reaching a `PHAsset` from a
   `PhotosPickerItem` requires photo-library **read authorization**, which the app does not request
   and whose absence is a stated privacy property. Owner/ADR decision, not an implementation detail (§4.2.2).

Ranked options in §5. Explicit "won't help latency" list in §6.

---

## 1. Where the repo stands today

| Surface | Path | Notes |
|---|---|---|
| Filmstrip / theme seed | `ProductAsyncImage` → Coil singleton → `ProductThumbFetcher` (`SourceFetchResult`, file path) → `IosHeifImageDecoder` (HEIC) or Coil `SkiaImageDecoder` (JPEG/PNG) | `ProductThumb.UI_THUMB_MAX_EDGE = 128` (`= ContentEditorTheme.SEED_MAX_EDGE`, pinned by `ProductThumbTest`) |
| HEIC decode | `IosImageIODecoder.decodeThumbnailBitmapWithMetadata` → `CGImageSourceCreateThumbnailAtIndex` (`FromImageAlways` + `WithTransform` + `ShouldCache=0` + `ThumbnailMaxPixelSize` + optional `SubsampleFactor`) → `CGBitmapContextCreate` over a Skia-owned buffer → `Bitmap.asImage()` | Phase 1 (commit `60bf8ff2`) already removed the L2/L3 copies |
| Policy | `IosHeifDecodePolicy.ProductUi` — edge clamped 64…512, `sampled = Never`, `imageIoShouldCache = false`, `allowSubsample = true` | `Preview` policy keeps `allowSubsample = false` |
| Coil request | `memoryCachePolicy(ENABLED)`, **`diskCachePolicy(DISABLED)`**, `size(128)`, `Precision.INEXACT`, explicit `memoryCacheKey` + `placeholderMemoryCacheKey` | key = `ewm_thumb;<path>;<edge>` (`ProductThumbKeyer`) |

Two things follow from that table and matter for the ranking:

- The **only** cache between the filmstrip and a 200 ms decode is Coil's in-memory LRU. Nothing
  survives eviction, a memory warning, or a relaunch. (Coil's disk cache would not help even if
  enabled — it caches *encoded source bytes*, and the source bytes are the expensive part.)
- 128 px thumbs are tiny (128×85×4 ≈ 43 KB), so 50 of them is ~2 MB and the memory cache holds them
  all comfortably. **The pain is therefore the first pass, not steady-state scrolling:** 50 photos ×
  ~200 ms ≈ 10 s of decode that no amount of copy-elision or scheduling makes cheaper.

---

## 2. Measurement (throwaway Swift probe)

### 2.1 Method and honest caveats

Three single-file Swift programs compiled with `swiftc -O`, run on **Apple M5 Pro, macOS 27.0
(26A5406e), Xcode 27.0 beta SDK**. `reps = 7`, median reported, a fresh `CGImageSource` per
iteration unless the row says `warmSource`. Sources: two real camera-originated HEICs
(`3158×4501`, `3273×4265`, both Display P3, `depth=8`), one `sips`-transcoded JPEG of the first, and
two repo fixtures from `sample-images/formats/`.

**This is macOS, not an iPhone 16 Pro.** Different ImageIO tuning, different HEVC decode hardware.
It is used here for *mechanism*, not for absolute device numbers. What licenses the inference is
that the macOS numbers land on top of the device numbers already in the repo
(`2026-08-14-ios-preview-perf-leftovers.md` S3: `io128_med` 202 ms, `io1920_med` 138 ms) **and
reproduce the same inversion**, which is a strong hint the mechanism is shared. **On-device
subsample close-out** (`build/ios-device-shots/s3-subsample-verify/ewm-device-perf.txt`, same night):
`io128` 183→198 ms with factor 8 (**no win**), `io1920` 143→124; dims preserved — consistent with
§2.2 / executive point 2 (reduction after tiles). Every recommendation below still needs the device
harness (`DEVICE_PERF_IO`) for absolute QL / sidecar numbers.

### 2.2 Option-set sweep — nothing in ImageIO's option dictionary moves the needle

`CGImageSourceCreateThumbnailAtIndex`, `FromImageAlways` + `WithTransform` + `ShouldCache=false`,
median ms:

| Variant | HEIC A @128 | HEIC B @128 | HEIC A @1920 | HEIC B @1920 |
|---|---:|---:|---:|---:|
| base (today's options) | 224.3 | 288.4 | 201.6 | 159.6 |
| `+ SubsampleFactor 8` (today's `ProductUi`) | 215.9 | 213.2 | 173.2 | 199.5 |
| `+ DecodeToSDR, kCGComputeHDRStats=false` | 197.3 | 238.6 | 294.8 | 164.6 |
| `+ GenerateImageSpecificLumaScaling=false` | 204.3 | 234.6 | 338.9 | 199.5 |
| `+ DecodeToSDR + SubsampleFactor 8` | 219.2 | 216.8 | 273.4 | 143.6 |
| `+ ShouldCacheImmediately=true` | 215.4 | 264.0 | 256.4 | 173.4 |

Reading: all six variants are inside each other's noise. **128 px is not cheaper than 1920 px** — the
repo's S3 inversion reproduces. The `CGContextDrawImage` into the Skia buffer costs **0.12 ms** at
128 px and 7.6–9.6 ms at 1920 px, confirming §1 of the Phase-1 doc: the handoff is noise at 128.

The HDR levers deserve a specific note because they looked like the strongest hypothesis going in.
Apple's forum thread and the Prodopsy write-up both report `kCGImageSourceDecodeRequest:
kCGImageSourceDecodeToSDR` + `kCGImageSourceDecodeRequestOptions: [kCGComputeHDRStats: false]`
curing "extreme CPU utilization at small sizes" for iPhone HEIC, and gain-map rendering is
independently reported at ~30 ms vs ~3 ms on a 12 MP gain-mapped preview. **On these two files it
does nothing**, and `CGImageSourceCopyAuxiliaryDataInfoAtIndex(kCGImageAuxiliaryDataTypeHDRGainMap)`
returned `nil` for both. Verdict: **plausible but not the cause here** — still worth one device
A/B because a modern iPhone 16 Pro capture is more likely to be gain-mapped than these samples,
and the option pair is a two-line change.

### 2.3 Stage breakdown — the decode is the whole cost, and it is per-tile

| | HEIC A (3158×4501, tiled) | HEIC B (3273×4265, tiled) | JPEG (same content as A) | fmt_1036.heic (3200×2133, **single frame**) | fmt_166.heic (1280×720, single frame) |
|---|---:|---:|---:|---:|---:|
| `openSource` | 0.0 | 0.0 | 0.0 | 0.0 | 0.0 |
| `copyProperties` | 1.6 | 1.6 | 0.2 | 0.2 | 0.1 |
| `createImage` f=1 (full decode) | 253.4 | 165.4 | **25.4** | **30.5** | 8.1 |
| `createImage` f=2 | 168.3 | 137.7 | 16.1 | 31.7 | 5.5 |
| `createImage` f=4 | 175.6 | 128.4 | 11.5 | 32.3 | 10.2 |
| `createImage` f=8 | 192.1 | 185.9 | **9.5** | 25.1 | 8.9 |
| `thumb@128` base | 244.6 | 189.6 | **10.0** | **24.5** | 5.8 |
| `thumb@128` + sub8 | 267.1 | 199.6 | 9.7 | 28.3 | 10.6 |
| `createImage` f=8 **+ CG scale → 128** | 401.6 | 375.6 | 9.8 | 58.4 | 13.9 |
| `thumb@128` warm `CGImageSource` | 222.7 | 171.9 | 9.5 | 19.7 | 16.3 |
| **×8 sequential** | 1568.0 | 1392.4 | 80.6 | 249.9 | 41.5 |
| **×8 concurrent** (`concurrentPerform`) | 819.7 | 669.9 | **12.1** | 109.7 | 24.9 |
| concurrency speedup | **1.91×** | **2.08×** | **6.68×** | 2.28× | 1.67× |

Four load-bearing readings:

1. **`createImage f=8` is not cheaper than `f=1` for HEIC** (192 vs 253, 186 vs 165) but is 2.7×
   cheaper for JPEG (9.5 vs 25.4). Subsampling is a *codec* capability; libjpeg-turbo has it, HEVC
   does not.
2. **Manually subsampling then scaling is strictly worse** (401 ms). Do not build that.
3. **Reusing the `CGImageSource` saves ~10%** (244 → 222). Real but small, and it means holding an
   open ImageIO source per filmstrip entry.
4. **ImageIO parallelises HEIC decodes only ~2× across 8 threads, versus 6.7× for JPEG.** So
   "prefetch the filmstrip in parallel" cannot rescue HEIC either — the decode pipeline is
   substantially serialised. This kills the scheduling-only class of fixes.

QuickLook and sidecar (probe 3, same host):

| | HEIC A | HEIC B | JPEG |
|---|---:|---:|---:|
| `QLThumbnailGenerator` `.thumbnail` **cold** | 182.5 | 91.5 | 11.4 |
| `QLThumbnailGenerator` `.thumbnail` **warm** | **1.0** | **2.1** | 1.2 |
| `QLThumbnailGenerator` `.lowQualityThumbnail` warm | 0.8 | 0.9 | 1.1 |
| `QLThumbnailGenerator` `.all` warm | 39.1 | 0.6 | 12.3 |
| write 256 px JPEG sidecar (incl. the one HEIC decode) | 266.9 | 96.8 | 10.7 |
| **read sidecar back @128** | **0.3** | **0.2** | 0.4 |
| sidecar bytes | 18.7 KB | 12.4 KB | 21.0 KB |

QuickLook returned correct oriented `90x128` / `98x128` images. Note `.all` was erratic (39 ms once)
— use `.thumbnail`, not `.all`.

### 2.4 Root cause: HEVC tiled grid

`ffprobe -select_streams v` stream inventory:

| File | Streams |
|---|---|
| HEIC A (3158×4501) | **126 × `hevc 512×512 yuvj420p`** + 40 × `hevc 512×512 gray` |
| HEIC B (3273×4265) | **126 × `hevc 512×512 yuvj420p`** + 40 × `hevc 512×512 gray` |
| `fmt_1036.heic` (3200×2133) | **1 × `hevc 3200×2134 yuvj420p`** |

3158/512 → 7 columns, 4501/512 → 9 rows = 63 tiles; 126 = two such grids, plus reduced-resolution
gray (single-channel) auxiliary grids. `profile=Main Still Picture` for colour tiles, `Rext` for gray.

That is the answer to "why doesn't the requested size matter". Camera HEIC is an ISO/IEC 23008-12
**grid derived item**: ~63 independent HEVC decodes plus composition, per image, before ImageIO has
anything to scale. `fmt_1036.heic` — a `sips`-style single-frame HEIC of *comparable pixel count* —
costs 25–30 ms. **2× the pixels, but 8× the time: the cost tracks tile count, not megapixels.**

This also explains why the repo's existing tests could not catch it. `IosImageIOSubsampleTest`
asserts only that subsampling preserves *output dimensions*, and its fixture is a
Compose-rendered PNG — so it proves the policy is quality-safe and proves nothing about whether
subsampling ever saves time on the real input population.

---

## 3. Question-by-question answers

### 3.1 Does Skia on iOS have native HEIF decode?

**No — and not on any platform, in mainline Skia.**

- Skia's decoder registry contains PNG, JPEG (+ Rust variants), WEBP, GIF (wuffs), ICO, BMP, WBMP,
  and *conditionally* AVIF (`SK_CODEC_DECODES_AVIF`, libavif or crabbyavif), JPEG-XL
  (`SK_CODEC_DECODES_JPEGXL`), and RAW. **There is no HEIF entry.**
  [`src/codec/SkCodec.cpp`](https://github.com/google/skia/blob/main/src/codec/SkCodec.cpp)
- `BUILD.gn` has **zero** occurrences of `heif`. The historical `SkHeifCodec` is gone.
  [`BUILD.gn`](https://github.com/google/skia/blob/main/BUILD.gn)
- Android's HEIF support is not a Skia codec: it is `optional("ndk_images")`, gated on
  `skia_use_ndk_images`, which `gn/skia.gni` defines as
  `is_android && defined(ndk_api) && ndk_api >= 30`. Its sources are
  `SkImageGeneratorNDK.cpp` / `SkImageEncoder_NDK.cpp` / `SkNDKConversions.cpp` and it links
  `jnigraphics` — i.e. HEIF arrives through **`SkImageGenerator` wrapping `AImageDecoder`**.
  [`gn/skia.gni`](https://github.com/google/skia/blob/main/gn/skia.gni)
- The defaults that would matter for a hypothetical AVIF/HEIF-adjacent path are all off:
  `skia_use_crabbyavif = false`, `skia_use_libavif = false`, `skia_use_libjxl_decode = false`.
- **skiko's own build turns none of them on.** JetBrains builds Skia via `skia-pack`, whose iOS arg
  list is `is_official_build`, `target_cpu`, the `skia_use_system_*=false` family, `skia_use_sfntly`,
  `skia_pdf_subset_harfbuzz`, `skia_enable_skottie`, `-frtti`, `skia_use_metal=true`,
  `target_os="ios"`, and the min-version flags. **No codec flag is added for iOS at all** (the only
  place codec flags appear is the `wasm` branch).
  [`skia-pack/script/build.py`](https://github.com/JetBrains/skia-pack/blob/master/script/build.py)
- Consequence, already known empirically: `Image.makeFromEncoded` fails on HEIC —
  [skiko#942](https://github.com/JetBrains/skiko/issues/942) (closed 2024-06-16, resolution "file it
  on Skia"), and [coil#2318](https://github.com/coil-kt/coil/issues/2318) (closed 2024-06-19,
  resolution "do it as an external `Decoder.Factory`").

**No libdav1d/libgav1 involvement either** — those are AV1, i.e. AVIF, not HEVC-in-HEIF, and both
are off in this build. Adding HEIF to skiko would mean patching Skia's GN + vendoring a HEVC
decoder, or writing the `SkImageGenerator` shim in skiko's C++ (§3.4).

### 3.2 Coil 3 iOS decoder chain — can a PHImageManager-backed Fetcher/Decoder be injected without leaving Coil?

**Yes, trivially, and Coil already ships an example of the exact shape.** Read from
`coil-core-3.5.0-sources.jar`.

Default component registry on Apple:

```
addCommonComponents():  StringMapper, PathMapper | FileUriKeyer, UriKeyer
                        | FileUriFetcher, ByteArrayFetcher, DataUriFetcher, BitmapFetcher
addAppleComponents():   NSURLMapper
addAndroidComponents()  ← nonAndroidMain actual, misleadingly named: adds SkiaImageDecoder.Factory
```

The mechanism that matters is `ImageFetchResult`:

```kotlin
// coil3/fetch/FetchResult.kt
/**
 * An [Image] result. Return this from a [Fetcher] if its data cannot
 * be converted into an [ImageSource].
 */
class ImageFetchResult(val image: Image, val isSampled: Boolean, val dataSource: DataSource) : FetchResult
```

and `coil3.fetch.BitmapFetcher` (commonMain) is a five-line proof that returning a decoded image
from a `Fetcher` is a first-class Coil path, not a hack:

```kotlin
override suspend fun fetch(): FetchResult =
    ImageFetchResult(image = data.asImage(), isSampled = false, dataSource = DataSource.MEMORY)
```

So a `PHImageManager`- or `QLThumbnailGenerator`-backed fetcher for `ProductThumb` slots in beside
the existing `ProductThumbFetcher.Factory()` and needs **no** decoder, no `ImageSource`, and no
change to `ProductAsyncImage`. `ProductThumbKeyer` already supplies the memory-cache key, which is
the one thing custom-data-type fetchers must not forget (the `Fetcher` KDoc says so explicitly).

Two incidental Coil findings worth recording:

- **`SkiaImageDecoder` full-decodes JPEG/PNG and then Canvas-scales.** `source.source().use {
  it.readByteArray() }` → `Image.makeFromEncoded(bytes)` → `Bitmap.makeFromImage(image, options)`,
  where `makeFromImage` does `allocN32Pixels(outW, outH)` + `Canvas.drawImageRect(...)`. There is no
  codec-level downsample anywhere in that path. Per §2.3 that is ~25 ms of full-res JPEG decode to
  produce a 128 px thumb where a `SubsampleFactor 8` ImageIO read would be ~9.5 ms.
  **The repo's JPEG/PNG filmstrip path is leaving ~2.5× on the table** — smaller absolutely than the
  HEIC problem, but a clean, contained win.
- `BitmapImage` remains the right return type. `Image.toBitmap(w, h, colorType, alphaType,
  colorSpace)` returns *the same* `Bitmap` object when all five properties match, so
  `DecodeResult(image = bitmap.asImage())` costs nothing extra downstream. (Same conclusion as the
  Phase-1 doc §7.2, re-verified against 3.5.0 sources.)

#### 3.2.2 The permission wall in front of PHImageManager

`PhotosPickerItem.itemIdentifier` is `nil` unless the picker is constructed with a
`photoLibrary:` — which `ContentView.swift` does (`photoLibrary: .shared()` on both picker edges) —
**and Apple's documentation plus two Developer Forums answers state the app needs photo-library read
authorization for that to be usable.** Selecting a photo in PHPicker does not grant durable access
to it; the recommended pattern is exactly what the repo already does (copy the bytes locally).

- <https://developer.apple.com/documentation/photosui/photospickeritem/itemidentifier>
- <https://developer.apple.com/forums/thread/823256> — "you will need to ensure your app has
  everything needed to prompt for Photo Library access… I would generally recommend you copy the
  image data to your app's local storage"
- <https://developer.apple.com/forums/thread/807143> — "the itemIdentifier is expected to be nil if
  you create the picker without a photo library (and to do so, your app needs photo library read
  authorization)"
- <https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app>

The repo currently requests only `.addOnly` at export time (`ImageExport.saveToPhotos`). Moving to
`.readWrite` to reach `PHImageManager` would add a full library-access prompt to the product. Given
"fully offline, no permissions needed" is a stated product property, **this is an ADR/owner call.**
Technically it is the best answer (Photos hands back a cached derivative and never touches the HEVC
grid); politically it is the most expensive one.

### 3.3 Compose `ImageBitmap` / `CGImage` interop — any path to display a `CGImage`/`UIImage` without a Skia raster?

**Through `ImageBitmap`: no. Through UIKit interop: yes, but don't.**

From `ui-graphics-iossimulatorarm64-1.12.0-beta01-sources.jar`:

```kotlin
// skikoMain/androidx/compose/ui/graphics/SkiaImageAsset.skiko.kt
fun ImageBitmap.asSkiaBitmap(): Bitmap = when (this) {
    is SkiaBackedImageBitmap -> bitmap
    else -> throw UnsupportedOperationException("Unable to obtain org.jetbrains.skia.Image")
}
```

and every draw entry point goes through it:

```
SkiaBackedCanvas.skiko.kt:46   val skiaBitmap = image.asSkiaBitmap()
SkiaBackedCanvas.skiko.kt:296  val bitmap = image.asSkiaBitmap()      // drawImageRect
SkiaShader.skiko.kt:126        image.asSkiaBitmap().makeShader(...)
```

`ImageBitmap` is a public interface, so you *can* implement a `CGImageBackedImageBitmap` — and
Compose will throw the moment it is drawn. `ActualImageBitmap(...)` and `createImageBitmap(bytes)`
both terminate in `SkiaBackedImageBitmap`. There is no CGImage-backed variant and no hook to add one.

The only real escape hatch is **`androidx.compose.ui.interop.UIKitView`**, which does exist in CMP
1.12 for iOS (`ui-iossimulatorarm64` ships `interop/UIKitView.ios.kt` plus the whole
`viewinterop/UIKitInterop*` machinery). A `UIImageView` per filmstrip cell would display a `CGImage`
with zero Skia involvement. Against it: every interop view is a real `UIView` spliced into the
hierarchy with its own clipping/z-order/touch handling; a `LazyRow` of them is the classic way to
make scrolling worse, not better; and `AGENTS.md` explicitly forbids `AndroidView`-bridged renderer
surfaces under the CMP-first convention. **Marked "won't help latency" — the 128 px raster costs
0.12 ms (§2.2); this would trade that for UIView churn.**

### 3.4 Skia `ImageGenerator` / `AndroidCodec` equivalents on native

- **`SkImageGenerator` is exactly the right extension point** — it is how Skia gets HEIF on Android
  (`SkImageGeneratorNDK.cpp`, §3.1). `SkAndroidCodec` (`src/codec/SkAndroidCodec.cpp`,
  `SkAndroidCodecAdapter.cpp`) is the Android-facing scaled-decode wrapper.
- **skiko binds neither.** A full-text search of the skiko 0.150.0 sources jar for
  `ImageGenerator`, `AndroidCodec`, `SkCodecs`, `registerCodec` returns **nothing**.
- What skiko *does* bind is `org.jetbrains.skia.Codec` → `SkCodec::MakeFromData`
  (`Codec.makeFromData(Data?)`, `readPixels(Bitmap, frame, priorFrame)`, `frameCount`,
  `encodedOrigin`, …). That is the registry-based codec, so it inherits §3.1: it will throw
  `"Unsupported format"` on HEIC. It is useful for animated GIF/WEBP, not for this.
- `Image` factories available on iOS: `makeRaster(info, ByteArray, rowBytes)` (copies),
  `makeRaster(info, Data, rowBytes)` (does not copy — `SkImages::RasterFromData`),
  `makeFromBitmap`, `makeFromPixmap`, `makeFromEncoded(ByteArray)`,
  `Image.Companion.makeFromEncoded(NSData)` (darwinMain convenience — and its own comment notes
  "skia makes an internal copy of the nsData bytes"), and `adoptTextureFrom(...)`.
- **Conclusion:** "wrap ImageIO in an `SkImageGenerator` so Skia/Coil/Compose see a normal lazy
  image" is architecturally the *correct* fix, and it requires C++ in skiko. That means an upstream
  skiko issue/PR, or vendoring a native lib — both explicitly out of scope per the Phase-1 doc's
  non-goals and per J5. **Not a local option.**

### 3.5 Can CMP/Skiko wrap `CVPixelBuffer` / `IOSurface` zero-copy to Metal?

**No. The binding does not exist, in Kotlin or in skiko's C++.**

What skiko exposes for Metal:

| Symbol | Exists? | Direction |
|---|---|---|
| `DirectContext.makeMetal(devicePtr, queuePtr)` | **yes** | GPU context |
| `BackendRenderTarget.makeMetal(w, h, texturePtr)` | **yes** | render **target** (destination) |
| `BackendTexture.makeGL(...)` | yes | source texture, **OpenGL only** |
| `BackendTexture.makeMetal(...)` | **no** | — |

`Image.adoptTextureFrom(context, backendTexture, origin, colorType)` is the `SkImages` GPU factory,
but the only `BackendTexture` you can construct from Kotlin is a GL one. Confirmed on the C++ side
too: [`BackendTexture.cc`](https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/BackendTexture.cc)
implements exactly `_1nGetFinalizer`, `_1nMakeGL` (`GrBackendTextures::MakeGL`), and
`_1nGLTextureParametersModified`. There is no `GrBackendTextures::MakeMtl` call anywhere in it, while
[`BackendRenderTarget.cc`](https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/BackendRenderTarget.cc)
does have `BackendRenderTarget_nMakeMetal` under `#ifdef SK_METAL`. So the asymmetry is deliberate
and complete: skiko can *render into* a Metal texture (that is how `MetalRedrawer.uikit.kt` presents
frames — `BackendRenderTarget.makeMetal(width, height, metalDrawable.texture.objcPtr())`) but cannot
*read from* one.

Even if that binding existed, a GPU-backed `org.jetbrains.skia.Image` still could not become a
Compose `ImageBitmap` (§3.3 — `SkiaBackedImageBitmap` wraps a raster `Bitmap`). So this path needs
**two** upstream changes, not one. Same verdict as the Phase-1 doc reached for different reasons.

### 3.6 Existing repo files

| File | Assessment |
|---|---|
| `render/IosImageIODecoder.kt` | Correct and already well past the ecosystem baseline. Options are right (`FromImageAlways` + `WithTransform`); `subsampleFactorFor` is quality-safe. Two observations: (a) options are built as hand-written `NSString.create(string = "kCGImageSource…")` keys rather than the cinterop constants that the same file already imports for `kCGImagePropertyOrientation` — the string values do match ImageIO's `CFSTR` constants (SDK header `CGImageSource.h`), and `ThumbnailMaxPixelSize` demonstrably works, so this is a readability/robustness nit, not the bug; (b) `kCGImageSourceShouldCacheImmediately` is never set — per §2.2 it changes nothing here, and one write-up disputes that it does anything at all. |
| `ui/image/IosHeifImageDecoder.kt` | Correct, and its KDoc is the most accurate description of this problem anywhere in the repo. One claim now needs softening: "Reduction only moves into the decoder when the policy sets `allowSubsample`" — §2.3 shows the reduction moves into the decoder *dimensionally* but not *temporally* for tiled HEIC. |
| `ui/image/IosHeifDecodePolicy.kt` | Good shape. `ProductUi.allowSubsample = true` is now measured as **inert for tiled camera HEIC** and **genuinely useful for JPEG** — except JPEG never reaches this decoder (§3.2). Worth revisiting as one policy knob that currently applies to the format that cannot use it. |
| `ui/image/ProductThumbFetcher.ios.kt` | Correct and minimal; the natural insertion point for a sidecar/QuickLook lookup (return `ImageFetchResult`, or redirect the `ImageSource` to the sidecar path). |
| `ui/image/ProductAsyncImage.kt` | Correct. `diskCachePolicy(DISABLED)` is the right call *as written* (Coil's disk cache stores encoded source bytes, which is the expensive part) — but it means nothing persists across eviction/relaunch, which is why §5.1 proposes an app-owned derived-thumb store rather than turning Coil's disk cache on. |
| `render/IosImageDecoder.kt`, `ui/image/ImageToComposeBitmap.ios.kt` | Not on the filmstrip critical path. Note `ImageToComposeBitmap.ios.kt` still round-trips `Bitmap → SkiaImage.makeFromBitmap → toComposeImageBitmap`, which re-rasters (Phase-1 doc §3.4); `bitmap.asComposeImageBitmap()` would be a pure wrap. Small, unrelated, and off the 128 px path. |
| `iosTest/.../IosImageIOSubsampleTest.kt` | Sound as a *safety* test, misleading as evidence. It proves dimension preservation on a synthetic PNG; it cannot see that subsampling saves no time on tiled HEIC. Suggest adding a fixture-based tile-count/shape assertion rather than strengthening this one. |

---

## 4. Options ranked by ROI for the 128 px filmstrip

Assume the target is "filmstrip cell paints in < 16 ms after the first pass, and the first pass is
bounded and off the critical path."

### 4.1 🥇 Derived-thumbnail store (app-owned encoded sidecar) — **might help, decisively**

Write a small encoded thumbnail (256 px JPEG q≈0.85, ~12–19 KB) next to each imported photo the
first time it is needed, then serve the filmstrip from it.

- **Measured effect: 190–250 ms → 0.2–0.3 ms per read** (§2.3). ~700–1000×.
- Cost: one HEIC decode per photo, once, ever — and it can be scheduled on the import background
  pass rather than on scroll. Disk: 50 photos ≈ 1 MB.
- Stays entirely inside Coil: `ProductThumbFetcher` checks for the sidecar and returns a
  `SourceFetchResult` pointing at it (JPEG → `SkiaImageDecoder`), else falls through to today's
  `IosHeifImageDecoder` and schedules the sidecar write. `ProductThumbKeyer` needs no change.
- No new permission, no new dependency, no skiko/CMP change, no export-fidelity impact (the HEIC
  original remains the export source, preserving the `preferredItemEncoding: .current` decision).
- Risks to design for: invalidation (key on the app-owned path, which the import coordinator owns —
  and these are app temp files, so staleness is bounded by session lifetime); a visual check per
  ADR-0010 that a 256 px JPEG is indistinguishable at a 128 px cell; and cleanup alongside the
  existing temp-file lifecycle.
- **Also fixes the theme-seed path for free**, since `ContentEditorTheme.SEED_MAX_EDGE == 128`
  shares the same Coil entry.

### 4.2 🥈 `QLThumbnailGenerator` as the thumbnail producer — **might help; less work, less control**

- **Measured: 91–182 ms cold (cheaper than our own ImageIO thumbnail), 0.8–2.1 ms warm**, with an
  OS-owned persistent cache that survives relaunch (§2.3).
- No permission; works on plain file URLs; returns `CGImage` → feeds the *existing*
  `CGBitmapContextCreate`-into-Skia-buffer bridge unchanged.
- Costs: async completion-handler API to bridge from Kotlin/Native (`suspendCancellableCoroutine`
  over `generateBestRepresentation`); out-of-process XPC, so failures/timeouts are a new error
  surface; the cache is system-owned and can be purged without notice; use `.thumbnail`, **not**
  `.all` (§2.3 shows `.all` spiking to 39 ms).
- Strictly less predictable than 4.1, but a fraction of the code. Reasonable as a *complement*:
  QuickLook for the cold path, sidecar for the guaranteed-warm path.

### 4.3 🥉 Route JPEG/PNG thumbs through ImageIO with `SubsampleFactor` — **might help, small and clean**

Today JPEG/PNG go to Coil's `SkiaImageDecoder`, which full-decodes then Canvas-scales (§3.2). ImageIO
at factor 8 is **9.5 ms vs 25.4 ms** for the same JPEG (§2.3), ~2.7×.

- Contained: extend `IosHeifImageDecoder` (or add a sibling) to claim JPEG for the `ProductUi`
  policy. `allowSubsample = true` finally does something.
- Only ~15 ms per image, so it will not be visible next to a 200 ms HEIC — but it becomes the *whole*
  cost once 4.1 or 4.2 lands, and it makes the sidecar re-read faster too.

### 4.4 Prefetch / concurrency tuning — **mostly won't help**

`concurrentPerform` over 8 HEIC decodes gets **1.9–2.1×**; JPEG gets 6.7× (§2.3). ImageIO
substantially serialises HEIF. Prefetching ahead of the scroll can hide *some* latency, but the
aggregate first-pass cost (50 × 200 ms) is not reducible by scheduling. Worth doing only *after*
4.1/4.2, and then it is nearly free anyway.

### 4.5 `PHImageManager` / `PHCachingImageManager` Fetcher — **would help most; blocked on privacy**

Technically the best: Photos serves a pre-rendered derivative and never decodes the HEVC grid, and
`PHCachingImageManager.startCachingImages` is purpose-built for exactly a filmstrip. Coil-side it is
clean (`ImageFetchResult`, §3.2). **Blocked** on photo-library read authorization (§3.2.2), which
contradicts a stated product property. Do not implement without an ADR and an owner decision.

### 4.6 `DecodeToSDR` / `kCGComputeHDRStats=false` / `GenerateImageSpecificLumaScaling=false` — **might help; 2-line device A/B**

Inert on the two files probed (§2.2), but multiple independent reports tie gain-map tone mapping to
exactly this symptom shape ("extreme CPU at small sizes"), and an iPhone 16 Pro capture is more
likely to be gain-mapped than these samples. Cheap enough to just measure with the existing
`DEVICE_PERF_IO` harness before ranking it properly.

---

## 5. Explicit "won't help latency"

| Option | Why not |
|---|---|
| More `kCGImageSourceSubsampleFactor` tuning for HEIC | Honoured dimensionally, **inert temporally** — 216 vs 224 ms (§2.2/§2.3). Tiles decode regardless. |
| `kCGImageSourceCreateThumbnailFromImageIfAbsent` (embedded thumbnail) | Both probed camera HEICs report **`embeddedThumb: none`** (§2.3). The Phase-1 doc rejected it for unpredictable sizing; it is also simply absent. |
| `kCGImageSourceShouldCacheImmediately` | No measurable change (§2.2); one write-up disputes it does anything. Affects *where* the decode lands, not how long it takes. |
| Manual `createImage(f=8)` + CG scale | **Worse**: 401 ms vs 244 ms (§2.3). |
| Reusing an open `CGImageSource` per entry | ~10% (244 → 222 ms) for holding N open ImageIO sources. Bad trade. |
| Parallel decode of HEIC | 1.9–2.1× across 8 threads (§2.3). Does not change aggregate first-pass cost. |
| Further L1/L2/L3 copy elimination | `CGContextDrawImage` at 128 px is **0.12 ms** (§2.2). The Phase-1 doc already said the filmstrip copy budget is ~10 µs; measurement agrees. **Closed.** |
| Custom `coil3.Image` instead of `BitmapImage` | `Image.toBitmap` returns the same `Bitmap` on a property match (§3.2). Zero benefit, less-travelled painter. |
| Custom CGImage-backed `ImageBitmap` | `asSkiaBitmap()` **throws** for non-`SkiaBackedImageBitmap` (§3.3). Not possible. |
| `UIKitView` + `UIImageView` per cell | Possible, but trades a 0.12 ms raster for per-cell `UIView` interop in a `LazyRow`, and violates the CMP-first convention in `AGENTS.md` (§3.3). |
| `CVPixelBuffer` / `IOSurface` / `MTLTexture` → Skia | `BackendTexture` is **GL-only** in both Kotlin and skiko C++ (§3.5). Needs two upstream changes. |
| Enabling Coil's disk cache | Caches *encoded source bytes*. The source bytes are the expensive part. Does nothing here. |
| Enabling HEIF in Skia / skiko | No HEIF codec exists in mainline Skia; skia-pack sets no codec flags for iOS; `SkImageGenerator` (Android's mechanism) has no skiko binding (§3.1, §3.4). Upstream PR territory, not a local fix. |
| Transcoding the import copy to JPEG wholesale | Would defeat `preferredItemEncoding: .current` (issue 26 H1) and change exported pixels. A *thumbnail* sidecar (§4.1) gets the win without touching the export source. |

---

## 6. Open questions — device measurements needed before committing

1. **Are the user's real photos tiled?** Add a one-line probe next to `DEVICE_PERF_IO_SHAPE` that
   reports the grid structure (or at least `createImage f=1` vs `thumb@128` and the ratio to
   megapixels). If album HEIC is single-frame, none of §2.4 transfers and the whole diagnosis needs
   redoing on device.
2. **Does `DecodeToSDR` + `kCGComputeHDRStats=false` help on an iPhone 16 Pro capture?** (§4.6.)
   Order-balanced A/B through the existing harness; two lines of options.
3. **Sidecar quality at 128 px.** ADR-0010 requires *viewing* a filmstrip screenshot pair, not
   comparing byte sizes. 256 px JPEG q0.85 → 128 px cell should be invisible; prove it.
4. **QuickLook cold cost and cache durability on device.** 91–182 ms on macOS; iOS may differ, and
   the purge policy is undocumented. Also: does QL respect EXIF orientation identically to
   `WithTransform` (macOS said yes — `90x128` / `98x128`)?
5. **First-pass budget.** What is the actual product requirement — "50 photos filmstrip fully warm
   within N seconds of import"? Without that number, §4.1 vs §4.2 cannot be chosen on evidence.
6. **Does `kCGImageSourceSubsampleFactor` change the returned `bitmapInfo`?** Still open from the
   Phase-1 doc §10.4. Probe 1 says both plain and subsampled thumbs return
   `bpc=8 bpp=32 alpha=1 byteOrder=0 cs=kCGColorSpaceDisplayP3` on macOS — i.e. **identical shape,
   and Display P3, not sRGB**, which independently confirms the Phase-1 doc's prediction that the
   §5 format-match fast path would miss on camera photos.

---

## 7. Sources

**Skia (primary, `main`)**
- `src/codec/SkCodec.cpp` — decoder registry (no HEIF; AVIF only under `SK_CODEC_DECODES_AVIF`) — <https://github.com/google/skia/blob/main/src/codec/SkCodec.cpp>
- `BUILD.gn` — `optional("ndk_images")` → `SkImageGeneratorNDK.cpp`, `libs = [ "jnigraphics" ]`; zero `heif` matches — <https://github.com/google/skia/blob/main/BUILD.gn>
- `gn/skia.gni` — `skia_use_ndk_images = is_android && defined(ndk_api) && ndk_api >= 30`; `skia_use_libavif = false`; `skia_use_crabbyavif = false`; `skia_use_libjxl_decode = false` — <https://github.com/google/skia/blob/main/gn/skia.gni>
- `include/codec/SkCodec.h` — no HEIF surface — <https://github.com/google/skia/blob/main/include/codec/SkCodec.h>

**JetBrains skia-pack / skiko**
- `script/build.py` — the complete iOS GN arg list; no codec flags outside the wasm branch — <https://github.com/JetBrains/skia-pack/blob/master/script/build.py>
- `BackendTexture.cc` — `MakeGL` only, no Metal — <https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/BackendTexture.cc>
- `BackendRenderTarget.cc` — `BackendRenderTarget_nMakeMetal` under `#ifdef SK_METAL` — <https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/BackendRenderTarget.cc>
- skiko#942, HEIC unsupported in `Image.makeFromEncoded` (closed 2024-06-16) — <https://github.com/JetBrains/skiko/issues/942>
- Local artifact read in full: `skiko-iossimulatorarm64-0.150.0-sources.jar` — `Image.kt`, `Image.darwin.kt`, `Codec.kt`, `BackendTexture.kt`, `BackendRenderTarget.kt`, `DirectContext.kt`, `uikitMain/.../MetalRedrawer.uikit.kt`; full-text searches for `ImageGenerator` / `AndroidCodec` / `SkCodecs` / `IOSurface` / `CVPixelBuffer` / `MTLTexture` return nothing

**Compose Multiplatform**
- Local artifact: `ui-graphics-iossimulatorarm64-1.12.0-beta01-sources.jar` — `SkiaImageAsset.skiko.kt` (`asSkiaBitmap()` throws), `SkiaBackedCanvas.skiko.kt:46,296`, `SkiaShader.skiko.kt:126`, `ImageBitmap.skiko.kt`
- Local artifact: `ui-iossimulatorarm64-1.12.0-beta01-sources.jar` — `interop/UIKitView.ios.kt`, `viewinterop/UIKitInterop*.ios.kt`
- Upstream — <https://github.com/JetBrains/compose-multiplatform-core>

**Coil 3.5.0**
- Local artifacts: `coil-core-3.5.0-sources.jar` — `appleMain/coil3/RealImageLoader.apple.kt`, `commonMain/coil3/RealImageLoader.kt` (`addCommonComponents`), `nonAndroidMain/coil3/RealImageLoader.nonAndroid.kt`, `nonAndroidMain/coil3/decode/SkiaImageDecoder.kt`, `nonAndroidMain/coil3/util/utils.nonAndroid.kt` (`Bitmap.makeFromImage`), `nonAndroidMain/coil3/Image.nonAndroid.kt`, `commonMain/coil3/fetch/FetchResult.kt`, `commonMain/coil3/fetch/Fetcher.kt`, `commonMain/coil3/fetch/BitmapFetcher.kt`
- coil#2318, HEIC on CMP (closed 2024-06-19, "external `Decoder.Factory`") — <https://github.com/coil-kt/coil/issues/2318>

**Apple (SDK headers + docs)**
- `iPhoneOS27.0.sdk/System/Library/Frameworks/ImageIO.framework/Headers/CGImageSource.h` — `kCGImageSourceSubsampleFactor` ("Supported file formats are JPEG, HEIF, TIFF, and PNG"; "If the specified scaling factor is not supported, a larger or full size normal image will be returned"), `kCGImageSourceShouldCacheImmediately` (default false), `kCGImageSourceDecodeRequest` / `kCGImageSourceDecodeToSDR` (iOS 17+), `kCGImageSourceGenerateImageSpecificLumaScaling` (iOS 18+, default true)
- `kCGImageSourceSubsampleFactor` — <https://developer.apple.com/documentation/imageio/kcgimagesourcesubsamplefactor>
- `CGImageSourceCreateThumbnailAtIndex` — <https://developer.apple.com/documentation/imageio/cgimagesourcecreatethumbnailatindex(_:_:_:)>
- `PhotosPickerItem.itemIdentifier` — <https://developer.apple.com/documentation/photosui/photospickeritem/itemidentifier>
- Delivering an enhanced privacy experience in your Photos app — <https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app>
- Developer Forums 823256 (itemIdentifier needs library access; prefer copying bytes) — <https://developer.apple.com/forums/thread/823256>
- Developer Forums 807143 (itemIdentifier nil without `photoLibrary:`; needs read authorization) — <https://developer.apple.com/forums/thread/807143>
- Developer Forums 819953 (HDR `NSImage` pathological CPU at small sizes; `kCGImageSourceDecodeToSDR`) — <https://developer.apple.com/forums/thread/819953>
- `QLThumbnailGenerator` — <https://developer.apple.com/documentation/quicklookthumbnailing/qlthumbnailgenerator>

**Ecosystem**
- Rork Lab, *Killing Thumbnail Scroll Jank in an iOS Wallpaper App* (ImageIO downsampling recipe) — <https://rorklab.net/en/articles/app-dev/ios-thumbnail-downsampling-scroll-performance>
- Michael Tsai, *Fast Thumbnails With CGImageSource* (+ comment: "QLThumbnailGenerator is invaluable for thumbnailing, particularly because it caches the results") — <https://mjtsai.com/blog/2026/04/19/fast-thumbnails-with-cgimagesource/>
- JuniperPhoton, *Inspect & optimize Image Decoding timing in iOS* (disputes `ShouldCacheImmediately`) — <https://juniperphoton.substack.com/p/inspect-and-optimize-image-decoding>
- Rio Ogino, *Adventures in EDR, Part 1* (gain-map render ~30 ms vs ~3 ms; Apple-captured HEIC uses gain-map HDR) — <https://rioogino.com/posts/2024/edr_1_photos/>
- Prodopsy, *How to Stop HDR Decoding Stutter* (`DecodeToSDR` + `kCGComputeHDRStats: false`) — <https://prodopsy.com/how-to-disable-hdr-drawing-of-nsimage/>
- FileKit #555 (same HEIC/Skia gap, solved by asking PHPicker for a compatible representation) — <https://github.com/vinceglb/FileKit/issues/555>

**Repo context**
- `docs/superpowers/research/2026-08-14-ios-cgimage-skia-zero-copy-plan.md` (L0–L3 vocabulary; Phase 1 landed in `60bf8ff2`; "the filmstrip is not worth optimising for copies" — now measured, and correct)
- `docs/superpowers/research/2026-08-14-ios-preview-perf-leftovers.md` (S1 decode ≈ 94% of a cold switch; S3 `io128_med` 202 ms vs `io1920_med` 138 ms)
- `docs/superpowers/research/2026-08-13-product-thumb-coil-ab.md`; ADR-0028 (Coil 3 for UI thumbs); ADR-0010 (verify renders by viewing); ADR-0021 (path-first import)

**Throwaway probe sources** (not committed; recreate from the tables above if needed)
- `/tmp/heicprobe/main.swift` — option-set sweep, layout dump, embedded-thumbnail check, subsample-honoured check
- `/tmp/heicprobe/probe2.swift` — stage split, `createImage` vs `thumb`, warm source, sequential vs concurrent
- `/tmp/heicprobe/probe3.swift` — `QLThumbnailGenerator` cold/warm, sidecar write + re-read
- Host: Apple M5 Pro, macOS 27.0 (26A5406e), Xcode 27.0 beta SDK, `swiftc -O`, `reps = 7`, median
