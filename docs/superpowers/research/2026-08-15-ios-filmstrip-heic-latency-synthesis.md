# iOS filmstrip HEIC latency — synthesis (2026-08-15)

Four parallel research tracks + on-device S3 close-out. **No production code in this note.**

| Track | Doc | Agent |
|---|---|---|
| Apple ImageIO / VT / PH / QL | `2026-08-15-ios-heic-thumb-apple-apis.md` | ImageIO HEIC thumbs |
| Skia / Skiko / CMP / Coil | `2026-08-15-skia-cmp-coil-heic-paths.md` | Skia CMP KMP pixel path |
| FFmpeg / libheif | `2026-08-15-ffmpeg-libheif-thumb-feasibility.md` | FFmpeg C++ libheif options |
| System thumb caches / privacy | `2026-08-15-ios-system-thumb-cache-apis.md` | Photos daemon cache research |

Device fact (authoritative): `build/ios-device-shots/s3-subsample-verify/ewm-device-perf.txt` —
album HEIC n=8, order-balanced plain vs sub in one process:

| | plain | subsample |
|---|---:|---:|
| `io128_med` | 183 | **198 (worse)** |
| `io1920_med` | 143 | **124** |

---

## Consensus (all four agree)

1. **Cost is a fixed full-frame HEVC decode**, not a "128 px tax." Camera HEICs are tiled grids
   (~63 × 512² tiles); ImageIO decodes every tile regardless of `ThumbnailMaxPixelSize`. No embedded
   `thmb` / aux thumbnail on modern album files (`IfAbsent` ≡ `Always` for cost).
2. **`kCGImageSourceSubsampleFactor` is closed as a filmstrip latency fix.** Dimensionally honored
   on HEIF in host probes; **not economically** (device: 183→198 ms at 128). JPEG DCT scaling still
   benefits — HEIF does not.
3. **No ImageIO option dictionary tweak beats production** (`Always` + MaxPixelSize ± Subsample).
   HDR knobs (`DecodeToSDR`, luma scaling off) measured inert or worse on gain-mapped files.
4. **Skia / Metal / FFmpeg / libheif are out.** Skia has no HEIF codec; skiko cannot wrap MTLTexture
   for readback; Compose cannot draw bare `CGImage`. FFmpeg cannot assemble tiled HEIC; libheif is
   LGPLv3 vs MIT + F-Droid.
5. **PhotoKit is fastest and the wrong privacy trade** for this app (add-only today; Limited library
   + PHPicker does not grant read of just-picked assets). Owner/ADR only.
6. **Coil `diskCachePolicy(DISABLED)` is correct for *source* bytes** — enabling it does not skip
   HEIC decode. What helps is a **derived** 128/256 px store (app-owned JPEG sidecar) or an OS
   thumbnail cache (QL), not Coil's encoded-source disk cache.

---

## Ranked options (ROI for 128 px filmstrip)

| Rank | Option | First paint | Repeat / cold relaunch | Privacy | Effort | Device status |
|---|---|---|---|---|---|---|
| **1** | Derive 128 from resident preview decode (720–1920 working set) | Hit: ~1 ms draw; miss: still ~180 ms | Session only | None | Low–med (Coil `ImageFetchResult` or shared raster) | **Next experiment** |
| **2** | App-owned JPEG sidecar (write once after first decode) | Same as today once | **~0.2–0.3 ms** host re-read | Writes downscaled user pixels to Documents — **owner call** | Low (Fetcher check + encode) | Mechanism solid; measure write cost on device |
| **3** | `QLThumbnailGenerator` | Host ~⅓ ImageIO; **device unknown** | Host **~1–2 ms** (OS cache) | No new permission; set `contentType` (provisional paths have no extension) | Med | **Must A/B on device** before commit |
| 4 | Carry `PhotosPickerItem.itemIdentifier` | N/A | Cache key / dedupe / preselect | Auth-free; PhotoKit consumer still gated | Tiny | Independent win |
| ✗ | PhotoKit `PHImageManager` | Best | Best | Read prompt + Limited empty-fetch | — | Rejected unless posture changes |
| ✗ | More ImageIO / VT / subsample | — | — | — | — | Closed |
| ✗ | libheif / FFmpeg / Skia HEIF | — | — | Licence / impossible | — | Closed |

---

## Recommended next experiment

**E1 — Derive filmstrip 128 from already-resident preview pixels** (Apple doc E1 / Skia §4.1's
in-session half).

Why first:

- Needs no new Apple framework, no permission, no persistent photo copies.
- Preview path already pays ~180 ms for focus ±2; filmstrip today pays it **again**.
- Success is observable without trusting host QL ratios: filmstrip cell on a focused photo should
  drop from ~200 ms class to draw-scale cost; miss path unchanged.

**Pass criteria (device):** for a photo already in the preview working set, filmstrip / Coil cold
thumb for that path completes in **&lt; 5 ms** median (decode skipped); screenshot parity vs today's
128 cell (ADR-0010 — view, don't byte-diff).

**Then E2 / E3** only if E1 leave cold-import / cold-relaunch filmstrip still painful:

1. Owner-gated sidecar (persistent) — privacy note in ADR or exception registry.
2. QL device A/B with mandatory `contentType` — do not ship on host 3× alone.

**Do not:** re-open S3 subsample for 128; enable Coil source disk cache expecting a HEIC win; add
libheif.

---

## Nuances worth keeping straight

- **128 vs 1920 inversion** is mostly fixed-cost variance + cheaper resample at 128, not a separate
  bug. Paired per-photo sampling may shrink the gap (Apple E0); it does not unlock a cheap ImageIO
  thumb.
- **Sidecar ≠ Coil disk cache.** Sidecar stores *derived JPEG*; Coil disk stores *source HEIC*.
- **QL cold may be worse than ImageIO on device** (out-of-process). Treat warm/OS-cache as the bet;
  measure cold separately.
- **Android `loadThumbnail` parity** would imply an iOS read permission the product deliberately
  avoids — exception-registry territory, not a silent port.

---

## Doc impact

Research only. If E1 or E2/E3 ship: update leftovers / CONTEXT as needed; PhotoKit would need an
ADR. No AGENTS.md change until a path lands.
