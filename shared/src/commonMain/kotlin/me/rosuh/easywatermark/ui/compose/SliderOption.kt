package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import me.rosuh.easywatermark.ui.theme.DesignSliderTrack
import kotlin.math.roundToInt

/**
 * Design-aligned slider (Figma `slider` component):
 * - track height **2dp** — inactive white@14%, active solid white
 * - thumb **20dp** white circle, **2dp** stroke matching editor bg
 * - value as plain label (no white bubble background)
 *
 * Track is drawn with [Canvas] + [DrawScope.drawLine] (same pattern as Material3's own Track
 * implementation: fixed-height canvas, stroke width = 2dp). Avoids nested
 * `Modifier.fillMaxWidth(fraction)` measure, which is fragile when the slider enters composition
 * during tab switches on CMP/iOS.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderOption(
    currentValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * Snap interval within [valueRange] (e.g. `20f` → 20/40/60/80/100).
     * `null` = integer steps across the full span (editor default).
     */
    step: Float? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
) {
    val thumbBorder = MaterialTheme.colorScheme.background.takeIf { it != Color.Unspecified }
        ?: DesignEditorBg
    val safeRange = if (valueRange.endInclusive >= valueRange.start) {
        valueRange
    } else {
        valueRange.endInclusive..valueRange.start
    }
    val coerced = currentValue.coerceIn(safeRange.start, safeRange.endInclusive)
    // Material `steps` = intermediate stops only (not including endpoints).
    val span = (safeRange.endInclusive - safeRange.start)
    val steps = when {
        step != null && step > 0f -> {
            val intervals = (span / step).roundToInt().coerceAtLeast(1)
            (intervals - 1).coerceAtLeast(0)
        }
        span <= 1f -> 0
        else -> (span.roundToInt() - 1).coerceAtLeast(0)
    }
    val snap: (Float) -> Float = { raw ->
        val clamped = raw.coerceIn(safeRange.start, safeRange.endInclusive)
        if (step != null && step > 0f) {
            val start = safeRange.start
            val n = ((clamped - start) / step).roundToInt()
            (start + n * step).coerceIn(safeRange.start, safeRange.endInclusive)
        } else {
            clamped.roundToInt().toFloat()
        }
    }
    val interactionSource = remember { MutableInteractionSource() }
    val activeColor = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
    val inactiveColor = if (enabled) DesignSliderTrack else DesignSliderTrack.copy(alpha = 0.5f)
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = activeColor,
        inactiveTrackColor = inactiveColor,
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledThumbColor = Color.White.copy(alpha = 0.4f),
        disabledActiveTrackColor = activeColor,
        disabledInactiveTrackColor = inactiveColor,
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent,
    )

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Slider(
            value = snap(coerced),
            onValueChange = { onValueChange(snap(it)) },
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            steps = steps,
            valueRange = safeRange,
            modifier = Modifier
                .weight(1f)
                .height(SliderHitHeight),
            colors = colors,
            interactionSource = interactionSource,
            thumb = {
                Box(
                    modifier = Modifier
                        .size(ThumbSize)
                        .border(width = 2.dp, color = thumbBorder, shape = CircleShape)
                        .clip(CircleShape)
                        .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f)),
                )
            },
            track = { sliderState ->
                // Canvas fixed height = hit area; stroke = design 2dp (Material Track pattern).
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(SliderHitHeight),
                ) {
                    drawDesignTrack(
                        fraction = sliderState.coercedValueAsFraction.coerceIn(0f, 1f),
                        activeColor = activeColor,
                        inactiveColor = inactiveColor,
                        trackHeight = DesignTrackHeight,
                    )
                }
            },
        )
        // Value label only — no white bubble (owner request / design polish).
        Text(
            text = coerced.roundToInt().toString(),
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 28.dp),
        )
    }
}

/** Figma slider track thickness. */
private val DesignTrackHeight: Dp = 2.dp

/** Thumb diameter (design). */
private val ThumbSize: Dp = 20.dp

/**
 * Vertical space for the slider control (thumb + padding). Track is stroked at 2dp and centered
 * inside this height so Material's slider layout still has a stable measure size.
 */
private val SliderHitHeight: Dp = 28.dp

/**
 * Draws a 2dp design track.
 *
 * Mirrors Material3's track drawing approach (Canvas + drawLine with StrokeCap.Round) so layout
 * is a single full-width placeable — no fractional-width child measure.
 */
private fun DrawScope.drawDesignTrack(
    fraction: Float,
    activeColor: Color,
    inactiveColor: Color,
    trackHeight: Dp,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val stroke = trackHeight.toPx().coerceAtLeast(1f)
    val y = size.height / 2f
    val startX = 0f
    val endX = size.width
    val isRtl = layoutDirection == LayoutDirection.Rtl

    // Inactive full track
    drawLine(
        color = inactiveColor,
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )

    if (fraction <= 0f) return

    val activeStart: Offset
    val activeEnd: Offset
    if (isRtl) {
        activeStart = Offset(endX - (endX - startX) * fraction, y)
        activeEnd = Offset(endX, y)
    } else {
        activeStart = Offset(startX, y)
        activeEnd = Offset(startX + (endX - startX) * fraction, y)
    }
    drawLine(
        color = activeColor,
        start = activeStart,
        end = activeEnd,
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
}
