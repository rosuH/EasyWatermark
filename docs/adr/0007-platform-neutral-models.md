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

## Implementation status — WaterMark config moved to commonMain (S4d-60/S4d-61, 2026-06-27)

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
- `WatermarkConfigRules` (S4d-61) now owns the pure legacy normalization rules in commonMain: text-size clamp,
  alpha percent-to-byte conversion, alpha byte clamp, h/v gap clamp, degree clamp, and text/icon mode transitions.
  `WaterMarkRepository` and `MainViewModel.updateAlpha` delegate to those rules, but repositories, DataStore keys,
  Android render adapters, and render algorithms stay Android-side and unchanged.
- Verification: `WatermarkConfigRulesTest` 8/0; `:shared:compileKotlinDesktop`, `:shared:compileKotlinIosSimulatorArm64`,
  `:shared:compileKotlinIosArm64`, `:app:compileDebugKotlin`, strict `:app:testDebugUnitTest` (48/0, no
  golden rebaseline), and `:app:assembleDebug :app:assembleRelease` all passed with `--max-workers=8`.

## Implementation status — ImageInfo moved to commonMain (S4d-71, 2026-06-27)

`ImageInfo` now lives in `shared/src/commonMain/.../data/model` with the same package/FQN; the app copy was
deleted. This was safe after S4d-52/S4d-53 because `ImageInfo.uri` is already `MediaRef`, `shareUri` is gone,
and `Result` / `JobState` are commonMain.

- The only removed coupling was the Android-only range annotation on `offsetX` / `offsetY`. It had no runtime
  check; the normalized 0f..1f invariant is now plain documentation and the retired annotation symbol is
  source-grep-clean.
- Field order, names, defaults, mutability, `isSameItem`, and `empty()` semantics were preserved.
- Verification: shared Desktop/iOS/iOS-sim compile, `:app:compileDebugKotlin`, strict unit/golden 48/0 with no
  rebaseline, and debug/release assemble all passed; the r1 comment-only cleanup reran zero-hit grep,
  `git diff --check`, and app compile.

This completes the image identity / watermark-config platform-neutral model set. It does **not** mean every
app-side data class moved: `UserPreferences` (preference boundary), Room `Template`, and UI `FuncTitleModel`
remain app-side edge models.

**Remaining (optional, not model-layer):** flipping gallery `Image.uri`/`uriList` only if a non-Android (Desktop/iOS)
gallery is ever built.

## Implementation status — config command vocabulary (S4d-72, 2026-06-27)

`FuncType` now lives in `shared/commonMain/.../data/model` as the platform-neutral editor-control vocabulary.
`FuncTitleModel` remains app-side because it carries Android `@StringRes` / `@DrawableRes` metadata, but its
`type` is the shared `FuncType` and the nested app-only type is gone.

`WatermarkConfigChange` is the commonMain typed command seam for editor config changes. It covers text, icon
`MediaRef`, color, alpha percent, degree, text size, typeface, tile mode, and h/v gaps.
`WatermarkConfigChange.from(FuncType, Any)` intentionally keeps the temporary raw Android/UI edge in one place:
it preserves the old fail-fast casts and h/v gap `(Float).roundToInt()` behavior. `MainViewModel.onWaterMarkChanged`
now maps once through that seam and dispatches to the existing update methods.

Verified by zero `FuncTitleModel.FuncType` hits, zero `any as` hits in `MainViewModel`, shared Desktop/iOS/iOS-sim
compile, app compile, strict unit/golden 48/0 with no rebaseline, debug/release assemble, and a new common
desktopTest covering typed construction, gap rounding, and fail-fast wrong types.

## Implementation status — DataStore creation seam (S4d-74, 2026-06-27, commit `59eb6e0`)

First DataStore KMP code in `:shared` — the *store-creation* infrastructure (not a model). `shared/commonMain/.../data/datastore/CreateDataStore.kt` is a driver-free helper
`createDataStore(storage: Storage<Preferences>) = DataStoreFactory.create(storage)` (no Android imports);
`shared/androidMain/.../CreateDataStore.android.kt` is a plain function (NOT an `actual`)
`createPreferencesDataStore(context, name)` = `PreferenceDataStoreFactory.create(produceFile = preferencesDataStoreFile(name), migrations = SharedPreferencesMigration(context, name))`.
`:app` `di/DataStoreModule.kt` keeps the `Context.userDataStore`/`waterMarkDataStore` property names (so `RepositoryModule`/`AppModule` are unchanged) with one store per file in-process. New catalog alias
`datastore = androidx.datastore:datastore` (version `datastorePreference = 1.2.1`); `:shared` `commonMain` depends on both `datastore` + `datastore-preferences`.

**Deliberately deferred (matches the S4d-73 readiness decision):** NO `commonMain expect` DataStore factory (would force empty actuals on all four targets and fail the desktop/iOS compile gates); NO iOS/desktop store creation; Android does **not** route through the common storage helper (byte-identical legacy preferences creation needs `PreferenceDataStoreFactory.create(produceFile, migrations)` — a byte-identical `Storage<Preferences>` would need the internal preferences serializer). Repositories (`UserConfigRepository`, `WaterMarkRepository`) stay Android-side at this point. Stored path/format/migration are byte-equivalent (same `filesDir/datastore/<SP_NAME>.preferences_pb`); no DataStore migration, no golden rebaseline. The common prefs consumer landed in S4d-77 below; the iOS/desktop store creation then landed in S4d-78 below — as plain per-platform functions, NOT an `expect/actual` promotion (that was deliberately not taken). Room KMP and the Koin common/platform split remain later milestones.

## Implementation status — UserPreferences + UserConfigRepository to commonMain (S4d-76 + S4d-77, 2026-06-27, commits `94aaf90` + `e8e861e`)

**S4d-76** moved `UserPreferences` to `shared/commonMain/.../data/model` (same FQN `me.rosuh.easywatermark.data.model.UserPreferences`), dropped the Android-only `@Keep`, and inlined the default `UserPreferences(ImageFormat.JPEG, 80)` so the model no longer depends on `UserConfigRepository`. The orphaned `UserConfigRepository.DEFAULT_OUTPUT_FORMAT` was removed; `DEFAULT_COMPRESS_LEVEL = 80` stays for the read clamp.

**S4d-77** moved `UserConfigRepository` to `shared/commonMain/.../data/repo` (same FQN) — the **first real common DataStore Preferences consumer**, which is what made the S4d-74 store-creation `expect/actual` promotion non-speculative. Three Android edges resolved with **no dependency change**:
- `okio.IOException` replaces `java.io.IOException` (resolves in commonMain via the transitive `datastore-core-okio`; `okio.IOException` is a JVM `typealias` to `java.io.IOException`, so Android behavior is identical). Proven by the compile gate on desktop + iosSimulatorArm64 + iosArm64 + app.
- `saveVersionCode(versionCode: Int)` keeps `BuildConfig.VERSION_CODE` at the Android caller edge (`MainViewModel.saveUpgradeInfo()`); `BuildConfig` is gone from the repository.
- `KEY_CHANGE_LOG` inlines the byte-identical literal `"sp_water_mark_config_key_change_log"`, so `WaterMarkRepository` (Android-side) is not a commonMain dependency.

`RepositoryModule`/`DataStoreModule` resolve the same FQN unchanged; Android store creation still goes through the S4d-74 `createPreferencesDataStore` helper. Persisted bytes/keys unchanged (no migration); strict goldens 48/0 with no rebaseline; R8 release retained both classes. The iOS/desktop store creation for this repo then landed in S4d-78 (below); `WaterMarkRepository`, Room, and templates remain Android-side.

## Implementation status — Desktop + iOS DataStore store creation (S4d-78, 2026-06-27, commit `258ace1`)

Desktop and iOS now have real preferences `DataStore<Preferences>` creation, using a public, serializer-free API and **no dependency change**:

- commonMain `CreateDataStore.kt` gained `createPreferencesDataStore(producePath: () -> okio.Path) = PreferenceDataStoreFactory.createWithPath(produceFile = producePath)` — the shared okio-backed factory. (`createWithPath` is public in `datastore-preferences-core` 1.2.1, verified by `javap`; okio arrives via the S4d-74 `datastore`/`datastore-core-okio` deps.)
- desktopMain `CreateDataStore.desktop.kt`: `createUserConfigDataStore(dir = ~/.easywatermark, name = UserConfigRepository.SP_NAME)` builds an okio path and delegates to the common factory (public JVM APIs only).
- iosMain `CreateDataStore.ios.kt`: `createUserConfigDataStore(name = UserConfigRepository.SP_NAME)` resolves `NSDocumentDirectory` (Foundation interop, `@OptIn(ExperimentalForeignApi)` for `NSURL.path`) → okio path → common factory.

**No `expect`/`actual createDataStore`** — this stays plain per-platform functions on purpose. The platform creators have genuinely different signatures (Android: `Context` + `SharedPreferencesMigration`; desktop: a `File` dir; iOS: derives `NSDocumentDirectory`), so a single `expect` would be a forced/contorted fit. **Android store creation is unchanged and byte-faithful** (`androidMain` untouched; strict goldens 48/0).

Proof: all-target compile (`:shared` desktop + iosSimulatorArm64 + iosArm64 + `:app`) + a **Desktop** `UserConfigRepository` roundtrip test (`:shared:desktopTest`, 1/0: empty-store defaults `(JPEG, 80)` → write `(PNG, 60)` → read back → `saveVersionCode(123)`). **iOS store creation is compile/link-proven only** — no iOS runtime roundtrip was run. Desktop app-entry wiring landed in S4d-80 (below); the iOS Swift-facing prefs bridge + iOS runtime roundtrip landed in S4d-81 (below). Room KMP, `WaterMarkRepository`, templates, and the Koin split remain later milestones.

## Implementation status — iOS UserConfig prefs bridge (S4d-81, 2026-06-27, commit `6408a27`)

`shared/src/iosMain/.../data/repo/IosUserConfigBridge.kt` is a thin Swift-facing wrapper over the common `UserConfigRepository`, so Swift can consume prefs without touching a Kotlin `Flow`:
- `suspend currentPreferences(): UserPreferences` — a one-shot snapshot via `repo.userPreferences.first()`.
- `suspend setOutputFormat(ImageFormat)` / `setCompressLevel(Int)` / `saveVersionCode(Int)` — write through the repo; `suspend` bridges to Swift `async`, so a write failure surfaces as a Swift error (not a raw Kotlin/Native crash). The read flow's own `IOException` fallback means the snapshot returns defaults on a read error.
- `defaultIosUserConfigBridge()` — builds over the iOS `createUserConfigDataStore()` (`NSDocumentDirectory`) store.

**No `Flow`/`DataStore` in the public signatures** (only `UserPreferences`/`ImageFormat`/`Int`; `Flow`/`DataStore` are implementation/KDoc only). **iOS runtime-proven:** `shared/src/iosTest/.../IosUserConfigBridgeTest.kt` RAN on `iosSimulatorArm64Test` — empty store defaults `(JPEG, 80)` → set `(PNG, 60)` → read back `(PNG, 60)` → `saveVersionCode(123)` ok (iOS suite 53/0). Scope at S4d-81 was Kotlin-only (no prefs UI, no iOS UI test, no 1:1 parity); the Swift app retention then landed in S4d-82 (below). The optional `:shared` `api`-exposure cleanup stays separate.

## Implementation status — iOS Swift bridge retention (S4d-82, 2026-06-27, commit `98e13d9`)

`iosApp/iosApp/WatermarkWorkflow.swift` retains exactly one `IosUserConfigBridge` via `IosUserConfigBridgeKt.defaultIosUserConfigBridge()` (one per process — `WatermarkWorkflow` is a `@StateObject`), and `loadUserConfigWitness()` calls `try await userConfigBridge.currentPreferences()` **once on launch** (read-only — writes no prefs), storing the `(outputFormat/compressLevel)` snapshot or an error string in a `@Published private(set) var userConfigWitness` that is **non-visible** (not referenced from any View body). `iosApp/iosApp/ContentView.swift` triggers it with `.task { await workflow.loadUserConfigWitness() }`.

This is a **link/async-interop witness only — no prefs/settings UI, no 1:1 parity.** The Swift `currentPreferences(completionHandler:)` import bridges to `async throws -> UserPreferences` (no `Flow`/`DataStore` crosses to Swift). **Build-proven** on the generic iOS Simulator SDK (`xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build` → `** BUILD SUCCEEDED **`). Two existing Swift files only (no new file, no `project.pbxproj`); the S4d-81 Kotlin bridge is unchanged. Next: real Desktop/iOS editor UIs consuming prefs (C4/C5); `WaterMarkRepository`/Room/templates/Koin readiness; the optional `:shared` `api`-exposure cleanup. Future prefs-UI work should replace the witness with real state usage.

## Implementation status — Desktop app-entry UserConfigRepository wiring (S4d-80, 2026-06-27, commit `3daa7c4`)

`desktopApp` `Main.kt` now constructs `UserConfigRepository(createUserConfigDataStore(dir = File("build/s4d80-desktop-userconfig")))` and proves read → `updateFormat(PNG)` + `updateCompressLevel(60)` → read via `runBlocking { userPreferences.first() }`, verified by `:desktopApp:run` (initial `(JPEG, 80)` → `(PNG, 60)`; a second run showed initial `(PNG, 60)`, confirming on-disk persistence across runs). This is the **first app-entry (non-test) construction** of the common prefs repo — an **app-level smoke/witness**, NOT the real Compose Desktop editor consuming prefs (C4) and NOT 1:1 UI/UX parity.

`desktopApp/build.gradle.kts` declares the existing catalog aliases `libs.kotlin.coroutine.core` + `libs.datastore.preference` (**no new dependency version**) because `:desktopApp` directly consumes `:shared`'s public-API types (`Flow`, `DataStore<Preferences>`), which `:shared` declares as `implementation` (so they do not transit to consumers — `:app` declares them likewise). A future optional cleanup is to promote those to `api` in `:shared` so consumers need not re-declare — a separate `:shared`-scoped decision, not this slice. Desktop-only: Android, `:shared`, iOS, renderer, Room, Koin, and goldens were untouched.
