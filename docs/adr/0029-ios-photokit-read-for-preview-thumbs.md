# ADR-0029: iOS PhotoKit read access for preview / chrome thumbs

**Status:** Accepted (owner 2026-08-15)  
**Context slice:** iOS editor cold-switch + filmstrip / UI thumb latency vs album HEIC  
**Related:** ADR-0021 (path-first import — **unchanged** for Session / export), ADR-0009 (EXIF strip on export), ADR-0028 (Coil UI thumbs)

## Context

Cold editor switch on album HEIC is decode-dominated (~214 ms / ~94% of wall clock;
`2026-08-14-ios-preview-perf-leftovers.md` S1). Camera HEIC is a tiled HEVC grid; ImageIO
pays a near-fixed full decode for any requested preview edge. Filmstrip 128 px shares that
floor (`2026-08-15-ios-filmstrip-heic-latency-synthesis.md`).

Apps such as CapCut / Douyin feel “instant” largely because they consume **system Photos
derivatives** (`PHImageManager`) and/or ship small sticker assets — not because ImageIO can
make a 12 MP HEIC thumb cheap from an app-owned file URL.

Until this ADR the iOS app declared **only** `NSPhotoLibraryAddUsageDescription` (add-only
save). Staging picks into Documents (ADR-0021) orphans path-keyed access to Photos’ thumbnail
store: nothing in PhotoKit accepts a file URL.

**Owner decision (2026-08-15):** requesting **read** photo-library access is acceptable.
Privacy posture remains **fully offline / no network / no tracking**; library read is
user-gated and documented in README / privacy materials alongside the existing add-only
save string. Offline is the hard promise; add-only-only is not.

## Decision

1. **Declare and request read access** via `NSPhotoLibraryUsageDescription` (keep
   `NSPhotoLibraryAddUsageDescription` for save). Request timing: at first editor need for
   PhotoKit-backed pixels (or an explicit settings affordance) — not at cold launch for its
   own sake.
2. **Carry `PhotosPickerItem.itemIdentifier`** (local identifier) on the existing
   NotificationCenter progressive-import payload. Zero `Shared.framework` public growth
   preferred (ADR-0021 item 3). Auth-free wins even before PhotoKit consume: stable cache key,
   dedupe, picker preselection.
3. **PhotoKit is a fast path for chrome + optional first paint only.** Session continues to
   hold **Ready owned paths** for render / export (ADR-0021). Final export still decodes /
   composes from app-owned bytes and strips EXIF (ADR-0009). Do not make `PHAsset` the Session
   source of truth.
4. **Ladder (cheapest first on miss):**
   - Authorized + resolvable `PHAsset` → `PHImageManager` / `PHCachingImageManager`
     (target size for filmstrip / opportunistic or high-quality for preview first frame).
   - Else → existing ImageIO path on owned file (today’s behaviour).
5. **Limited library is a first-class miss path.** PHPicker selection does **not** extend
   limited-library membership (Apple DTS). If `fetchAssets` is empty for a just-picked id,
   fall back to ImageIO without blocking the editor and without re-prompting in a loop.
6. **Denied / restricted:** never soft-fail into a broken editor; ImageIO-only mode. Optional
   UI copy may explain faster thumbs need Photos access — no dark patterns.
7. **Scope:** iOS only for this ADR. Android already has MediaStore thumbnail paths under its
   own permission model; do not invent a shared `expect` PhotoKit layer without a named
   second consumer.

## Consequences

- **Positive:** can match CapCut-class “tap → image appears” when Photos has derivatives and
  authorization is `.authorized` / usable limited set; prefetch via
  `PHCachingImageManager` for focus ±2.
- **Positive:** privacy story stays coherent: offline + user-consented library read + no
  network exfiltration.
- **Negative / residual:** Limited users may still pay ImageIO full decode for picks outside
  the allowed set; must ship and test that fallback.
- **Negative:** App Store / privacy policy / README must describe read access (purpose:
  faster in-app previews of photos the user selected — not scanning the whole library for
  upload).
- **Doc:** AGENTS.md / iosApp README privacy lines update with this ADR; research notes
  `2026-08-15-ios-watermark-preview-perf-1plus2.md` treat PhotoKit as unblocked.

## Non-goals

- Replacing ADR-0021 path-first Session with asset-id Session.
- Shipping libheif / FFmpeg for thumbs.
- Requiring read access for export or save (add-only save remains).
- Claiming byte-parity between PhotoKit first paint and ImageIO preview (ADR-0010: view
  screenshots).

## Implementation sketch (not part of the decision freeze)

1. Plist + localized usage string; README / privacy blurb.  
2. Plumb `itemIdentifier` on progressive NC payload.  
3. Host-side `PHImageManager` producer → Coil `ImageFetchResult` for filmstrip / optional
   preview placeholder.  
4. Device A/B: authorized hit vs Limited miss vs denied (ImageIO).  
5. Neighbor `startCachingImages` once switch path is green.
