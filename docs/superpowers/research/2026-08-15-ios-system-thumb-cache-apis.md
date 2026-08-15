# iOS system thumbnail caches for app-owned file paths (2026-08-15)

Research record. **No production edits.** Question: the filmstrip's 128 px chrome thumb costs
≈180–200 ms per HEIC on device because it goes through path-based ImageIO. Is there an Apple path
that serves a *pre-decoded* or *daemon-cached* thumbnail instead, given that iOS progressive import
(ADR-0021) has already staged the picker's file into an app-owned path?

Primary sources are the iOS 27.0 SDK headers on this machine
(`/Applications/Xcode-27.0.0-Beta.app/.../iPhoneOS27.0.sdk`), developer.apple.com reference JSON,
and Apple DTS forum answers. §11 lists everything.

---

## 0. Executive verdict

1. **The brief's headline hypothesis is half right, and the half that is wrong matters.** Staging to
   `Documents` does **not** make the decode slower, and it is **not** why 128 px costs the same as
   1920 px. That inversion has a fully-explained, already-diagnosed cause:
   `kCGImageSourceCreateThumbnailFromImageAlways` decodes the whole image and *then* scales
   (`2026-08-14-ios-preview-perf-leftovers.md` S3, confirmed by the SDK header text in §6.1). A
   ~180–200 ms full 12 MP HEVC decode needs no cache theory to explain it.
   What staging *does* destroy is the **only public API that can return a photo thumbnail without
   decoding the photo** — `PHImageManager`, which is keyed by `PHAsset`, not by file URL. So the
   correct statement is: **orphaning is not the cost, orphaning is why the cost is unavoidable on
   the current path.**
2. **There is no public path-keyed door into Photos' thumbnail store.** Nothing in PhotoKit accepts
   a file URL. `MDItem`/Spotlight does not exist on iOS at all (§4 — proven from the SDK, not
   recalled). The two *system* caches reachable from a file path are Quick Look's thumbnail cache
   (§3) and ImageIO's own in-process decode cache (§5) — and the second one is not a thumbnail
   cache and is almost certainly inert on our call today.
3. **S3 `SubsampleFactor` was measured on device (2026-08-14 night) and does *not* win at 128.**
   Same 8 album HEICs, order-balanced plain vs sub in one process
   (`build/ios-device-shots/s3-subsample-verify/ewm-device-perf.txt`):
   `io128_med=183` vs `io128_sub_med=198` (**+15 ms**), `io1920_med=143` vs
   `io1920_sub_med=124` (−19 ms); all `DEVICE_PERF_IO_SHAPE` plain==sub dims. So factor-8
   arithmetic ("1/64 pixels") does **not** describe what ImageIO actually does for these
   album HEICs at 128 — gate #1 is **closed as a filmstrip latency fix**. 1920 gets a small
   win; filmstrip policy should not expect subsample to erase the ~180 ms class cost.
   Options below (QL, disk cache, identity, owner-gated PhotoKit) remain live.
4. **`PHImageManager` is the fastest option and the one we should probably not take.** It costs
   `NSPhotoLibraryUsageDescription` plus a read-access prompt — the app ships add-only today — and
   Apple DTS states plainly that PHPicker **does not** extend limited-library access to what the
   user just picked, so under `.limited` the fetch returns *empty* for the exact photos in the
   editor. Paying a privacy prompt for a path that silently fails is the worst of both trades
   (§2.4).
5. **`QLThumbnailGenerator` is the only candidate that uses a system thumbnail cache *and* keeps the
   current privacy posture.** File-URL keyed, no authorization, header-documented cache, generation
   out-of-process (so the pixels are not on our jetsam-visible heap), and
   `generateRepresentations` gives a cache-or-fast first paint before the good one. Its first-call
   cost is undocumented and could easily be *worse* than in-process ImageIO+subsample. Candidate B,
   behind a measurement.
6. **The identity plumbing is worth doing on its own merits, independent of PHImageManager.** The
   picker already hands us `PhotosPickerItem.itemIdentifier` — the app passes
   `photoLibrary: .shared()` today, which is exactly the condition for that value to be non-nil —
   and we throw it away. Carrying it costs one optional string on the existing NotificationCenter
   payload (zero `Shared.framework` growth, ADR-0021 item 3 intact) and buys **auth-free** wins:
   a cache key that survives re-import and process restart, dedupe of the same asset picked twice,
   and `preselectedAssetIdentifiers` on picker re-open. The *PhotoKit consumer* is the part that
   needs an owner decision; the identity itself does not.
7. **Do not skip the boring option.** No Coil disk cache is configured on iOS
   (`ProductImageLoader.ios.kt`), so every 128 px thumb is re-decoded from scratch on every cold
   launch. A 128 px thumb is ~43 KB. An app-owned disk thumb cache is the only option here with a
   cache we can key, inspect, evict, and reason about — and it is 100% offline. It does nothing for
   the *first* decode, which is why it is complementary to (3), not a substitute.

**Recommended order (post S3 device close-out):** app-owned disk cache + embedded-thumbnail /
aux-image probe → QL measurement → (only if the owner accepts a read-access prompt) PhotoKit.
Identity plumbing can land at any point; it is independent. Do **not** re-open subsample as the
128 px latency bet without new ImageIO evidence.

---

## 1. What the 180–200 ms actually is

Repo evidence, so the rest of this document argues against a real baseline rather than a vibe:

| Fact | Source |
|---|---|
| A cold editor preview switch is **~94% decode** (214 ms of 226 ms) | `2026-08-14-ios-preview-perf-leftovers.md` S1 |
| `io128_med` **202 ms** vs `io1920_med` **138 ms** — smaller output, *more* time | same, S3 |
| Cause: `…ThumbnailFromImageAlways` decodes full, then scales | same, S3 + SDK header (§6.1) |
| Skia cannot decode HEIC at all, so ImageIO is not optional for HEIC | `2026-08-13-product-thumb-coil-ab.md` |
| The redundant pixel copies are ~10 µs at 128 px — noise | `2026-08-14-ios-cgimage-skia-zero-copy-plan.md` §1.2 |

So at 128 px we are paying a **full-resolution HEVC decode of a 12 MP still** to produce 43 KB of
output. On an A18-class core that is the right order of magnitude for 180–200 ms. There is no
missing time to attribute to a cache miss.

Two consequences worth stating explicitly because they cut against the brief's framing:

- **File-system locality is not the problem.** A provisional file written seconds ago by the same
  process is warm in the unified buffer cache. Re-reading ~2 MB of HEIC is not 180 ms.
- **The fix space is therefore "don't decode 12 MP", not "read the file faster".** Every option
  below is judged on whether it avoids the full decode.

---

## 2. `PHImageManager` with `localIdentifier` vs a file URL

### 2.1 There is no file-URL door

`PHImageManager`'s entire image surface takes a `PHAsset`:

```
- (PHImageRequestID)requestImageForAsset:(PHAsset *)asset targetSize:(CGSize)targetSize
        contentMode:(PHImageContentMode)contentMode options:(nullable PHImageRequestOptions *)options
        resultHandler:(void (^)(UIImage *_Nullable result, NSDictionary *_Nullable info))resultHandler;
```
`iPhoneOS27.0.sdk/.../Photos.framework/Headers/PHImageManager.h`

A `PHAsset` comes from a fetch against the library (`fetchAssets(withLocalIdentifiers:options:)`),
never from a path. **Answer to the brief's Q1: `localIdentifier` and file URL are not two ways into
the same API; they are two different APIs, and only the identifier one has a cache.**

### 2.2 The cache is real and Apple says so

> The image manager **caches** the asset images and data it provides, so later requests for the same
> assets with similar parameters will return results more quickly.
> — [`PHImageManager` overview](https://developer.apple.com/documentation/photokit/phimagemanager)

> If the image you request is among those already prepared, the `PHCachingImageManager` object
> immediately returns that image. **Otherwise, Photos prepares the image on demand and caches it for
> later use.**
> — [`PHCachingImageManager` overview](https://developer.apple.com/documentation/photokit/phcachingimagemanager)

Plus the levers that matter for a filmstrip:

| Lever | Documented behaviour |
|---|---|
| `deliveryMode = .fastFormat` | "client will get one result only and it **may be degraded**" (header); returns a low-quality image rather than waiting |
| `deliveryMode = .opportunistic` | may call the handler **synchronously before `requestImage` returns** if low-quality data is immediately available |
| `resizeMode = .fast` | "use targetSize as a hint for **optimal decoding** … (i.e. subsampling)" (header) |
| `PHCachingImageManager.startCachingImages(for:targetSize:…)` | explicit preheat ahead of scroll position — the documented collection-view pattern |
| `isNetworkAccessAllowed` | **defaults to NO** — so an offline-only posture is the default, not something we have to enforce |
| `PHImageResultIsDegradedKey` | tells us which quality tier we got |

One deprecation to note: `PHCachingImageManager.allowsCachingHighQualityImages` is
`API_DEPRECATED("This property is unused and will be removed in a future release", ios(8, 26.0))`.
Do not plan around it.

This is the closest thing on iOS to reading a pre-made derivative: `.fastFormat` +
`.fast` + a small `targetSize` is asking photolibraryd for a cached thumbnail, and the synchronous
first callback under `.opportunistic` is Apple telling you the data can already exist.

### 2.3 We already have the identifier and we already throw it away

`ContentView.swift` presents the picker as:

```swift
.photosPicker(
    isPresented: $isPhotoPickerPresented,
    selection: $pickedItems,
    maxSelectionCount: 50,
    matching: .images,
    preferredItemEncoding: .current,
    photoLibrary: .shared(),
)
```

And Apple's contract for the identifier is exactly that condition:

> **`PhotosPickerItem.itemIdentifier`** — The local identifier of the item.
> Discussion: This value is `nil` **if you create a Photos picker without a photo library**.
> — <https://developer.apple.com/documentation/photokit/photospickeritem/itemidentifier>

Same rule on the UIKit side, from the SDK header:

```
/// Initializes a new configuration with the system photo library. This configuration never returns asset identifiers.
- (instancetype)init API_UNAVAILABLE(watchos);
```
`iPhoneOS27.0.sdk/.../PhotosUI.framework/Headers/PHPicker.h`

So the asset identity exists at the Swift edge on every pick today. `PhotoImportCoordinator`
consumes `PhotosPickerItem` and emits paths; the identifier dies there.

### 2.4 The blocker: using the identifier costs a read-access prompt, and degrades badly

Presenting the picker needs nothing:

> The user explicitly grants access only to items they choose, so **photo library access
> authorization is not needed**.
> — [`photosPicker(isPresented:selection:matching:preferredItemEncoding:photoLibrary:)`](https://developer.apple.com/documentation/swiftui/view/photospicker(ispresented:selection:matching:preferreditemencoding:photolibrary:))

*Using* the identifier is a different story:

> If your app requires PhotoKit's advanced features, like **retrieving assets** and collections, or
> updating the library, the user must explicitly authorize it to access those features. … If your app
> only adds to the library, use the `NSPhotoLibraryAddUsageDescription` key. For all other cases, use
> `NSPhotoLibraryUsageDescription`. **Attempting to access the Photos library without a valid usage
> description causes your app to crash.**
> — [Delivering an Enhanced Privacy Experience in Your Photos App](https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app)

`iosApp/iosApp/Info.plist` declares **only** `NSPhotoLibraryAddUsageDescription`
("Save your watermarked photo to your library."). PhotoKit read access would be a new permission on
an app whose product promise is "no permissions needed" on the Android side and whose iOS story is
currently "the picker runs out of process and we never touch your library".

And it does not fail cleanly. Apple DTS, on the PHPicker + limited-library interaction:

> Please note that **PHPicker does not extend the Limited Photos Library access for the selected
> items** if the user put your app in Limited Photos Library mode. It would be a good opportunity to
> reconsider if the app really needs direct Photos Library access or can work with just the image and
> video data.
> — <https://developer.apple.com/forums/thread/650902> (mirrored at
> <https://stackoverflow.com/questions/62625797/>, and the same point in WWDC20 session 10652 at
> 10m20s)

Corroborated independently:

> If you only have `.limited` access, and if this is not one of the assets to which the user has
> explicitly granted you access, then the call to `fetchAssets` will return an **empty
> PHFetchResult**.
> — <https://www.biteinteractive.com/picking-a-photo-in-ios-14/>

So the outcome matrix after paying the prompt:

| User's answer | Chrome thumbs via PhotoKit |
|---|---|
| Allow All | works, fast, daemon-cached |
| **Limited** ("Select Photos…") | **empty fetch** for anything not in the separately-chosen limited set — i.e. usually every photo in the editor |
| Deny | nothing |

Limited is not an exotic branch; it is the default-feeling option in the system prompt. **Verdict:
`PHImageManager` is a real accelerator, but as a *requirement* it trades a privacy promise for a
path that fails on the most likely user answer.** The only defensible shape is opportunistic —
`authorizationStatus(for: .readWrite) == .authorized` *already*, never prompt — and since this app
has no other reason to hold read access, that branch would be dead code in practice. Ship it only
behind an owner decision and an ADR.

---

## 3. `QLThumbnailGenerator` for file URLs

This is the one API in the brief that is both file-URL keyed and cache-backed. Availability
`ios(13.0)`, so it clears minSdk-equivalent concerns entirely.

### 3.1 The cache is stated in the header, in the representation-type comments

```
QLThumbnailGenerationRequestRepresentationTypeIcon                = 1 << 0,  // Request an icon, that is an image that represents the file type of the request. …
QLThumbnailGenerationRequestRepresentationTypeLowQualityThumbnail = 1 << 1,  // Request a thumbnail representing the file that may come from a previously generated and cached copy or faster lower quality generation, not satisfying the parameters of the request (can be larger or smaller).
QLThumbnailGenerationRequestRepresentationTypeThumbnail           = 1 << 2,  // Request a thumbnail representing the file, satisfying the parameters of the request (either retrieved from the cache, or generated).
```
`iPhoneOS27.0.sdk/.../QuickLookThumbnailing.framework/Headers/QLThumbnailGenerationRequest.h`

"previously generated and cached copy" and "either retrieved from the cache, or generated" are as
close to a documented system thumbnail cache as iOS gets for arbitrary files. Note what is **not**
documented: where the cache lives, how it is keyed (size? scale? file identity? mtime?), how long
entries survive, or whether app-container files are cached at all versus regenerated every time.
Treat residency as unmeasured.

### 3.2 The progressive API is the one to use

```
- (void)generateRepresentationsForRequest:(QLThumbnailGenerationRequest *)request
                            updateHandler:(void (^ _Nullable)(QLThumbnailRepresentation * _Nullable thumbnail,
                                                              QLThumbnailRepresentationType type,
                                                              NSError * _Nullable error))updateHandler;
```

> QuickLookThumbnailing calls the `updateHandler` **in order of lower quality to higher quality**
> thumbnail types. If a better quality thumbnail becomes available before a lower quality one, the
> framework may skip the call to the `updateHandler` for the lower quality thumbnail. You can rely on
> QuickLookThumbnailing to call the `updateHandler` **at least once**.
> — [`generateRepresentations(for:update:)`](https://developer.apple.com/documentation/quicklookthumbnailing/qlthumbnailgenerator/generaterepresentations(for:update:))

That shape maps cleanly onto a filmstrip: take `[.lowQualityThumbnail, .thumbnail]`, paint the first
callback immediately, replace on the second. It is also the documented workaround for
`generateBestRepresentation` stalling on heavy inputs.

### 3.3 Fit with this codebase

| Property | Effect here |
|---|---|
| Output is a `CGImage` (`QLThumbnailRepresentation.cgImage`) | drops straight into the existing `CGImage → Skia` primitives from `2026-08-14-ios-cgimage-skia-zero-copy-plan.md` §7.1 — **no new pixel plumbing** |
| Non-UI framework, no UIKit link required | per `QLThumbnailRepresentation` overview |
| `iconMode` defaults to `NO` | correct for us: "a raw undecorated thumbnail", no frame/curled corner/shadow |
| `minimumDimension` | guards against a cache hit that is uselessly tiny — "If set and it is not possible to generate thumbnails of minimumDimension … **no thumbnail will be provided**" |
| `contentType` override | useful: our staged names may not carry a `.heic` extension |
| Generation is out-of-process | pixels are the daemon's, not on the K/N GC heap — relevant to the 128 MiB joint ceiling and jetsam (§S5 of the perf-leftovers doc) |
| `platform.QuickLookThumbnailing` exists in the K/N distribution | verified in `~/.konan/kotlin-native-prebuilt-macos-aarch64-2.4.20-Beta1/klib/platform/ios_simulator_arm64` — could be an `iosMain` decoder, no Swift, no framework surface growth (J5-safe) |

**Privacy note, stated because privacy is a product promise, not an afterthought:** this hands a
path inside our container to a system thumbnail daemon over XPC. It is on-device, offline, no
network, no third party — consistent with the app's promises — but it *is* a new system component
touching user photos, and any writes it makes to its own cache are outside our control and outside
our eviction. Worth naming in the ADR rather than discovering later.

### 3.4 The honest risk

For a 12 MP HEIC with no cache entry, QL's image thumbnail provider has to decode the same photo we
would have decoded, plus XPC round-trips, plus a cross-process image transfer. **First-call cost
could be worse than in-process ImageIO + subsample.** No Apple document gives a number.
Community reports are consistent about "it seems to cache" and consistent about
`generateBestRepresentation` being slow enough on large files that people are told to switch to the
progressive call. **Candidate B, gated on §8's measurement. If the cold call is worse than
subsampled ImageIO and the warm call is not dramatically better, drop it.**

---

## 4. `MDItem` / Spotlight thumbnails — no public API on iOS

Answered from the SDK, not from memory. On iOS, `CoreServices.framework` ships exactly three
headers:

```
$ ls iPhoneOS27.0.sdk/System/Library/Frameworks/CoreServices.framework/Headers/
CoreServices.h   UTCoreTypes.h   UTType.h

$ find iPhoneOS27.0.sdk -name "MDItem*"
(nothing)
```

`MDItem` / `MDQuery` / `kMDItemFSName` are macOS-only (`Metadata` inside macOS `CoreServices`).
There is no iOS Spotlight *read* API for file metadata at all, let alone a thumbnail accessor.

The nearest iOS relative is **CoreSpotlight**, and it runs the wrong direction: `CSSearchableItem` /
`CSSearchableItemAttributeSet` let an app *donate* content to the index, including a
`thumbnailData`/`thumbnailURL` the app supplies. It is a write API for search results. It will never
hand us a thumbnail for a photo we did not index, and indexing user photos into a system search
index would be a privacy regression this app should not even prototype.

**Verdict: closed. No follow-up.**

---

## 5. `CGImageSourceCreateWithURL` + `shouldCache` / `shouldCacheImmediately`

Two findings here, and the second one is a live (small) defect in our code.

### 5.1 These keys are not a thumbnail cache

From the SDK header, verbatim:

```
/**
 * A Boolean value that indicates whether to cache the decoded image.
 *
 * The value of this key is a CFBoolean. The default value is kCFBooleanTrue for 64-bit architectures,
 * and kCFBooleanFalse for 32-bit architectures.
 *
 * Include this key in the options dictionary you pass to the functions
 * ``CGImageSourceCopyPropertiesAtIndex(_:_:_:)`` and ``CGImageSourceCreateImageAtIndex(_:_:_:)``.
 */
IMAGEIO_EXTERN const CFStringRef kCGImageSourceShouldCache  IMAGEIO_AVAILABLE_STARTING(10.4, 4.0);

/**
 * A Boolean value that indicates whether image decoding and caching happens at image creation time.
 *
 * … The default value is kCFBooleanFalse, which causes decoding and caching to happen only when you
 * render the image.
 *
 * Include this key in the options dictionary you pass to the functions
 * ``CGImageSourceCopyPropertiesAtIndex(_:_:_:)`` and ``CGImageSourceCreateImageAtIndex(_:_:_:)``.
 */
IMAGEIO_EXTERN const CFStringRef kCGImageSourceShouldCacheImmediately  IMAGEIO_AVAILABLE_STARTING(10.9, 7.0);
```

Read that carefully:

- It is an **in-process, per-`CGImageSource` decoded-image cache**. It has nothing to do with any
  daemon, any on-disk store, or anything that survives the `CGImageSourceRef` — let alone the
  process. **It cannot be the answer to "use the system thumbnail cache".**
- `shouldCacheImmediately` moves the decode *earlier* (creation instead of first render). It never
  makes a decode cheaper. For our synchronous "decode then hand to Skia" flow it is a no-op at best.
- The documented function list is `CGImageSourceCopyPropertiesAtIndex` and
  `CGImageSourceCreateImageAtIndex`. **`CGImageSourceCreateThumbnailAtIndex` is not on it.**

### 5.2 Our `imageIoShouldCache` policy knob is on the wrong dictionary

`IosImageIODecoder.createThumbnail` puts `kCGImageSourceShouldCache` into the options dict of
`CGImageSourceCreateThumbnailAtIndex` (the function the header does *not* list), while
`withUrlSource` creates the source with `options = null`:

```412:412:shared/src/iosMain/kotlin/me/rosuh/easywatermark/render/IosImageIODecoder.kt
            CGImageSourceCreateWithURL(cfUrl as CFURLRef, null)
```

```454:457:shared/src/iosMain/kotlin/me/rosuh/easywatermark/render/IosImageIODecoder.kt
            setObject(
                NSNumber.numberWithInt(if (shouldCache) 1 else 0),
                forKey = NSString.create(string = "kCGImageSourceShouldCache"),
            )
```

So `IosHeifDecodePolicy.imageIoShouldCache = false` — described in its KDoc as "off for scroll
thumbs (don't pin decode cache)" — is very likely **inert**: it is passed where the key is not
documented to apply, and it is *not* passed where it is. The intent (don't retain decoded frames
while scrolling) is reasonable; the mechanism is unverified. Note also that the default is
`kCFBooleanTrue` on 64-bit, so "off" is a deliberate departure that we have never confirmed takes
effect.

**Recommendation:** do not delete the knob on a hunch. Add it to §8's probe — measure
`shouldCache` on the *source-creation* dictionary, and measure current-vs-omitted on the thumbnail
dictionary — then either re-scope it with a comment recording what was measured, or remove it.
Expected effect on the 180–200 ms: **none.** This is a correctness/clarity cleanup, not a perf item.

---

## 6. Does copying out of the Photos library lose the thumbnail database?

**Yes, for the Photos-managed derivatives. No, for the thumbnail baked into the file itself.** That
distinction is the actionable part of this whole document.

### 6.1 What is lost

Photos' cached derivatives are addressed through `PHAsset` only (§2.1). A staged copy is a new file
at a new path with no recorded relationship to any asset. There is no public API — not ImageIO, not
Quick Look, not `NSFileManager` extended attributes, nothing — that maps a container path back to a
library asset. Even the reverse direction is closed: `PHAssetResource` gives you resource data, not
"the thumbnail for this file I already have".

So after staging, the only way to produce pixels is to decode the file, and per the header, our
current flag combination guarantees that decode is full-resolution:

> **`kCGImageSourceCreateThumbnailFromImageAlways`** … If you set the value of this key to
> `kCFBooleanTrue`, the image source **creates the thumbnail from the full image**, subject to the
> limit specified by `kCGImageSourceThumbnailMaxPixelSize`.

That sentence is the 128 ≈ 1920 inversion, in Apple's words.

### 6.2 What survives: the container's own thumbnail item

The header for the sibling flag describes a *conditional* behaviour we are not using:

> **`kCGImageSourceCreateThumbnailFromImageIfAbsent`** — A Boolean value that indicates whether to
> create a thumbnail image automatically **if the data source doesn't contain one**. … The default
> value is `kCFBooleanFalse`.

The phrasing "if the data source doesn't contain one" implies the complementary behaviour, and it is
the useful one: **with neither `…Always` nor `…IfAbsent` set, `CGImageSourceCreateThumbnailAtIndex`
returns the file's embedded thumbnail if one exists, and `NULL` if it does not.** That is a
zero-full-decode probe: either you get a small pre-made image for the cost of parsing the container,
or you get `NULL` and fall through.

iPhone-captured HEIC generally carries a thumbnail item in the container, and PHPicker with
`preferredItemEncoding: .current` exports the file itself — so the embedded thumbnail should travel
with the staged copy. **This is a hypothesis with a cheap test**, and it is exactly the "pre-decoded
thumbnail for an app that already has a file path" the brief was looking for. It is a *per-file*
cache rather than a *system* cache, which is arguably better: it needs no daemon, no permission, and
no XPC.

The known catches, all handleable:

| Catch | Handling |
|---|---|
| Size is whatever the camera wrote (repo S3 correctly called it "unpredictable, possibly tiny") | read `CGImageGetWidth/Height` and accept only if long edge ≥ 128; otherwise fall through |
| Orientation | keep `kCGImageSourceCreateThumbnailWithTransform` (already set) |
| May not reflect user edits | for a 128 px filmstrip cell this is cosmetic, but it is a real fidelity delta and must be verified by **viewing** screenshots (ADR-0010), not by dimensions |
| Absent for some sources (screenshots, some third-party HEIC, re-encoded exports) | `NULL` → fall through to §7's subsampled path |
| Cap it | `kCGImageSourceThumbnailMaxPixelSize` still applies |

### 6.3 The cache we do not have, and could

`ProductImageLoader.ios.kt` builds the `ImageLoader` with no `diskCache`. So a 128 px thumb is
re-decoded on every cold launch, forever. And `ProductThumbKeyer` keys on the ref string:

```12:13:shared/src/commonMain/kotlin/me/rosuh/easywatermark/ui/image/ProductThumbKeyer.kt
fun productThumbCacheKey(refValue: String, maxEdgePx: Int): String =
    "ewm_thumb;${refValue};${maxEdgePx}"
```

On iOS that ref is a staged path, which changes between `ewm_import_provisional_*` and `ewm_src_*`
and again on any re-import — so even a disk cache would miss across sessions unless the key becomes
identity-based. **That is the concrete, auth-free payoff of §9's identity plumbing**, and it is why
the identity work is worth doing even if PhotoKit never is.

---

## 7. Decode-at-downsample options that are not `SubsampleFactor`

Complete list of `CGImageSource` option keys in the iOS 27 SDK, with what each is actually worth to
us:

| Key (availability: macOS, iOS) | Reduces decode work? | Notes |
|---|---|---|
| `kCGImageSourceThumbnailMaxPixelSize` (10.4, 4.0) | **No** | output cap only; with `…Always` the full decode still happens |
| `kCGImageSourceCreateThumbnailFromImageAlways` (10.4, 4.0) | **No — it is the problem** | "creates the thumbnail from the full image" |
| `kCGImageSourceCreateThumbnailFromImageIfAbsent` (10.4, 4.0) / neither flag | **Yes, hugely, when present** | §6.2 embedded thumbnail; unpredictable size, needs a floor check |
| `kCGImageSourceSubsampleFactor` (10.11, 9.0) | **Yes, 2/4/8** | landed for `ProductUi`; **device-verified 2026-08-14: no 128 latency win** (183→198 ms); 1920 −19 ms; dims preserved |
| `kCGImageSourceShouldCache` / `…Immediately` | No | §5 — in-process, and on the wrong dictionary today |
| `kCGImageSourceShouldAllowFloat` (10.4, 4.0) | No (can only cost) | leave off |
| `kCGImageSourceCreateThumbnailWithTransform` (10.4, 4.0) | No | correctness (orientation) — keep |
| `kCGImageSourceTypeIdentifierHint` (10.4, 4.0) | Marginal | skips format sniffing; we know it is HEIC |
| `kCGImageSourceAllowableTypes` (27.0, 27.0) | No | a *security* control (restrict accepted formats); interesting for a privacy-focused app hardening its decode surface, unrelated to speed |
| **`kCGImageSourceDecodeRequest` = `kCGImageSourceDecodeToSDR`** (14.0, **17.0**) | **Plausibly yes, unmeasured** | see below |
| **`kCGImageSourceGenerateImageSpecificLumaScaling`** (15.0, **18.0**), default `kCFBooleanTrue` | **Plausibly yes, unmeasured** | see below |
| `kCGComputeHDRStats` (16.0, 19.0) | No — extra work | leave off |

### 7.1 The under-explored lever: HDR / gain-map work on iPhone HEIC

The header comments:

```
/* kCGImageSourceDecodeRequest - value is one of the predefined keys (kCGImageSourceDecodeToHDR, kCGImageSourceDecodeToSDR, ...) */
/* kCGImageSourceGenerateImageSpecificLumaScaling - generate a global tone mapping function based on the gain map. Default value is "YES" (kCFBooleanTrue) */
```

Modern iPhone camera HEIC ships an HDR gain map, and by default ImageIO **generates a tone-mapping
function from that gain map**. For a 128 px filmstrip cell that work is pure waste. Explicitly
requesting `kCGImageSourceDecodeToSDR` and setting `kCGImageSourceGenerateImageSpecificLumaScaling`
to false is cheap to try, applies to *every* HEIC (unlike the embedded-thumbnail probe), and needs
no permission.

I have found **no Apple statement quantifying it**, and it may well be that the gain-map path is
already skipped for thumbnail requests. Do not present it as a win before §8 measures it. Note also
the correctness consequence: forcing SDR changes the tone of the thumbnail relative to the preview,
so it must be checked by *viewing* a filmstrip screenshot next to the preview (ADR-0010) — a
subtle-but-real risk of a filmstrip that no longer matches what the editor shows.

### 7.2 The UIKit route

`UIImage.h` (iOS 15+):

```
- (nullable UIImage *)imageByPreparingThumbnailOfSize:(CGSize)size;
- (void)prepareThumbnailOfSize:(CGSize)size completionHandler:(void (^)(UIImage *_Nullable))completionHandler;
- (void)prepareForDisplayWithCompletionHandler:(void (^)(UIImage *_Nullable))completionHandler;
```

With `UIImage(contentsOfFile:)` (lazy — no decode until needed) plus `prepareThumbnail(of:)`, UIKit
does the decode-and-downsample off the main thread and hands back a display-ready image. Real
caveats for us:

- **No documented reduction guarantee.** Apple documents the *concurrency* and display-readiness
  benefit ("Decodes an image asynchronously and provides a new one for display"), not subsampling.
  It may internally do exactly the full-decode-then-scale we already have.
- The header warns the prepared image is screen-specific: "The prepared `UIImage` is not related to
  the original image. If the properties of the screen … change … it may not render correctly."
- Output is a `UIImage`/`CGImage`, so we re-enter the same `CGImage → Skia` hand-off — no saving
  there, but no new cost either.
- It adds a UIKit dependency to a decode path that is currently ImageIO-only.

Worth one bench arm because it is three lines. Not worth a design around.

---

## 8. Measurement plan (do this before writing any production code)

Everything above is either already-landed-but-unmeasured, or a hypothesis. The repo has the harness:
`IosDevicePerfBench` already stages `Documents/ewm-12mp-drop`, emits `DEVICE_PERF_IO` lines per
`IoMode`, and — per S1's lesson — **alternates measurement order** because a fixed position let the
first arm absorb first-touch cost.

### 8.1 Arms

Add `IoMode` variants, all at maxEdge 128 on the same album HEIC set (n ≥ 8, long edge ≥ 3000):

| Arm | What | Cost to add |
|---|---|---|
| `A0` current, `allowSubsample = false` | the 202 ms baseline (regression anchor) | none |
| `A1` current, `allowSubsample = true` | **S3's missing after-number — the whole point** | none |
| `B` embedded-thumbnail-only | omit both `…Always`/`…IfAbsent`; record hit/miss and returned dimensions per file | ~10 lines |
| `C` `A1` + `DecodeToSDR` + luma-scaling off | §7.1 | ~6 lines |
| `D` `QLThumbnailGenerator`, `.lowQualityThumbnail` first callback | cold and warm; `platform.QuickLookThumbnailing` from `iosMain` | ~30 lines |
| `E` `QLThumbnailGenerator`, `.thumbnail` | cold and warm | shares D |
| `F` `UIImage(contentsOfFile:)` + `prepareThumbnail(of:)` | §7.2 | ~10 lines |
| `G` `shouldCache` on the *source-creation* dict (§5.2) | on/off, order-balanced | ~5 lines |
| `H` (only if the owner opens the PhotoKit gate) `PHImageManager` `.fastFormat`/`.fast`, cold + preheated | needs read auth on the test device | Swift edge |

Report per arm: median, first-position vs second-position split, and for `B` the **hit rate** across
the album — an embedded-thumbnail path that hits 40% of the time is a different design than one that
hits 95%.

### 8.2 Gates, written before the numbers exist

- **If `A1` ≤ ~40 ms:** the problem is solved. Land nothing from this document except the identity
  plumbing (§9, which is not a perf item) and the §5.2 cleanup. Close the rest.
- **If `A1` is still ≥ ~100 ms:** subsampling is not doing what the arithmetic predicts — find out
  why (is the factor actually being applied? does the HEIF codec honour it?) before adding a
  subsystem on top of a broken one.
- **`B` proceeds only if** hit rate ≥ ~70% *and* the accepted thumbnails clear the 128 px floor.
- **`D`/`E` proceed only if** warm ≤ ~1/4 of `A1` **and** cold ≤ `A1`. A cache that is only fast the
  second time, behind a cold call that is slower than what we have, is a net loss for a first-import
  filmstrip — which is the case that actually hurts.
- **Any arm that changes pixels** (`B`, `C`, `D`, `E`, `F`) needs a **viewed** filmstrip screenshot
  next to the current build and next to the editor preview. Dimensions matching is not evidence
  (ADR-0010; and S3's sharpness check is still outstanding for exactly this reason).
- Strict FNV golden gate stays local-only. Never in PR CI (ADR-0010).

### 8.3 Standing operational constraints

Device work here is a physical-iPhone measurement, and the last two attempts (S3, S4) lost their
after-numbers to a device disconnect. Budget for that. Sustained emulator/build load has frozen this
machine before — warn the owner before starting a long automated run, and do not shut down live
simulators used for ongoing migration work.

---

## 9. Proposed architecture: asset identity for chrome, paths for render

The brief asks for a design that keeps `PHAsset` identity for chrome thumbs while Session keeps
paths for render. Here it is, with the layering ADR-0021 and ADR-0017 already imply.

### 9.1 Shape

```
PHPicker (out of process, no authorization)
   │  PhotosPickerItem { itemIdentifier: String?, FileRepresentation }
   ▼
Swift PhotoImportCoordinator
   │  stages provider temp → ewm_import_provisional_* (unchanged)
   │  NotificationCenter fileReady payload gains ONE optional field: assetIdentifier
   ▼
Kotlin IosProductRootHost
   │  Session.publish(ImageInfo(uri = MediaRef(ownedPath)))   ← UNCHANGED. Render contract untouched.
   │  ChromeThumbIdentityRegistry[ownedPath] = assetIdentifier ← in-memory only, Host-scoped
   ▼
ProductThumb(ref = path)  →  Coil keyer consults the registry for a stable key
   ▼
iOS chrome-thumb source ladder (§9.3)
```

Invariants this preserves, deliberately:

- **Session still holds Ready paths only** (ADR-0021 item 2). The render/export spine
  (`IosFinalRenderSpine`, `CommonWatermarkPipeline`) never learns that PhotoKit exists. Export stays
  full-res, sRGB, EXIF-stripped (ADR-0009).
- **Zero `Shared.framework` public growth** (ADR-0021 item 3 / J5): one extra key on an existing
  NotificationCenter payload, one `internal` registry in `iosMain`.
- **No `expect`/`actual` extraction** for any of it — there is no off-iOS consumer of an asset
  identifier, so per the repo convention this stays platform-local.
- **Identity is memory-only.** It is never written to DataStore or Room. No new persisted bytes, no
  new data at rest, nothing to migrate, nothing to leak. (It is also a device-local opaque string,
  not a stable cross-device identifier — but "we don't persist it" is the cleaner promise.)

### 9.2 What the identity buys with **zero** authorization

This is the part worth landing regardless of the PhotoKit decision:

1. **A cache key that survives.** Key chrome thumbs on the asset identity when present, falling back
   to path. That makes an app-owned disk thumb cache (§6.3) actually hit across the
   provisional→owned rename, across re-import of the same photo, and across cold launch. Today's
   path-derived key cannot.
2. **Dedupe.** The same asset picked twice in one session is currently two paths and two decodes.
3. **`preselectedAssetIdentifiers`.** Re-opening the picker can show the current selection as
   selected — a genuine UX gap on iOS today, available only because we already pass
   `photoLibrary: .shared()` (the SDK header notes it needs exactly that).
4. **Cheap correlation in bench logs** without logging user paths.

None of these require reading the library. All of them are blocked today purely because the
identifier is discarded at the Swift edge.

### 9.3 Chrome-thumb source ladder (cheapest first, each rung independently revertible)

```
0. Coil memory cache                                  (today)
1. App-owned disk thumb cache, keyed by identity      (new; §6.3 — ~43 KB/entry)
2. Embedded container thumbnail, if long edge ≥ 128   (new; §6.2 — no full decode)
3. ImageIO thumbnail + kCGImageSourceSubsampleFactor  (today, S3 — measure first)
4. QLThumbnailGenerator .lowQualityThumbnail/.thumbnail  (optional, gated on §8 arm D/E)
5. PHImageManager .fastFormat via asset identity      (OWNER-GATED; only if authorization
                                                       is ALREADY .authorized — never prompt)
```

Design rules for the ladder:

- **Rungs 4 and 5 are additions, not replacements.** Rung 3 remains the guaranteed floor: it needs
  no permission, no daemon, and works for every file. Nothing above it may become load-bearing.
- **Rung 5 never triggers a prompt.** `authorizationStatus(for: .readWrite) == .authorized` only —
  not `.limited` (§2.4: the fetch returns empty for picker-selected assets), not `.notDetermined`.
- **One rung at a time, one commit each**, with the rollback HEAD recorded in the PR body. This is
  the same discipline J4 imposes on dependency slices and it applies here for the same reason: five
  simultaneous changes to a decode path cannot be attributed.
- The Coil `isSampled = false` contract stays (`IosHeifDecodePolicy.SampledMode.Never`) — sampled
  entries fail Coil's size validation and blank-flash on `LazyRow` recycle. Any new rung must
  respect it.

### 9.4 Android parity read (the brief's Q7)

Android already does what this proposal is reaching for, which is the tell that the iOS divergence
is accidental rather than principled:

| | Android (today) | iOS (today) | iOS (proposed) |
|---|---|---|---|
| Identity in Session | MediaStore content `Uri` | staged file path | staged file path (unchanged) |
| Chrome thumb source | `ContentResolver.loadThumbnail(uri, Size, signal)` → MediaProvider's cached/generated thumbnail | ImageIO decode of a copied file | ladder §9.3 |
| Fallback | `openInputStream` + `inSampleSize` subsampled decode | (none — the decode *is* the path) | rung 3 |
| Permission | none needed on API 29+ | none | none for rungs 0–4 |

Android's `loadThumbnail` is the platform's "ask the media provider for a thumbnail" call, and
`ProductThumbFetcher.android.kt` uses it first and only falls back to a subsampled decode for
app-private refs. **The iOS equivalent of `loadThumbnail` is `PHImageManager` + `PHAsset`** — and
the iOS equivalent of Android's *fallback* is what iOS uses for everything. The structural
difference is not the API surface, it is that Android never copies the file, so provider identity
survives to the UI layer. Staging is the right call on iOS for other reasons (ADR-0021: bounded
memory, no `List<ByteArray>` retention, progressive fill) — so the fix is to carry the identity
*alongside* the path, not to stop staging.

The parity caveat worth writing into the exception registry: Android's thumbnail comes free with a
permission the app already has; iOS's equivalent costs a permission the app deliberately does not
request. **Parity of behaviour here would mean divergence of privacy posture.** That is an owner
call, not an engineering one.

---

## 10. Rejected / non-goals

| Option | Verdict | Why |
|---|---|---|
| `MDItem` / Spotlight thumbnails | **Impossible** | Not in the iOS SDK at all (§4) |
| CoreSpotlight `thumbnailData` as a cache | **Rejected** | Write-only donation API; indexing user photos into system search is a privacy regression |
| `kCGImageSourceShouldCache(Immediately)` as "the system cache" | **Rejected** | In-process, per-source, not a thumbnail cache (§5.1) |
| `PHImageManager` as the primary chrome-thumb path | **Rejected as a requirement** | Needs `NSPhotoLibraryUsageDescription` + read prompt; PHPicker does not extend limited access, so it returns *empty* for picked assets under `.limited` (§2.4). Owner-gated opportunistic rung only |
| Stop staging; render straight from `PHAsset` | **Rejected** | Undoes ADR-0021's memory bound, reintroduces full-payload retention, and inherits the same authorization problem |
| Persist asset identifiers to DataStore/Room | **Rejected** | New data at rest and a schema migration for a device-local cache key. Memory-only |
| Make QL a *replacement* for the ImageIO path | **Rejected** | Undocumented cold cost and cache residency; rung 3 must stay the guaranteed floor (§9.3) |
| Chase the `CGImage → Skia` copies for the filmstrip | **Rejected (already)** | ~10 µs at 128 px — noise against 200 ms (`2026-08-14-ios-cgimage-skia-zero-copy-plan.md` §1.2) |
| Alpha Swift export / new public framework surface | **No** | J5 |
| Re-add a strict FNV golden gate to CI to police thumbnail changes | **No** | ADR-0010, local-only |

---

## 11. Sources

**Apple — SDK headers read directly** (`/Applications/Xcode-27.0.0-Beta.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS27.0.sdk`)
- `ImageIO.framework/Headers/CGImageSource.h` — all option keys, `shouldCache` function scoping,
  `…ThumbnailFromImageAlways`/`…IfAbsent` semantics, `SubsampleFactor` (2/4/8; JPEG/HEIF/TIFF/PNG),
  `DecodeRequest`/`DecodeToSDR`, `GenerateImageSpecificLumaScaling`, `AllowableTypes`
- `QuickLookThumbnailing.framework/Headers/QLThumbnailGenerationRequest.h` — representation-type
  cache comments, `iconMode`, `minimumDimension`, `contentType`
- `QuickLookThumbnailing.framework/Headers/QLThumbnailGenerator.h` — `generateRepresentations`,
  `saveBestRepresentation`
- `Photos.framework/Headers/PHImageManager.h` — `requestImage…` signature, delivery/resize mode
  comments, `networkAccessAllowed` default NO, `allowsCachingHighQualityImages` deprecation
- `Photos.framework/Headers/PHPhotoLibrary.h` — `PHAuthorizationStatusLimited`, `PHAccessLevel`
- `PhotosUI.framework/Headers/PHPicker.h` — "This configuration never returns asset identifiers",
  `preselectedAssetIdentifiers` requires `photoLibrary`
- `UIKit.framework/Headers/UIImage.h` — `imageByPreparingThumbnailOfSize:`,
  `prepareThumbnailOfSize:completionHandler:`, `prepareForDisplayWithCompletionHandler:`
- `CoreServices.framework/Headers/` — only `CoreServices.h`, `UTCoreTypes.h`, `UTType.h`; no `MDItem*`
  anywhere in the SDK

**Apple — developer.apple.com**
- `PHImageManager` ("The image manager caches the asset images and data it provides") — <https://developer.apple.com/documentation/photokit/phimagemanager>
- `PHCachingImageManager` ("Photos prepares the image on demand and caches it for later use") — <https://developer.apple.com/documentation/photokit/phcachingimagemanager>
- `PHImageRequestOptionsDeliveryMode.fastFormat` / `.opportunistic` — <https://developer.apple.com/documentation/photokit/phimagerequestoptionsdeliverymode>
- `PHImageRequestOptionsResizeMode.fast` ("Photos can use image subsampling") — <https://developer.apple.com/documentation/photokit/phimagerequestoptionsresizemode/fast>
- `PHAsset.fetchAssets(withLocalIdentifiers:options:)` — <https://developer.apple.com/documentation/photokit/phasset/fetchassets(withlocalidentifiers:options:)>
- `QLThumbnailGenerator` / `.Request.RepresentationTypes` / `generateRepresentations(for:update:)` / `QLThumbnailRepresentation` — <https://developer.apple.com/documentation/quicklookthumbnailing/qlthumbnailgenerator>
- `PhotosPickerItem.itemIdentifier` ("nil if you create a Photos picker without a photo library") — <https://developer.apple.com/documentation/photokit/photospickeritem/itemidentifier>
- `photosPicker(isPresented:selection:matching:preferredItemEncoding:photoLibrary:)` ("photo library access authorization is not needed") — <https://developer.apple.com/documentation/swiftui/view/photospicker(ispresented:selection:matching:preferreditemencoding:photolibrary:)>
- `PHPickerViewController` / `PHPickerConfiguration` / `PHPickerResult.assetIdentifier` — <https://developer.apple.com/documentation/photosui/phpickerviewcontroller>
- Delivering an Enhanced Privacy Experience in Your Photos App (usage-description keys; limited library) — <https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app>
- `CGImageSourceCreateThumbnailAtIndex` — <https://developer.apple.com/documentation/imageio/cgimagesourcecreatethumbnailatindex(_:_:_:)>

**Apple — DTS / WWDC**
- Apple DTS: "PHPicker does not extend the Limited Photos Library access for the selected items" — <https://developer.apple.com/forums/thread/650902>
- WWDC20 session 10652, *Meet the new Photos picker* (asset identifiers section ~10m20s) — <https://developer.apple.com/videos/play/wwdc2020/10652/?t=620>
- Forum thread on `PHAsset` fetch failing for non-limited-set picks — <https://developer.apple.com/forums/thread/660696>

**Corroborating third party** (used only where Apple is silent; flagged as such in the text)
- <https://stackoverflow.com/questions/62625797/how-to-retrieve-phasset-from-phpickerviewcontroller>
- <https://www.biteinteractive.com/picking-a-photo-in-ios-14/> (empty `PHFetchResult` under `.limited`)
- <https://mackuba.eu/notes/wwdc20/meet-the-new-photos-picker/> (WWDC20 notes)
- QL thumbnails appear to cache automatically; `generateBestRepresentation` stalls on large files — <https://medium.com/@itsuki.enjoy/swiftui-quicklook-preview-edit-files-in-app-generate-thumbnails-for-files-on-the-fly-18bcc7e475db>, <https://stackoverflow.com/questions/70724015/>

**Android (for the parity read)**
- *Generate media thumbnails* (`ContentResolver.loadThumbnail`, `ThumbnailUtils.createImageThumbnail`,
  `ImageDecoder.setTargetSampleSize`) — offline KB `kb://android/social-and-messaging/guides/media-thumbnails/index`

**Repo**
- `docs/adr/0021-ios-progressive-photo-import.md` (path-first staging; Session holds Ready paths; zero framework growth)
- `docs/adr/0028-coil-kmp-ui-image-loading.md`, `docs/adr/0010-c2-golden-policy-delta.md`,
  `docs/adr/0009-exif-strip-is-a-feature.md`
- `docs/superpowers/research/2026-08-14-ios-preview-perf-leftovers.md` (S1 decode-dominated; S3 the 128-vs-1920 inversion; S5 the 128 MiB joint ceiling)
- `docs/superpowers/research/2026-08-13-product-thumb-coil-ab.md` (Skia cannot decode HEIC)
- `docs/superpowers/research/2026-08-14-ios-cgimage-skia-zero-copy-plan.md` (`CGImage → Skia` primitives; filmstrip copies are noise)
- `shared/src/iosMain/.../render/IosImageIODecoder.kt`, `.../ui/image/IosHeifDecodePolicy.kt`,
  `IosHeifImageDecoder.kt`, `ProductThumbFetcher.ios.kt`, `ProductImageLoader.ios.kt`,
  `ui/IosDevicePerfBench.kt`
- `shared/src/commonMain/.../ui/image/ProductThumb.kt`, `ProductThumbKeyer.kt`
- `shared/src/androidMain/.../ui/image/ProductThumbFetcher.android.kt` (the `loadThumbnail` pattern)
- `iosApp/iosApp/ContentView.swift` (`photoLibrary: .shared()`), `PhotoImportCoordinator.swift`,
  `ProgressiveImportNotifications.swift`, `Info.plist` (add-only usage description)
- K/N platform klibs verified present: `org.jetbrains.kotlin.native.platform.Photos`,
  `…PhotosUI`, `…QuickLookThumbnailing` (2.4.20-Beta1, `ios_simulator_arm64`)

---

## 12. Open questions

1. **What does S3's subsampling actually do on device at 128 px?** Blocks everything. Arm `A1`.
2. **Do PHPicker-exported iPhone HEICs carry an embedded container thumbnail, at what size, and how
   often?** Determines whether rung 2 exists. Arm `B` with per-file hit/size logging.
3. **QL cold vs warm at 128 px on a 12 MP HEIC** — and does the cache survive app relaunch and
   device reboot for *app-container* files? Undocumented. Arms `D`/`E`, plus a relaunch check.
4. **Is `kCGImageSourceGenerateImageSpecificLumaScaling` already skipped for thumbnail requests?**
   If the gain-map tone-mapping work is not happening, arm `C` is worth nothing.
5. **Is `IosHeifDecodePolicy.imageIoShouldCache` inert?** §5.2 says probably. Arm `G` confirms, and
   the knob then gets re-scoped or removed with the measurement recorded.
6. **Owner decision: is a Photos read-access prompt acceptable at all?** If no, rung 5 is deleted
   from the design rather than deferred, and §9.4's parity caveat becomes a permanent entry in the
   iOS/Desktop exception registry.
7. **Does the embedded thumbnail (or QL's, or PhotoKit's `.fastFormat`) look right next to the
   editor preview?** Viewed screenshots only (ADR-0010). Applies to every pixel-changing arm.
8. **Does `.thumbnail`-vs-`.lowQualityThumbnail` sizing interact badly with the fixed 56/48/40
   filmstrip geometry** owned by `EditorFilmstripScaffold`? QL may return "larger or smaller" than
   requested for the low-quality rung.
