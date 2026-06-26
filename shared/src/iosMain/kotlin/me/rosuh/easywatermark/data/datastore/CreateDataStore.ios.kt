package me.rosuh.easywatermark.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS preferences DataStore creation (S4d-78). Resolves the app's `NSDocumentDirectory` and delegates
 * to the common, serializer-free [createPreferencesDataStore]. Foundation interop only (Kotlin/Native
 * bundled) — no new dependency, no Android types.
 *
 * Caller owns single-instance-per-file semantics (a real iOS app binds this once).
 */
@OptIn(ExperimentalForeignApi::class)
fun createUserConfigDataStore(
    name: String = UserConfigRepository.SP_NAME,
): DataStore<Preferences> = createPreferencesDataStore {
    val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    (requireNotNull(documentDirectory).path + "/$name.preferences_pb").toPath()
}
