# Diagnosis: ~50-image filmstrip scroll + image-switch latency

**Date:** 2026-08-12  
**Primary platform:** **iOS** (iosSimulatorArm64 N=50 host session + physical iPhone 16 Pro for product context)  
**Branch / HEAD:** `feat/migrate_to_compose` @ `73222df1` (+ uncommitted Coil ADR-0028 / hard-cut / neighbor WM / `IosDecodePurposeProbe`)  
**Goal kind:** analysis (diagnostic probes + tests only; **no product fix**)  
**Evidence sink:** goal scratch + committed tests listed below  

---

## 1. Method

| Layer | What was used |
|-------|----------------|
| **Primary runtime N=50 (current HEAD)** | `IosFiftyImageSessionLatencyTest` on `iosSimulatorArm64Test`: real `deliverPickedPhotosBatch` (50 PNG → `ewm_src_*`) → join `prefetchFilmstripThumbs` → 50× `switchImageAndAwaitForTests` (same order as product `onImageSelected`) → 50× Coil `ProductThumbFetcher` |
| **Stage timers (shipped)** | `IosPreviewBench` / `ClampDragBench` lines emitted by `IosPreviewRaster` + `renderPreviewForCurrentSelection` during that session |
| **Decode purpose counters (shipped)** | `IosDecodePurposeProbe` on FilmstripRepo / ProductThumbCoil / WatermarkedPreview / SourcePlaceholder call sites |
| Behavioral cache | `IosPreviewFiftyImageBudgetTest` — production WM/filmstrip budgets |
| Structural topology | `FiftyImageFilmstripSwitchDiagnosisTest` |
| Historical device Instruments | `jank-repro-20260808-170839` — **context only**, not primary for current Coil dual-path symptoms (pre-Coil / full-strip-on-settle era) |

**Android / Desktop:** not the user’s 50-image progressive repro. Shared hard-cut applies everywhere; dual filmstrip path + `IosPreviewImageRepository` thrash are **iOS-host**.

---

## 2. Repro recipe (fixed ~50 images)

### 2.1 Automated (primary evidence — current HEAD)

```bash
./gradlew :shared:iosSimulatorArm64Test \
  --tests me.rosuh.easywatermark.ui.IosFiftyImageSessionLatencyTest
```

Produces `FIFTY_IMAGE_SESSION …` + per-paint `IosPreviewBench` lines in test system-out.

### 2.2 Manual product (device)

1. Cold open → PHPicker multi-select **~50** photos.  
2. Fling filmstrip; settle on distant cells; note usable watermarked preview time.  
3. Filter Console: `IosPreviewBench`, `FIFTY_IMAGE_SESSION`, `switch_image`.

---

## 3. Quantified metrics (N=50, **current HEAD**, primary)

Source: `TEST-…IosFiftyImageSessionLatencyTest.xml` system-out (2026-08-12).

```
FIFTY_IMAGE_SESSION n=50
  import_prefetch_ms=83
  switch_sum_ms=203  switch_max_ms=5
  switch_miss_n=49  switch_miss_med_ms=4  switch_miss_p90_ms=5  switch_hit_n=1
  coil_thumb_sum_ms=51  coil_thumb_med_ms=1
  decode_filmstrip_repo=50
  decode_product_thumb_coil=50
  decode_wm_preview=50
  decode_placeholder=1
  wm_cache_entries=10
```

(Representative re-run; wall times vary slightly with sim load. Mechanism counters are stable across runs.)

| Symptom | Metric | Value | Interpretation |
|---------|--------|------:|----------------|
| **(a) Filmstrip load** | Import + full-strip **repo** prefetch | **~83–133 ms** for 50 paths | Host still pays ImageIO×50 into Filmstrip purpose |
| **(a) Filmstrip load** | Coil ProductThumb×50 (UI path) | **sum ~51 ms**, med **1 ms** (solid PNG sim) | **Second** full pass of same 50 paths; does **not** reuse repo cache |
| **(a) Dual decode** | `decode_filmstrip_repo` + `decode_product_thumb_coil` | **50 + 50** (stable) | Runtime proof of dual path (not structural only) |
| **(b) Image switch** | Miss rate over 50 sequential switches | **49/50 miss**, **1 hit** (stable) | Working set ≪ 50 |
| **(b) Image switch** | WM cache after scrubbing all 50 | **10 entries** (stable) | Production byte/entry caps |
| **(b) Image switch** | First cold WM (`IosPreviewBench wm_preview`) | **~26–61 ms** (compose-dominated) | Full raster on miss; scales with content |
| **(b) Image switch** | Later solid-PNG misses | **~3–5 ms** med | Lower bound; real HEIC on device is much higher (historical hangs, secondary) |
| **(b) Image switch** | Sum of 50 switch awaits | **~189–203 ms** | Sequential scrub cost on sim solid PNGs |

**Note on magnitude:** Session used synthetic solid PNGs (800×600). Mechanism proof is **49/50 miss + dual 50+50 ImageIO + cache=10**. Device camera HEIC + Main Compose/Metal was historically hundreds of ms (secondary §3.1) — do **not** read sim med 4 ms as “switch is fine on device.”

### 3.1 Historical Main-thread hangs (Instruments 2026-08-08) — **secondary context only**

Not used as primary quantification for **current** Coil dual-path / neighbor-thrash (different code era; recipe was add-more ~8–12, not N=50).

| Metric | Value |
|--------|------:|
| Hang events | 44 |
| Mean / max | 461 / 1098 ms |

Stacks named `bindProgressiveFocus` / `renderWatermarked` / ImageIO / Compose+Metal — still relevant **symbols** for cold WM miss, but **not** proof of today’s dual Coil path.

### 3.2 Watermarked cache capacity at N=50 (shipped budgets)

Production (`IosPreviewImageRepository`):

- `DEFAULT_WATERMARKED_ENTRIES_MAX = **12**`
- `DEFAULT_WATERMARKED_BYTES_MAX = **16 MiB**`
- Joint non-filmstrip = **40 MiB** (also holds placeholders/export thumbs)

| Preview edge | Bytes / ARGB bitmap | Effective WM slots (min entry, byte) | focus+±2 (5 frames) overflows 16 MiB? |
|-------------:|--------------------:|--------------------------------------:|:--------------------------------------|
| 720 | 2.07 MiB | **8** | no (~10.3 MiB) |
| 1080 | 4.45 MiB | **3** | **yes** |
| 1440 | 7.91 MiB | **2** | **yes** |
| 1920 | 14.1 MiB | **1** | **yes** |

**Proof:** `IosPreviewFiftyImageBudgetTest` (passed on `iosSimulatorArm64Test`):

- 50×720 WM puts → survivors ≤ entry+byte caps; path `ewm_src_0` / mid-session paths **evicted**.  
- focus+±2 at 1080 → **cannot keep all 5**; at least one neighbor missing → next switch cold-misses.

Implication for 50-image scrubbing: **≥ 50−8 = 42 cold WM rasters** if every image is visited once at 720; at 1080 **≥ 47 cold**. Neighbor prefetch **cannot** keep a useful working set at 1080+.

### 3.3 Cold WM paint cost (pipeline proxy)

`SwitchPreviewRasterCostTest` via shipped `DesktopPreviewRaster` → `CommonWatermarkPipeline` (same compose as iOS):

```
SWITCH_PREVIEW_COST edge720_ms=33,31,35,32,33 med=33
                    edge1080_ms=36,35,36,36,34 med=36
```

- Desktop PNG lower bound **~33–36 ms** per cold paint (already **> 2 frames**).  
- iOS device HEIC + ImageIO + Main publication historically sits in the **hundreds of ms** hang band (Instruments), so real switch miss is **pipeline + main publish + Session**, not just JVM compose.

### 3.4 Switch animation

`previewCrossfadeDurationMs` is **always 0** (hard-cut). Animation is **not** the residual latency.  
Confirmed: `MotionPolicy` + `FiftyImageFilmstripSwitchDiagnosisTest.previewCrossfade_isHardCut_zeroMs`.

---

## 4. Ranked root causes (current HEAD)

### R1 — Watermarked preview cache too small for 50 images + thrash from neighbor prefetch  
**Severity:** P0 for **image switch**  

| | |
|--|--|
| **Runtime evidence** | N=50 session: **49/50 switch misses**, **wm_cache_entries=10** after full scrub; `decode_wm_preview=50`. Budget test: sequential 50×720 WM puts evict early paths; focus+±2 at 1080 cannot keep 5 frames under 16 MiB. |
| **Code correlation** | `IosPreviewImageRepository` `DEFAULT_WATERMARKED_ENTRIES_MAX=12`, `DEFAULT_WATERMARKED_BYTES_MAX=16MiB`; `prefetchNeighborWatermarkedPreviews` ±2. |
| **Mechanism** | LRU + entry/byte caps. Neighbor prefetch of full committed-edge frames self-evicts at ≥1080. |
| **User symptom** | Switch feels slow unless image was painted in the last few focuses. |

### R2 — Cold switch path always full-raster on miss (no cheap draft-first paint)  
**Severity:** P0 for **image switch**  

| | |
|--|--|
| **Evidence** | `onImageSelected` ~1057–1131; `renderPreviewForCurrentSelection` ~2025–2125; `IosPreviewRaster.renderWatermarked` (decodePathThumbnail + CommonWatermarkPipeline); hang stacks name these symbols |
| **Mechanism** | Order: optimistic WM peek → `SelectCurrent` → placeholder peek only → **await full watermarked**. Miss runs ImageIO thumbnail at committed bucket + text/icon compose. No intermediate “draft 320/720 then upgrade” for switch (draft reserved for CLAMP drag). Hard-cut removes fade but **not** raster work. |
| **User symptom** | Canvas stays on previous image or unwatermarked placeholder until raster finishes (often hundreds of ms on device). |

### R3 — Dual filmstrip path: Coil UI vs dead repository prefetch  
**Severity:** P0 for **filmstrip load / import CPU**, P1 for scroll blanking  

| | |
|--|--|
| **Runtime evidence** | `IosFiftyImageSessionLatencyTest`: **`decode_filmstrip_repo=50` and `decode_product_thumb_coil=50`** for the **same** 50 staged paths. Import phase records FilmstripRepo via `prefetchFilmstripThumbs` → `decodeFilmstripThumb` → `IosDecodePurposeProbe.FilmstripRepo`. Coil phase runs shipped `ProductThumbFetcher` → `IosDecodePurposeProbe.ProductThumbCoil`. Total dual ImageIO ≈ **100** for 50 files. Wall: import+repo prefetch **133 ms** + Coil sum **51 ms** (solid PNG). |
| **Code correlation** | `IosProductRootHost.decodeFilmstripThumb` records FilmstripRepo; `ProductThumbFetcher.ios` records ProductThumbCoil; both call `IosImageIODecoder.decodeThumbnail`. UI thumbnail lambda is Coil-only (`ProductAsyncImage`); 0 `produceState` calls. |
| **Mechanism** | ADR-0028 moved visible cells to Coil. Host import still full-strip warms **IosPreviewPurpose.Filmstrip**, which **no UI reads**. Two memory owners (Coil vs repository LRU). |
| **Library principle** | Coil memory hits need key + `isSampled=false` + INEXACT; host LRU cannot satisfy Coil’s `MemoryCache` keys. |

### R4 — Filmstrip settle → focus pipeline still heavy on miss  
**Severity:** P1 (improved vs 2026-08-08 P0, still costly)  

| | |
|--|--|
| **Evidence** | `EditorFilmstripScaffold` settle `snapshotFlow` → `onItemSelected` (~345–388); progressive `requestFocusReady` → **double** `scheduleFocusPreview` (optimistic + post-Select) ~812–826; `bindProgressiveFocus(UserScroll)` still calls `renderPreviewForCurrentSelection` on miss |
| **Mechanism** | Snap fling + dual haptics + Session focus publish + cancelable bind. Early-exit if WM already showing for path; else same as R2. Prior full-strip filmstrip prefetch on settle **removed** (confirmed comments + UserScroll path). |
| **User symptom** | Fling settle hitches when landing on uncached WM; scroll motion itself may be OK until settle. |

### R5 — Coil thumb decode cost + LazyRow default prefetch only  
**Severity:** P1 for **filmstrip scroll loading**  

| | |
|--|--|
| **Runtime evidence** | N=50 Coil phase: **50** `ProductThumbCoil` decodes, sum **51 ms** solid PNG (sim). On device HEIC this scales up; combined with R3 the process already paid **50** FilmstripRepo decodes that did not prevent these 50 Coil fetches. |
| **Code correlation** | `ProductThumbFetcher.ios`: ImageIO + `readPixels` + BGRA pack + Skia; `EditorPhotoStrip` has no `LazyLayoutCacheWindow` / `beyondBoundsItemCount`. |
| **Mechanism** | First visit of far cells is cold Coil fetch; default LazyRow beyond-bounds is small. Repo cache does not help Coil. |
| **User symptom** | “滑动加载也慢” — blank/slow cells during long flings across 50 thumbs. |

### R6 — Session / composition blast on select (secondary)  
**Severity:** P2  

| | |
|--|--|
| **Evidence** | Prior compose compiler reports: `ImageInfo` unstable; list identity rebuild on select; `EditorScreen` takes lists |
| **Mechanism** | `SelectCurrent` → new launch UI state → Editor recompose. Strong Skipping helps only when instances stable. Not the multi-hundred-ms cost center vs raster, but adds Main work during hangs. |
| **User symptom** | Extra frame cost around selection; compounds R2/R4. |

---

## 5. Disproved / demoted hypotheses

| Hypothesis | Verdict | Why |
|------------|---------|-----|
| Crossfade animation causes switch lag | **Disproved (current)** | `previewCrossfadeDurationMs` always 0 |
| Full-strip filmstrip prefetch on **every settle** | **Fixed since 2026-08-08; still on import batch** | UserScroll path documents “never full-strip”; import `deliverPickedPhotosBatch` still calls `prefetchFilmstripThumbs(all)` — but into **wrong** cache (R3) |
| Filmstrip repository 8 MiB/48 entry is too small for 50×128 thumbs | **Mostly disproved for 128px** | 50×128 ≈ 3.1 MiB; entry cap 48 drops 2 oldest — fine if UI used repo; UI uses Coil instead |
| produceState epoch restart still blanks strip | **Disproved for UI** | 0 `produceState` calls; Coil path. Epoch still bumped on ownership replace/trim (irrelevant to Coil keys) |
| isSampled=true Coil blank on recycle | **Fixed in working tree** | `isSampled=false` + memory/placeholder keys; guarded by `ProductThumbMemoryCacheTest` |
| Desktop JVM compose alone explains iOS hang mean 461 ms | **Disproved as sole cause** | Desktop cold ~33 ms; hang capture includes Main Compose/Metal + ImageIO HEIC + bind pipeline |

---

## 6. Call-site map (reader can re-find)

| Cause | File | Anchor |
|-------|------|--------|
| WM budget caps | `shared/.../IosPreviewImageRepository.kt` | `DEFAULT_WATERMARKED_*`, `enforceBudgetsLocked` |
| Switch handler | `IosProductRootHost.kt` | `onImageSelected` ~1057 |
| WM raster | `IosPreviewRaster.kt` | `renderWatermarked` |
| Neighbor prefetch ±2 | `IosProductRootHost.kt` | `prefetchNeighborWatermarkedPreviews` |
| Focus bind | `IosProductRootHost.kt` | `bindProgressiveFocus` |
| Dead filmstrip prefetch | `IosProductRootHost.kt` | `prefetchFilmstripThumbs`, `ensureFocusFilmstripThumb` |
| Coil UI thumbs | `IosProductRootHost.kt` thumbnail lambda; `ProductAsyncImage.kt`; `ProductThumbFetcher.ios.kt` |
| Settle → select | `EditorPhotoStrip.kt` | `EditorFilmstripScaffold` settle `LaunchedEffect` |
| Progressive focus | `IosProgressiveImportController.kt` | `requestFocusReady` / `scheduleFocusPreview` |
| Hard-cut | `MotionPolicy.kt` | `previewCrossfadeDurationMs` |

---

## 7. Platform separation

| Platform | Filmstrip | Switch preview | Notes |
|----------|-----------|----------------|-------|
| **iOS** | Coil ProductThumb + progressive strip; host dual cache | `IosPreviewImageRepository` WM + `IosPreviewRaster` | **Primary** — user 50-image path |
| Android | Coil ProductThumb (shared); no progressive Pending slots | Android common raster / canvas | Shared hard-cut; no iOS repo dual path |
| Desktop | Same shared strip when multi-image | DesktopPreviewRaster | Microbench only; not user repro |

---

## 8. Recommended fix order (not implemented)

1. **Unify filmstrip cache ownership** — either Coil-only (delete / no-op `prefetchFilmstripThumbs` + `ensureFocusFilmstripThumb` for UI) **or** bridge host decode into Coil memory; stop double ImageIO.  
2. **Resize watermarked working set for multi-image** — raise entry/byte caps **or** store smaller switch draft (e.g. 480–720) for non-focus + full commit for focus; **or** LRU keyed by path with aggressive downsample for neighbors. Stop neighbor prefetch from thrashing focus at 1080+.  
3. **Switch first paint** — on miss, paint source placeholder **or** draft-edge WM immediately; upgrade committed in background (cancel via `previewGen`).  
4. **Coil scroll** — optional modest `beyondBoundsItemCount` / cache window; keep disk off; avoid full-strip warm.  
5. **Settle** — single `scheduleFocusPreview` after Session (drop double optimistic+post if redundant); keep cancelable gen.  
6. **Optional** — stabilize `ImageInfoUi` list identity for strip skip (P2).

---

## 9. Committed proof artifacts

| Artifact | Role |
|----------|------|
| `IosFiftyImageSessionLatencyTest` | **Primary N=50 wall-clock** + dual decode counters + switch miss rate |
| `IosDecodePurposeProbe` (iosMain) | Runtime purpose tags on shipped decode sites |
| `switchImageAndAwaitForTests` / `awaitLastFilmstripPrefetchForTests` | Host seams mirroring product switch/import prefetch |
| `IosPreviewFiftyImageBudgetTest` | Budget eviction / neighbor thrash |
| `FiftyImageFilmstripSwitchDiagnosisTest` | Structural topology |
| `SwitchPreviewRasterCostTest` | Desktop pipeline lower bound |
| Historical `jank-repro-20260808-170839` | Secondary device hang context only |
| This file | Ranked diagnosis |

**Test commands (green this session):**

```bash
./gradlew :shared:iosSimulatorArm64Test \
  --tests me.rosuh.easywatermark.ui.IosFiftyImageSessionLatencyTest \
  --tests me.rosuh.easywatermark.render.IosPreviewFiftyImageBudgetTest
./gradlew :shared:desktopTest \
  --tests me.rosuh.easywatermark.ui.FiftyImageFilmstripSwitchDiagnosisTest \
  --tests me.rosuh.easywatermark.render.SwitchPreviewRasterCostTest
```

---

## 10. Bottom line

With **exactly 50 images** on the **current HEAD** iOS host path:

1. **Switch:** **49/50** cold misses; WM cache holds **10** entries after full scrub; first cold WM paint **61–68 ms** (solid PNG) — real HEIC/device higher. Not crossfade (0 ms).  
2. **Filmstrip:** import pays **50** FilmstripRepo ImageIO, then Coil pays **another 50** ProductThumbCoil ImageIO for the same paths — dual path is **measured**, not inferred.  
3. Historical device hang means remain useful as “Main + raster can cost hundreds of ms under real photos,” but **primary N=50 quantification is the session test above**.
