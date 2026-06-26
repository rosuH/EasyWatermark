package me.rosuh.easywatermark.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Desktop (JVM) preferences DataStore creation (S4d-78). Builds an okio path under [dir] and
 * delegates to the common, serializer-free [createPreferencesDataStore]. Real public APIs only
 * (`java.io.File` + the common okio-path factory) — no Android types, no `Context`, no migration.
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

private fun defaultDesktopDataDir(): File =
    File(System.getProperty("user.home"), ".easywatermark")
