# ADR-0030: Cross-platform preview working set

**Status:** Proposed  
**Related:** ADR-0018 (common raster), ADR-0028 (Coil UI only), ADR-0029 (iOS Library Read / PhotoKit — **not** in this repository)

Editor preview on iOS already reused a decoded `SourcePlaceholder` and only recomposed on config change. Desktop re-read + `ImageIO.read` on every slider tick (plus 250 ms debounce). Android often re-entered `decodeSampled` / `BitmapCache` and could start overlapping `composeToBitmap` work.

**Decision:** one commonMain `PreviewImageRepository<T>` owns Source / Watermarked residency (key = owned path + pixel bucket + purpose). Decode, EXIF, and file/`ContentResolver` IO stay at the platform edge — no `expect` decode. `DraftRenderConflator` bounds slider + CLAMP to one in-flight render plus the latest ticket. `WatermarkIconCache` is a single 256 px slot. Desktop and Android hosts adopt the iOS working-set rules. **PhotoKit / Library derivative / Library Read upsell / `PHCachingImageManager` stay iOS-only** and never become compose backgrounds.

`Watermarked` keys must not include config or offset. ApplyConfig clears Watermarked; offset commit invalidates that path’s Watermarked; draft frames are never written as Watermarked. **ADR-0033:** the editor main preview no longer requires a Watermarked working-set hit and must not use [PreviewPaintPolicy.showSourceWhileComposing] as the wait policy. Source residency stays. Wait chrome is the Coil filmstrip thumb or an empty slot until Source + matching overlay can publish together. Joint eviction order remains ExportThumbnail → SourceFastPath → Watermarked → SourcePlaceholder. Filmstrip thumbs stay Coil (ADR-0028). Android must not `recycle` Bitmaps. `HIGH_MEMORY_JOINT_MAX` stays 128 MiB. Types are `@HiddenFromObjC` — not Shared.framework API.

## Considered and rejected

- `expect fun decode` in commonMain — would pull ImageIO / ContentResolver / EXIF into the shared module.  
- Porting PhotoKit first-paint to Android/Desktop — no Photos daemon; ADR-0029 stays iOS.  
- Keeping the cache Desktop-only inside `:desktopApp` — desktopTest / shared hosts could not share the state machine.

## Consequences

- iOS keeps a thin `IosPreviewImageRepository` subclass so existing Host call sites stay the same.  
- Desktop drops the 250 ms preview debounce. Large committed panes (up to 3840) may still be compose-heavy; draft-while-sliding is a later slice. Filmstrip switch drops the previous live overlay immediately (thumb / empty wait), then awaits the focus LiveLayers paint before warming focus±2 Source — neighbors must not start on the same committed-bucket decode as the photo the user just clicked. Desktop neighbor ImageIO must hop off Main (`PreviewImageRepository` completion is the Host dispatcher). Desktop committed bucket is pane-stable (not per-image Fit) so aspect changes do not miss the working set.  
- Android focus Source is a strong repository entry so `onTrimMemory` can drop Watermarked / neighbors without forcing three `openInputStream`s mid-gesture.
