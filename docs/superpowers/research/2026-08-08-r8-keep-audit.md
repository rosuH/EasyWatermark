# R8 Keep-Rules Audit — EasyWatermark

**Date:** 2026-08-09  
**Scope:** `:app` release minify surface  
**Method:** Heuristic (Path C) — AGP **9.2.1** (< 9.3.0 standalone analyzer task); no R8 config analyzer proto run this session  
**Skill:** `.agents/skills/r8-analyzer` (report-shaped, evidence-only)

## Configuration

| Item | Value | Notes |
|------|-------|-------|
| AGP | 9.2.1 | Coroutine atomic rewrite **on by default** for R8-processed builds |
| `isMinifyEnabled` (release) | true | |
| `isShrinkResources` (release) | true | |
| Default rules | `proguard-android-optimize.txt` | Full optimize baseline |
| App rules | `app/proguard-rules.pro`, `app/coroutines.pro` | Also benchmark variant + `benchmark-rules.pro` |
| `android.enableR8.fullMode=false` | **Not set** | Full mode remains default (good) |
| `nonFinalResIds` | AGP 9 default | Comment in `gradle.properties`: required for optimized resource shrinking |

## App keep inventory

### `app/proguard-rules.pro` (1 rule)

```
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}
```

| Finding | Severity | Evidence | Action |
|---------|----------|----------|--------|
| DataStore Preferences protobuf Lite field keep | Low / likely **library-redundant** | Historical DataStore guidance; modern DataStore ships consumer Proguard rules | **No change this round** — zero runtime failure evidence; removing without release dogfood risks silent pref corruption |
| No package-wide `-keep class me.rosuh.**` | Good | File is intentionally thin | Keep thin |
| No `-dontobfuscate` / `-dontoptimize` | Good | | |

### `app/coroutines.pro` (intentional)

Rules force R8 side-effect assumptions for Main dispatcher / FastServiceLoader / DebugKt flags and keep `AndroidDispatcherFactory`.

| Finding | Severity | Evidence | Action |
|---------|----------|----------|--------|
| Coroutines Android dispatcher keep + assumenosideeffects | Info | Aligns with kotlinx + Google R8 coroutine guidance; AGP 9.2.1 already rewrites atomicfu updaters | **Keep as-is** — not a bulk “disable R8” file; enables smaller/faster coroutine dispatch path |

## Subsumed / broad rules

- **None found** in app-owned files (no `-keep class ** { *; }`, no whole-package keeps).
- Library consumer rules (AndroidX, Coil, Koin, Room, CMP) are not duplicated in app `proguard-rules.pro`.

## Coroutines 2× claim (measurement note)

Google’s “R8 made Kotlin Coroutines ~2× faster” rewrite requires **R8-processed** builds (release minify). Measuring on **debug** (typically non-minified) will **not** show the atomicfu→Unsafe win.

**How to measure here:**

1. Use **release** or **benchmark** variant with minify on (`app/build.gradle.kts` release/benchmark proguard files already include `coroutines.pro`).
2. Prefer Macrobenchmark / Simpleperf on device; focus clickable / LaunchedEffect churn, not unit tests on JVM.
3. Do **not** treat Robolectric debug unit tests as evidence for the 2× claim.

## Recommendations (no code change unless new evidence)

1. **Do not** expand keep rules “just in case.”
2. Optional follow-up (owner-gated): build release once, exercise DataStore cold start + export, then trial-remove the GeneratedMessageLite keep if consumer rules already cover it — only with a revert plan.
3. When AGP ≥ 9.3.0 is adopted (Studio floor permitting), re-run Path A `analyzeReleaseR8Config` for quantitative keep impact.

## Conclusion

**Keep-rule diffs this session: zero.** App surface is already minimal; `coroutines.pro` is purposeful. No evidence of broken reflection requiring new keeps. R8 full optimize path remains enabled.
