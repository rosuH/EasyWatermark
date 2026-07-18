package me.rosuh.easywatermark.domain

import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.MediaRef
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WatermarkConfigRules
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.data.repo.WaterMarkRepository

/**
 * Platform-neutral use-case for editing persisted watermark config via [WaterMarkRepository].
 *
 * Applies edit rules (text-size clamp, alpha percent→byte, empty-icon guard). Methods are `suspend`
 * And own no coroutine scope — callers launch on their own scope. */
class WatermarkConfigEditor(private val repo: WaterMarkRepository) {

    suspend fun updateText(text: String) {
        repo.updateText(text)
    }

    suspend fun updateTextSize(textSize: Float) {
        val finalTextSize = textSize.coerceAtLeast(0f)
        repo.updateTextSize(finalTextSize)
    }

    suspend fun updateTextColor(color: Int) {
        repo.updateColor(color)
    }

    suspend fun updateTextStyle(style: TextPaintStyle) {
        repo.updateTextStyle(style)
    }

    suspend fun updateTextTypeface(typeface: TextTypeface) {
        repo.updateTypeFace(typeface)
    }

    suspend fun updateAlpha(alpha: Float) {
        val finalAlpha = WatermarkConfigRules.alphaPercentToByte(alpha)
        repo.updateAlpha(finalAlpha)
    }

    suspend fun updateHorizon(gap: Int) {
        repo.updateHorizon(gap)
    }

    suspend fun updateVertical(gap: Int) {
        repo.updateVertical(gap)
    }

    suspend fun updateDegree(degree: Float) {
        repo.updateDegree(degree)
    }

    suspend fun updateIcon(iconUri: MediaRef) {
        if (iconUri.value.isNotEmpty()) {
            repo.updateIcon(iconUri)
        }
    }

    suspend fun updateTileMode(tileMode: WatermarkTileMode) {
        repo.updateTileMode(tileMode)
    }

    /**
 * Synchronous offset-only update. Returns the repository-installed [ImageInfo], or null if the
 * URI is not in the list (no-op). Caller object is never mutated.
     */
    fun updateOffset(info: ImageInfo): ImageInfo? = repo.updateOffset(info)
}
