package me.rosuh.easywatermark.font

import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.model.WatermarkFontRef

/**
 * Preview after a font commit must paint the committed identity, not whatever
 * LaunchScreenState currently holds. Session's DataStore collector may still
 * be publishing the previous WaterMark.
 */
object FontSelectionCommit {
    fun committedWaterMark(
        published: WaterMark,
        committedRef: WatermarkFontRef,
        supportedStyles: Set<TextTypeface>,
    ): WaterMark {
        val capability = FontStyleCapability(supportedStyles)
        return published.copy(
            fontRef = committedRef,
            textTypeface = capability.normalize(published.textTypeface),
        )
    }

    /**
     * Null means do not paint: persist failed, or this request is stale.
     */
    fun waterMarkForRender(
        persistSucceeded: Boolean,
        requestGeneration: Int,
        currentGeneration: Int,
        published: WaterMark,
        committedRef: WatermarkFontRef,
        supportedStyles: Set<TextTypeface>,
    ): WaterMark? {
        if (!persistSucceeded) return null
        if (requestGeneration != currentGeneration) return null
        return committedWaterMark(published, committedRef, supportedStyles)
    }
}
