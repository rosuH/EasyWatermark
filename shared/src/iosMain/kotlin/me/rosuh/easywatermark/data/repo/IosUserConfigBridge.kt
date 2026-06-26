package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.datastore.createUserConfigDataStore
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.data.model.UserPreferences

/**
 * S4d-81: the iOS Swift-facing bridge for the common [UserConfigRepository].
 *
 * Swift never touches the Kotlin `Flow`: [currentPreferences] is a **one-shot snapshot** (it collects
 * `repo.userPreferences.first()`), and the writes are plain `suspend` functions. The Kotlin/Native
 * Swift importer bridges `suspend` to Swift `async` (with a thrown error surfaced to the Swift `catch`),
 * so a DataStore write failure becomes a Swift error rather than an unexplained Kotlin/Native crash —
 * mirroring the [me.rosuh.easywatermark.render.IosWatermarkRenderBridge] boundary. The read flow's own
 * `IOException` fallback (in [UserConfigRepository]) means [currentPreferences] returns the default
 * [UserPreferences] on a read error instead of throwing.
 *
 * [UserPreferences] / [ImageFormat] are the only types crossing to Swift (a plain value holder + enum) —
 * no `Flow`, no `DataStore`. Construct with an explicit repo (testable; caller owns the store), or use
 * [defaultIosUserConfigBridge] for the app's default `NSDocumentDirectory`-backed store.
 *
 * Single-instance-per-file: DataStore forbids a second active store for the same file, so a real iOS app
 * must retain ONE bridge (e.g. in a Swift `ObservableObject`/environment), exactly as Desktop binds one.
 */
class IosUserConfigBridge(private val repo: UserConfigRepository) {

    /** One-shot snapshot of the current preferences (no `Flow` exposed to Swift). */
    suspend fun currentPreferences(): UserPreferences = repo.userPreferences.first()

    /** Persist the output [format]. Suspends; a write failure surfaces as a Swift `async` error. */
    suspend fun setOutputFormat(format: ImageFormat) {
        repo.updateFormat(format)
    }

    /** Persist the compress [level]. Suspends; a write failure surfaces as a Swift `async` error. */
    suspend fun setCompressLevel(level: Int) {
        repo.updateCompressLevel(level)
    }

    /** Persist the app [versionCode] changelog marker. Suspends; failures surface to Swift. */
    suspend fun saveVersionCode(versionCode: Int) {
        repo.saveVersionCode(versionCode)
    }
}

/**
 * Build an [IosUserConfigBridge] over the app's default iOS preferences store
 * ([createUserConfigDataStore], `NSDocumentDirectory`). A real iOS app calls this ONCE and retains the
 * result (single-instance-per-file).
 */
fun defaultIosUserConfigBridge(): IosUserConfigBridge =
    IosUserConfigBridge(UserConfigRepository(createUserConfigDataStore()))
