package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.domain.WatermarkConfigEditor

/**
 * S4d-102: the iOS Swift-facing bridge for the common [WaterMarkRepository] — the **first off-Android
 * consumer of the shared watermark editor state**. Mirrors [IosUserConfigBridge]:
 *
 * Swift never touches the Kotlin `Flow` or `DataStore`. [currentText] is a **one-shot snapshot** of the
 * persisted watermark text (`repo.waterMark.first().text`), and [setText] is a plain `suspend` write
 * routed through the shared [WatermarkConfigEditor] use-case (not a parallel Swift field). The
 * Kotlin/Native importer bridges `suspend` to Swift `async`, so a DataStore write failure surfaces as a
 * Swift error rather than a fatal crash. Only value types cross to Swift — `String` (text), `Float`
 * (degree, S4d-103, clamped by `WatermarkConfigRules.clampDegree`), and the `WatermarkTileMode` enum
 * (S4d-104; the iOS UI uses REPEAT/CLAMP only). All writes route through the shared editor.
 *
 * Single-instance-per-file: DataStore forbids a second active store for the same file, so a real iOS app
 * retains ONE bridge (e.g. in a Swift `ObservableObject`), exactly as [IosUserConfigBridge] is retained.
 */
class IosWatermarkConfigBridge(private val repo: WaterMarkRepository) {

    private val editor = WatermarkConfigEditor(repo)

    /** One-shot snapshot of the current persisted watermark text (no `Flow` exposed to Swift). */
    suspend fun currentText(): String = repo.waterMark.first().text

    /** Persist the watermark [text] through the shared editor use-case. Failures surface to Swift. */
    suspend fun setText(text: String) {
        editor.updateText(text)
    }

    /** S4d-103: one-shot snapshot of the current persisted rotation degree (default 315°). */
    suspend fun currentDegree(): Float = repo.waterMark.first().degree

    /** S4d-103: persist the rotation [degree] through the shared editor use-case (clamped 0..360). */
    suspend fun setDegree(degree: Float) {
        editor.updateDegree(degree)
    }

    /** S4d-104: one-shot snapshot of the current persisted tile mode (default REPEAT). */
    suspend fun currentTileMode(): WatermarkTileMode = repo.waterMark.first().tileMode

    /** S4d-104: persist the [tileMode] through the shared editor use-case. UI uses REPEAT/CLAMP only. */
    suspend fun setTileMode(tileMode: WatermarkTileMode) {
        editor.updateTileMode(tileMode)
    }
}

/** Default watermark text on a fresh iOS store — matches the prior hardcoded Swift constant. */
private const val DEFAULT_WATERMARK_TEXT = "EasyWatermark 水印"

/**
 * Build an [IosWatermarkConfigBridge] over the app's default iOS watermark-config store
 * ([createWaterMarkDataStore], `NSDocumentDirectory`). The three [WaterMarkRepository] edges are
 * supplied with platform-neutral iOS values: a default-text provider, the **pure**
 * [WatermarkTileMode.fromStorageId] read mapper (the Android SDK-gated legacy DECAL-id-3→REPEAT mapper
 * is Android-only — there is no legacy iOS data), and a `println` logger (no dependency). A real iOS app
 * calls this ONCE and retains the result (single-instance-per-file).
 */
fun defaultIosWatermarkConfigBridge(): IosWatermarkConfigBridge =
    IosWatermarkConfigBridge(
        WaterMarkRepository(
            dataStore = createWaterMarkDataStore(),
            defaultTextProvider = { DEFAULT_WATERMARK_TEXT },
            tileModeFromStorageId = { WatermarkTileMode.fromStorageId(it) },
            logError = { message -> println("IosWatermarkConfigBridge: $message") },
        ),
    )
