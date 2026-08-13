# ADR-0028: Coil KMP for UI image loading (exclude watermark compose/export)

**Status:** Accepted (2026-08-11)  
**Owner decision:** grill-with-docs Round 1–2 (Q1=B, Q2 exclude compose/export, Q3=A, Q4=A, Q5=B, Q6=A, Q7=A, Q8=A, Q9=A, Q10=A, Q11=A)  
**Related:** ADR-0027 (content editor theme seed), MediaRef / commonMain UI, privacy offline

## Context

UI thumbnails and list images are loaded inconsistently: Android Gallery/Save/Icon already use Coil 3 with a custom `MediaStoreThumbnail` Fetcher; Editor filmstrip and content-theme seed use hand-rolled `produceState` + MediaStore/`BitmapUtils`; iOS/Desktop filmstrip/export thumbs use platform `decodeThumbnail` maps. Coil 3 is multiplatform, but the dependency lives only in `:app`. Default Coil `.data(contentUri)` is unsafe for gallery-scale scroll (opens full content; known jank). Product remains offline-local (no network image product path).

Owner wants **full UI Coil-ization on KMP**, **one shot (not phased)**, while **watermark composition / export decode stay non-Coil**.

## Decision

### 1. Scope — UI load only

**In scope (must use product Coil path):**

- Gallery grid cells  
- Editor filmstrip cells  
- Save/export sheet thumbs  
- Icon option preview  
- Content-theme **seed** decode (same max-edge as UI thumb policy)  
- Desktop/iOS UI thumbs that today call `decodeThumbnail` for chrome (filmstrip, sheet thumbs)

**Out of scope (forbidden to replace with Coil as product decode engine):**

- Central watermarked **preview** composition (`WaterMarkCanvas` / iOS watermarked `previewBitmap` pipeline / `CommonWatermarkPipeline` consumers for editor preview)  
- Final **export** full-resolution decode  
- Renderer icon bytes into composition (engine path)  
- Golden / unit tests that decode fixtures for pixel gates  

Displaying an **already composed** `ImageBitmap` in the preview slot is not “Coil loading”; do not force Coil there.

### 2. KMP placement

- Depend on **`io.coil-kt.coil3:coil-compose`** from **`:shared`** (commonMain) so shared UI can call `AsyncImage` / `ImageRequest`.  
- **Do not** add `coil-network-*` for this product slice (offline; local models only).  
- **One process-level `ImageLoader` per platform** (singleton / `SingletonImageLoader` pattern), registered with platform Fetchers/Keyers at app start.  
- Remove ad-hoc `remember { ImageLoader.Builder() }` that fragment memory caches (converge `galleryImageLoader()` into the singleton).

### 3. Data model and Android MediaStore (Q4=A)

- Common model (name may vary): **`ProductThumb(ref: MediaRef, maxEdgePx: Int, purpose: …)`** (or equivalent).  
- **Android:** product path must **not** use bare `.data(contentUri)` for gallery/session media. Map to **MediaStore thumbnail fetch** (existing `MediaStoreThumbnail` logic promoted under the common model / androidMain Fetcher).  
- **iOS/Desktop:** Fetcher/decode from path/`MediaRef` with **maxEdge** sampling, never full-res for UI thumbs.  
  **iOS amendment (2026-08-13):** `ProductThumbFetcher` hands a file [SourceFetchResult]. JPEG/PNG use Coil’s Skia decoder + request size. HEIC/HEIF use [IosHeifImageDecoder] (ImageIO thumbnail) — Skia `makeFromEncoded` cannot decode HEIF (coil#2318, skiko#942). Decoder is **policy-driven** (`IosHeifDecodePolicy.ProductUi` for filmstrip/seed; `Preview` for larger HEIF; per-request `iosHeifMaxEdgePx`).  
  **Desktop amendment (2026-08-13):** same SourceFetch + Skia downsample (A/B: ~4ms vs ~15ms ImageIO+repack on 3000×2000 JPEG → 128). [DesktopProductSkiaDecoder] is Coil’s Skia path with `isSampled=false` for LazyRow memory hits. Do **not** re-bake JPEG EXIF — skiko `makeFromEncoded` already applies orientation (`SkiaExifDecodeProbeTest`).  
- Coil **ImageRequest** official params (`.size`, cache policies, crossfade, bitmapConfig, extras) stack **on top of** custom data — they do not replace the MediaStore/read-source strategy.

### 4. Size and cache sharing (Q10=A)

- Single UI thumb max-edge constant aligned with content-theme seed policy (**128** preferred, or one shared `UI_THUMB_MAX_EDGE` used by filmstrip + theme seed + comparable chrome).  
- Memory cache keys include **ref + maxEdge** (and purpose only if edges differ). Prefer **same edge** so filmstrip and theme seed **share** cache entries.  
- Local UI loads: prefer **memory cache on**, **disk cache off** (or no benefit for content URIs).

### 5. Delivery (Q9=A — one shot)

Implement as **one coordinated change** (not Android-only phase then iOS later):

- shared Coil dependency + common API (`ProductAsyncImage` / request builders as needed)  
- android / ios / desktopMain Fetchers + singleton loader wiring  
- migrate all in-scope call sites  
- delete dead hand paths once replaced  

**Ship bar (Q5=B):** architecture complete + HEIC/EXIF/content-URI thumbs not blank + filmstrip/gallery scroll no regression vs current MediaStore path; compile all targets. Physical iOS multi-image stress is **strongly recommended** before calling the hang residual closed (preview-cache budget remains separate from this ADR).

### 6. Explicit non-goals

- Coil as watermark engine or export decoder  
- Network image CDN / `coil-network` in this slice  
- Bare content-URI product path “because Coil supports size”  
- Phased “Android only” ship (owner chose one-shot)

## Considered options (rejected)

| Option | Why not |
| --- | --- |
| Bare `.data(Uri)` + `.size` only | Default ContentUri fetcher still opens full content; jank/blank history |
| Coil stays `:app` only | Contradicts KMP / shared UI direction |
| Phased Android-then-iOS (grill Q9-B) | Owner chose Q9=A one-shot |
| Coil for watermark preview compose | Mixes engine/export with UI loader; golden/privacy decode policy |
| Multiple ImageLoaders per surface | Splits memory cache; worse hit rate |

## Consequences

- New glossary terms: **Product UI image load (Coil)**, **ProductThumb**, exclusion of **Watermark compose/export decode**.  
- `:app` Coil dependency moves/centers on `:shared`; Android retains MediaStore Fetcher semantics.  
- Implementation is a large cross-platform slice; treat as owner-gated milestone with full-target compile and UI regression list.  
- Does **not** by itself fix multi-image watermarked **preview** RAM (ADR-0027 hang item 3); that remains a separate budget ADR/task.
