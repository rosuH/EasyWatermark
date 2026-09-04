package me.rosuh.easywatermark.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.cinterop.ExperimentalForeignApi
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/**
 * iOS preferences DataStore creation. Resolves the app's `NSDocumentDirectory` and delegates
 * to the common, serializer-free [createPreferencesDataStore]. Foundation interop only (Kotlin/Native
 * bundled) — no new dependency, no Android types.
 *
 * Caller owns single-instance-per-file semantics (a real iOS app binds this once).
 */
@OptIn(ExperimentalForeignApi::class)
fun createUserConfigDataStore(
    name: String = UserConfigRepository.SP_NAME,
): DataStore<Preferences> = createPreferencesDataStore {
    iosPreferencesPath(name)
}

/**
 * iOS watermark-config DataStore creation, mirroring [createUserConfigDataStore] but keyed on
 * [WaterMarkRepository.SP_NAME]. The Swift-facing iOS watermark editor (`IosWatermarkConfigBridge`) is
 * the first off-Android consumer of the common [WaterMarkRepository]; it persists the watermark config
 * in the app's `NSDocumentDirectory`. Foundation interop only — no new dependency, no Android types.
 * Caller owns single-instance-per-file semantics (the iOS app retains one bridge).
 */
@OptIn(ExperimentalForeignApi::class)
fun createWaterMarkDataStore(
    name: String = WaterMarkRepository.SP_NAME,
): DataStore<Preferences> = createPreferencesDataStore {
    iosPreferencesPath(name)
}

@OptIn(ExperimentalForeignApi::class)
private fun iosPreferencesPath(name: String) = run {
    val documentDirectory: NSURL? = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    (requireNotNull(documentDirectory).path + "/$name.preferences_pb").toPath()
}
