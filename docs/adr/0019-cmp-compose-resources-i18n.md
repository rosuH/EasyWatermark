# ADR-0019: Compose Multiplatform resources for product UI strings and drawables

**Status:** Accepted (implementation Phases 0–3 landed 2026-07-12; Hosted Weblate retarget completed 2026-11-21)  
**Related:** plan `docs/superpowers/plans/2026-07-12-cmp-compose-resources-i18n-plan.md`; Weblate retarget record `docs/superpowers/research/2026-07-12-s-i18n-1-weblate-retarget.md`; AGENTS.md Strings / i18n section  
**Does not supersede:** ADR-0004 / 0018 renderer policies; Android system-only `app/res` for themes/mipmaps/gallery edges

## Context

Shared CMP product screens (Launch / Editor / About / Recovery / templates / save sheet) live in `:shared` `commonMain`. Historically they avoided JetBrains `composeResources` because of **CMP-9547** (compose multiplatform resources not packaged into the Android APK when using AGP 9 + `com.android.kotlin.multiplatform.library`). Product strings stayed on Android `R.string` + edge bags; off-Android hosts used English hardcodes; logo used expect/actual byte loaders.

Weblate continues to own non-default translations. Hosted Weblate (`easywatermark/app-strings`) was retargeted to `composeResources` (`cmp-resource`) on 2026-11-21 after syncing accumulated `zh-rCN` translations on `master`.

## Decision

1. **Official JetBrains compose multiplatform resources** are the multiplatform product UI resource system for EasyWatermark:
   - Strings: `shared/src/commonMain/composeResources/values(-*)/strings.xml` → `stringResource(Res.string.*)`
   - Drawables: `.../composeResources/drawable/` → `painterResource(Res.drawable.*)` / `SharedProductDrawables` / `FuncType.iconPainter()`
2. **Packaging gate (this stack):** enable  
   `kotlin { android { androidResources { enable = true } } }` on `:shared` so `copyAndroidMainComposeResourcesToAndroidAssets` packs `.cvr` / drawable assets into the consumer APK.  
   Do **not** dependency-substitute `org.jetbrains.compose.components.*` to `androidx.compose.components` (artifact does not exist).
3. **Product UI APIs:** commonMain screens read `Res` directly. Do **not** reintroduce product `*Strings` bags for migrated screens.  
   `FuncType.label()` / `FuncType.iconPainter()` are the shared option chrome vocabulary.
4. **Weblate (`cmp-resource`):** directly tracks `shared/src/commonMain/composeResources/values-*/strings.xml` (monolingual base `values/strings.xml`, format `cmp-resource`, `language_code_style: android`). Default EN dual-write to `app/src/main/res/values/strings.xml` is retired (`app/res` keeps `app_name` for `AndroidManifest.xml`). Never hand-edit non-default locales. Do not use Android backslash escapes (`\'`, `\?`, `\"`) in `composeResources` XML — CMP renders them literally.
5. **Out of scope for composeResources:** watermark Noto fonts, Room seed DBs, Android themes/mipmaps, narrow gallery/ColorPicker Android edges still on `R.drawable` until migrated.

## Consequences

- **Positive:** one product string/icon catalog for Android + Desktop + iOS; system locale picks qualifiers; packaging proven (Phase 0 spike EN + zh-rCN); single Weblate SSOT without dual-write.  
- **Negative / process:** incomplete locales (ru/uk/…) stay incomplete (no invented translations); residual unused keys in `app/src/main/res/values*/strings.xml` can be trimmed later (`app_name` stays for `AndroidManifest.xml`).  
- **Retired rule:** absolute “no compose-resources / CMP-9547 ban” in AGENTS — replaced by packaging rules above; post-retarget EN dual-write retired 2026-11-21.  
- **Not chosen:** moko-resources as default; long-term English bags on Desktop/iOS.

## Evidence (Phase 0)

- Desktop headless + `CmpSpikeResourcesTest` / `ComposeResourcesCatalogTest` / drawable map tests.  
- Android debug APK contains `assets/composeResources/...` strings `.cvr` and `drawable/*`; device log + UI for spike + product labels under zh-CN.

## Follow-ups

| Item | Owner |
|------|--------|
| Weblate component retarget to composeResources | Done (2026-11-21, `cmp-resource`) |
| Drop dual-write / shrink `app/res` product strings | Dual-write retired (2026-11-21); shrinking residual `app/res` keys optional (`app_name` stays) |
| Gallery-only Android drawables into composeResources | Optional |
| In-app language switch (`LocalAppLocale`) | Separate plan if product needs it |
