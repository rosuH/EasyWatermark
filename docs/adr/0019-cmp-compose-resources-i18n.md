# ADR-0019: Compose Multiplatform resources for product UI strings and drawables

**Status:** Accepted (implementation Phases 0–3 landed 2026-07-12; owner authorized Phase 0→3)  
**Related:** plan `docs/superpowers/plans/2026-07-12-cmp-compose-resources-i18n-plan.md`; Weblate deferral note `docs/superpowers/research/2026-07-12-s-i18n-1-weblate-retarget.md`; AGENTS.md Strings / i18n section  
**Does not supersede:** ADR-0004 / 0018 renderer policies; Android system-only `app/res` for themes/mipmaps/gallery edges

## Context

Shared CMP product screens (Launch / Editor / About / Recovery / templates / save sheet) live in `:shared` `commonMain`. Historically they avoided JetBrains `composeResources` because of **CMP-9547** (compose multiplatform resources not packaged into the Android APK when using AGP 9 + `com.android.kotlin.multiplatform.library`). Product strings stayed on Android `R.string` + edge bags; off-Android hosts used English hardcodes; logo used expect/actual byte loaders.

Weblate continues to own non-default translations. Owner deferred **Weblate component retarget** until this branch merges to `master`.

## Decision

1. **Official JetBrains compose multiplatform resources** are the multiplatform product UI resource system for EasyWatermark:
   - Strings: `shared/src/commonMain/composeResources/values(-*)/strings.xml` → `stringResource(Res.string.*)`
   - Drawables: `.../composeResources/drawable/` → `painterResource(Res.drawable.*)` / `SharedProductDrawables` / `FuncType.iconPainter()`
2. **Packaging gate (this stack):** enable  
   `kotlin { android { androidResources { enable = true } } }` on `:shared` so `copyAndroidMainComposeResourcesToAndroidAssets` packs `.cvr` / drawable assets into the consumer APK.  
   Do **not** dependency-substitute `org.jetbrains.compose.components.*` to `androidx.compose.components` (artifact does not exist).
3. **Product UI APIs:** commonMain screens read `Res` directly. Do **not** reintroduce product `*Strings` bags for migrated screens.  
   `FuncType.label()` / `FuncType.iconPainter()` are the shared option chrome vocabulary.
4. **Weblate (until post-`master` retarget):** remains on `app/src/main/res/values-*/strings.xml`. Dual-write default EN keys to both trees; never hand-edit non-default locales. After merge to `master`, retarget Weblate to composeResources CMP format (checklist in research note).
5. **Out of scope for composeResources:** watermark Noto fonts, Room seed DBs, Android themes/mipmaps, narrow gallery/ColorPicker Android edges still on `R.drawable` until migrated.

## Consequences

- **Positive:** one product string/icon catalog for Android + Desktop + iOS; system locale picks qualifiers; packaging proven (Phase 0 spike EN + zh-rCN).  
- **Negative / process:** dual-write until Weblate retarget; incomplete locales (ru/uk/…) stay incomplete (no invented translations).  
- **Retired rule:** absolute “no compose-resources / CMP-9547 ban” in AGENTS — replaced by packaging rules above.  
- **Not chosen:** moko-resources as default; long-term English bags on Desktop/iOS.

## Evidence (Phase 0)

- Desktop headless + `CmpSpikeResourcesTest` / `ComposeResourcesCatalogTest` / drawable map tests.  
- Android debug APK contains `assets/composeResources/...` strings `.cvr` and `drawable/*`; device log + UI for spike + product labels under zh-CN.

## Follow-ups

| Item | Owner |
|------|--------|
| Weblate component retarget to composeResources | After merge to `master` |
| Drop dual-write / shrink `app/res` product strings | After Weblate retarget + Phase 2 residual cleanup |
| Gallery-only Android drawables into composeResources | Optional |
| In-app language switch (`LocalAppLocale`) | Separate plan if product needs it |
