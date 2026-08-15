# iOS HEIC thumbnail latency: what Apple's APIs actually do (2026-08-15)

Primary-source investigation of why a 128 px HEIC thumbnail can cost as much as (or more than) a
1920 px one on iOS, and whether any Apple API avoids the full decode for a UI thumb.

Follow-up to `2026-08-14-ios-preview-perf-leftovers.md` (S3) and
`2026-08-14-ios-cgimage-skia-zero-copy-plan.md`. **Research only — no production Kotlin or Swift was
modified.**

---

## Executive verdict

1. **There is no "128 px penalty" to fix. There is a fixed full-frame HEVC decode to avoid, reuse, or
   amortise.** For HEVC-in-HEIF, `kCGImageSourceThumbnailMaxPixelSize` does not change the amount of
   work ImageIO does. An iPhone album HEIC contains exactly **one** coded image, at full resolution,
   and no embedded thumbnail (§2.3, measured). ImageIO must decode that whole intra frame whatever
   size you ask for. What the requested size *does* change is only the final resample, which is
   0.12 ms at 128 px and 9.5 ms at 1920 px on the host (§2.1). So 128 px carries a *smaller* variable
   cost than 1920 px on top of an identical fixed cost — the two orderings are separated by less than
   the run-to-run variance of the fixed part, which is why the measured direction flips between runs.
2. **`kCGImageSourceSubsampleFactor` is honoured dimensionally for HEIF but not economically.** With
   `CGImageSourceCreateImageAtIndex` the output shrinks by exactly 2/4/8 (verified). The decode time
   does not fall with it. The same key on JPEG *does* buy a real ~1.8× (DCT-scaled decode). HEIF is
   the exception, and Apple's own wording never promised otherwise: the header says the result "will
   be smaller and have reduced spatial quality", i.e. an **output contract, not a performance
   contract** (§2.2).
3. **Nothing in the ImageIO options dictionary can beat `Always` + `Subsample` for 128 px album
   HEIC.** I swept requested size 128→4096, all three subsample factors, `ShouldCache` both ways,
   `IfAbsent`, no-flags, and the HDR knobs (`kCGImageSourceDecodeToSDR` +
   `kCGImageSourceGenerateImageSpecificLumaScaling=false`). Order-balanced, every variant lands on
   the same flat floor; the SDR variant is measurably **worse** (§2.1). The current production
   options are already at the ImageIO ceiling.
4. **The one Apple API that beat it in measurement is `QLThumbnailGenerator`** — ~1/3 of the host
   ImageIO cost on first touch, and **~1.4 ms on any repeat call for the same file** from a
   system-managed persistent cache (§2.5). It works on plain file URLs, needs no new permission, and
   is therefore the top-ranked experiment. It has two sharp edges for this repo: our provisional
   files have **no file extension**, so `request.contentType` must be set or generation fails
   outright; and `LowQualityThumbnail` returns nothing for a freshly-copied file, so there is no
   "cheap cached first paint" on the import path.
5. **`PHImageManager` / `PHCachingImageManager` are rejected on product-policy grounds before perf
   even enters.** They require a `PHAsset`, which requires photo-library **read** authorisation. This
   app ships add-only (`NSPhotoLibraryAddUsageDescription` only) precisely so it never asks. Also,
   `PHImageRequestOptionsResizeModeFast` is documented as *subsampling* — the same mechanism that
   §2.2 shows does nothing for HEVC (§4).
6. **VideoToolbox has no still-HEIC thumb path.** `kVTDecompressionPropertyKey_ReducedResolutionDecode`
   exists but is optional for decoders to implement, and the Apple-silicon HEVC decoder does not:
   absent from the supported-property dictionary, and `VTSessionSetProperty` returns **-12900**
   `kVTPropertyNotSupportedErr` (§2.6, measured).
7. **The highest-ROI fix is entirely local and needs no new Apple API:** stop paying a second full
   HEVC decode for the 128 px filmstrip cell when a decode of the same photo is already resident in
   the preview working set. Deriving 128 px from an already-decoded frame is a ~1 ms draw against a
   ~180 ms decode.

---

## 1. Method, and what counts as authoritative here

Three evidence tiers, kept separate on purpose.

| Tier | What it is | How it is labelled |
|---|---|---|
| **Device fact** | iPhone 16 Pro, 8 album HEICs, order-balanced: io128 plain 183 ms / sub 198 ms; io1920 plain 143 ms / sub 124 ms; Coil cold ≈210 ms. Given to me as authoritative. | "device" |
| **Header / doc text** | Read verbatim out of the iOS 27.0 SDK on this machine, plus the matching developer.apple.com pages (all URLs HTTP-200 checked). | quoted, with symbol + path |
| **Host reproduction** | Throwaway Swift probes run on **this Mac** (macOS 27 beta SDK, Apple silicon) against two real iPhone-captured HEICs. | "host" |

Host absolute latency does **not** transfer to device: this Mac spends ~400–530 ms on a 14 MP HEIC
thumbnail where the iPhone 16 Pro spends ~180 ms, which is a ~40× gap against the JPEG control on
the same machine and strongly suggests the Mac is not using the same accelerated HEVC still path.
Host numbers are used only for **structure** (is a key honoured, does cost scale with requested size,
does a file contain an embedded thumbnail, does a property get accepted) and for **ratios between
variants measured back-to-back in one process**.

Probe sources are reproduced in §7 so the numbers can be re-derived; they lived in `/tmp` and are not
part of the repo.

**On the device fact:** nothing below contradicts it. §2.1 explains it — a size-independent fixed
decode plus a small size-dependent resample — and predicts that under per-photo paired sampling at
higher n the 183/143 gap narrows rather than inverting cleanly. That prediction is Experiment E0.

---

## 2. Findings

### 2.1 The cost is size-independent for HEIF and size-proportional for JPEG

`CGImageSourceCreateThumbnailAtIndex` with production-identical options
(`FromImageAlways` + `WithTransform` + `ShouldCache=false`), requested long edge swept, **order
balanced** (variant order reversed on alternate reps, n=7, median), every variant then drawn down to
the same final 128 px output so all rows deliver the same product:

| Requested edge | HEIC A create | HEIC A draw→128 | HEIC B create | JPEG control create | JPEG draw→128 |
|---:|---:|---:|---:|---:|---:|
| 128 | 230.6 | 0.12 | 258.3 | **9.8** | 0.08 |
| 256 | 232.3 | 0.33 | 295.4 | 10.0 | 0.27 |
| 384 | 216.9 | 0.59 | 222.7 | 10.3 | 0.45 |
| 512 | 235.2 | 0.84 | 215.6 | 10.8 | 0.70 |
| 720 | 262.1 | 1.59 | 241.8 | 15.3 | 1.11 |
| 1024 | 275.7 | 2.66 | 278.0 | 15.8 | 2.07 |
| 1440 | 218.1 | 5.55 | 181.8 | 29.8 | 4.01 |
| 1920 | 242.8 | 9.46 | 244.3 | 30.7 | 7.12 |
| 2560 | 157.2 | 16.74 | 144.9 | 78.0 | 12.26 |
| 4096 | 193.5 | 43.29 | 170.4 | **95.3** | 31.15 |

(ms, host. HEIC A = 3273×4265 Display P3, HEIC B = 3158×4501 Display P3, JPEG = A re-encoded.)

The JPEG column is cleanly monotone across a 9.7× range. **Both HEIC columns are flat and noisy
across the same sweep** — no trend, spread ≈145–300 ms with no relation to the requested size.

This is the whole explanation for the brief's puzzle. Two corollaries worth stating plainly:

- A grouped-reps measurement of the same thing produced a clean-looking 2:1 "inversion" (e128
  350 ms vs e1920 167 ms). Order-balancing erased it. This is the exact order-bias failure S1 already
  documented in this repo, reappearing in a new measurement; treat any HEIF size comparison as
  order-biased until proven otherwise.
- "Ask ImageIO for a bigger thumbnail and downscale yourself" buys nothing, because ImageIO's own
  resample was never the cost: the 512→128 draw is 0.84 ms.

### 2.2 `kCGImageSourceSubsampleFactor` for HEIF: dimensions yes, decode no

Header, verbatim (`CGImageSource.h:224–239`, iOS 27.0 SDK):

> Specifies that, if possible, an image should be returned as scaled down (in height and width) by a
> specified factor. The resulting image will be smaller and have reduced spatial quality but will
> otherwise have the same characteristics as the full size normal image. If the specified scaling
> factor is not supported, a larger or full size normal image will be returned. **Supported file
> formats are JPEG, HEIF, TIFF, and PNG.** The value of this key must be an integer CFNumberRef
> (allowed values: 2, 4, and 8).

So HEIF is explicitly in scope, and the guarantee is about the returned image's *size and quality*.
Apple never states where in the pipeline the reduction happens, and never promises a time saving.
Measured (`CGImageSourceCreateImageAtIndex`, lazy create timed separately from the forced draw):

| Variant | HEIC out dims | HEIC decode | JPEG out dims | JPEG decode |
|---|---|---:|---|---:|
| plain | 3273×4265 | 614.5 | 3273×4265 | 16.3 |
| sub2 | 1636×2132 | 457.5 | 1636×2132 | 16.1 |
| sub4 | 818×1066 | 423.3 | 818×1066 | **9.1** |
| sub8 | **409×533** | **581.2** | **409×533** | **9.1** |

(ms, host, n=5 median.)

The factor is honoured **exactly** for HEIF — output is a clean /2, /4, /8. The time is not: sub8 is
within noise of plain, and non-monotone against sub4. JPEG shows what a real decoder-side reduction
looks like: 16.3 → 9.1 ms, monotone, ~1.8×. Conclusion: for HEVC-in-HEIF the reduction is applied
**after** the full frame is decoded; for JPEG it is applied **inside** the decode (DCT scaling).

This is consistent with the codec: a single-layer HEVC intra still has no reduced-resolution decode
mode. There is nothing smaller than the full frame in the bitstream to decode.

Also note, for the repo's `IosImageIOSubsampleTest` invariant: when combined with
`kCGImageSourceThumbnailMaxPixelSize` on the **thumbnail** API, output dimensions are governed by
`MaxPixelSize`, so subsampling is invisible in the output. That matches the device observation
"output dims identical plain vs sub" and means the existing dimension test cannot detect whether the
key did anything at all.

### 2.3 Real iPhone HEICs contain no embedded thumbnail — so `IfAbsent` has nothing to hit

Header (`CGImageSource.h:162–198`):

> `kCGImageSourceCreateThumbnailFromImageIfAbsent` — Specifies whether a thumbnail should be
> automatically created for an image if a thumbnail **isn't present in the image source file**.
> `kCGImageSourceCreateThumbnailFromImageAlways` — … created from the full image **even if a
> thumbnail is present** … The thumbnail will be created from the full image, subject to the limit
> specified by `kCGImageSourceThumbnailMaxPixelSize`.

`CGImageSourceGetCount` is documented as "the number of images (**not including thumbnails**)"
(`CGImageSource.h:331–344`), so an embedded thumbnail is not addressable as an image index; the only
enumeration surface is `kCGImagePropertyThumbnailImages`.

Measured on two real iPhone-captured HEICs:

```
FILE 7BD930F1-…heic  type=public.heic  count=1  primaryImageIndex=0
  container property keys: ["CanAnimate", "FileSize", "{FileContents}"]
  kCGImagePropertyThumbnailImages: ABSENT at container level
  image[0]: 3273x4265 depth=8 colorSpace=Display P3 primary=1
  AUX kCGImageAuxiliaryDataTypeISOGainMap: PRESENT desc=[Width: 1637, Height: 2133, …]
```

One coded image. No thumbnail array. `IfAbsent` therefore has nothing to return and falls through to
generating from the full image — measured identical to `Always` (`ifAbsent@128` 533.4 ms vs
`always@128` 533.2 ms in the grouped run). The existing production comment ("IfAbsent returns
whatever embedded thumbnail the file happens to carry, at an unpredictable and possibly tiny size")
describes a hazard that, for this input population, **does not arise** — but the flag also buys
nothing, so keeping `Always` is still right.

Incidental host observation: passing *neither* flag still returned a correctly sized thumbnail, while
passing `Always=0` explicitly returned `nil`. Undocumented; do not build on it.

`kCGImageSourceCreateThumbnailWithTransform` (`CGImageSource.h:212–222`) is the orientation/aspect
baker and must stay — it is the reason this path needs no separate rotate step (matching the AGENTS
EXIF policy: bake once at the decode edge, never re-rotate).

### 2.4 Auxiliary images are not thumbnails, and the gain map is not the cost

`CGImageSourceCopyAuxiliaryDataInfoAtIndex` (`CGImageSource.h:523–541`) returns depth data, a data
description, metadata and an optional colour space. The complete list of aux types
(`CGImageProperties.h:2340–2374`) is: `Depth`, `Disparity`, `PortraitEffectsMatte`, five
`SemanticSegmentation*Matte`s, `HDRGainMap`, `ISOGainMap`. **No thumbnail type exists.** There is no
"embedded preview track" to enumerate.

Both test HEICs carry `kCGImageAuxiliaryDataTypeISOGainMap` at roughly half resolution plus a
`Headroom` property, which makes a plausible story: maybe the fixed cost is a *second* HEVC decode
for the gain map plus tone mapping (`kCGImageSourceGenerateImageSpecificLumaScaling` defaults to
`kCFBooleanTrue`, `CGImageSource.h:558–560`). **Measured, and the story is wrong:**

| File | Gain map | ask128 | ask1920 |
|---|---|---:|---:|
| original iPhone HEIC | YES | 412.7 | 416.1 |
| same image re-encoded HEIC, primary only | **no** | 533.5 | 467.9 |
| same image re-encoded JPEG | no | **10.6** | **41.2** |

(ms, host, n=7 median, thumbnail create only.)

Stripping the gain map did not reduce the cost. And asking ImageIO to skip HDR work explicitly
(`kCGImageSourceDecodeRequest: kCGImageSourceDecodeToSDR` +
`kCGImageSourceGenerateImageSpecificLumaScaling: false`) made 128 px **worse**, twice: 292.9 vs 230.6
on HEIC A, 295.7 vs 258.3 on HEIC B. The HDR knobs are a dead end for this workload; the cost is the
HEVC intra frame itself.

### 2.5 `QLThumbnailGenerator` is the only Apple API that measured faster

Header (`QLThumbnailGenerationRequest.h`), the three representation types:

> `…TypeIcon` — an image that represents the **file type** of the request …
> `…TypeLowQualityThumbnail` — a thumbnail … that **may come from a previously generated and cached
> copy** or faster lower quality generation, not satisfying the parameters of the request …
> `…TypeThumbnail` — a thumbnail representing the file, satisfying the parameters of the request
> (**either retrieved from the cache, or generated**).

Measured on host, against a file copied into `NSTemporaryDirectory()` under the repo's own
`ewm_import_provisional_<UUID>` naming (i.e. **no file extension**), which is what
`PhotoImportCoordinator`/`ImageFileTransfer` actually produces:

| Request | Icon (t0) | LowQuality (t1) | Thumbnail (t2) |
|---|---|---|---|
| `.heic` extension, `contentType` unset | 256×256 @ 21 ms | **nil, error 2** | 294×384 @ 93.6 ms |
| no extension, `contentType` unset | 256×256 @ 28 ms | nil, error 2 | **nil, error 0 — total failure** |
| no extension, `contentType = .heic` | 256×256 @ 0.1 ms | nil, error 2 | 294×384 @ 77.6 ms |

Repeat calls against the **same** file:

| Call | Result |
|---|---|
| 1 | 98×128 @ **99.9 ms** |
| 2 | 98×128 @ **1.5 ms** |
| 3 | 98×128 @ **1.4 ms** |
| 4 | 98×128 @ **1.1 ms** |

Size sweep, fresh file each time: 128@1× → 85.9 ms, 128@3× → 81.6 ms, 384@1× → 74.3 ms,
512@1× → 81.7 ms, 56@3× → 85.9 ms. **Flat, same signature as ImageIO** — but at roughly a third of
the host ImageIO cost for the same delivered pixels (74–100 ms vs 217–235 ms).

Five things follow, and the last three are why this is an experiment rather than a recommendation:

1. `contentType` is **mandatory** for this repo. Our provisional files have no extension and QL
   derives type from the extension by default (`QLThumbnailGenerationRequest.contentType` doc). Get
   it wrong and you get a hard failure, not a slow path.
2. `Icon` is a file-type glyph, not the photo. It is useless as a filmstrip cell and must never be
   accepted as one — which means `representationTypes: .all` needs an explicit filter on
   `type == .thumbnail`.
3. `LowQualityThumbnail` never fired for a freshly-copied file. There is no cached-first-paint win on
   the import path; the cache only pays off on revisit.
4. QL is **out-of-process** (a thumbnail extension via XPC). The 74–100 ms includes IPC, so the real
   decode is cheaper still — but it also means unknown concurrency limits, and a fast filmstrip
   scroll could serialise behind the agent. Untested.
5. The host advantage may be a host artefact. If the Mac's ImageIO path is unaccelerated and QL's
   agent is not, the ratio could collapse to ~1.0 on device, where ImageIO already costs only
   180 ms. **This must be measured on the iPhone 16 Pro before any code moves.**

### 2.6 VideoToolbox: the HEVC decoder does not offer reduced-resolution decode

`kVTDecompressionPropertyKey_ReducedResolutionDecode` (`VTDecompressionProperties.h:256–266`):

> Requests decoding at a smaller resolution than full-size. This is an **optional property for video
> decoders to implement**. Decoders that only support a fixed set of resolutions should pick the
> smallest resolution greater than or equal to the requested width x height.

Measured: created a `VTDecompressionSession` for an `hvc1` track on this machine, then

- `VTSessionCopySupportedPropertyDictionary` → 43 supported keys;
  `ReducedResolutionDecode` is **absent**. Only `PixelTransferProperties`, `RealTime`,
  `ReducedFrameDelivery` matched `Reduced*`/`PixelTransfer*`.
- `VTSessionSetProperty(…ReducedResolutionDecode, 1/8 size)` → **-12900**
  (`kVTPropertyNotSupportedErr`).
- `VTSessionCopyProperty(…PixelFormatsWithReducedResolutionSupport)` → **-12900**, value nil.

Same result for `avc1`. So even if you were willing to parse ISO BMFF to pull `hvcC` + the coded item
out of the HEIC yourself, the decoder would still hand you a full-resolution frame, and
`kVTDecompressionPropertyKey_PixelTransferProperties` / `kVTPixelTransferPropertyKey_ScalingMode`
only scale *after* that. There is no thumb path here — only the ImageIO path with all the colour
management, orientation baking and format handling removed.

Caveat: macOS decoder, not the iOS one. The property is optional per-decoder, so an iOS HEVC decoder
*could* in principle answer differently; if anyone wants to close that, the check is four lines of
Swift on device and it is listed as E5.

### 2.7 Two production suspicions checked and cleared

Both worth recording because they are the kind of thing that would silently invalidate the device
numbers.

- **`IosImageIODecoder.createThumbnail` builds its options dictionary from raw string literals**
  (`NSString.create(string = "kCGImageSourceCreateThumbnailFromImageAlways")`) instead of the
  ImageIO constants. Verified by printing the constants: every `kCGImageSource*` option key's CFString
  **value equals its symbol name**, so all five literals are correct. (Property keys are *not* like
  this — `kCGImagePropertyPixelWidth` is `"PixelWidth"`, `kCGImagePropertyOrientation` is
  `"Orientation"` — which the existing `propertyInt(dictionary, key, fallback)` already handles via
  its `fallback` argument.)
- **The boolean options are passed as `NSNumber.numberWithInt(1)`** where the header says
  "must be a CFBooleanRef". Verified equivalent: `NSNumber(1)` and `kCFBooleanTrue` produce identical
  output, and `NSNumber(0)` correctly disables generation (returns nil). Undocumented tolerance, but
  it works today on iOS 27 / macOS 27 SDKs.

### 2.8 Repo state that shapes the ranking

- **Coil's disk cache is off.** `productThumbDefaults()` is `memoryCachePolicy(ENABLED)` +
  `diskCachePolicy(DISABLED)`, so every cold app session re-pays the full HEVC decode for every
  filmstrip cell. This is the single largest amortisation opportunity and it is one line — but it
  writes derived copies of user photos to app-private storage, which is a **privacy-posture decision
  for the owner**, not an implementation detail (§5, E2).
- **`ProductUi` sets `imageIoShouldCache = false`.** Correct for scroll thumbs. One host observation
  deserves follow-up: in the grouped (order-biased) run, `cache=true` + subsample landed at
  247–283 ms against `cache=false` + subsample at 332–337 ms. That may be ImageIO reusing decoded
  state across reps of the same file — i.e. an artefact of the biased design — so it is listed as a
  cheap device check (E4) and **not** as a finding.
- **`IosPreviewImageRepository` already has an `IosPreviewPurpose.Filmstrip`**, alongside
  `SourcePlaceholder` at 720–1920 for focus ±2, while the shared filmstrip UI goes through the Coil
  `ImageLoader` with `IosHeifImageDecoder`. So the app can hold a resident full decode of photo *N*
  and still pay a second full HEVC decode to draw photo *N*'s 128 px cell. That is E1.

---

## 3. Statement asked for: what could beat ImageIO `Always` + `Subsample` at 128 px

**No ImageIO option combination can.** The current production options are at the ceiling of that API
for this input, because the file offers nothing smaller than a full-resolution HEVC frame and the
subsample key does not move the reduction into the HEVC decoder. Anything that wins has to change
*what is decoded* or *how often*:

| Strategy | Ceiling | Why it can win |
|---|---|---|
| **Reuse a decode already in memory** | ~1 ms | The preview working set often already holds this photo at 720–1920. Deriving 128 px is a draw (host: 0.84 ms from 512 px), not a decode. |
| **`QLThumbnailGenerator`** | host ~75 ms first touch, **~1.4 ms** on repeat | A system-managed persistent thumbnail cache outside our process, plus a cheaper generator. Device ratio unknown. |
| **Persist our own 128 px thumbs** | ~1–5 ms on any later session | Turns a per-session 180 ms into a one-time 180 ms. Pure amortisation; no API risk. |
| **Move the cost off the critical path** | unchanged total, better perceived | Prefetch neighbours, bound concurrency, paint a placeholder. Does not make the decode cheaper. |

Everything else investigated is rejected below.

---

## 4. Rejected options

| Option | Verdict | Why (evidence) |
|---|---|---|
| `kCGImageSourceCreateThumbnailFromImageIfAbsent` instead of `…Always` | **Rejected** | iPhone HEICs have no embedded thumbnail — `GetCount`=1, `kCGImagePropertyThumbnailImages` absent (§2.3). Nothing to hit; measured identical to `Always`. |
| Enumerate embedded previews via auxiliary-image APIs | **Rejected** | The aux type list is depth/disparity/mattes/HDR-and-ISO gain maps only. No thumbnail type exists (`CGImageProperties.h:2340–2374`, §2.4). |
| Drop `kCGImageSourceSubsampleFactor` (it looked worse at 128 on device) | **Not yet** | It is dimensionally free and, on host, never worse than plain outside noise. Removing it should be a *measured* decision (E0), not a reaction to one 183-vs-198 pair. |
| Raise/lower the requested edge to find a cheap size | **Rejected** | Cost is flat 128→4096 for HEIF (§2.1). No sweet spot exists. |
| Ask for a mid-size thumbnail and downscale ourselves | **Rejected** | ImageIO's resample was never the cost: 512→128 draw is 0.84 ms (§2.1). |
| `kCGImageSourceDecodeToSDR` + disable `GenerateImageSpecificLumaScaling` | **Rejected** | Measurably **worse** on both HEICs at 128 px (§2.4). Gain map is not the driver — stripping it entirely did not help either. |
| `kCGImageSourceShouldCacheImmediately` | **Rejected as written** | With `ShouldCache=false` it produced a double decode on host (create 507 ms *and* draw 537 ms). If ever tried, it must be paired with `ShouldCache=true` and a residency budget. |
| `PHImageManager.requestImage` / `PHCachingImageManager.startCachingImagesForAssets` | **Rejected (policy, then perf)** | Needs a `PHAsset` → photo-library **read** authorisation. The app declares only `NSPhotoLibraryAddUsageDescription` and calls `requestAuthorization(for: .addOnly)`; adding read access contradicts the no-permission privacy promise in AGENTS.md. Perf is also not obviously better: `PHImageRequestOptionsResizeModeFast` is documented as "use targetSize as a hint for optimal decoding when the source image is a compressed format (**i.e. subsampling**)" — the mechanism §2.2 shows is inert for HEVC. `DeliveryModeFastFormat` is documented as "may be degraded", i.e. unpredictable sharpness for a filmstrip cell. |
| `PHPickerConfiguration(photoLibrary:)` to obtain `assetIdentifier` | **Rejected** | `PHPickerResult.assetIdentifier` is nullable and only populated when the configuration was built with a photo library; the identifier is then only useful with read authorisation. Same policy wall, plus it would rework the whole path-first import (ADR-0021). |
| VideoToolbox / `VTDecompressionSession` for still HEIC | **Rejected** | HEVC decoder does not implement `ReducedResolutionDecode`: absent from the supported dictionary, `-12900 kVTPropertyNotSupportedErr` (§2.6). You would hand-parse ISO BMFF, lose colour management and orientation baking, and still decode the full frame. |
| `IOSurface` / `CVPixelBuffer` / Metal-texture path to skip the CPU RGBA draw | **Rejected** | The draw at 128 px is **0.12 ms** against a ~180 ms decode (§2.1). Confirms the same call in `2026-08-14-ios-cgimage-skia-zero-copy-plan.md` §9. |
| Transcode HEIC→JPEG once, then use the fast JPEG path | **Rejected** | The transcode itself pays the full HEVC decode, plus an encode; and a JPEG sidecar per imported photo is a worse version of E2's cache with extra quality loss. |
| Replace Coil, or return a non-`BitmapImage` from the decoder | **Rejected** | ADR-0028; and the copy accounting in the zero-copy doc §3.5/§7.2 already showed no benefit. |
| Re-add a strict FNV golden gate to police thumbnail sharpness | **Rejected** | ADR-0010: strict gate is local-only, and renders are verified by *viewing*. |

---

## 5. Ranked experiments for this repo (cheapest first)

Each is independently revertible. E0 comes first because it decides whether there is anything to fix
in the ImageIO options at all, and it costs no production change.

### E0 — Re-measure the device 128-vs-1920 gap, per-photo paired (measurement only)

**Cost:** bench harness only. **Blocks:** the entire "128 is anomalously slow" premise.

`IosDevicePerfBench` currently reports per-path medians. Change the *analysis*, not the decoder:
record `(photo, variant) → ns` and report the **paired** delta per photo plus its spread, with
variant order reversed on alternate reps (§2.1's lesson). Add `ask512` and `ask720` rows so a flat
profile is visible if it is there.

*Predicted outcome:* the 183/143 pair narrows toward ~9 ms (the resample delta), and 198-vs-183 for
subsample collapses into noise. If instead the 40 ms gap survives paired sampling at n≥30, the
device HEVC path differs from the host in a way worth escalating — that would be a genuinely new
finding.

*Success criteria:* a paired per-photo table with spread, not four medians. **No perf claim either
way until this exists.**

### E1 — Derive the 128 px filmstrip cell from an already-resident preview decode

**Cost:** small, local, no new Apple API. **Expected win:** ~180 ms → ~1 ms on hits.

The preview working set already holds `SourcePlaceholder` frames at 720–1920 for focus ±2
(`IosPreviewImageRepository`). Before the Coil `IosHeifImageDecoder` opens a `CGImageSource`, check
whether a decode of the same owned path is resident at a *larger* bucket and, if so, produce the
128 px cell by drawing it down.

Design notes that matter:

- Downscaling 1920→128 in one step is a ~15× reduction; use the same high-quality interpolation the
  ImageIO thumbnail applies, and **compare screenshots** (ADR-0010) — this is the one experiment here
  with a real sharpness risk.
- The hit rate is bounded by the working set (focus ±2 of 5–6 entries), so the win is on the
  *visible neighbourhood*, exactly where scroll jank is felt. Report hit rate, not just latency.
- Do not let a filmstrip request *cause* a preview-sized decode; read-only opportunistic reuse.
- Eviction priority already prefers dropping `SourcePlaceholder` last for cost reasons
  (`2026-08-14-ios-preview-perf-leftovers.md` S5) — this makes that ordering pay twice.

*Success criteria:* measured hit rate ≥ 40% during a normal scrub; on hits, cell latency in single-digit
ms; filmstrip screenshots viewed side by side against today. Revert if sharpness differs visibly.

### E2 — Persist the 128 px thumbs (owner-gated: privacy posture)

**Cost:** one line to flip, plus a retention policy. **Expected win:** first touch unchanged;
every later session ~free.

`productThumbDefaults()` sets `diskCachePolicy(DISABLED)`. Enabling a small, capped disk cache for
`ProductUi` only turns a per-session 180 ms/photo into a one-time cost.

**This needs an owner decision before code.** The app's promise is offline and zero-tracking, not
zero-derived-storage — but writing downscaled copies of user photos into app-private storage is
visible in a Data-Safety/privacy review and needs: an explicit cap, a documented eviction/retention
rule, clearing on session end or app data clear, and a line in the privacy documentation. If the
answer is no, say no in an ADR and stop; do not ship it quietly.

*Success criteria:* second-launch filmstrip paint under 20 ms/cell; disk footprint capped and
asserted by test; ADR recorded either way.

### E3 — `QLThumbnailGenerator` A/B on device

**Cost:** medium — a Swift host-side path plus a bridge, behind a probe flag. **Expected win:**
unknown on device; host says 3×.

Do this **after** E0 and E1, because it adds a cross-process dependency to the hot path and E1 may
already remove most of the cost.

Must-haves, all from §2.5:

- `request.contentType = UTType.heic` (or derived from the picker's type), because provisional files
  have no extension and generation **fails** without it.
- Accept only `type == .thumbnail`; never paint `.icon`.
- `scale` chosen so the delivered pixels match the 56/48/40 dp filmstrip geometry, not 3× by
  accident.
- Measure under a *fast scroll*, not one-at-a-time: the XPC concurrency limit is the unknown that
  matters, and a per-request 75 ms that serialises is worse than a 180 ms that parallelises.
- Fall back to ImageIO on any nil/error, with the fallback rate logged.

*Success criteria:* order-balanced device medians beating ImageIO by ≥ 30% **and** no scroll
regression under a 30-item fast scrub, plus viewed screenshots. Otherwise revert and record the
negative result.

### E4 — `imageIoShouldCache = true` for `ProductUi`, order-balanced

**Cost:** one boolean. **Expected win:** unclear; may be zero and may cost memory.

Chases the §2.8 host observation that `cache=true` + subsample was faster. Likely an artefact of a
biased design, so measure it properly (paired, order-reversed, cold per photo) and watch peak RSS —
this app runs under a 128 MiB joint preview ceiling on a jetsam-prone platform. Revert unless the win
is real and the memory cost is bounded.

### E5 — Four-line device confirmation that iOS's HEVC decoder also lacks reduced-resolution decode

**Cost:** trivial. **Expected win:** none — this closes §2.6's caveat.

On device, create a `VTDecompressionSession` for an HEVC format description and log
`VTSessionCopySupportedPropertyDictionary` plus the `VTSessionSetProperty` status for
`kVTDecompressionPropertyKey_ReducedResolutionDecode`. If it returns `noErr` on iOS, §2.6's rejection
reopens and the VideoToolbox route deserves a real look. If it returns -12900, the door is shut with
device evidence and this line of enquiry can be closed permanently.

### Not an experiment: things to stop considering

Subsample tuning, requested-size tuning, HDR/SDR knobs, `IfAbsent`, aux-image enumeration,
VideoToolbox, and zero-copy pixel transfer for the filmstrip. §2 and §4 close all of them with
measurement or header text.

---

## 6. Honest limits

- **Every absolute latency in §2 is macOS host, not iOS device.** The host is ~40× slower than its
  own JPEG control on HEIC, which the iPhone is not, so at least one accelerated path differs. Only
  the structural conclusions and within-process variant ratios should be carried across.
- **n is small** (5–7 per variant, 2 HEIC files). Enough to establish "flat vs monotone" and
  "dimensions honoured vs not"; **not** enough to rank two variants within ~15% of each other. No
  ranking in this document depends on such a margin, and where one might (E4) it is labelled as an
  artefact to be re-measured.
- **The QuickLook 3× is the least trustworthy number here** and the most consequential if true. It
  could be entirely a host artefact. E3 exists to find out; nothing should be built on it first.
- **§2.6 is macOS-only** for the decoder capability. The property is documented as optional
  per-decoder, so iOS is not strictly proven. E5 closes it.
- **E1's sharpness is unverified.** A 15× single-step downscale is not obviously equivalent to
  ImageIO's own reduction, and per ADR-0010 that must be settled by viewing screenshots, not by
  asserting dimensions.
- **I did not test on an actual device at all.** No claim in this document is a device perf claim.
- **The 8-photo device bench population is small and same-camera.** Whether older HEICs (no gain map,
  smaller, different chroma format) behave the same is untested; §2.4's re-encode is a proxy, not a
  real older capture.

---

## 7. Reproducing the host measurements

Throwaway Swift, compiled with `xcrun -sdk macosx swiftc -O`. Not repo code; reproduced so the
numbers are checkable.

**Structure dump (§2.3, §2.4)** — counts, primary index, thumbnail arrays, aux types:

```swift
guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return }
print(CGImageSourceGetCount(src), CGImageSourceGetPrimaryImageIndex(src))
let container = CGImageSourceCopyProperties(src, nil) as? [String: Any]
print(container?[kCGImagePropertyThumbnailImages as String] ?? "ABSENT")
for t in [kCGImageAuxiliaryDataTypeDepth, kCGImageAuxiliaryDataTypeHDRGainMap,
          kCGImageAuxiliaryDataTypeISOGainMap] {
    if CGImageSourceCopyAuxiliaryDataInfoAtIndex(src, 0, t) != nil { print("AUX", t) }
}
```

**Order-balanced size sweep (§2.1)** — the ordering discipline is the point:

```swift
for r in 0..<reps {
    let order = r % 2 == 0 ? variants : variants.reversed()   // cancel order bias
    for v in order {
        let src = CGImageSourceCreateWithURL(url as CFURL, nil)!   // fresh source per sample
        let t1 = DispatchTime.now().uptimeNanoseconds
        let img = CGImageSourceCreateThumbnailAtIndex(src, CGImageSourceGetPrimaryImageIndex(src),
                                                     v.opts as CFDictionary)
        let t2 = DispatchTime.now().uptimeNanoseconds      // create
        // then draw to a fixed 128 px target and time that separately
    }
}
```

**Subsample honoured-or-not (§2.2)** — `CreateImageAtIndex`, so output dims are not masked by
`ThumbnailMaxPixelSize`; time the forced draw, because create is lazy:

```swift
var o: [CFString: Any] = [kCGImageSourceShouldCache: false]
o[kCGImageSourceSubsampleFactor] = 8
let img = CGImageSourceCreateImageAtIndex(src, 0, o as CFDictionary)!
print(img.width, img.height)      // 409x533 from 3273x4265 — honoured
// draw into a CGBitmapContext here; that draw is where the decode actually happens
```

**VideoToolbox capability (§2.6):**

```swift
VTDecompressionSessionCreate(allocator: nil, formatDescription: fd, decoderSpecification: nil,
                             imageBufferAttributes: nil, outputCallback: nil,
                             decompressionSessionOut: &session)
VTSessionCopySupportedPropertyDictionary(session!, supportedPropertyDictionaryOut: &supported)
let s = VTSessionSetProperty(session!, key: kVTDecompressionPropertyKey_ReducedResolutionDecode,
                             value: [kVTDecompressionResolutionKey_Width: w/8,
                                     kVTDecompressionResolutionKey_Height: h/8] as CFDictionary)
// s == -12900 (kVTPropertyNotSupportedErr) for hvc1 and avc1
```

**QuickLook (§2.5)** — note `contentType`, and only accepting `.thumbnail`:

```swift
let req = QLThumbnailGenerator.Request(fileAt: url, size: CGSize(width: 128, height: 128),
                                       scale: 1.0, representationTypes: .all)
req.iconMode = false
req.contentType = UTType.heic          // REQUIRED: provisional files have no extension
QLThumbnailGenerator.shared.generateRepresentations(for: req) { rep, type, err in
    guard type == .thumbnail, let rep else { return }   // .icon is a file-type glyph
}
```

---

## 8. Sources

**Apple SDK headers** (read verbatim; iOS 27.0 SDK, `…/Xcode-27.0.0-Beta.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS27.0.sdk`)

- `System/Library/Frameworks/ImageIO.framework/Headers/CGImageSource.h` —
  `kCGImageSourceSubsampleFactor` (224–239), `kCGImageSourceCreateThumbnailFromImageIfAbsent`
  (162–179), `…FromImageAlways` (181–198), `kCGImageSourceThumbnailMaxPixelSize` (200–210),
  `kCGImageSourceCreateThumbnailWithTransform` (212–222), `kCGImageSourceShouldCache` (116–129),
  `kCGImageSourceShouldCacheImmediately` (131–142), `CGImageSourceGetCount` (331–344),
  `CGImageSourceGetPrimaryImageIndex` (510–521), `CGImageSourceCopyAuxiliaryDataInfoAtIndex`
  (523–541), `kCGImageSourceDecodeRequest` / `…DecodeToSDR` /
  `kCGImageSourceGenerateImageSpecificLumaScaling` (544–560)
- `ImageIO.framework/Headers/CGImageProperties.h` — aux data types (2340–2374),
  `kCGImagePropertyImageCount` (2409), `kCGImagePropertyImages` / `kCGImagePropertyThumbnailImages`
  (2443–2446), `kCGImagePropertyPrimaryImage` (266–271), `kCGImagePropertyHEIFDictionary` (39)
- `Photos.framework/Headers/PHImageManager.h` — `PHImageRequestOptionsDeliveryMode`,
  `PHImageRequestOptionsResizeMode` ("i.e. subsampling"), `requestImageForAsset:targetSize:contentMode:options:resultHandler:`,
  `PHImageResultIsDegradedKey`, `PHCachingImageManager.startCachingImagesForAssets`
- `PhotosUI.framework/Headers/PHPicker.h` — `PHPickerResult.assetIdentifier` (232),
  `PHPickerConfiguration initWithPhotoLibrary:` (214–215)
- `QuickLookThumbnailing.framework/Headers/QLThumbnailGenerationRequest.h` — representation types,
  `contentType`, `minimumDimension`, `iconMode`
- `QuickLookThumbnailing.framework/Headers/QLThumbnailGenerator.h` —
  `generateRepresentationsForRequest:updateHandler:` ordering contract
- `QuickLookThumbnailing.framework/Headers/QLThumbnailRepresentation.h` — representation types
- `VideoToolbox.framework/Headers/VTDecompressionProperties.h` —
  `kVTDecompressionPropertyKey_ReducedResolutionDecode` (256–266),
  `…ReducedCoefficientDecode` (269–277), `…PixelFormatsWithReducedResolutionSupport` (364–371),
  `…PixelTransferProperties` (404–411)
- `VideoToolbox.framework/Headers/VTPixelTransferProperties.h` —
  `kVTPixelTransferPropertyKey_ScalingMode` and `kVTScalingMode_*` (66–123)

**Apple documentation** (all URLs HTTP-200 verified 2026-08-15)

- <https://developer.apple.com/documentation/imageio/kcgimagesourcesubsamplefactor>
- <https://developer.apple.com/documentation/imageio/kcgimagesourcecreatethumbnailfromimagealways>
- <https://developer.apple.com/documentation/imageio/kcgimagesourcecreatethumbnailfromimageifabsent>
- <https://developer.apple.com/documentation/imageio/kcgimagesourcecreatethumbnailwithtransform>
- <https://developer.apple.com/documentation/imageio/kcgimagesourcethumbnailmaxpixelsize>
- <https://developer.apple.com/documentation/imageio/kcgimagesourceshouldcache>
- <https://developer.apple.com/documentation/imageio/cgimagesourcegetcount(_:)>
- <https://developer.apple.com/documentation/imageio/cgimagesourcecopyauxiliarydatainfoatindex(_:_:_:)>
- <https://developer.apple.com/documentation/imageio/kcgimagepropertythumbnailimages>
- <https://developer.apple.com/documentation/imageio/kcgimageauxiliarydatatypeisogainmap>
- <https://developer.apple.com/documentation/photokit/phimagemanager>
- <https://developer.apple.com/documentation/photokit/phimagerequestoptions>
- <https://developer.apple.com/documentation/photokit/phcachingimagemanager>
- <https://developer.apple.com/documentation/photokit/phaccesslevel>
- <https://developer.apple.com/documentation/photokit/phphotolibrary>
- <https://developer.apple.com/documentation/photokit/phpickerresult>
- <https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app>
- <https://developer.apple.com/documentation/bundleresources/information-property-list/nsphotolibraryusagedescription>
- <https://developer.apple.com/documentation/quicklookthumbnailing/qlthumbnailgenerator>
- <https://developer.apple.com/documentation/quicklookthumbnailing/qlthumbnailgenerator/request/representationtypes-swift.struct>
- <https://developer.apple.com/documentation/videotoolbox/kvtdecompressionpropertykey_reducedresolutiondecode>
- <https://developer.apple.com/documentation/videotoolbox/kvtdecompressionpropertykey_pixelformatswithreducedresolutionsupport>
- <https://developer.apple.com/documentation/videotoolbox/kvtpropertynotsupportederr>

**WWDC**

- WWDC17 session 503, *Introducing HEIF and HEVC* — <https://developer.apple.com/videos/play/wwdc2017/503/>
- WWDC17 session 511, *Working with HEIF and HEVC* — <https://developer.apple.com/videos/play/wwdc2017/511/>

**Repo context**

- `shared/src/iosMain/.../render/IosImageIODecoder.kt` — `createThumbnail` options,
  `subsampleFactorFor`, the `CGImage → Skia` draw
- `shared/src/iosMain/.../ui/image/IosHeifDecodePolicy.kt` — `ProductUi` (128, `allowSubsample=true`,
  `imageIoShouldCache=false`) vs `Preview`
- `shared/src/commonMain/.../ui/image/ProductImageLoader.kt` — `diskCachePolicy(DISABLED)`
- `shared/src/iosMain/.../render/IosPreviewImageRepository.kt` — `IosPreviewPurpose` incl. `Filmstrip`
- `iosApp/iosApp/PhotoImportCoordinator.swift` — `ImageFileTransfer` writes
  `ewm_import_provisional_<UUID>` with **no extension**
- `iosApp/iosApp/Info.plist`, `iosApp/iosApp/ImageExport.swift` — add-only photo authorisation
- `docs/superpowers/research/2026-08-14-ios-preview-perf-leftovers.md` — S1 order bias, S3 the
  original 128-vs-1920 observation, S5 the 128 MiB joint ceiling
- `docs/superpowers/research/2026-08-14-ios-cgimage-skia-zero-copy-plan.md` — copy accounting; why the
  filmstrip is not a copy problem
- ADR-0010 (verify renders by viewing), ADR-0021 (path-first import), ADR-0028 (Coil 3 for UI thumbs)
