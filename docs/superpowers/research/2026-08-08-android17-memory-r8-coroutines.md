# Research: Android 17 Memory Limits + R8 Coroutines 2× + Blog Shortlist

**Date:** 2026-08-08  
**Audience:** EasyWatermark maintainers (KMP / CMP photo watermark app)  
**Visual companion:** `~/.agent/diagrams/android17-memory-r8-research.html`

## Sources (primary)

| # | Source | Role |
|---|--------|------|
| 1 | [Prioritizing Memory Efficiency (Android Developers Blog, 2026-06-02)](https://android-developers.googleblog.com/2026/06/prioritizing-memory-efficiency-steps-for-android-17.html) | English original of the WeChat post |
| 2 | [微信公众号：Android 17 内存管理升级](https://mp.weixin.qq.com/s/IeE0ElFil6X_-66J3o2P8Q) | CN mirror of #1 (same authors: Alice Yuan / Ajesh Pai / Fung Lam) |
| 3 | [How R8 made Kotlin Coroutines on Android 2x faster (2026-07-27)](https://android-developers.googleblog.com/2026/07/how-r8-made-kotlin-coroutines-2x-faster.html) | R8 Atomic*FieldUpdater → Unsafe |
| 4 | [Behavior changes: all apps — App memory limits](https://developer.android.com/about/versions/17/behavior-changes-all#app-memory-limits) | Official limits + `adb am memory-limiter` |
| 5 | X / @AndroidDev, @MishaalRahman | Official + community signal on memory limits & R8 |
| 6 | Blog index curation | Additional R8 / resource shrinking / Compose perf / Room 3 posts |

## EasyWatermark toolchain (repo truth at research time)

| Item | Value | Implication |
|------|-------|-------------|
| AGP | **9.2.1** | Already past AGP 9.2.0 → R8 coroutine atomic rewrite **enabled by default** on R8-processed builds |
| Release R8 | `isMinifyEnabled=true`, `isShrinkResources=true`, `proguard-android-optimize.txt` | Aligned with Google’s “full R8” checklist |
| compileSdk | **37** | ART may also optimize similar atomic patterns natively (~15% on Google’s coroutine microbenchmarks) |
| targetSdk | **36** | Memory limits apply **regardless of targetSdk** when running on Android 17 |
| Coroutines | kotlinx 1.11.0 | Benefits from R8 rewrite on Android release only |
| `onTrimMemory` | **Not found** in app sources | Gap vs pillar 4 |
| ProfilingManager / MemoryLimiter exit logging | **Not found** | Gap vs pillar 5 (privacy-sensitive) |

---

## Part A — Android 17 app memory limits

### What changed

Starting Android 17, the system may enforce **per-app memory caps based on device total RAM** (OEM-enabled Memory Limiter). Exceeding the cap → process kill **with no stack trace**.

- Applies to **all apps** on Android 17 devices that enable the limiter (not gated on `targetSdk`).
- Field signal: `ApplicationExitInfo.reason == REASON_OTHER` and description contains **`MemoryLimiter:AnonSwap`**.
- Pre-kill dumps: `ProfilingManager` + `TRIGGER_TYPE_ANOMALY` (heap dump before kill); `TRIGGER_TYPE_OOM` for classic OOM.

### Why

A privileged process (foreground / FGS) that leaks is protected from LMK early on. The system then kills many smaller cached apps to free RAM → destroyed multitasking, cold starts, lost scroll/nav state, battery thrash. Limits contain the “one bad actor.”

### Local simulation

```bash
adb shell am memory-limiter status
adb shell am memory-limiter ignore <uid>|none|all
adb shell am memory-limiter manual <pid> <limit_mb>|max|none
```

### Five pillars (article)

1. **R8 full optimization** — resident code shrink; Monzo: −35% ANR, −30% cold start, −9% size  
2. **Image loading** — downsample, no baked letterbox, `RGB_565` when no alpha, vectors for chrome, recycle/pool  
3. **Leak detection** — Studio LeakCanary task; Context / listener / View leaks  
4. **`onTrimMemory`** — only `TRIM_MEMORY_UI_HIDDEN` + `TRIM_MEMORY_BACKGROUND` still matter post-14/15  
5. **ProfilingManager** — field heap dumps via ANOMALY / OOM triggers  

### EasyWatermark risk map

| Risk | Why |
|------|-----|
| Full-res multi-image export | Source + intermediate + encode buffers; ARGB_8888 ≈ W×H×4 |
| `BitmapCache` without trim | Good for reuse; bad if retained while backgrounded under not-visible caps |
| Filmstrip / gallery | Must stay thumbnail/sampled; never N full-res decodes |
| Silent kill | No stack — must use exit history + optional anomaly dumps |

**Already good:** R8 minify+shrink+optimize; `inSampleSize` / `BitmapCache`; export intermediate recycle discipline; RGB_565 for some thumbnails.

**Gaps:** no `onTrimMemory` cache trim; no MemoryLimiter exit detection; no ProfilingManager (debug-only would fit privacy stance).

---

## Part B — R8 makes coroutines ~2× faster (launch/cancel)

### Problem

Compose uses coroutines heavily (pointer, animations, interactions). Method traces showed ~**80%** of `Modifier.clickable` create/update time spent **launching/cancelling** internal coroutines for `InteractionSource`.

### Root cause

`kotlinx.atomicfu` uses `Atomic*FieldUpdater`. Each CAS pays **reflective safety checks** on ART. Benchmark (Pixel 5 API 33):

| | AtomicReference | atomicfu FieldUpdater |
|--|----------------:|----------------------:|
| compareAndSet | 50.7 ns | 135 ns (~2.7× slower) |

Write-path ops often **2×–4×** slower; reads already cheap.

### Fix (R8 9.2 / AGP ≥ 9.2.0)

Three stages on **statically obvious** updater patterns:

1. **Instrument** — synthetic field offset via `Unsafe.objectFieldOffset`  
2. **Replace** — call sites → `Unsafe.compareAndSwapObject` (etc.) when holder/type proven  
3. **Cleanup** — drop unused updater or unused offset  

Result: Compose `LaunchedEffect` launch+cancel **~2×** in runtime microbenchmarks. ART native path adds ~**15%** on recent devices (API 37 narrative).

### Caveats

- Requires **R8-processed** bytecode (release minify) — debug often won’t show the win  
- **Android-only** (not Desktop JVM without R8, not iOS)  
- Does **not** 2× export/decode/I/O — only Job/atomic lifecycle  
- Dynamic updater patterns left alone (partial opt OK)

### EasyWatermark

**Already on AGP 9.2.1 + release minify** → free win on Android release Compose interaction path. Measure on **release/benchmark**, not debug.

KOL note (@VasiliyZukanov on @AndroidDev thread): impact is most noticeable **because Compose makes heavy use of coroutines** — aligns with product shape (editor effects, filmstrip, sheets).

---

## Part C — Curated Google blog posts (for this app)

| P | Post | Why |
|---|------|-----|
| **P0** | [Memory efficiency for Android 17](https://android-developers.googleblog.com/2026/06/prioritizing-memory-efficiency-steps-for-android-17.html) | Limits + 5 pillars; highest product risk |
| **P0** | [Use R8 to shrink, optimize…](https://android-developers.googleblog.com/2025/11/use-r8-to-shrink-optimize-and-fast.html) | Full R8 checklist; Monzo/Reddit/Disney+ data |
| **P0** | [Configure R8 Keep Rules](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html) | Keep-rule anti-patterns; AGP 9 consumer filtering |
| **P1** | [R8 coroutines 2×](https://android-developers.googleblog.com/2026/07/how-r8-made-kotlin-coroutines-2x-faster.html) | **Already unlocked** at AGP 9.2.1 |
| **P1** | [Optimized resource shrinking](https://android-developers.googleblog.com/2025/09/improve-app-performance-with-optimized-resource-shrinking.html) | Code+resource joint shrink; default with AGP 9 + shrink |
| **P1** | [Deeper Performance Considerations](https://android-developers.googleblog.com/2025/11/deeper-performance-considerations.html) | Baseline/Startup profiles, Compose 1.9/1.10 lazy |
| **P1** | [Room 3.0](https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html) | KMP-first Room; plan migration (seeds + SupportSQLite edge) |
| **P2** | [Compose 5 years](https://android-developers.googleblog.com/2026/07/five-years-of-jetpack-compose.html) | Narrative + adaptive APIs; less immediate |

### X signal (high-signal only)

- [@AndroidDev](https://x.com/AndroidDev) (2026-07-27): R8 coroutines 2× / AGP 9.2.0 deep dive  
- [@AndroidDev](https://x.com/AndroidDev) (2026-06-06): memory foundation tips (R8, bitmaps, LeakCanary, onTrimMemory) linking the June blog  
- [@MishaalRahman](https://x.com/MishaalRahman) (I/O 2026 era): runtime app memory limits to stop bad actors; lock-free MessageQueue + young-gen GC called out alongside  

---

## Recommended action order (repo-shaped)

| When | Action | Priority |
|------|--------|----------|
| Now | Multi-image + large export stress under `am memory-limiter manual` | P0 |
| Now | Wire `onTrimMemory` → clear/half-evict `BitmapCache` / UI preview bitmaps | P0 |
| Now | Run R8 Configuration Analyzer / `r8-analyzer` skill on keep rules | P0 |
| Next | Debug-only: log `ApplicationExitInfo` for `MemoryLimiter:AnonSwap`; optional ANOMALY trigger (no cloud upload without privacy decision) | P1 |
| Next | Confirm optimized resource shrinking inherited under AGP 9.2.1 | P1 |
| Later | Room driver migration path toward Room 3 | P2 |
| Measure | Release/benchmark Compose interaction latency (already has free R8 coroutine win) | P1 |

---

## Doc impact

Research-only; no ADR required unless product adopts:

- Application-level `onTrimMemory` policy for `BitmapCache`  
- Debug ProfilingManager / exit-info logging (privacy review)

---

## Citations

All factual claims above trace to sources #1–#4 and repo file reads (`gradle/libs.versions.toml`, `app/build.gradle.kts`, `Dependencies.kt`, bitmap/export sources).

## Implementation status (2026-08-09)

| WP | Item | Status | Notes |
|----|------|--------|-------|
| A | `BitmapCache.evictAll` / `trimForMemoryLevel` | **Done** | UI_HIDDEN → soft ~25% (`trimToSize(max/4)`); BACKGROUND+ → `evictAll`; **never recycle** |
| B | `MyApp.onTrimMemory` | **Done** | Calls trim; DEBUG logs via `EwmMemoryLimiter` |
| C | Historical `ApplicationExitInfo` | **Done** | DEBUG cold start; tags `MemoryLimiter:AnonSwap` |
| D | ProfilingManager ANOMALY/OOM | **Done** | DEBUG: API≥35 `registerForAllProfilingResults` (local path log only); API≥36 `addProfilingTriggers`; API≥37 **ANOMALY+OOM**; **no upload** |
| E | Export sheet bounded thumbs | **Done** | `MediaStoreThumbnail` + `galleryImageLoader` (not bare Coil URI) |
| F | `scripts/android-memory-limiter-stress.sh` | **Done** | status/install/meminfo/exits/manual-limit/checklist; limiter no-op friendly |
| G | R8 keep audit | **Done** | `docs/superpowers/research/2026-08-08-r8-keep-audit.md`; **zero** keep diffs |
| H | Research measure note | **Done** | See below |
| I | Room 3 ADR only | **Done** | `docs/adr/0024-room3-hold-no-migration.md` **Proposed** — no code migration |
| — | Preview max-edge 720 | **Out of scope** | Approved default |
| — | Export streaming redesign | **Out of scope** | Peak still ~2–3 full-res buffers/image sequential |

### Measure note — R8 coroutines ~2× (do not use debug unit tests)

AGP **9.2.1** enables R8’s atomicfu `Atomic*FieldUpdater` → `Unsafe` rewrite on **R8-processed** builds. That path is **release/benchmark minify**, not `assembleDebug` / Robolectric.

To measure on this app:

1. Build **release** or **benchmark** (`isMinifyEnabled=true`, already wires `coroutines.pro`).
2. Device Simpleperf / Macrobenchmark around high coroutine churn (pointer input, export job fan-in), not JVM unit tests.
3. Compare against a control with R8 optimize disabled only in a throwaway branch — never ship that control.

Memory-limiter dogfood: `scripts/android-memory-limiter-stress.sh checklist` + DEBUG logcat tag `EwmMemoryLimiter`.

