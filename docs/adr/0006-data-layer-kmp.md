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
