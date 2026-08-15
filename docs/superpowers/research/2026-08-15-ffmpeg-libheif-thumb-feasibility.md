# Can FFmpeg / libheif / libde265 / dav1d beat Apple ImageIO for the iOS filmstrip 128 px HEIC thumb?

**Date:** 2026-08-15
**Question:** the device fact from `2026-08-14-ios-preview-perf-leftovers.md` S3 — on an iPhone 16 Pro, album HEIC,
`io128_sub ≈ 198 ms` is *slower* than `io1920_sub ≈ 124 ms`. Smaller output, more time. Is a self-shipped
C++ HEIF stack the way out?
**Answer: don't ship it.** Not for this problem, not on iOS, and not with an MIT app that also ships on F-Droid.

---

## Verdict first

| | |
|---|---|
| **Ship libheif+libde265 on iOS?** | **No.** LGPLv3 is the blocker before performance is even discussed, and the performance case does not hold on the untiled majority. |
| **Ship FFmpeg on iOS?** | **No, and it does not currently work.** FFmpeg 9.0.1 cannot produce an assembled image from a tiled iPhone HEIC at all — verified below, on a real file, on this machine. |
| **Is the 198 ms real?** | **Yes, and it reproduces off-device.** But the cause is not "Apple's codec is slow". It is "ImageIO has no cheap small-output path for HEIC, and the app pays that full cost again on every cold launch". |
| **Best available move** | Stop paying the decode repeatedly (disk cache is currently **off**), not swap the decoder. |

The strongest argument against the FFmpeg/libheif route is not that it's slower. On one file class it is genuinely
faster, and I say so below with numbers. It's that the licence forecloses it, the win does not generalise, and the
actual defect — the app re-decodes the same 128 px thumbnail from scratch on every cold launch — costs nothing to fix
and dominates any codec delta.

---

## What I measured, and where

All numbers below are **macOS 27 / Apple Silicon**, not the iPhone. Same CPU architecture family and the same ImageIO
implementation lineage, so relative behaviour transfers; absolute milliseconds do not. Treat this as
"which direction does the cost move", not "what the device will report".

- ImageIO: Swift harness calling `CGImageSourceCreateThumbnailAtIndex` directly, medians of 8–12 runs, warm file cache,
  `kCGImageSourceShouldCache: false`.
- libheif 1.23.1 + libde265 1.1.1 (Homebrew arm64), C harness calling `heif_decode_image` directly, median of 10.
  Homebrew's build is a generic release build, not a hand-tuned one — a bespoke build could plausibly do better,
  though libde265 has no NEON-heavy fast path comparable to what Apple's hardware block does.
- FFmpeg 9.0.1 (Homebrew, `--enable-gpl`), CLI + `ffprobe`.
- Files: two real HEICs off a phone lineage (3273×4265 and 3158×4501, both 7×9 grids of 512×512 tiles, both carrying an
  ISO gain map), plus the repo's own `sample-images/formats/fmt_1036.heic` (3200×2133, untiled, no gain map).

### The anomaly reproduces

Real tiled HEIC with gain map, `CGImageSourceCreateThumbnailAtIndex` at varying `kCGImageSourceThumbnailMaxPixelSize`:

| requested max edge | median | output |
|---:|---:|---|
| 128 | 267 ms | 98×128 |
| 256 | 205 ms | 196×256 |
| 512 | 253 ms | 392×512 |
| 1024 | 243 ms | 786×1024 |
| 1440 | 190 ms | 1106×1440 |
| 1920 | 202 ms | 1474×1920 |
| 2560 | 166 ms | 1964×2560 |
| *(no limit)* | 196 ms | 3273×4265 |

The device observation is not a measurement artefact. Asking ImageIO for a *smaller* HEIC thumbnail is, if anything,
slightly *more* expensive, and the whole curve is flat-to-inverted within noise. There is no requested size at which
this becomes a cheap operation. The sweep is noisy and non-monotonic — I would not build a policy on the individual
rows — but the shape is unambiguous: **the requested size is close to irrelevant; you are paying a fixed full decode
every time.**

### `kCGImageSourceSubsampleFactor` appears to be a no-op for HEIC

| file | 128, no subsample | 128, subsample 8 |
|---|---:|---:|
| tiled HEIC + gain map | 267 ms | 267 ms |
| `fmt_1036.heic` (untiled) | 41.7 ms | 41.6 ms |

Output dimensions were identical in every arm, and so was the time. On JPEG the option does real work; on these HEICs
it does nothing measurable. This matters for the repo directly: the S3 fix in
`2026-08-14-ios-preview-perf-leftovers.md` added `kCGImageSourceSubsampleFactor`. **On-device close-out
(2026-08-14 night, `build/ios-device-shots/s3-subsample-verify/ewm-device-perf.txt`):** same 8 album
HEICs, order-balanced plain vs sub — `io128_med=183` vs `io128_sub_med=198` (**no win**),
`io1920` 143→124 (small win); `DEVICE_PERF_IO_SHAPE` dims always match. That matches this off-device
finding: **for these HEICs the subsample fix is effectively inert at 128**. `IosImageIOSubsampleTest`
proves dimensions are preserved; it never proved the reduction moved into the decoder.

That is a finding worth acting on independently of anything in this document: the S3 code is not wrong, it is just not
buying anything on the format it was aimed at.

### libheif vs ImageIO, same file, same machine

| file | ImageIO → 128 px | libheif full decode | libheif "thumbnail" |
|---|---:|---:|---:|
| tiled HEIC + gain map, 3273×4265 | 240–267 ms | **119 ms** | 122 ms *(no thumbnail item exists)* |
| `fmt_1036.heic`, 3200×2133, untiled | **41.7 ms** | 206 ms | 206 ms *(same)* |

Read this honestly, because it cuts both ways.

On the modern tiled-plus-gain-map file, **libheif's base-layer decode is about twice as fast as ImageIO's thumbnail
call.** That is a real result and it is the strongest thing the FFmpeg/libheif hypothesis has going for it.

But it is not the same job. libheif decodes the 63-tile HEVC grid and stops. ImageIO additionally handles the ISO gain
map, the display colour transform, the `Headroom` property, and the resample down to 98×128 — all of which libheif
leaves to you, and the resample is the part the measurements say is not free. Requesting
`kCGImageSourceDecodeRequest: kCGImageSourceDecodeToSDR` — i.e. explicitly asking ImageIO to skip HDR reconstruction —
recovered only about 10% (240 ms vs 258 ms), so gain-map handling is a contributor but not the whole gap.

And on the untiled file, which is the entire rest of the world's HEIC and everything not from a recent iPhone camera,
**ImageIO is ~5× faster than libheif.** You would be shipping 2 MB of LGPL code that makes the common case five times
worse to make one case twice as good, and then paying the resample back on the fast case anyway.

---

## The six questions

### 1. Can FFmpeg or libheif decode only a thumbnail tile / grid / preview item, skipping the full HEVC frame?

**In principle yes. On the actual input, there is nothing to decode.**

libheif has exactly the API you would want — `heif_image_handle_get_number_of_thumbnails`,
`heif_image_handle_get_list_of_thumbnail_IDs`, `heif_image_handle_get_thumbnail` — and Apple's own WWDC17 session 513
describes iPhone HEIC as a 512×512 tile grid plus **a 320×240 HEVC-encoded thumbnail item** linked by a `thmb`
reference, chosen at 4× the size of a classic 160×120 JPEG thumbnail specifically for dense displays.

That description is now out of date. On both real phone-lineage HEICs I probed, and on the repo's own HEIC fixtures,
`heif_image_handle_get_number_of_thumbnails` returned **0**. `heif-thumbnailer -s 128` still emits a 98×128 PNG — by
silently decoding the full 14 MP grid and scaling it, at full-decode cost. `ffprobe` agrees: 83 streams, 63 of them in
one `Tile Grid` stream group, no stream tagged as a thumbnail item.

Apple's own ImageIO agrees too. `kCGImageSourceCreateThumbnailFromImageIfAbsent` — the option whose entire purpose is
"give me the embedded thumbnail if there is one" — returned the same 98×128 at the same cost as `...FromImageAlways`.
If a `thmb` item were present, IfAbsent would have returned it at its native size, which is the documented failure mode
SDWebImage hit in [PR #3038](https://github.com/SDWebImage/SDWebImage/pull/3038) (asked for 400, got the embedded 120×160).
It didn't, because there isn't one.

So the central premise of the plan — "skip the full frame, grab the little one" — has no little one to grab. Photos.app
feels instant scrolling thousands of images because it keeps its own thumbnail database, not because it re-reads a
`thmb` box.

**Caveat on n:** two real files, both plausibly re-encoded on their way out of Photos / out of a third-party camera app.
A straight-off-the-camera original might still carry a `thmb`. If someone wants to falsify this, it is a five-minute
check — `heif-info` on an unexported original — and it would change the picture for question 1 only. It would not
change the licence answer.

### 2. Typical mobile binary size for libheif + libde265 (+ optional encoder)

Measured on arm64 dylibs from Homebrew (macOS arm64 is a good size proxy for iOS arm64):

| library | dylib | `__TEXT` | needed for HEIC decode? |
|---|---:|---:|---|
| libheif 1.23.1 | 1.67 MiB | 1.00 MiB | yes |
| libde265 1.1.1 | 0.34 MiB | 224 KiB | yes (HEVC decode) |
| dav1d 1.5.4 | 0.79 MiB | 656 KiB | **no** — AV1/AVIF only, irrelevant to HEIC |
| libx265 | 7.13 MiB | — | no (encoder, and **GPL**) |
| libaom | 3.91 MiB | — | no |
| `libheif.a` static, pre-dead-strip | 4.72 MiB | — | — |

**Decode-only realistic cost: ~2 MB added to the iOS arm64 slice.** App Store delivery is thinned to one architecture,
so it's ×1, not ×2. Android would need it per ABI — arm64-v8a plus armeabi-v7a plus x86_64 is ~5–6 MB of APK growth
unless split by ABI, and Android does not need it at all (see question 5).

FFmpeg for reference, full Homebrew build: libavcodec 9.28 MiB + libavformat 2.26 + libavutil 0.61 + libswscale 0.66 ≈
**12.8 MiB**. A minimal `--disable-everything --enable-decoder=hevc --enable-demuxer=mov` build would land far lower —
plausibly 1.5–3 MB — but see question 4 for why the number doesn't matter.

2 MB is not fatal on its own. It is fatal relative to what it buys, which on the untiled majority is negative.

### 3. KMP FFI cost: cinterop / XCFramework vs calling ImageIO from Kotlin today

**The per-call FFI cost is not the argument. The build-system cost is, and it is large.**

Kotlin/Native cinterop compiles to direct C calls. Passing a `CPointer` to a pixel buffer is free; there is no
marshalling layer to blame. If libheif were fast and legal, the FFI would not stand in the way.

What stands in the way is that the current arrangement costs *nothing*:

- `IosImageIODecoder` already calls `platform.ImageIO.*` straight from `iosMain`. Those bindings ship with
  Kotlin/Native as part of the Apple SDK. Zero build configuration, zero vendored binaries, zero CI minutes.
- `shared/build.gradle.kts` builds a classic ObjC dynamic `Shared.framework` for `iosArm64` + `iosSimulatorArm64`
  (J5 policy: no experimental Swift export). Adding a native dep means a `.def` file, CMake cross-compiles of libheif
  **and** libde265 for device *and* simulator, `linkerOpts` on both framework targets, and matching link settings in
  the Xcode project that consumes the framework.
- Then it has to survive CI. `pr_pre_check.yml`'s macOS `ios` job would either build two codecs from source on every
  PR, or consume committed prebuilt `.a` files.
- Then it has to be done twice more if the goal is one KMP codebase: **Android via NDK, Desktop via JNI/JNA** —
  Kotlin/JVM cannot use cinterop. Three separate native toolchains for one thumbnail path.

There is exactly one place where this cost buys a capability rather than milliseconds, and it deserves to be said
plainly: **Desktop cannot open HEIC at all today.** `DesktopImageDecoder` is AWT `ImageIO`, which has no HEIF plugin;
there is not a single `heic`/`heif` reference anywhere in `desktopMain`. If EasyWatermark ever wants to watermark an
iPhone photo on the desktop app, libheif is the only realistic option. That is a *feature* decision about Desktop,
argued on its own merits, under its own licence analysis, on the JVM via JNI — not a reason to put a codec on iOS,
where the platform decoder already works.

### 4. Does FFmpeg VideoToolbox hwaccel help still-image HEIC thumbs, or only video?

**Neither, in the sense that matters — but the honest answer is "the question is moot, because FFmpeg cannot do this job today."**

Run against the real 3273×4265 tiled HEIC with FFmpeg 9.0.1:

```
$ ffmpeg -i probe.heic -vf scale=128:-1 out.png
[vost#0:0/png] Filtergraph 'scale=128:-1' was specified for a stream fed from a
complex filtergraph. Simple and complex filtering cannot be used together...
Error opening output files: Invalid argument

$ ffmpeg -i probe.heic -map "0:g:0" out.png     # documented tile-grid mapping
[image2] Cannot write more than one file with the same name...
  → wrote a 512×512 tile, not the photo
```

There is no `heif` demuxer (`-demuxers` lists only `image2`/`image2pipe`; HEIF rides the `mov` demuxer). The
[2024 tile-grid work](https://ffmpeg.org/pipermail/ffmpeg-cvslog/2024-February/141015.html) added
`AV_STREAM_GROUP_PARAMS_TILE_GRID` so libavformat *exports* the 63 tiles and their offsets — deliberately leaving
reassembly to the caller. This matches the independent finding in
[stashapp/stash#6732](https://github.com/stashapp/stash/issues/6732): "FFmpeg 8 has no HEIF demuxer… cannot be used as
a fallback for HEIC", and that project routed HEIC to libvips+libheif instead.

So using FFmpeg here means writing your own grid compositor over 63 `AVStream`s. At that point you have written the
interesting half of libheif and inherited its licence anyway.

On VideoToolbox specifically, three reasons it is not the lever:

1. FFmpeg's VideoToolbox path is an `AVHWAccel` around `VTDecompressionSession`, built for a *stream* of frames with a
   stable format description. A still image is one frame; you pay session setup per photo and amortise it over nothing.
   Every source on session management says the same thing — reuse sessions, recreation is the expensive part — and a
   filmstrip of differently-sized photos is the pathological case for that.
2. It decodes to a `CVPixelBuffer` in a hardware format. You still owe a colour convert and a resample to 128 px,
   which the measurements above identify as a non-trivial share of the cost you were trying to avoid.
3. **ImageIO already uses that same hardware block.** SDWebImage's own documentation states Apple's ImageIO does
   hardware-accelerated HEIF decoding on A9+. Routing through FFmpeg to reach VideoToolbox is a longer path to the
   identical silicon. The ceiling is "match ImageIO", and the realistic outcome is below it.

### 5. Android parallel — and why Android doesn't have this problem

`ProductThumbFetcher.android.kt` already does the right thing, and it is instructive about what iOS is missing:

```57:61:shared/src/androidMain/kotlin/me/rosuh/easywatermark/ui/image/ProductThumbFetcher.android.kt
private fun loadThumbBitmap(context: Context, uri: Uri, sizePx: Int): Bitmap? {
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            val thumb = context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            if (thumb != null && !thumb.isRecycled) return thumb
```

On API 29+, `ContentResolver.loadThumbnail` returns a thumbnail **MediaStore already generated and cached**. Android
does not decode the HEIC at all in the common path. Below 29 it uses `MediaStore.Images.Thumbnails` with `RGB_565`,
again a cached artefact. Only the app-private / non-MediaStore fallback does a real `BitmapFactory` decode, and that one
uses `inSampleSize` — which, unlike ImageIO's `kCGImageSourceSubsampleFactor` on HEIC, actually works.

`BitmapRegionDecoder` is not relevant here: it's for cropping a region out of a large image, not for cheap whole-image
reduction. `MediaCodec` would be the Android analogue of the VideoToolbox idea and fails for the same reasons.

**The asymmetry is the whole story.** Android gets a system-maintained thumbnail cache for free through MediaStore.
iOS's equivalent is the Photos thumbnail database, reachable via `PHImageManager`/`PHCachingImageManager` — and the
app deliberately cannot reach it, because ADR-0021's path-first PHPicker import takes a file copy into app tmp with no
photo-library permission and no retained `localIdentifier`. That is a *correct* privacy trade, and it is precisely
what makes iOS pay a decode where Android pays a lookup.

Which reframes the problem correctly: **iOS is missing a thumbnail cache, not a codec.** The app should supply the
cache it gave up, not import a decoder.

### 6. Why apps abandon custom HEIF stacks on iOS

The most direct precedent is SDWebImage, the most widely deployed iOS image library, which maintains
[`SDWebImageHEIFCoder`](https://github.com/SDWebImage/SDWebImageHEIFCoder) — a libheif+libde265 coder — and tells you in
its own README not to use it:

> Apple's Image/IO framework supports Hardware-Accelerated HEIF decoding (A9+ chip)… This coder is used for
> backward-compatible solution. And the codec only do Software decoding / encoding, which is slower than Image/IO.
> So if possible, choose to use Image/IO (SDWebImage's built-in coder) firstly.

Its usage example is an `@available(iOS 11.0, *)` check that installs the libheif coder only on iOS 9–10. The library
that did the integration work concluded the integration should not be used on any currently supported OS.

Two supporting threads. Perceived libheif slowness has been a running complaint —
[HN discussion](https://news.ycombinator.com/item?id=25704624) claims libheif is >50× slower than Apple's decoder,
which my numbers do *not* support at that magnitude (5× on the untiled file, and libheif won on the tiled one), so
treat that as folklore rather than evidence. And on the other side, `libheif` 1.19+ is a moving target with real
correctness churn: stash's issue notes "earlier versions may crash on iPhone 16 photos or produce wrong pixel values on
tiled HEVC grids". A vendored C++ HEVC parser processing untrusted photo files is an ongoing CVE-watch obligation for a
project that currently has zero native dependencies to patch.

---

## Legal — this is the part that ends the discussion

EasyWatermark is **MIT** (`LICENSE`, © 2020 rosuH). Everything in the candidate stack is copyleft:

| component | licence | consequence |
|---|---|---|
| libheif | **LGPLv3** | forces LGPL relinking obligations |
| libde265 | **LGPLv3** | same |
| FFmpeg (default) | **LGPL 2.1+** | same family; `--enable-gpl` (needed for x264/x265) makes it **GPL** |
| x265 / kvazaar | GPL / BSD | encoder only — not needed for decode |
| dav1d | BSD-2 | permissive, but AV1 only, useless for HEIC |

Three concrete problems, in descending order of how quickly they kill the idea:

**1. LGPL on the App Store is contested and has been enforced against.** LGPL §4 requires that the end user be able to
modify the library and relink the application. Apple's App Store terms do not permit a user to install a relinked
binary. This is not theoretical: the FSF pursued a GNU Go port, and in January 2011 Apple removed VLC from the App
Store after VLC contributor Rémi Denis-Courmont filed a copyright complaint on exactly this basis. Apple's resolution
was to delete the app rather than change its rules.

**2. The libheif author has stated the specific conclusion for exactly this shape of project.** In
[heic2any#59](https://github.com/alexcorvi/heic2any/issues/59), an MIT-licensed project bundling libheif was told:

> You also have to change your license from MIT to GPL as there is no way for the end user to replace libheif.

That is an MIT app statically bundling libheif being told to relicense. It is the same fact pattern. Compliance would
mean shipping libheif and libde265 as *separately replaceable dynamic* frameworks plus publishing relinkable object
files — which is significant work, still contested on iOS, and buys nothing the app wants.

**3. Patents are a separate axis from licence.** FFmpeg's own legal FAQ is blunt: the licence grants no patent rights,
and "MPEG LA is vigilant and diligent about collecting for MPEG-related technologies." Today HEVC decoding rides on
Apple's platform licence. Shipping your own HEVC decoder moves that exposure onto the app — for a **paid** Google Play
SKU, which is precisely the commercial-distribution posture patent pools notice.

**4. F-Droid forbids the escape hatch.** The [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) requires
everything be built from source with a 100% FLOSS toolchain; prebuilt binaries are only accepted from Debian main or
whitelisted Maven repos. So "vendor prebuilt `.a` files" is not available — F-Droid would have to cross-compile libheif
and libde265 for every ABI as part of the app build. Doable (F-Droid does build FFmpeg from source for some apps) and a
permanent maintenance liability for a benefit Android does not need at all.

**Any one of these is disqualifying. All four together are not a close call.**

---

## Ranked alternatives

Ordered by expected value per unit of risk. Nothing above rank 4 requires a new dependency.

### 1 — Turn on the disk cache (highest value, near-zero risk, currently free money)

Every product image request in the codebase sets `diskCachePolicy(CachePolicy.DISABLED)` —
`ProductImageLoader.productThumbDefaults()`, `ProductAsyncImage`, `RememberProductThumbBitmap`. Memory-only.

That means the 198 ms is paid **on every cold launch, for every image, forever**, and again after any memory eviction.
The measured cost of a filmstrip is not one decode, it is one decode per app start.

A 128 px thumbnail is a few tens of KB encoded. A 50-image session is single-digit MB on disk. Coil 3 supports a disk
cache on iOS out of the box. This converts the problem from "make one decode faster" to "do the decode once ever",
which is a strictly larger win than any codec swap could deliver, and it is a policy change plus a cache-key review.

The one thing to get right is privacy, and it deserves care rather than assumption: a persistent thumbnail cache is
durable derived imagery of the user's photos sitting in app storage, which is a genuine change in posture for an app
whose pitch is that it holds nothing. It needs a size cap, eviction, and clearing on session end. That is a design
conversation and probably an ADR — but it is *this app's* design conversation, not a 2 MB LGPL dependency.

### 2 — Confirm the subsample no-op on device, then delete or re-scope S3

The evidence above says `kCGImageSourceSubsampleFactor` does nothing for HEIC. If that holds on device, the
`ProductUi` policy is carrying complexity and a test that pins a property nobody benefits from. Confirm with the
existing bench, then either remove `allowSubsample` from the `ProductUi` path or restrict it to JPEG, where it works.
Cheap, and it stops the codebase asserting a win it doesn't have.

### 3 — Derive the filmstrip thumb from a decode the app already did

`IosPreviewWorkingSet` already holds decoded source frames for focus ±2, and S1 established that a decode is ~215 ms
against a ~7 ms compose. A 128 px filmstrip cell scaled in Skia from an already-resident 1920 px frame is a sub-
millisecond operation on a buffer already in memory. The filmstrip currently ignores that and asks ImageIO for an
independent second decode of the same file.

This does not cover images outside the working set, so it is a complement to rank 1, not a replacement. But it removes
the redundant decode for exactly the frames the user is most likely to touch next.

### 4 — Re-examine `QLThumbnailGenerator`

`QuickLookThumbnailing` generates thumbnails from a file URL with no photo-library permission, and can return
system-cached representations — closer to Android's MediaStore lookup than to a raw decode. It is the only Apple API in
this space the codebase has not evaluated. Unknown whether the system cache helps for app-tmp copies (it may well not,
since the file is new each import), which is why it is rank 4 and framed as a measurement, not a plan. Cost to find
out: one afternoon and zero dependencies.

### 5 — Request a larger ImageIO thumbnail and downscale in Skia

The sweep shows 2560 px at 166 ms against 128 px at 267 ms on the tiled file. If that holds on device, asking for a
large thumbnail and reducing in Skia could be ~30% cheaper than asking for 128 directly. Ranked low deliberately: the
sweep is noisy and non-monotonic, the direction is counter-intuitive enough that I do not trust it without device
confirmation, and it trades a memory spike (a full-size buffer per filmstrip cell) for latency on a platform that gets
jetsammed — which is the opposite of the trade S5 just made. Investigate, don't adopt.

### 6 — libheif, scoped to Desktop only, as a capability

Not for iOS. Not for performance. Only if the product decides Desktop must open HEIC at all, which it currently cannot.
Separate decision, separate ADR, JNI not cinterop, and the LGPL analysis is materially easier for a desktop app
distributed outside the App Store — dynamic linking with replaceable libraries is straightforwardly compliant there.

### 7 — FFmpeg, anywhere in this app

No. It cannot assemble a tiled iPhone HEIC today, it has no thumbnail-item shortcut to offer, its hardware path is a
longer route to the decoder ImageIO already uses, and it brings the same copyleft problem in a 12.8 MB package.

---

## What would change the verdict

Stated so this document can be falsified rather than merely believed:

- **A straight-off-camera iPhone original that does carry a `thmb` item.** Would revive question 1 for that file class.
  Would not touch the licence, and ImageIO's `...FromImageIfAbsent` would get the same win with zero dependencies —
  so even then, the answer is an ImageIO option, not libheif.
- **On-device numbers contradicting the macOS shape**, i.e. `kCGImageSourceSubsampleFactor` measurably working for HEIC
  on iOS 26. Then S3 is real, the 198 ms has a cheaper Apple answer, and this whole question dissolves.
- **A permissively-licensed HEIF decoder.** BSD/MIT/Apache-licensed, actively maintained, HEVC grid support. Would
  remove objection 1 and leave only the patent question and the 5×-slower-on-untiled result. I am not aware of one.
- **Desktop HEIC support becoming a committed product requirement.** Changes rank 6 from "not now" to "scope it" —
  and still says nothing about iOS.

## Sources

- Apple, WWDC17 session 513, *High Efficiency Image File Format* — 512×512 tile grid, 320×240 HEVC `thmb` item.
- libheif: [COPYING](https://github.com/strukturag/libheif/blob/master/COPYING) (LGPLv3), thumbnail API in
  `libheif/api/libheif/heif.h`; maintainer position in [heic2any#59](https://github.com/alexcorvi/heic2any/issues/59).
- [SDWebImageHEIFCoder README](https://github.com/SDWebImage/SDWebImageHEIFCoder) — "slower than Image/IO", iOS <11 only.
- [SDWebImage PR #3038](https://github.com/SDWebImage/SDWebImage/pull/3038) — `FromImageIfAbsent` returning tiny
  embedded thumbnails.
- FFmpeg [LICENSE.md](https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md) and
  [legal FAQ](https://ffmpeg.org/legal.html); [tile-grid HEIF commit](https://ffmpeg.org/pipermail/ffmpeg-cvslog/2024-February/141015.html);
  [stashapp/stash#6732](https://github.com/stashapp/stash/issues/6732) (FFmpeg 8 cannot handle tiled HEIC).
- [FSF on VLC / App Store](https://www.fsf.org/blogs/licensing/vlc-enforcement);
  [Ars Technica, Jan 2011](https://arstechnica.com/gadgets/2011/01/vlc-for-ios-vanishes-2-months-after-eruption-of-gpl-dispute/).
- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/).
- Apple, [`VTDecompressionSessionCreate`](https://developer.apple.com/documentation/videotoolbox/vtdecompressionsessioncreate(allocator:formatdescription:decoderspecification:imagebufferattributes:outputcallback:decompressionsessionout:)).
- Repo: `2026-08-14-ios-preview-perf-leftovers.md` (S1/S3/S5), `2026-08-13-product-thumb-coil-ab.md`, ADR-0021,
  ADR-0028, `ProductThumbFetcher.android.kt`, `IosImageIODecoder.kt`, `ProductImageLoader.kt`, `shared/build.gradle.kts`.
- Measurements: local harnesses on macOS 27 / Apple Silicon against libheif 1.23.1, libde265 1.1.1, FFmpeg 9.0.1.
  Not device numbers; see the caveat at the top of *What I measured*.
