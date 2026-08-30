# Research: Official “Manage your app's memory” vs EasyWatermark

**Date:** 2026-08-30  
**Source:** [Manage your app's memory](https://developer.android.com/topic/performance/memory) (same article as `/topic/performance/memory/manage-app-memory`)  
**Related:** `2026-08-08-android17-memory-r8-coroutines.md` (WP-A–D already landed `onTrimMemory` + ProfilingManager)

This pass checks whether the official article still has unused strategies for this photo-watermark app.

## Already aligned (do not redo)

| Article section | Repo truth |
|---|---|
| R8 + `proguard-android-optimize.txt` | Release/benchmark minify + shrink. Keep audit: `2026-08-08-r8-keep-audit.md` (zero keep diffs). |
| Avoid bloated libraries / dual image stacks | Coil 3 only for UI thumbs (ADR-0028). Preview/export stay on the pipeline. No analytics/crash SDK. |
| Intentional image loading | `inSampleSize` preview; MediaStore / RGB_565 thumbs; export recycles owned source after compose. Joint working-set caps in `PreviewWorkingSetBudget`. |
| `onTrimMemory` UI_HIDDEN / BACKGROUND | `BitmapCache.trimForMemoryLevel` (WP-A/B). Post-14 other trim constants are ignored. |
| `ProfilingManager` OOM / ANOMALY | DEBUG-only, local files, no upload (`AndroidMemoryDiagnostics`). |
| No persistent `Service` | Manifest has Activity + FileProvider + `profileable` only. |
| No `android:largeHeap` | Not requested. |
| Leak posture | `MyApp.instance` is the `Application` (process-scoped). Privacy forbids LeakCanary / crash SDKs. |

## Gaps this pass closed

The article’s two unused *code* strategies were:

1. **Release UI bitmap caches on trim** — Coil product thumbs hold **30%** of available memory (`PRODUCT_THUMB_MEMORY_CACHE_PERCENT`) and were **not** on the trim path. Combined with `BitmapCache` (heap/8) that is a large cached-process footprint.
2. **`getMemoryInfo()` before memory-intensive work** — `getAvailableMemory()` existed in `BitmapUtils` with **zero callers**. Full-res export is the intensive path.

Also: preview trim ran on *every* `onTrimMemory` level and never distinguished BACKGROUND (keep-focus vs evict-all).

| Change | Mapping |
|---|---|
| `AndroidMemoryPressure` | Single coordinator for trim / low-memory / pre-export |
| Coil `MemoryCache` | UI_HIDDEN → remove until ~25% of `maxSize`; BACKGROUND+ → `clear()` |
| Preview working set | UI_HIDDEN → keep focus Source; BACKGROUND+ → `clearFromOwner()`; below UI_HIDDEN → no-op |
| `Application.onLowMemory` | Last-ditch full evict (complement; official focus remains `onTrimMemory`) |
| Export | `releaseReconstructableIfNeeded` — system `lowMemory` **or** Java heap remaining &lt; 64 MiB; **never skip** the save |

## Deliberately not applied

| Article idea | Why not |
|---|---|
| Hilt / Dagger instead of reflection DI | ADR-0005: Koin + interfaces. Compile-time DI is a separate migration. |
| LeakCanary in the memory profiler | Privacy: no crash / leak SDK, including debug-shipping agents. Use Studio profiler + DEBUG `EwmMemoryLimiter`. |
| `SparseArray` over `HashMap` | No Android-only `HashMap<Int, _>` hot path worth a commonMain split. |
| Lite protobufs | Not used. |
| Object pools / `inBitmap` SoftReference set | `addInBitmapOptions` is dead. Article warns pools can worsen GC; do not activate without a churn profile. |
| Close Room on BACKGROUND | Templates DB is tiny; a write can be in flight. |
| Convert API 29+ `loadThumbnail` to RGB_565 | `hasAlpha()` is often true for camera JPEGs; extra copy + dither risk. Fallback decode already prefers RGB_565. |
| Skip export when `lowMemory` | Product must still save; we only free reconstructable caches. |
| Android Vitals LMK dashboards | Ops, not code. |

## Residual research (not this PR)

- Device stress under `adb shell am memory-limiter manual` (script already in `scripts/android-memory-limiter-stress.sh`).
- Release/benchmark heap of gallery fling after Coil UI_HIDDEN soft-trim (disk cache is off).
- Whether a measured `inBitmap` pool helps export batch encode (article: measure first).
