# CGImage → Skia transfer A/B (Phase 1)

**Date:** 2026-08-14  
**Harness:** `IosCgImageTransferAbBenchTest` on iOS Simulator (`iosSimulatorArm64`)  
**Plan:** `docs/superpowers/research/2026-08-14-ios-cgimage-skia-zero-copy-plan.md`  
**Question:** Does drawing into Skia-owned memory (and ending Compose on `asComposeImageBitmap`) actually remove the L2/L3 full-frame buffers the plan claimed?

## Metrics (planned before collect)

| Metric | Role | How measured |
|--------|------|----------------|
| **`accounted_alloc_bytes`** | **Primary** | Sum of full-frame (`w×h×4`) buffers allocated on the transfer path. Matches research §1.2 “wasted writes”, not OS RSS. |
| **`full_frame_writes`** | Gate | Expected A=3 (compose) / 2 (bitmap\|image); B=1. |
| **`handoff_ns`** | Secondary latency | Time after `CGContextDrawImage` until the Skia/Compose object is published (`installPixels` / `makeRaster(ByteArray)` / Compose re-raster vs ~noop wrap). |
| **`draw_ns`** | Control | `CGContextDrawImage` only — should be ~equal across arms. |
| **`e2e_ms`** | Honesty | Full ImageIO thumbnail + transfer; decode can dominate (esp. HEIC). |
| **Pixel parity** | Correctness | A vs B sampled RGB within 0.02 at 720. |

Not claimed: jetsam RSS, Main-thread jank, physical-device 12MP album (still needs `DEVICE_PERF` on device).

## Arms

| Arm | Mode | Path |
|-----|------|------|
| **A** | `LegacyByteArray` | pin Kotlin `ByteArray` → Draw → `installPixels` / `makeRaster(ByteArray)` → (compose) `Image.toComposeImageBitmap()` |
| **B** | `SkiaOwned` (production) | `allocPixels` / `Data.makeUninitialized` → Draw → (compose) `Bitmap.asComposeImageBitmap()` |

Fixture: busy 2400×1600 PNG (and ImageIO-encoded HEIC when the simulator can write `public.heic`).  
Cold decode, 1 warmup + 5 timed samples per arm; medians reported. Mode toggled via `IosCgImageTransferProbe` so A does not require checking out an old commit.

## Results (this machine / Simulator)

### Compose surface (preview / export-thumb product path) — PNG

| edge | out | A writes / alloc | B writes / alloc | alloc saved | A→B handoff | A→B e2e med |
|------|-----|------------------|------------------|-------------|-------------|-------------|
| 128 | 128×85 | 3 / 128 KiB | 1 / 43 KiB | **85 KiB** | 48→10 µs | 12→12 ms |
| 720 | 720×480 | 3 / 3.95 MiB | 1 / 1.32 MiB | **2.64 MiB** | 409→11 µs | 17→17 ms |
| 1920 | 1920×1280 | 3 / **28.1 MiB** | 1 / **9.4 MiB** | **18.8 MiB** | 2047→18 µs (~**2.0 ms**) | 28→24 ms |

### Compose surface — HEIC (same edges)

| edge | out | A alloc | B alloc | alloc saved | A→B handoff | A→B e2e med |
|------|-----|---------|---------|-------------|-------------|-------------|
| 128 | 128×84 | 126 KiB | 42 KiB | 84 KiB | 54→14 µs | 24→23 ms |
| 720 | 720×480 | 3.95 MiB | 1.32 MiB | 2.64 MiB | 440→15 µs | 25→25 ms |
| 1920 | 1920×1280 | **28.1 MiB** | **9.4 MiB** | **18.8 MiB** | 2045→15 µs (~**2.0 ms**) | 55→54 ms |

HEIC `draw_ns` (~11–28 ms) dwarfs handoff; e2e barely moves. Memory story is unchanged vs PNG.

### Surface split at PNG 1920 (same fixture)

| surface | A writes / alloc | B writes / alloc | handoff A→B |
|---------|------------------|------------------|-------------|
| bitmap (Coil) | 2 / 18.8 MiB | 1 / 9.4 MiB | 636→0 µs |
| image | 2 / 18.8 MiB | 1 / 9.4 MiB | 612→7 µs |
| compose | 3 / 28.1 MiB | 1 / 9.4 MiB | 1972→14 µs |

### Correctness

`pixels_matchBetweenArms_at720` — **pass** (sampled RGB).

## Verdict

| Claim from the plan | Measured? |
|---------------------|-----------|
| Compose path drops 3→1 full-frame allocs | **Yes** — exact `frameBytes × writes` accounting. |
| Preview 1920 saves ~2× frame of waste (~22 MiB estimate used 1920×1440; here 1920×1280 → **18.8 MiB** saved) | **Yes**, order-of-magnitude match. |
| Filmstrip 128 is noise for latency | **Yes** — e2e flat; handoff tens of µs. |
| Win is **memory**, not decode ms | **Yes** — HEIC e2e −1 ms; PNG 1920 −4 ms (~hand-off only). |
| Draw cost unchanged across arms | **Yes** — `draw_med_us` within normal jitter. |

**Ship Phase 1 as a memory win.** Do not sell it as a filmstrip/HEIC latency fix. Phase 2 (skip-Draw format gate) still needs a real album hit-rate probe before any code.

## Commands

```bash
./gradlew :shared:iosSimulatorArm64Test \
  --tests me.rosuh.easywatermark.render.IosCgImageTransferAbBenchTest
# Lines: CG_TRANSFER_AB …
```

## Raw lines (excerpt)

```
CG_TRANSFER_AB … edge=1920 … A_writes=3 A_alloc_B=29491200 … B_writes=1 B_alloc_B=9830400 … alloc_saved_B=19660800 handoff_delta_us=2028
CG_TRANSFER_AB … fmt=heic edge=1920 … A_e2e_med_ms=55 … B_e2e_med_ms=54 … alloc_saved_B=19660800
```

## Device witness (iPhone 16 Pro, 2026-08-14)

Installed Debug `me.rosuh.easywatermark.ios`, launched with `-ewmCgTransferAb`.
Raw log: `parity-shots/cg-transfer-ab-device/ewm-cg-transfer-ab.txt`.

### Compose PNG @ 1920 (device)

| | A legacy | B owned | delta |
|--|----------|---------|-------|
| writes / alloc | 3 / 28.1 MiB | 1 / 9.4 MiB | **−18.8 MiB** |
| handoff med | 1593 µs | 8 µs | **−1.6 ms** |
| e2e med | 28 ms | 26 ms | −2 ms |

Memory story matches Simulator; handoff savings ~1.6 ms on device (Simulator was ~2.0 ms). HEIC @ 1920 same **−18.8 MiB**, e2e 22→20 ms.

App left running without the bench arg for interactive use.
