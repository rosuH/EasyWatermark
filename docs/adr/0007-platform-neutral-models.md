# ADR-0007: Platform-neutral model layer (TileMode, ImageFormat, MediaRef)

**Status:** Accepted (2026-06-13) · **Plan ref:** D7

## Context
Android types leaked into the domain: `WaterMark.tileMode: Shader.TileMode` (persisted as android enum **ordinal** in DataStore), `iconUri`/`ImageInfo.uri: android.net.Uri`, `UserPreferences.outputFormat: Bitmap.CompressFormat`, `ViewInfo: Matrix+ScaleType`. Cross-enum ordinal equality is fragile and blocks commonMain. The status blocks below record which leaks have since been closed.

## Decision
Introduce app-owned `TileMode` and `ImageFormat` enums with explicit ordinal-compatible mappers (+ DataStore migration for the persisted ordinal), a `MediaRef` value class for image identity, kotlinx-datetime for time. `ViewInfo` is deleted by ADR-0004 C2b, not ported. The recent `Bitmap.CompressFormat` standardization in `SaveExportSheet` was a deliberate stepping stone; it swaps to `ImageFormat` in ONE move (sheet + prefs + repo together, plan C3.5). `:cmonet` is replaced by an `isDynamicColorAvailable()` capability (Android actual keeps the OEM allowlist; iOS/Desktop return false; static color schemes in Theme.kt are the fallback).

## Consequences
- Models become movable to commonMain; persistence stays backward-compatible via the mappers.
- Parcelize survives only in androidMain if needed; nav args use `@Serializable` (ADR-0003).

## Implementation status — MediaRef (S4d-50, accepted 2026-06-24)

`MediaRef` landed as `@JvmInline value class MediaRef(val value: String)` in `shared/commonMain` (the
`@JvmInline` annotation requires `import kotlin.jvm.JvmInline` in `commonMain` — omitting it was the
cause of a rejected v1 `data class` fallback misdiagnosis; the annotation is not auto-imported).

**Scope of S4d-50:** only `WaterMark.iconUri: android.net.Uri` → `MediaRef` was migrated. Storage is
**byte-identical** under the existing `KEY_ICON_URI` `stringPreferencesKey` (write `iconUri.value`,
read `MediaRef.parse(it[KEY_ICON_URI] ?: "")`) — **no DataStore migration, no golden rebaseline**.
Android `Uri` survives only at the edge via `utils/ktx/MediaRefExt.kt`
(`MediaRef.toUri()` / `Uri.toMediaRef()`), mirroring the `ImageFormat`/`WatermarkTileMode` mapper seam.
The picker (`IconOption`) returns `Uri` and converts at the edge (`it.toMediaRef()`); the two decode
edges (export + preview) convert back (`iconUri.toUri()`).

## Implementation status — DynamicColorCapability (S4d-43, accepted 2026-06-25)

The `:cmonet`-replacement clause of the Decision is now **partially realized as an indirection** (Option A).
A platform-neutral `interface DynamicColorCapability { isAvailable(): Boolean; setForcedSupport(enabled: Boolean) }`
landed in `shared/commonMain/.../platform`; the Android actual `AndroidDynamicColorCapability` delegates
**verbatim** to the unchanged `:cmonet` `CMonet` (same OEM allowlist, same `sp_water_mark_c_monet` /
`dynamic_color_force` SharedPreferences key — byte-identical behavior), bound as a Koin `single` in
`AppModule`. The **live Compose consumers were migrated off direct `CMonet`**: `ComposeMainActivity`'s
`AppTheme(dynamicColor = …)` gate + the About-route initial toggle state, and `AboutViewModel`'s toggle
write (`setForcedSupport`). Desktop/iOS actuals return `false` (static schemes in `Theme.kt` are the fallback).

**Accepted boundary / what is NOT yet done:** `:cmonet` is **retained** behind the Android actual — this slice
is an indirection, not an absorption. `MyApp.init` (`CMonet.init`/`applyToActivitiesIfAvailable`), the
`ContextExtension` color getters, and the logo/static path still call `:cmonet` directly (deferred). Full
`:cmonet` absorption/removal is an **owner-gated follow-up**; each of those paths needs its own proof.

**Visual gate (accepted):** ran on `emulator-5554` vs production `me.rosuh.easywatermark` 2.10.0. No
Koin/FATAL crash, `CMonet.isDynamicColorAvailable=true`, Material You dynamic path active via the capability,
About toggle initial ON, toggle write persisted `dynamic_color_force=false`, editor-with-image rendered.
**Coordinator-narrowed claim:** *not* full-page pixel parity (whole-screen prod-vs-debug differences are the
View-stack vs Compose-branch difference, not S4d-43 regressions) — the accepted claim is that the indirection
**did not regress the dynamic-color path**. The **force-OFF static-fallback / force-ON write** were not
visually exercisable because the emulator is `Google`-allowlisted (so `isDynamicColorAvailable()` stays true
regardless of the force flag); accepted as an environmental limitation (verbatim `:cmonet` delegation; static
Compose branch unchanged). A non-allowlisted-device run is optional supplementary evidence, not a blocker.

**Remaining `android.net.Uri` surfaces (after S4d-52 + S4d-53):** gallery `Image.uri` +
`Action.SystemPickerImageSelected.uriList` + `SaveExportSheet.imageUris` (kept `Uri` as Android edges),
the picker contracts (`PickImageContract` / `MultiPickContract`),
`BitmapUtils`/`BitmapCache`/`FileUtils`/decode/Coil/save, and the MediaStore/FileProvider flow.
(`ImageInfo.uri` and `WaterMarkRepository.imageInfoMap` are **no longer** `Uri` — S4d-52; the dead
`ImageInfo.shareUri` accessor was **removed** in S4d-53, and the dead `KEY_URI`/`SP_KEY_URI` key
declarations were **removed** in S4d-54 — see the status blocks below. **No model-layer `Uri` hygiene remains.**)

**S4d-51 readiness — ACCEPTED (read-only, 2026-06-24).** *(Historical readiness analysis — its "still `Uri`" / `imageInfoMap<Uri, Int>` descriptions are pre-S4d-52; the migration below superseded them.)* The decision pack for de-Androidizing
`ImageInfo.uri` is done (`done/20260624-091450--s4d51-imageinfo-uri-readiness`). Findings: `ImageInfo.uri`
is **transient** (never persisted — `KEY_URI`/`SP_KEY_URI` is dead; rebuilt each session in
`_imageMapFlow`/`_selectedImage`), so it needs **no DataStore migration**. There are **7 constructor
sites** where a `Uri` enters `ImageInfo` (picker `List<Uri>`, gallery `Image.uri`, share-in `List<Uri>`,
`select(uri)`, `empty()`, `buildPreviewShader`). The system-picker reducer site
`MainViewModel.kt:1011-1012` is **`ImageInfo(Uri)`** — `Action.SystemPickerImageSelected` declares
`val uriList: List<Uri>` (`LaunchScreen.kt:170-172`) and `newList = action.uriList`, so `it: Uri`; there
is **no `ImageInfo(Image)` and no latent compile issue** (an earlier readiness draft wrongly flagged
one; corrected in r2 before acceptance). `imageInfoMap<Uri, Int>` keys on `imageInfo.uri`, so it flips
to `MediaRef` automatically (value/string equality). `ImageInfo.shareUri` (`result?.data as? Uri?`)
appears **unused** in the Compose build (possibly dead) — confirm liveness before migrating.

## Implementation status — ImageInfo.uri (S4d-52, accepted 2026-06-25)

`ImageInfo.uri: android.net.Uri` → `MediaRef` **landed and accepted** (`done/20260625-223300--s4d52-imageinfo-mediaref`,
after one r1 duplicate-import cleanup). Boundary (5 files: `ImageInfo.kt`, `WaterMarkRepository.kt`,
`MainViewModel.kt`, `EditorScreen.kt`, `ComposeMainActivity.kt`):

- `ImageInfo.uri` is `MediaRef`; `ImageInfo.empty()` → `MediaRef.Empty`. `WaterMarkRepository.imageInfoMap`
  now keys by `MediaRef` and `select(ref: MediaRef)`. `MediaRef` value-class string equality keeps
  `imageInfoMap` / `isSameItem` / `select` semantics identical.
- **`Uri ↔ MediaRef` conversion stays at the Android edges:** `Uri → MediaRef` at picker reducer /
  share-in (`generateImageInfoList`) / gallery (`selectGallery`/`onGalleryDismiss`) / `buildPreviewShader` /
  compress-output construction (`it.toMediaRef()`); `MediaRef → Uri` at export+preview decode, compress
  `openInputStream`, and filmstrip/save-sheet Coil (`it.uri.toUri()`), via `MediaRefExt`.
- **Deliberately kept `Uri` (Android edges, NOT migrated):** gallery `Image.uri`,
  `Action.SystemPickerImageSelected.uriList`, `SaveExportSheet.imageUris`,
  `PickImageContract`/`MultiPickContract`, `BitmapUtils.decodeSampledBitmapFromResource` /
  `BitmapCache.BitmapInfo.uri` / `FileUtils`. Keeping `Image.uri`/`uriList` as `Uri` (converting at the one
  `ImageInfo(...)` construction point each) was the narrower boundary than flipping them. (S4d-52 also left
  `ImageInfo.shareUri: Uri?` in place; **S4d-53 then removed it as dead** — see the status block below.)
- **No persistence change** — `ImageInfo` is transient (in-memory `_imageMapFlow`/`_selectedImage`);
  `KEY_URI`/`SP_KEY_URI` remained declared (deletion deferred at S4d-52; **later removed in S4d-54**), no DataStore migration.
- **Verified:** `git diff --check` clean; `:app:compileDebugKotlin`; `WATERMARK_GOLDEN_STRICT=true` unit
  gate **byte-identical (no rebaseline** — `MediaRef(s).toUri() == Uri.parse(s)`); `assembleDebug` +
  `assembleRelease`. **Device gate on `emulator-5554`** (fresh candidate APK) passed **5 flows**:
  picker→editor, editor preview, filmstrip thumbnails, save/export-sheet thumbnails, ACTION_SEND share-in.
  Not a full-page pixel-parity claim vs the production View stack — the claim is "no regression of the
  image-selection/preview/save/share path".

## Implementation status — shareUri removed (S4d-53, 2026-06-25)

`ImageInfo.shareUri: Uri?` (the computed `result?.data as? Uri?` accessor) was **removed as dead**. Liveness
proof: across `app/src` + `shared/src` the only `shareUri` occurrences were the property definition + a
comment — **no `.shareUri` read, no `::shareUri`/`"shareUri"` reflection, no test/androidTest reference**, and
the Compose share-out button is an **unwired empty lambda** (`onShareClick = {}`). Removing the accessor does
**not** drop any export/share data — the real export result stays in `ImageInfo.result` / `jobState`
(set by `MainViewModel.generateImage`). Also dropped the now-unused `import android.net.Uri` from `ImageInfo.kt`.
Verified: `git diff --check` clean; `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green; daemon stopped.
1-file source change; no docs-listed leak remains for `shareUri`.

## Implementation status — KEY_URI/SP_KEY_URI removed (S4d-54, 2026-06-25)

The dead `KEY_URI = stringPreferencesKey(SP_KEY_URI)` and `SP_KEY_URI` key declarations were **removed** from
`WaterMarkRepository.kt` (2 lines). Liveness re-proven: the only source occurrences were those two declarations
(no `it[KEY_URI]` read/write anywhere, no other `SP_KEY_URI`/`sp_water_mark…key_uri` user). **No persisted
behavior change** — `KEY_ICON_URI` and all real watermark prefs are untouched, no DataStore migration. Verified:
`git diff --check` clean; `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green; daemon stopped. **The
platform-neutral model layer is now `Uri`-free** — the C3 model-`Uri` de-Androidization (ADR-0007: `TileMode`,
`ImageFormat`, `MediaRef`) is complete; all remaining `Uri` is at genuine Android edges.

## Implementation status — WaterMark config moved to commonMain (S4d-60, 2026-06-27)

`WaterMark`, `TextTypeface`, `TextPaintStyle`, and the new neutral `WatermarkMode` now live in
`shared/src/commonMain/.../data/model`. This clears the first C4.2 model-neutralization slice after the S4d-59
readiness map.

- `WatermarkMode.Text(0)` / `Image(1)` replaces the deleted `WaterMarkRepository.MarkMode` and preserves the
  exact `KEY_MODE` persisted ints. Missing/unknown values still map to `Text`, matching the old
  `if (value == Image) Image else Text` rule.
- `TextTypeface.serializeKey()` keeps 0-3, and `TextPaintStyle.serializeKey()` keeps 0-1. The old
  `SerializableSealClass<Int>` / `java.io.Serializable` base was deleted.
- Android render types stay outside commonMain: `WaterMark.obtainTileMode()` is now an Android extension in
  `TileModeExt.kt`; `TextPaintStyle.obtainSysStyle()` is an Android extension in `TextStyleExt.kt`.
- Verification: `:shared:compileKotlinDesktop`, `:shared:compileKotlinIosSimulatorArm64`,
  `:shared:compileKotlinIosArm64`, `:app:compileDebugKotlin`, strict `:app:testDebugUnitTest` (48/0, no
  golden rebaseline), and `:app:assembleDebug :app:assembleRelease` all passed with `--max-workers=8`.

**Remaining (optional, not model-layer):** flipping gallery `Image.uri`/`uriList` only if a non-Android (Desktop/iOS)
gallery is ever built.
