package me.rosuh.easywatermark.data.repo

import kotlinx.coroutines.flow.first
import me.rosuh.easywatermark.data.datastore.createWaterMarkDataStore
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
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
 * (degree, S4d-103, clamped by `WatermarkConfigRules.clampDegree`), the `WatermarkTileMode` enum
 * (S4d-104; the iOS UI uses REPEAT/CLAMP only), the alpha byte/percent (S4d-105; read as the stored
 * 0..255 byte, written as a 0..100 percent via `WatermarkConfigRules.alphaPercentToByte`), the ARGB
 * text color `Int` (S4d-107), the text size `Float` (S4d-109), the horizontal/vertical gap percents
 * `Int` (S4d-110), the `TextTypeface` enum (S4d-112), and the `TextPaintStyle` enum (S4d-113). All
 * writes route through the shared editor.
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

    /** S4d-105: one-shot snapshot of the persisted alpha **byte** (0..255; default 255 = opaque). */
    suspend fun currentAlphaByte(): Int = repo.waterMark.first().alpha

    /**
     * S4d-105: persist the watermark opacity as a [percent] (0..100) through the shared editor use-case,
     * which converts it to the stored byte via `WatermarkConfigRules.alphaPercentToByte` (truncating).
     */
    suspend fun setAlphaPercent(percent: Float) {
        editor.updateAlpha(percent)
    }

    /** S4d-107: one-shot snapshot of the persisted ARGB text color (default `0xFFFFB800` amber). */
    suspend fun currentTextColor(): Int = repo.waterMark.first().textColor

    /** S4d-107: persist the ARGB text [color] through the shared editor use-case. */
    suspend fun setTextColor(color: Int) {
        editor.updateTextColor(color)
    }

    /** S4d-109: one-shot snapshot of the persisted text size (default 14, read-clamped to >= 1). */
    suspend fun currentTextSize(): Float = repo.waterMark.first().textSize

    /** S4d-109: persist the text [size] through the shared editor use-case (clamped >= 0 on write, the
     *  repo read clamps to >= 1). */
    suspend fun setTextSize(size: Float) {
        editor.updateTextSize(size)
    }

    /** S4d-110: one-shot snapshot of the persisted horizontal gap percent (default 0; clamped 0..500). */
    suspend fun currentHGap(): Int = repo.waterMark.first().hGap

    /** S4d-110: persist the horizontal [gap] percent through the shared editor (clamped 0..500). */
    suspend fun setHGap(gap: Int) {
        editor.updateHorizon(gap)
    }

    /** S4d-110: one-shot snapshot of the persisted vertical gap percent (default 0; clamped 0..500). */
    suspend fun currentVGap(): Int = repo.waterMark.first().vGap

    /** S4d-110: persist the vertical [gap] percent through the shared editor (clamped 0..500). */
    suspend fun setVGap(gap: Int) {
        editor.updateVertical(gap)
    }

    /** S4d-112: one-shot snapshot of the persisted text typeface (default Normal). */
    suspend fun currentTextTypeface(): TextTypeface = repo.waterMark.first().textTypeface

    /** S4d-112: persist the text [typeface] through the shared editor use-case. */
    suspend fun setTextTypeface(typeface: TextTypeface) {
        editor.updateTextTypeface(typeface)
    }

    /** S4d-113: one-shot snapshot of the persisted text paint style (default Fill). */
    suspend fun currentTextStyle(): TextPaintStyle = repo.waterMark.first().textStyle

    /** S4d-113: persist the text paint [style] (Fill/Stroke) through the shared editor use-case. */
    suspend fun setTextStyle(style: TextPaintStyle) {
        editor.updateTextStyle(style)
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
