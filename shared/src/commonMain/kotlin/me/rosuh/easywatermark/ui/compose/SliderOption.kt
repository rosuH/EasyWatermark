package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rosuh.easywatermark.ui.AccessibilitySemantics
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import me.rosuh.easywatermark.ui.theme.DesignSliderTrack
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Design-aligned slider (Figma `slider` component):
 * - track height **2dp** — inactive white@14%, active solid white
 * - thumb **20dp** white circle, **2dp** stroke matching editor bg
 * - value as plain label (no white bubble background)
 *
 * Track is drawn with [Canvas] + [DrawScope.drawLine] (same pattern as Material3's own Track
 * Implementation: fixed-height canvas, stroke width = 2dp). Avoids nested * `Modifier.fillMaxWidth(fraction)` measure, which is fragile when the slider enters composition
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
     * Optional product name for a11y (I2). Combined with the current value for
     * [contentDescription]; omit when a visible text label already merges in the parent.
     */
    label: String? = null,
    /**
     * When true and [label] is non-blank, paint a form inspector layout:
     * **label row** (title start + mono value end) **above** a full-width track.
     * Avoids unequal track widths from different title lengths.
     * Phone bottom chrome keeps false so layout stays track+value only.
     */
    showLabel: Boolean = false,
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
    val valueDisplay = coerced.roundToInt().toString()
    val a11yName = AccessibilitySemantics.sliderContentDescription(label, valueDisplay)
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
    val snap: (Float) -> Float = { raw -> snapSliderValue(raw, safeRange, step) }
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

    fun applyStep(deltaUnits: Int) {
        if (!enabled || deltaUnits == 0) return
        val next = sliderStepValue(coerced, safeRange, step, deltaUnits)
        if (next != snap(coerced)) {
            onValueChange(next)
            onValueChangeFinished?.invoke()
        }
    }

    val interactionModifier = Modifier
        .fillMaxWidth()
        // I2: name + value/state; disabled when not interactive.
        .semantics(mergeDescendants = true) {
            contentDescription = a11yName
            stateDescription = valueDisplay
            if (!enabled) disabled()
        }
        .focusable(enabled = enabled, interactionSource = interactionSource)
        .onPreviewKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val units = if (event.isShiftPressed) 10 else 1
            when (event.key) {
                Key.DirectionLeft, Key.DirectionDown -> {
                    applyStep(-units)
                    true
                }
                Key.DirectionRight, Key.DirectionUp -> {
                    applyStep(units)
                    true
                }
                else -> false
            }
        }
        .pointerInput(enabled, coerced, safeRange, step) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (!enabled || event.type != PointerEventType.Scroll) continue
                    val scroll = event.changes.firstOrNull()?.scrollDelta ?: continue
                    // Vertical wheel preferred; horizontal trackpad also steps.
                    val delta = when {
                        scroll.y != 0f -> -scroll.y.sign.toInt().coerceIn(-1, 1)
                        scroll.x != 0f -> scroll.x.sign.toInt().coerceIn(-1, 1)
                        else -> 0
                    }
                    if (delta != 0) {
                        applyStep(delta)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .testTag("sliderOption")

    val sliderControl: @Composable (Modifier) -> Unit = { sliderModifier ->
        Slider(
            value = snap(coerced),
            onValueChange = { onValueChange(snap(it)) },
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            steps = steps,
            valueRange = safeRange,
            modifier = sliderModifier
                .height(SliderHitHeight)
                .testTag("sliderTrack"),
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
    }

    val valueLabel: @Composable () -> Unit = {
        // Value label only — no white bubble (owner request / design polish).
        // Form path uses mono so DEMO right-value reads as a metric, not body copy.
        Text(
            text = valueDisplay,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (showLabel) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 28.dp)
                .testTag("sliderValue"),
        )
    }

    if (showLabel && !label.isNullOrBlank()) {
        // Form inspector: title + value on one row; full-width track below (equal track widths).
        Column(
            modifier = modifier.then(interactionModifier),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = label,
                    color = if (enabled) Color.White.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.4f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(end = 8.dp)
                        .testTag("sliderLabel"),
                )
                valueLabel()
            }
            sliderControl(Modifier.fillMaxWidth())
        }
    } else {
        // Phone bottom chrome: track + value on one row (no title).
        Row(
            modifier = modifier.then(interactionModifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sliderControl(Modifier.weight(1f))
            valueLabel()
        }
    }
}

/** Figma slider track thickness. */
private val DesignTrackHeight: Dp = 2.dp

/** Thumb diameter (design). */
private val ThumbSize: Dp = 20.dp

/**
 * Vertical space for the slider control (thumb + padding). Track is stroked at 2dp and centered
 * Inside this height so Material's slider layout still has a stable measure size. */
private val SliderHitHeight: Dp = 28.dp

/**
 * Draws a 2dp design track.
 *
 * Mirrors Material3's track drawing approach (Canvas + drawLine with StrokeCap.Round) so layout
 * Is a single full-width placeable — no fractional-width child measure. */
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
