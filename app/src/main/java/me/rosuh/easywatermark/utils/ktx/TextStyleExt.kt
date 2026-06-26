package me.rosuh.easywatermark.utils.ktx

import android.graphics.Paint
import me.rosuh.easywatermark.data.model.TextPaintStyle

/**
 * Android edge mapper for the platform-neutral [TextPaintStyle] (S4d-60). The neutral model lives in
 * `:shared/commonMain` and cannot reference `android.graphics.Paint`; this extension supplies the
 * Android `Paint.Style` that production text rendering needs (consumed in `PainKtx.kt`). Mapping is
 * byte-identical to the former `TextPaintStyle.obtainSysStyle()` member: Fill→FILL, Stroke→STROKE.
 */
fun TextPaintStyle.obtainSysStyle(): Paint.Style = when (this) {
    TextPaintStyle.Fill -> Paint.Style.FILL
    TextPaintStyle.Stroke -> Paint.Style.STROKE
}
