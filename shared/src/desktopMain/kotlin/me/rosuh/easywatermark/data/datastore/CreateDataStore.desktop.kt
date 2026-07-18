package me.rosuh.easywatermark.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Desktop (JVM) preferences DataStore creation. Builds an okio path under [dir] and
 * Delegates to the common, serializer-free [createPreferencesDataStore]. Real public APIs only * (`java.io.File` + the common okio-path factory) — no Android types, no `Context`, no migration.
 *
 * Caller owns single-instance-per-file semantics (DataStore forbids a second active store for the
 * same file); a desktop app would bind this as a singleton, mirroring `:app`'s Koin `single`.
 */
fun createUserConfigDataStore(
    dir: File = defaultDesktopDataDir(),
    name: String = UserConfigRepository.SP_NAME,
): DataStore<Preferences> = createPreferencesDataStore {
    dir.apply { mkdirs() }.resolve("$name.preferences_pb").toOkioPath()
}

/**
 * Desktop (JVM) **watermark-config** DataStore creation, mirroring [createUserConfigDataStore]
 * But keyed on [WaterMarkRepository.SP_NAME] — the Desktop analogue of the iOS `createWaterMarkDataStore`. * It lets `:desktopApp` construct the common [WaterMarkRepository] over a real on-disk preferences store.
 * Real public APIs only (`java.io.File` + the common okio-path factory) — no Android types, no `Context`,
 * no migration. Caller owns single-instance-per-file semantics (DataStore forbids a second active store
 * for the same file).
 */
fun createWaterMarkDataStore(
    dir: File = defaultDesktopDataDir(),
    name: String = WaterMarkRepository.SP_NAME,
): DataStore<Preferences> = createPreferencesDataStore {
    dir.apply { mkdirs() }.resolve("$name.preferences_pb").toOkioPath()
}

private fun defaultDesktopDataDir(): File =
    File(System.getProperty("user.home"), ".easywatermark")
