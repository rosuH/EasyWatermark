# iOS editor preview: measurement, cache correctness, HEIC decode cost (2026-08-14)

Follow-up to `2026-08-13-preview-source-reuse-working-set.md`. Five slices, each independently
revertible. Device numbers are iPhone 16 Pro, **album** HEIC (long edge ≥ 3000), `n=8`.

## S1 — the switch was unmeasured, not half-unattributed

`IosPreviewBench` logged with `println`, which never reaches the device unified log, so the
per-stage marks were invisible on device and the switch looked ~50% unaccounted for. Added `NSLog`
alongside, plus `IosPreviewBench.Attribution` — a thread-safe stage accumulator that lets the host
decompose one switch into decode / compose / icon / dispatch / other.

The switch is **decode-dominated**, not unattributed:

| Lap | Total | Decode | Compose | Icon | Dispatch | Other |
|---|---:|---:|---:|---:|---:|---:|
| L1 | 226 ms | 214 ms | 7 ms | 0 | 0 | 1 ms |
| L2 | 234 ms | 219 ms | 9 ms | 0 | 0 | 2 ms |

**Decode is ~94% of a cold switch; compose is ~3%.** This number is what justifies S4's source
reuse and S5's eviction inversion, and it is the reason cost-asymmetric eviction is worth having
at all.

The IO measurement also had an **order bias**: each long-edge was always measured in the same
position, so the first one absorbed first-touch cost. Alternating the order per path removed it and
exposed the S3 finding below.

## S2 — the cache was FIFO, and the watermark icon was re-decoded every compose

Eviction picked `cache.keys.first()` on an insertion-ordered map, so it was FIFO despite being
described as a working set. Production inserts **focus first, then ±2**, which is exactly the order
under which FIFO discards the frame the user is looking at and re-decodes it one tap later. Fixed by
re-inserting hits at the tail (`touchLocked`), making the head genuinely the least recently used.

`IosWatermarkIconCache` memoises the decoded icon per `(MediaRef, maxEdge)`; it was previously
decoded on every compose in Image mode.

**Bench result: unchanged (201/209 ms FIFO vs 204/209 ms LRU), and the reason matters.** The
8-photo album bench cannot distinguish the two policies: whatever it evicts, it immediately
re-warms, so both policies report 7/8 hits on laps 3–4. LRU correctness is therefore held by
`IosPreviewLruProductionOrderTest` (production insert order, ±3 is the victim rather than the frame
just viewed), not by this bench. A bench that could see the difference needs a longer scrub than
the working set.

## S3 — a 128px HEIC thumbnail cost *more* than a 1920px preview

Order-balanced measurement showed `io128_med` **202 ms** against `io1920_med` **138 ms**. Smaller
output, more time. Cause: `kCGImageSourceCreateThumbnailFromImageAlways` decodes the full image and
then scales, so the request size barely matters — and `IosHeifImageDecoder`'s KDoc actively denied
this, claiming the flag avoided a full-res decode. The doc was wrong and is corrected.

Fix: `kCGImageSourceSubsampleFactor`, choosing the largest of 8 / 4 / 2 whose subsampled long edge
still meets the requested edge, so **output dimensions do not change** (`IosImageIOSubsampleTest`).
Gated by policy — `ProductUi` (filmstrip, theme seed) opts in, `Preview` is untouched.

`kCGImageSourceCreateThumbnailFromImageIfAbsent` was rejected: it returns the embedded thumbnail at
an unpredictable, possibly tiny size.

**Device verification pending** — the iPhone 16 Pro disconnected before the after-run. The before
number and the code are landed; the after-number and the filmstrip sharpness screenshots are
outstanding and no perf win is claimed.

## S4 — CLAMP drag had no backpressure

Every pointer sample launched its own render. `previewGen` discarded the stale *results*, but only
after each had paid a full decode + compose, so a fast drag queued as many 12MP decodes as it had
samples.

- `IosDraftRenderConflator`: a `CONFLATED` channel bounds the pipeline at one render in flight plus
  one pending, and guarantees the newest offset is the one that paints.
- The draft now reuses the Source decode across the gesture. This is sound because a Source
  placeholder is the un-watermarked decode — offset plays no part in it — so sharing it with the
  committed path is at worst a duplicate and often a hit. Drafts still never write a *Watermarked*
  entry, so export cannot observe draft paint.

Together: one decode per drag instead of one per frame. Commit-time bench extras now emit
`draftSamples` vs `draftRenders` so the ratio is measurable rather than assumed. **Device
measurement outstanding.**

## S5 — bytes were the wrong control, and eviction had its priority backwards

Two defects, both invisible to the tests that existed.

1. **Aspect assumption.** `bytesPerFrame` modelled a 4:3 frame, so it under-counted every source at
   or above 1:1 (a 1920 square frame is 14.7 MiB against an 11.06 MiB model). Byte eviction held 3
   frames while the code promised focus + ±2 — and the only fixture was 4:3, so no test failed.
   Bytes are now a fence sized for the **worst** aspect ratio, and **per-purpose entry counts**
   control residency, which makes the promise aspect-independent. Sources get one extra slot,
   because a CLAMP draft legitimately decodes a sixth source mid-gesture and evicting a neighbour's
   decode is the worst available trade.

2. **Eviction priority.** Joint pressure dropped `SourcePlaceholder` before `Watermarked`. Given
   S1, that is backwards: a Watermarked frame whose source is resident is a ~7 ms compose, a source
   is a ~215 ms cold decode. Order is now ExportThumbnail → Watermarked → SourcePlaceholder, so
   memory pressure costs a recompose instead of a re-decode. It cannot blank the screen — the host
   holds the visible bitmap itself, so the cache only decides what *returning* to a frame costs.

Frames-per-layer by bytes after the change (high-memory):

| Bucket | 4:3 | 5:4 | 1:1 | Binding cap |
|---|---:|---:|---:|---|
| 720 | 32 | 30 | 24 | entries (5) |
| 1080 | 14 | 13 | 10 | entries (5) |
| 1440 | 8 | 7 | 6 | entries (5) |
| 1920 | 6 | 5 | 4 | entries (5) |

Entry count binds at every phone bucket, for every aspect that reaches it. A true 1:1 source cannot
reach 1920 through the Fit path — it is fitted to the pane *width*, landing at 1440 — so the 1:1
column at 1920 is only reachable via the metadata-missing fallback, which is deliberately
conservative. The 128 MiB joint ceiling is unchanged.

## Honest limits

- S3, S4, S5 have code plus unit tests but **no device numbers**: the iPhone 16 Pro went
  `unavailable` mid-session. No perf win is claimed for them.
- S3's filmstrip sharpness has **not** been visually verified (ADR-0010 requires viewing, not byte
  sizes). Subsampling is proven dimension-preserving by unit test, which is not the same as proven
  visually equivalent.
- The 8-photo album bench cannot distinguish LRU from FIFO for the working-set-sized scrub it
  performs.
