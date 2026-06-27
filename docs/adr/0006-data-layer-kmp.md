# ADR-0006: Data layer — Room/DataStore on KMP coordinates; prepopulated DB via createFromFile

**Status:** Accepted (2026-06-13) · **Plan ref:** D6

## Context
Room 2.8.4 and DataStore 1.2.1 are Google-published KMP artifacts (coordinates unchanged / `-core` rename). Today `AppModule` prepopulates templates with `createFromAsset(ewm-db-ch.db | ewm-db-eng.db)` selected by locale — an Android-assets-only API; the KMP database builder is path-based.

## Decision
- Room 2.8.4: `@ConstructedBy` + expect constructor object, `BundledSQLiteDriver`, suspend-only DAOs, no `withTransaction` in common code. KSP per target: `ksp` (Android, AGP 8.x), `kspIosArm64`, `kspIosSimulatorArm64`, `kspIosX64`, `kspJvm`. Do NOT adopt Room 3 alpha (`androidx.room3`).
- Prepopulated DBs: bundle both `.db` files as resources; `expect fun prepopulatedDbPath(locale): String` copies to a writable path; switch to `createFromFile`; set `exportSchema = true` and commit the schema (KMP Room validates prepackaged DBs against it).
- DataStore: `datastore-preferences-core` in commonMain (rename from `datastore-preferences`) + `expect fun createDataStore()` (OkioStorage on iOS).
- `Template.creationDate java.util.Date` → `kotlinx.datetime.Instant`; drop `Parcelable` from the entity.

## Consequences
- Template feature survives all platforms; schema export becomes mandatory (Risk R15).
- Locale detection needs a common API decision at implementation time.

## Addendum — realized implementation (S4d-90/91/92, accepted 2026-06-27, commit `8d245d9`)
The Room/templates move landed; the **Android** half deliberately diverges from the original 2026-06-13 plan above, while the cross-platform plan still holds for the deferred desktop/iOS half:

- **Android driver: compatibility mode (no explicit `SQLiteDriver`)** — *not* `BundledSQLiteDriver`. Android keeps the framework `SupportSQLiteOpenHelper` + `createFromAsset("ewm-db-ch.db"/"ewm-db-eng.db")` (locale-selected), so the existing prepackaged DBs open byte-identically and **no** `sqlite-bundled`/`libsqliteJni.so` (or extra `sqlite-framework`) ships. Chosen from a Robolectric prepopulated-DB smoke (`TemplatePrepopulatedDbSmokeTest`, 2/0), not preference. The `BundledSQLiteDriver` line of the original Decision applies to the **desktop/iOS** path only.
- **`exportSchema = false`, `version = 1` kept** — *not* the planned `exportSchema = true` + committed schema. No schema/migration change accompanied the move (pure relocation), so schema export stays unnecessary and Risk R15 is not yet triggered. It becomes relevant only at a future version bump or when desktop/iOS open the seed DBs.
- **`createFromAsset` stays Android-side** in `:shared/androidMain` `buildTemplateDatabase(context)` (Room KMP's prepackaged-DB APIs are Android-only). The planned `expect fun prepopulatedDbPath(locale)` + `createFromFile` + bundled `.db` resources is the **gated S4d-93** for desktop/iOS, only once those platforms expose a templates UI.
- **`@ConstructedBy` + `expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase>`** adopted as planned. `Template` time fields already use **stdlib `kotlin.time.Instant`** (S4d-35) — not `kotlinx.datetime.Instant` — and `Parcelable` was already dropped (S4d-34).
- **`Dispatchers.IO` injected** into `TemplateRepository` as `ioContext: CoroutineContext` (Koin passes `Dispatchers.IO`) because it is not accessible in commonMain on Native.
- KSP per target = `kspAndroid`/`kspDesktop`/`kspIosArm64`/`kspIosSimulatorArm64` (the `kspJvm`/`kspIosX64` names in the original Decision were placeholders; `desktop` is the `jvm("desktop")` target). Room 2.8.4 / `androidx.room` plugin as planned; Room 3 not adopted.

**Net:** Android templates are commonMain + compatibility mode (no new payload); the original all-platform Decision (BundledSQLiteDriver, createFromFile, mandatory schema export) is preserved as the **desktop/iOS** plan for S4d-93.
