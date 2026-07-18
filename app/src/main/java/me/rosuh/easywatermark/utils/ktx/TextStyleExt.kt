package me.rosuh.easywatermark.utils.ktx

import android.graphics.Paint
import me.rosuh.easywatermark.data.model.TextPaintStyle

/**
 * Android edge mapper for the platform-neutral [TextPaintStyle]. The neutral model lives in
 * `:shared/commonMain` and cannot reference `android.graphics.Paint`; this extension supplies the
 * Android `Paint.Style` that production text rendering needs (consumed in `PainKtx.kt`). Mapping is
 * Byte-identical to the former `TextPaintStyle.obtainSysStyle()` member: Fill→FILL, Stroke→STROKE. */
fun TextPaintStyle.obtainSysStyle(): Paint.Style = when (this) {
    TextPaintStyle.Fill -> Paint.Style.FILL
    TextPaintStyle.Stroke -> Paint.Style.STROKE
}
