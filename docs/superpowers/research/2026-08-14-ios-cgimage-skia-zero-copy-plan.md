# iOS CGImage → Skia: removing the pixel copies (2026-08-14)

Research + implementation plan for `IosImageIODecoder`'s `CGImage → Skia` hand-off. Scope is the
ImageIO decode paths only: filmstrip HEIC (Coil), editor preview, and — a surface the original
brief did not name — **final export**, which turns out to be the worst offender.

**Status (2026-08-14):** Phase 0 compile spike + Phase 1 production path landed in
`IosImageIODecoder` / HEIF `IosImageDecoder` call sites (`allocPixels` /
`Data.makeUninitialized` + `interpretCPointer` → `CGBitmapContextCreate`;
`decodeThumbnail` / HEIF ImageBitmap ends via `asComposeImageBitmap`). **A/B numbers:**
`docs/superpowers/research/2026-08-14-ios-cgimage-skia-transfer-ab.md`
(`IosCgImageTransferAbBenchTest`). Phase 2 format-match skip-Draw remains deferred.
Everything below remains the research record that justified that change.

---

## 1. Problem statement

### 1.1 The current copy stack

`IosImageIODecoder.copyCgImagePixels` allocates a Kotlin `ByteArray`, pins it, creates a
`CGBitmapContext` over the pinned address (forced sRGB + `kCGImageAlphaPremultipliedFirst` +
`kCGBitmapByteOrder32Little`), and calls `CGContextDrawImage`. That array is then handed to Skia.

```386:427:shared/src/iosMain/kotlin/me/rosuh/easywatermark/render/IosImageIODecoder.kt
    private fun copyCgImagePixels(image: CGImageRef): RgbaPixels {
        val width = CGImageGetWidth(image).toLong()
        val height = CGImageGetHeight(image).toLong()
        // ...
        val pixels = ByteArray(byteCount.toInt())
        // ... CGBitmapContextCreate(data = pinned.addressOf(0), ...) + CGContextDrawImage
    }
```

What happens *after* that is the part that was not previously accounted for. Both Skia entry points
we use copy again, and the Compose bridge copies a third time. All three are verified against
skiko's own C++ (see §3).

| Surface | Kotlin call chain | Full-frame writes |
|---|---|---|
| **Filmstrip HEIC** (Coil) | `decodeThumbnailBitmapWithMetadata` → `cgImageToSkiaBitmap` → `Bitmap.installPixels(ByteArray)` → `bitmap.asImage()` | **2** |
| **Preview** | `decodeThumbnail` → `cgImageToSkia` → `Image.makeRaster(ByteArray)` → `Image.toComposeImageBitmap()` | **3** |
| **Final export** | `IosImageDecoder.decode` → `decodePrimarySkiaFromBytes` → `cgImageToSkia` → `toComposeImageBitmap()` | **3**, at full resolution |

Write 1 is `CGContextDrawImage` (this one is doing real work: format + colour conversion).
Write 2 is a `malloc` + `memcpy` inside skiko's C++.
Write 3 is `allocPixels` + `Canvas.drawImage` inside Compose's `Image.toComposeImageBitmap()`.

Writes 2 and 3 are **pure overhead**. They convert nothing.

### 1.2 Estimated cost by surface

These are *estimates*, not measurements — Phase 0 exists to replace them. Bytes are exact
(`w × h × 4`); times assume ~6 GB/s effective single-thread `memcpy` on an A18-class core, which is
optimistic for a cold buffer and pessimistic for an L2-resident one.

| Surface | Output px | Bytes/write | Wasted writes today | Wasted bytes | Est. wasted time |
|---|---|---:|---:|---:|---:|
| Filmstrip thumb | 128×85 | 43 KB | 1 | 43 KB | **~10 µs** |
| Preview 720 | 720×540 | 1.55 MB | 2 | 3.1 MB | ~0.5 ms |
| Preview 1920 | 1920×1440 | 11.1 MB | 2 | 22.1 MB | ~3.7 ms |
| Export 12 MP | 4032×3024 | 48.8 MB | 2 | 97.5 MB | ~16 ms |

Against the measured cold preview switch of ~215 ms decode
(`2026-08-14-ios-preview-perf-leftovers.md` S1), **the time saving at 1920 is ~1.5–2%.** Anyone
selling this as a latency win is overselling it.

The honest justification is **transient memory**, not milliseconds:

| Surface | Peak transient buffers today | After a single-write path |
|---|---|---|
| Preview 1920 | 3 × 11.1 MB = **33.2 MB** | 11.1 MB |
| Export 12 MP | 3 × 48.8 MB = **146 MB** | 48.8 MB |

The 12 MP export figure matters. This app runs under a 128 MiB joint preview cache ceiling on a
platform that jetsams (`2026-08-14-ios-preview-perf-leftovers.md` S5), and one of those three
buffers is an 48.8 MB Kotlin/Native `ByteArray` on the K/N GC heap, freed non-deterministically.
**Export is the highest-ROI surface for this work and it was not in the brief's scope.**

Conversely: **the filmstrip is not worth optimising for copies.** 43 KB against a ~200 ms HEIC
decode is noise. If filmstrip scroll is the complaint, the answer is subsampling and decode
scheduling (already landed in S3), not this.

---

## 2. Vocabulary — what "zero copy" means here

Precise levels, so nobody claims a win they did not get:

- **L0 — codec output.** ImageIO decodes HEIC/JPEG into *some* buffer owned by CoreGraphics, in
  *its* chosen layout and colour space. Unavoidable. Not a copy we control.
- **L1 — format/colour conversion.** `CGContextDrawImage` into our chosen sRGB/BGRA/premul layout.
  This is a *transform*, not a copy: it is only removable when the source layout already matches
  the destination exactly. Removing it is §7's "fast path".
- **L2 — Kotlin↔native marshalling.** Getting L1's result into memory Skia owns. Today this is one
  full `malloc`+`memcpy` in skiko C++. **Fully removable, no semantic change.**
- **L3 — Compose bridging.** `Image.toComposeImageBitmap()` re-rasters into a fresh `SkBitmap`.
  **Fully removable, no semantic change.**

"Zero copy" in this document means **L2 = L3 = 0**, i.e. exactly one full-frame write (L1) that
does real conversion work. It does **not** mean "CoreGraphics and Skia share the same pages" —
that stronger claim is §7's rejected Option B and is not what we should ship.

---

## 3. Official API findings

### 3.1 Apple

**QA1509 — *Getting the pixel data from a CGImage object*.** Two documented ways to read a
`CGImage`'s pixels:

1. `CGDataProviderCopyData(CGImageGetDataProvider(img))` → `CFDataRef`, then `CFDataGetBytePtr`.
2. Draw into a `CGBitmapContext` you allocate (what we do today).

The warning attached to route 1 is the whole ballgame:

> **WARNING:** The pixel data returned by `CGDataProviderCopyData` has not been color matched and
> is in the format that the image is in, as described by the various `CGImageGet` functions. If you
> want the image in a different format, or color matched to a specific color space, then you should
> draw the image to a bitmap context as described later in this Q&A, with the caveat that alpha
> information from the image will be multiplied into the color components.

<https://developer.apple.com/library/archive/qa/qa1509/_index.html>

So route 1 is only safe when we have *verified* the image's `CGImageGetBitmapInfo`,
`CGImageGetBitsPerComponent`, `CGImageGetBitsPerPixel`, `CGImageGetBytesPerRow` and
`CGImageGetColorSpace` already match what we want. Which is the entire content of §5.

Note also the name: `CGDataProviderCopy Data`. Whether it actually copies is undocumented and
provider-dependent; for a lazily-decoded ImageIO-backed provider it can force a full decode. We
cannot rely on it being free. **Hypothesis, needs Phase 0.**

`CGDataProvider` reference: <https://developer.apple.com/documentation/coregraphics/cgdataprovider>

`CGBitmapContextCreate` accepts any caller-supplied `data` pointer and any `bytesPerRow ≥ width ×
bytesPerPixel`, which is what makes §7's design possible — the destination does not have to be a
Kotlin `ByteArray`:
<https://developer.apple.com/documentation/coregraphics/cgcontext/init(data:width:height:bitspercomponent:bytesperrow:space:bitmapinfo:releasecallback:releaseinfo:)>

### 3.2 Skia (C++)

- `SkBitmap::installPixels(const SkImageInfo&, void* pixels, size_t rowBytes, void (*releaseProc)(void*, void*), void* context)` — installs **external** pixels with a release callback; no copy.
  <https://api.skia.org/classSkBitmap.html>
- `SkData::MakeUninitialized(size_t length)` — Skia-owned malloc'd buffer.
- `SkData::writable_data()` — mutable pointer into it. Skia's own doc for the sibling
  `MakeZeroInitialized` states the contract exactly:
  > The caller should call `writable_data()` to write into the buffer, but this must be done before
  > another `ref()` is made.

  <https://api.skia.org/classSkData.html>
- `SkData::MakeWithoutCopy(const void* data, size_t length)` — "Call this when the data parameter is
  already const and **will outlive the lifetime of the SkData**. Suitable for use with const
  globals." That phrasing ("const globals") is Skia telling you this is not for refcounted
  platform buffers.
- `SkImages::RasterFromData(info, sk_sp<SkData>, rowBytes)` — no copy; the `SkImage` holds a ref on
  the `SkData`. <https://api.skia.org/namespaceSkImages.html>

### 3.3 JetBrains Skiko (verified against skiko `master` C++ and the 0.150.0 sources jar)

This is the section that changes the plan. All four facts below were read out of the actual
artifacts on this machine plus skiko's C++ on GitHub.

**(a) `Bitmap.installPixels(info, ByteArray, rowBytes)` copies.** On Kotlin/Native
`InteropScope.toInterop(ByteArray)` merely *pins* and hands over `addressOf(0)`, releasing the pin
when the scope exits — so the C++ side is obliged to copy, and it does:

```cpp
// skiko/src/nativeJsMain/cpp/Bitmap.cc
SKIKO_EXPORT KBoolean org_jetbrains_skia_Bitmap__1nInstallPixels(...) {
  KNativePointer copyPtr = malloc(pixelsLen);
  void* copy = memcpy(copyPtr, pixelsArr, pixelsLen);
  return instance->installPixels(imageInfo, copy, rowBytes, deletePixelsBytes, nullptr);
}
```
<https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/Bitmap.cc>

Despite the name, skiko's `installPixels` is **not** Skia's zero-copy `installPixels`. The Kotlin
API only has `ByteArray` overloads; there is no pointer overload on native.

**(b) `Image.makeRaster(info, ByteArray, rowBytes)` also copies** — `SkImages::RasterFromPixmapCopy`.
**`Image.makeRaster(info, Data, rowBytes)` does not** — `SkImages::RasterFromData`:

```cpp
// skiko/src/nativeJsMain/cpp/Image.cc
_1nMakeRaster      -> SkImages::RasterFromPixmapCopy(SkPixmap(imageInfo, bytesArr, rowBytes));
_1nMakeRasterData  -> SkImages::RasterFromData(imageInfo, sk_ref_sp(data), rowBytes);
```
<https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/Image.cc>

**(c) Skia-owned mutable buffers are reachable from Kotlin.** `Data.makeUninitialized(length)` and
`Data.writableData(): NativePointer` are both public in `org.jetbrains.skia`, mapping straight onto
`SkData::MakeUninitialized` / `SkData::writable_data`:

```cpp
// skiko/src/nativeJsMain/cpp/Data.cc
_1nMakeUninitialized(KInt length) -> SkData::MakeUninitialized(length).release();
_1nWritableData(KNativePointer ptr) -> instance->writable_data();
```
<https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/Data.cc>

Same for `Bitmap.allocPixels(info, rowBytes)` (→ `SkBitmap::tryAllocPixels(info, rowBytes)`) and
`Bitmap.peekPixels(): Pixmap?` → `Pixmap.addr: NativePointer`. **This is the pointer we can hand to
`CGBitmapContextCreate`.**

**(d) `Data.makeWithoutCopy` exists but its ownership anchor is skiko-typed only:**

```kotlin
fun makeWithoutCopy(memoryAddr: NativePointer, length: Int, underlyingMemoryOwner: Managed): Data
```

`underlyingMemoryOwner` is `org.jetbrains.skia.impl.Managed`. A `CFDataRef` is not a `Managed`, so
there is **no supported way to make a skiko `Data` keep a `CFData` alive**. See §6.

Docs: <https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia/-data/-companion/index.html>

**(e) There is no native `installPixelsFromPointer`.** The C++ symbol exists but is annotated
`// Currently for web only:` and the only Kotlin binding is the web-only
`Bitmap.installPixelsFromArrayBuffer` added in skiko PR #1158
(<https://github.com/JetBrains/skiko/pull/1158>,
<https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia.webext/install-pixels-from-array-buffer.html>).
Do not plan around it on iOS.

### 3.4 Compose Multiplatform bridge

`Image.toComposeImageBitmap()` is **not** a wrapper — it re-rasters:

```kotlin
// ui-graphics skikoMain/SkiaImageAsset.skiko.kt
fun Image.toComposeImageBitmap(): ImageBitmap = SkiaBackedImageBitmap(toBitmap())

// ui-graphics skikoExcludingWebMain/Actuals.skikoExcludingWeb.kt
internal actual fun Image.toBitmap(): Bitmap {
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL))
    val canvas = org.jetbrains.skia.Canvas(bitmap)
    canvas.drawImage(this, 0f, 0f)
    bitmap.setImmutable()
    return bitmap
}
```

`Bitmap.asComposeImageBitmap()` in the same file **is** a pure wrap
(`SkiaBackedImageBitmap(this)`). So *ending the ImageIO path on a `Bitmap` instead of an `Image`
deletes L3 outright.* (Read from `ui-graphics-iossimulatorarm64-1.12.0-beta01-sources.jar` in the
local Gradle cache; upstream: <https://github.com/JetBrains/compose-multiplatform-core>.)

### 3.5 Coil 3 on iOS

- `coil3.Image` is a plain interface: `width`, `height`, `size`, `shareable`, `draw(org.jetbrains.skia.Canvas)`.
  `coil3.BitmapImage` is just one implementation, over `org.jetbrains.skia.Bitmap`.
- `Image.asPainter()` (nonAndroid) branches: `BitmapImage` → `toBitmap()` → `asComposeImageBitmap()`
  → `BitmapPainter`; **anything else** → `coil3.compose.ImagePainter`, whose `onDraw` scales the
  `DrawScope` and calls `image.draw(nativeCanvas)`.
- `toBitmap()` on a `BitmapImage` returns the *same* `Bitmap` when width/height/colorType/alphaType/
  colorSpace all match, so today's filmstrip path does not copy after `installPixels`.

Verified by `javap` on `coil-core-jvm-3.5.0.jar` and `coil-compose-core-jvm-3.5.0.jar`
(nonAndroid sources are shared with iOS). Upstream: <https://github.com/coil-kt/coil>.

**Consequence:** a Coil `Decoder` *can* return a non-`BitmapImage`. We are not forced into
`Bitmap` — but as §7 shows, we do not need to escape it either.

---

## 4. Open-source findings

### 4.1 The community `UIImage.toSkiaImage()` — and why it is a cautionary tale

The widely-copied CMP snippet (Kotlin Slack `#compose-ios`, later reproduced in the
Mobile Innovation Network Medium post) does exactly the brief's "Option A": grab
`CGDataProviderCopyData`, read `CFDataGetBytePtr`, build a `ByteArray`, `Image.makeRaster`.

It has three documented failure modes, all of which are direct evidence against Option A:

1. **Wrong colours on Apple Photos images.** Stefan Oltmann, 2023-08-23: *"Unfortunately the code in
   `toSkiaImage()` leads to wrong coloured images if they come from Apple Photos UIImages."* That is
   QA1509's colour-matching warning landing on exactly our input population — iPhone camera HEIC is
   Display P3, not sRGB.
   <https://slack-chats.kotlinlang.org/t/14161801/what-s-the-most-efficient-way-to-get-from-an-uiimage-to-an-s>
2. **Channel-order bugs.** The Medium variant has to hand-swap R and B in a Kotlin loop because it
   assumed `RGBA_8888` — a per-pixel Kotlin loop on a 12 MP frame.
   <https://medium.com/mobile-innovation-network/uiimage-to-imagebitmap-compose-multiplatform-497230a71f37>
3. **`ByteArray(length) { bytePointer[index].toByte() }`** — a per-byte Kotlin lambda over ~50 MB.
   Orders of magnitude worse than the `memcpy` it was trying to avoid.

The escape hatch most projects took was `UIImagePNGRepresentation`/`UIImageJPEGRepresentation` →
`Image.makeFromEncoded`, i.e. a full re-encode. Our current ImageIO path is already far better than
the ecosystem baseline; the remaining wins are the L2/L3 ones, not L1.

### 4.2 HEIC on Skia — the constraint that put us here

- `JetBrains/skiko#942` — HEIC unsupported by `Image.makeFromEncoded`; closed as
  "Skiko is a wrapper around Skia, so for image format support you'd file a bug on Skia".
  <https://github.com/JetBrains/skiko/issues/942>
- `coil-kt/coil#2318` — "Add support for HEIC images in Compose Multiplatform"; maintainer response:
  do it as an external `Decoder.Factory`, Coil will not take it. The issue's sample decoder
  transcodes HEIC → JPEG → `Image.makeFromEncoded` → `Bitmap.makeFromImage` (two extra full
  re-encodes/copies). One commenter reports abandoning Coil for iOS thumbnails entirely.
  <https://github.com/coil-kt/coil/issues/2318>
- `vinceglb/FileKit#555` + #576 — the same HEIC/Skia gap surfacing through a file picker; fixed by
  asking PHPicker for a *compatible* representation rather than by decoding HEIC.
  <https://github.com/vinceglb/FileKit/issues/555>

Our `IosHeifImageDecoder` (ImageIO thumbnail + subsample, no transcode) is already the best-in-class
version of this pattern. **Nothing in the ecosystem does the ImageIO → Skia hand-off without at
least one redundant copy.** I could not find a single open-source Kotlin/Native project using
`Data.makeUninitialized` + `writableData()` as a `CGBitmapContext` backing store. That is a gap, not
a red flag — but it does mean we will be first, so Phase 0's compile spike is not optional.

### 4.3 Prior art on the "let the OS own the pixels" school

The MVP Factory / conzit write-ups argue the opposite direction: convert Skia bitmaps *back* to
`CGImage`/`UIImage` so iOS can evict them under memory pressure, because K/N GC allocations are
invisible to jetsam.
<https://mvpfactory.io/blog/taming-compose-multiplatform-image-decoding-on-ios-skia-codec-pitfalls-nsimage/>

This is a real and reasonable architecture, and it is **out of scope** — it would mean replacing
Compose's `ImageBitmap` rendering path, not tuning a decoder. But it reinforces §1.2's framing: the
prize here is bytes, not milliseconds.

---

## 5. Format compatibility gate

Required *only* for the L1 skip (Phase 2). Every one of these must hold, or we fall back to
`CGContextDrawImage`.

| Check | Accept | Why |
|---|---|---|
| `CGImageGetBitsPerComponent` | `== 8` | Skia N32 is 8-bit; 10-bit HDR HEIC returns 16 |
| `CGImageGetBitsPerPixel` | `== 32` | rejects 24-bit RGB and 8-bit grey |
| `bitmapInfo & kCGBitmapFloatComponents` | `== 0` | rejects half-float wide-gamut |
| `CGImageGetBytesPerRow` | `≥ width*4` | Skia accepts arbitrary `rowBytes` ≥ min, so row padding is *fine* — do not require `== width*4` |
| `CGImageGetColorSpace` name | `kCGColorSpaceSRGB` (or `kCGColorSpaceDeviceRGB`) | QA1509: provider bytes are **not** colour-matched |
| alpha + byte order | see table below | determines Skia `ColorType`/`ColorAlphaType` |
| `CGImageGetDataProvider` | non-null, and `CGDataProviderCopyData` returns `length ≥ rowBytes*height` | guards lazy/streamed providers |

Skia mapping (`bitmapInfo & kCGBitmapAlphaInfoMask`, `bitmapInfo & kCGBitmapByteOrderMask`):

| CG alpha info | Byte order | Skia `ColorType` | Skia `ColorAlphaType` |
|---|---|---|---|
| `PremultipliedFirst` (ARGB) | `32Little` | `BGRA_8888` (`= ColorType.N32`) | `PREMUL` |
| `NoneSkipFirst` (XRGB) | `32Little` | `BGRA_8888` | `OPAQUE` |
| `First` (ARGB, unpremul) | `32Little` | `BGRA_8888` | `UNPREMUL` |
| `PremultipliedLast` (RGBA) | `32Big` / default | `RGBA_8888` | `PREMUL` |
| `NoneSkipLast` (RGBX) | `32Big` / default | `RGBA_8888` | `OPAQUE` |
| `Last` (RGBA, unpremul) | `32Big` / default | `RGBA_8888` | `UNPREMUL` |
| anything else | — | **reject → draw** | — |

`ColorType.N32` is hard-wired to `BGRA_8888` in skiko (`ColorType.kt:338`), which is the correct
choice on Apple silicon and matches the `PremultipliedFirst | byteOrder32Little` we ask for today.

**Predicted hit rate: low, on exactly the photos we care about.** iPhone camera HEIC is Display P3,
and `CGImageSourceCreateThumbnailAtIndex` has no documented obligation to hand back sRGB BGRA. If
the gate rejects, we have spent a handful of `CGImageGet*` calls (nanoseconds) and lost nothing.
**This is a hypothesis. Phase 0 measures the real hit rate before Phase 2 is written.**

Note the second-order trap: `UNPREMUL` output is *legal* for Skia but changes what
`CommonWatermarkPipeline` composites over. Phase 2 should reject `UNPREMUL` on the first pass rather
than reason about premultiply semantics under a deadline.

---

## 6. Ownership / lifetime model

Three candidate owners of the pixel bytes, and what each costs:

### 6.1 CoreGraphics owns them (`CFData` from `CGDataProviderCopyData`) — **rejected**

To wrap them with `SkData::MakeWithoutCopy`, something must keep the `CFData` alive for at least as
long as the `SkImage`. The failure mode is a use-after-free that paints garbage or crashes in
Skia's blitter, arbitrarily long after the decode returned.

Why it cannot be made safe with today's APIs:

1. skiko's `Data.makeWithoutCopy(addr, length, underlyingMemoryOwner: Managed)` only accepts a
   skiko `Managed` as the anchor. A `CFDataRef` is not one, so we would have to keep the `CFData`
   alive from Kotlin ourselves.
2. The Kotlin object holding the `CFRelease` would have to outlive the Coil memory-cache entry, and
   Coil evicts on its own schedule. There is no `Image` disposal callback we can hook.
3. Even if we tied it to K/N `createCleaner`, K/N GC finalisation is non-deterministic and — per
   §4.3 — does not participate in iOS memory pressure. We would be trading a bounded `memcpy` for
   an unbounded correctness risk.
4. `SkData::MakeWithoutCopy`'s own doc says "suitable for use with const globals". A refcounted
   platform buffer is the opposite of that.

### 6.2 Skia owns them — **recommended**

Either
`Bitmap.allocPixels(info, rowBytes)` (a `SkPixelRef` owns the buffer, freed when the last
`SkBitmap`/`SkImage` ref drops) or `Data.makeUninitialized(n)` (an `SkData` owns it, ref'd by
`SkImages::RasterFromData`). In both cases:

- lifetime is refcounted by Skia, exactly as it is today after `installPixels`;
- CoreGraphics touches the memory only inside `CGBitmapContextCreate` … `CGContextRelease`, a scope
  that is strictly nested inside the decode call;
- no Kotlin object needs to outlive anything;
- there is nothing new for Coil, the preview LRU, or the K/N GC to get wrong.

The one rule: for the `SkData` variant, call `writableData()` **before** `Image.makeRaster` takes
its ref (Skia's documented `MakeZeroInitialized` contract, §3.2), and never call it again after.
For the `Bitmap` variant, call `setImmutable()` after the draw and before publishing.

### 6.3 Kotlin/Native owns them (`ByteArray` + `usePinned`) — **today's model**

Correct, but it puts a 48.8 MB array on the GC heap per full-res export and then forces a `memcpy`
out of it, because skiko's pin is scope-bounded.

---

## 7. Recommended design

**Invert the direction of the transfer.** Stop asking "how do I get pixels *out* of a CGImage
cheaply" and start asking "how do I make CoreGraphics write *into* memory Skia already owns". We
already pay one `CGContextDrawImage`; make its destination Skia's buffer.

This gets L2 = L3 = 0 with no lifetime hazard, no format gate, and no behaviour change — and it
works on 100% of inputs, unlike the format fast path.

### 7.1 Two primitives (replacing `copyCgImagePixels`)

**A. `Bitmap`-producing** — for the Coil decoder and for everything that ends as an `ImageBitmap`:

```kotlin
// sketch, not production code
val info = ImageInfo.makeS32(w, h, ColorAlphaType.PREMUL)
val bitmap = Bitmap()
check(bitmap.allocPixels(info, rowBytes))       // SkBitmap::tryAllocPixels — Skia owns the buffer
val addr = bitmap.peekPixels()!!.use { it.addr } // NativePointer into that buffer
CGBitmapContextCreate(data = addr.toLong().toCPointer<ByteVar>(), /* ... as today ... */)
CGContextDrawImage(ctx, rect, cgImage)          // the ONLY full-frame write
bitmap.setImmutable()
```

**B. `Image`-producing** — for callers that genuinely need `org.jetbrains.skia.Image`:

```kotlin
val data = Data.makeUninitialized(byteCount)     // SkData::MakeUninitialized
val addr = data.writableData()                   // must precede makeRaster (Skia contract)
// ... CGBitmapContextCreate over addr, CGContextDrawImage ...
val image = Image.makeRaster(info, data, rowBytes) // RasterFromData — refs, does not copy
```

Then rewire endpoints:

| Caller | Today | After |
|---|---|---|
| `IosHeifImageDecoder` | `cgImageToSkiaBitmap` (2 writes) | primitive **A** (1 write) |
| `IosPreviewRaster.decodePathThumbnail` | `decodeThumbnail` → `Image` → `toComposeImageBitmap` (3) | primitive **A** → `asComposeImageBitmap()` (1) |
| `IosImageDecoder.decodeThumbnail`/`decode` HEIF branch | `…SkiaFromBytes` → `toComposeImageBitmap` (3) | primitive **A** → `asComposeImageBitmap()` (1) |
| `IosExportThumbnailLoader` | `decodeThumbnail` (3) | primitive **A** (1) |
| Anything still typed `-> org.jetbrains.skia.Image` | `makeRaster(ByteArray)` (2) | primitive **B** (1) |

### 7.2 Should the Coil `Decoder` stop returning `BitmapImage`?

**No.** §3.5 shows `BitmapImage` costs nothing extra: `asPainter` → `toBitmap()` returns the same
`Bitmap` object → `asComposeImageBitmap()` wraps it. A custom `coil3.Image` over a skia `Image`
would route through `coil3.compose.ImagePainter` instead — a *different, less-travelled* painter,
for zero measured benefit. The brief's suggestion that Option B "may require a Coil Decoder change"
is only true for the `makeWithoutCopy` design we are rejecting.

Keep this in the back pocket for a Phase 3 only if a measurement demands it.

### 7.3 Colour-tag consistency (a real, small behaviour delta)

Today the two paths disagree and nobody noticed:

- filmstrip ends on `ImageInfo.makeS32` → **sRGB-tagged** `Bitmap`;
- preview ends on Compose's `Image.toBitmap()` → `ImageInfo.makeN32` → **untagged** `Bitmap`.

Unifying on `makeS32` (as the sketch does) is almost certainly a no-op, because
`CommonWatermarkPipeline` composites into a Compose-created `ImageBitmap`, whose skia backing is
already sRGB-tagged (`ActualImageBitmap` → `ColorSpaces.Srgb.toSkiaColorSpace()`), and Skia treats
an untagged source as "same space as destination". **Reasoned, not measured** — ADR-0010 says verify
renders by viewing screenshots, so Phase 1 must ship a before/after preview screenshot pair, not a
byte comparison.

---

## 8. Phased implementation plan

The brief proposed Phase 1 = skip-Draw, Phase 2 = `makeWithoutCopy`. **I have deliberately reordered
these**, because §3.3 shows the L2/L3 elimination is unconditional, universal and hazard-free, while
the L1 skip is conditional, input-dependent and (§4.1) has a documented history of shipping wrong
colours. Doing the safe universal win first also gives Phase 2 a clean baseline to be measured
against.

### Phase 0 — probe and spike (no production change)

**Do this before writing anything else.** Two independent unknowns.

*Files*
- `shared/src/iosMain/.../ui/IosDevicePerfBench.kt` — add a `DEVICE_PERF_CGSHAPE` line per decode
  logging `bitsPerComponent`, `bitsPerPixel`, `bytesPerRow`, `alphaInfo`, `byteOrder`,
  `CGColorSpaceCopyName`, and whether the §5 gate would pass.
- `shared/src/iosMain/.../render/IosImageIODecoder.kt` — a `#if DEBUG`-equivalent internal
  `copyStageNanos` accumulator splitting `CGContextDrawImage` from the Skia hand-off.
- New `shared/src/iosTest/.../render/IosSkiaOwnedBufferSpikeTest.kt` — compile+run spike proving
  `Data.writableData().toLong().toCPointer<ByteVar>()` and `Bitmap.peekPixels()!!.addr` are
  usable from our source set, and that a `CGBitmapContext` over them produces the same pixels as
  today's path for a synthetic fixture.

*Why the spike matters:* `NativePointer` is `kotlin.native.internal.NativePtr`. Converting it to a
`CPointer` should work via `.toLong().toCPointer<ByteVar>()` (skiko itself does `_ptr.toLong()`),
but **I have not compiled it**, and no open-source project was found doing it (§4.2). If it does
not compile cleanly without internal-API opt-ins, the whole plan collapses to Phase 2 only, and we
need to know that on day one.

*Bench*
```bash
./gradlew :shared:iosSimulatorArm64Test --tests '*IosSkiaOwnedBufferSpikeTest*'
# device: run the existing DEVICE_PERF harness over the ~/ewm-12mp-drop album, then
# xcrun devicectl / Console.app filter on DEVICE_PERF_CGSHAPE
```

*Success criteria*
- spike compiles and pixel-matches the current path on a synthetic fixture;
- we have a real hit-rate number for the §5 gate over ≥ 20 album HEIC + ≥ 5 JPEG;
- we have a real ms split for `CGContextDrawImage` vs Skia hand-off at 128 / 720 / 1920 / full-res.

*Rollback:* delete the probe. Nothing shipped.

*Gate:* **if the §5 gate hit rate on real album HEIC is < 30%, Phase 2 is cancelled**, not deferred.

---

### Phase 1 — destination-in-Skia (removes L2 and L3, all inputs)

*Files*
- `shared/src/iosMain/.../render/IosImageIODecoder.kt` — replace `copyCgImagePixels` +
  `cgImageToSkia` + `cgImageToSkiaBitmap` with primitives A/B (§7.1). Keep the public function
  signatures; keep `IosImageIOOwnershipProbe` counters and the `try/finally` release discipline
  exactly as they are.
- `shared/src/iosMain/.../render/IosPreviewRaster.kt` — `decodePathThumbnail` returns
  `bitmap.asComposeImageBitmap()`.
- `shared/src/iosMain/.../render/IosImageDecoder.kt` — HEIF branches of `decode` / `decodeThumbnail`
  end on `Bitmap`, not `Image`.
- `shared/src/iosMain/.../ui/IosExportThumbnailLoader.kt`, `.../ui/image/IosHeifImageDecoder.kt` —
  call-site updates only.

*Tests*
- `IosImageIOPathDecoderTest` — extend: decoded pixels are byte-identical to the pre-change path for
  JPEG/PNG/HEIF fixtures (capture a reference `ByteArray` via `Bitmap.readPixels` in the test).
- `IosImageIOSubsampleTest` — unchanged, must still pass (dimension invariants).
- `IosHeifCoilDecoderTest` — `DecodeResult.image` is still a `BitmapImage` with the same
  width/height/`isSampled`.
- `IosPreviewRasterTest`, `IosFinalRenderSpineTest`, `IosWatermarkRendererGoldenTest` — unchanged,
  must still pass.
- **New** `IosImageIOOwnedBufferTest` — asserts the returned `Bitmap` is immutable, and that
  ownership-probe create/release counts stay balanced when the composer throws
  (`throwAfterCreateForTests`).

*Bench*
```bash
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:iosSimulatorArm64Test --tests '*IosProductThumbAbDecodeBenchTest*'
# device: DEVICE_PERF harness, compare io720/io1920 medians + the Phase 0 stage split
```

*Success criteria*
- pixel-identical output on all fixtures (assert, do not eyeball);
- before/after **preview screenshots** viewed and compared (ADR-0010 — never claim a render is fine
  because the bytes are the same size);
- device peak-RSS during a 12 MP export drops by ≈ 2 × frame bytes;
- decode ms is **allowed to be flat**. Flat is a pass. If we only claim the memory win, we are being
  honest.

*Rollback:* single commit revert; record the rollback HEAD in the PR body before merging.

---

### Phase 2 — format-match fast path (removes L1, *some* inputs)

**Only if Phase 0's gate says ≥ 30% hit rate.**

*Files*
- `shared/src/iosMain/.../render/IosImageIODecoder.kt` — add `cgImageDirectLayoutOrNull(image)`
  implementing §5 exactly, plus the `CGDataProviderCopyData` read. On a hit, still copy into the
  Skia-owned buffer via `memcpy` (one write, but no colour conversion); on a miss, fall through to
  Phase 1's `CGContextDrawImage`.

Note the deliberate conservatism: **Phase 2 does not skip the copy, it skips the *conversion*.**
Sharing the `CFData` pages with Skia is §6.1, which is rejected. If the `CGDataProviderCopyData`
turns out to itself copy (§3.1, unverified), Phase 2 nets *zero* and should be abandoned — Phase 0
must measure this.

*Tests*
- **New** `IosCgImageLayoutGateTest` — table-driven over synthetic `CGImage`s covering every row of
  §5's mapping table, asserting accept/reject and the chosen `ColorType`/`ColorAlphaType`.
- **New** `IosCgImageFastPathParityTest` — for any fixture the gate accepts, fast-path output must be
  byte-identical to the `CGContextDrawImage` output. **This is the anti-regression for §4.1's
  "wrong colours from Apple Photos" bug and is non-negotiable.**

*Bench*
```bash
./gradlew :shared:iosSimulatorArm64Test --tests '*IosCgImage*'
# device: DEVICE_PERF with the gate forced on/off, order-balanced (per S1's order-bias lesson)
```

*Success criteria*
- 100% byte parity on every accepted fixture;
- measurable ms win on accepted inputs, or the phase is reverted;
- filmstrip + preview screenshots viewed side by side against Phase 1.

*Rollback:* the gate is one function returning `null`; a one-line change disables it.

---

### Phase 3 — optional, owner-gated

Do **not** start without an explicit owner decision and a measurement that demands it.

1. **`Data.makeWithoutCopy` over a `CFData`** (the brief's Option B). Rejected on §6.1 grounds.
   Would become viable only if skiko gains a `releaseProc`-carrying factory upstream; if we want
   that, the move is a skiko issue/PR, not a local workaround.
2. **Custom `coil3.Image` over `org.jetbrains.skia.Image`.** Only if a measurement shows
   `BitmapImage` costing something, which §3.5 says it does not.
3. **`ImageBitmap` → `CGImage` hand-back** so iOS can evict under pressure (§4.3). This is an
   architecture change with an ADR attached, not a decoder tweak.

---

## 9. Non-goals / rejected options

| Option | Verdict | Reason |
|---|---|---|
| Rust / C interop shim for the copy | **No** | Explicitly out of scope; also unnecessary — §3.3(c) shows Kotlin can reach the pointer |
| Patch or fork skiko to expose `installPixelsFromPointer` on native | **No** | Vendoring a native lib for a `memcpy`; upstream issue/PR is the correct channel |
| `Bitmap.installPixelsFromArrayBuffer` | **N/A** | Web-only binding (skiko #1158) |
| Metal / `IOSurface` / `CVPixelBuffer` sharing for the filmstrip | **No** | §1.2: filmstrip copy cost is ~10 µs. Enormous complexity for noise |
| Replace Coil with a bespoke iOS loader | **No** | ADR-0028; and §3.5 shows Coil is not the bottleneck |
| Return a non-`BitmapImage` from the Coil decoder | **Deferred** | No measured benefit (§7.2) |
| Share `CFData` pages with Skia via `MakeWithoutCopy` | **Rejected** | Unanchorable lifetime → UAF (§6.1) |
| `CGDataProviderCopyData` without the §5 gate | **Rejected** | QA1509 colour-matching warning; §4.1 shows the exact bug this ships |
| Alpha Swift export / new framework surface | **No** | J5 |
| Re-adding a strict FNV golden gate to CI to police this | **No** | ADR-0010 — local-only |

---

## 10. Open questions (must be answered before Phase 2+)

1. **Does `NativePtr → CPointer` compile in our source set without internal-API opt-ins?** Phase 0
   spike. Blocks everything.
2. **Does `CGDataProviderCopyData` copy, and can it force a decode?** Undocumented. If it copies,
   Phase 2's ceiling is "skip the colour conversion", not "skip the write" — and if it forces a
   decode on a lazy provider it could be *slower*. Device measurement only.
3. **What layout does `CGImageSourceCreateThumbnailAtIndex` actually return** for (a) iPhone album
   HEIC (Display P3), (b) HDR/10-bit HEIC, (c) JPEG, (d) subsampled vs not? Determines whether
   Phase 2 exists at all.
4. **Does `kCGImageSourceSubsampleFactor` change the returned bitmap layout?** If subsampled and
   non-subsampled thumbnails differ in `bitmapInfo`, the gate's hit rate is policy-dependent and
   the two `IosHeifDecodePolicy` values will behave differently.
5. **Is the sRGB-tag unification (§7.3) visually a no-op?** Reasoned yes; needs a viewed screenshot
   pair per ADR-0010.
6. **Does `Bitmap.allocPixels(info, rowBytes)` ever return a `rowBytes` other than what we asked?**
   The sketch reads `bitmap.rowBytes` back rather than assuming — confirm that is what
   `CGBitmapContextCreate` gets.
7. **Peak-RSS measurement method for a 12 MP export on device.** We have no RSS harness today; §1.2's
   memory claim is unverifiable without one, and it is the main justification for Phase 1.

---

## 11. Sources

**Apple**
- Technical Q&A QA1509, *Getting the pixel data from a CGImage object* — <https://developer.apple.com/library/archive/qa/qa1509/_index.html>
- `CGDataProvider` — <https://developer.apple.com/documentation/coregraphics/cgdataprovider>
- `CGContext` bitmap-context initialiser — <https://developer.apple.com/documentation/coregraphics/cgcontext/init(data:width:height:bitspercomponent:bytesperrow:space:bitmapinfo:releasecallback:releaseinfo:)>
- `CGImageSourceCreateThumbnailAtIndex` — <https://developer.apple.com/documentation/imageio/cgimagesourcecreatethumbnailatindex(_:_:_:)>

**Skia**
- `SkBitmap` (incl. `installPixels` with `releaseProc`) — <https://api.skia.org/classSkBitmap.html>
- `SkData` (`MakeUninitialized`, `MakeZeroInitialized`, `MakeWithoutCopy`, `writable_data`) — <https://api.skia.org/classSkData.html>
- `SkImages` (`RasterFromData`, `RasterFromPixmapCopy`) — <https://api.skia.org/namespaceSkImages.html>

**JetBrains Skiko**
- `Data.cc` — <https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/Data.cc>
- `Bitmap.cc` — <https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/Bitmap.cc>
- `Image.cc` — <https://github.com/JetBrains/skiko/blob/master/skiko/src/nativeJsMain/cpp/Image.cc>
- `Image.kt` — <https://github.com/JetBrains/skiko/blob/master/skiko/src/commonMain/kotlin/org/jetbrains/skia/Image.kt>
- `Data.Companion` API docs — <https://jetbrains.github.io/skiko/skiko/org.jetbrains.skia/-data/-companion/index.html>
- PR #1158, web-only `installPixelsFromArrayBuffer` — <https://github.com/JetBrains/skiko/pull/1158>
- Issue #942, HEIC unsupported — <https://github.com/JetBrains/skiko/issues/942>
- Issue #787, `Failed to Image::makeFromEncoded` — <https://github.com/JetBrains/skiko/issues/787>
- Local artifacts read directly: `skiko-iossimulatorarm64-0.150.0-sources.jar`
  (`Data.kt`, `Bitmap.kt`, `Image.kt`, `Pixmap.kt`, `ColorType.kt`, `impl/Native.native.kt`,
  `impl/Managed.native.kt`)

**Compose Multiplatform**
- `ui-graphics-iossimulatorarm64-1.12.0-beta01-sources.jar` →
  `skikoMain/.../SkiaImageAsset.skiko.kt`, `skikoExcludingWebMain/.../Actuals.skikoExcludingWeb.kt`
- Upstream repo — <https://github.com/JetBrains/compose-multiplatform-core>

**Coil 3**
- Issue #2318, HEIC on CMP — <https://github.com/coil-kt/coil/issues/2318>
- Local artifacts read via `javap`: `coil-core-jvm-3.5.0.jar` (`Image`, `BitmapImage`,
  `Image_nonAndroidKt`), `coil-compose-core-jvm-3.5.0.jar` (`ImagePainter`,
  `ImagePainter_nonAndroidKt`)
- Upstream repo — <https://github.com/coil-kt/coil>

**Ecosystem / community**
- Kotlin Slack `#compose-ios`, "most efficient way from UIImage to Skia" (wrong colours from Apple
  Photos) — <https://slack-chats.kotlinlang.org/t/14161801/what-s-the-most-efficient-way-to-get-from-an-uiimage-to-an-s>
- Kotlin Slack `#compose-ios`, original `UIImage.toSkiaImage()` snippet — <https://slack-chats.kotlinlang.org/t/12086405/hi-all-how-to-convert-ios-uiimage-to-compose-imagebitmap-in->
- Mobile Innovation Network, *UIImage to ImageBitmap — Compose Multiplatform* — <https://medium.com/mobile-innovation-network/uiimage-to-imagebitmap-compose-multiplatform-497230a71f37>
- MVP Factory, *Taming Compose Multiplatform Image Decoding on iOS* — <https://mvpfactory.io/blog/taming-compose-multiplatform-image-decoding-on-ios-skia-codec-pitfalls-nsimage/>
- FileKit #555 (HEIC/Skia via PHPicker representation mode) — <https://github.com/vinceglb/FileKit/issues/555>

**Repo context**
- `docs/superpowers/research/2026-08-14-ios-preview-perf-leftovers.md` (decode is ~94% of a cold
  switch; the 128-vs-1920 inversion; the 128 MiB joint cache ceiling)
- `docs/superpowers/research/2026-08-13-product-thumb-coil-ab.md` (why HEIC must go through ImageIO)
- `docs/adr/0010-c2-golden-policy-delta.md` (verify renders by viewing, not by byte size)
- ADR-0028 (Coil 3 for UI thumbs, not watermark export decode)

---

## Executive verdict

- **The brief undercounted the copies, and the biggest one is off-list.** Verified in skiko's C++:
  `Bitmap.installPixels(ByteArray)` does `malloc`+`memcpy`, `Image.makeRaster(ByteArray)` is
  `RasterFromPixmapCopy`, and Compose's `Image.toComposeImageBitmap()` re-rasters through
  `allocPixels`+`drawImage`. Preview and **full-res export** each pay **three** full-frame writes,
  not two — that is ~146 MB of transient buffers for a 12 MP export.
- **The right fix is to invert the transfer, not to chase `makeWithoutCopy`.** `Data.makeUninitialized`
  + `writableData()`, or `Bitmap.allocPixels` + `peekPixels().addr`, give a Skia-owned buffer that
  `CGBitmapContextCreate` can draw straight into. One write, all inputs, refcounted by Skia, zero
  lifetime hazard. `Image.makeRaster(info, Data, rowBytes)` (`RasterFromData`) does not copy.
- **The `CFData` zero-copy variant is rejected on lifetime grounds.** skiko's
  `makeWithoutCopy(..., underlyingMemoryOwner: Managed)` cannot anchor a `CFDataRef`, Coil gives us
  no disposal hook, and Skia's own doc scopes `MakeWithoutCopy` to "const globals". The payoff is a
  bounded `memcpy`; the risk is an unbounded use-after-free.
- **The format-match fast path is the weakest of the three ideas and is gated behind a probe.** QA1509
  warns provider bytes are not colour-matched, and the community CMP snippet that does exactly this
  is on record producing wrong colours for Apple Photos images. iPhone HEIC is Display P3. If Phase 0
  measures < 30% gate hit rate on real album photos, cancel it.
- **Sell this as memory, not speed, and do not touch the filmstrip for it.** At 1920 the removable
  copies are ~1.5–2% of a ~215 ms decode; at 128 px they are ~10 µs. The defensible wins are ~22 MB
  (preview 1920) and ~98 MB (12 MP export) of transient allocation removed on a jetsam-prone platform.
- **No Coil change is needed.** `asPainter` returns the same `Bitmap` for a `BitmapImage` without
  copying, so keeping `DecodeResult(image = bitmap.asImage())` is correct; changing the return type
  would move us onto a less-travelled painter for no measured gain.
