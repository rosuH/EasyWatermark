package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.tips_cancel_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_choose_color_dialog
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * Multiplatform color picker sheet — skydoves-style layout without Android-only deps:
 * - 2D **S×V** plane for the current hue
 * - vertical **hue** rainbow bar
 * - **alpha** via shared [SliderOption] (same track/thumb as editor)
 * - hex field + live preview + Confirm / Cancel
 *
 * Sheet chrome matches text-edit / export sheets ([RectangleShape], olive surface, brand CTA).
 * Heavy SV/hue canvases mount after the first frame so the sheet can open without a hitch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomColorPickerSheet(
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val (initH, initS, initV) = remember(initialColor) { argbToHsv(initialColor) }
    val initA = remember(initialColor) { ((initialColor ushr 24) and 0xFF) / 255f }

    var hue by remember { mutableFloatStateOf(initH) }
    var sat by remember { mutableFloatStateOf(initS) }
    var value by remember { mutableFloatStateOf(initV) }
    var alpha by remember {
        mutableFloatStateOf(initA.coerceIn(0f, 1f).let { if (it == 0f) 1f else it })
    }
    var hexDraft by remember {
        mutableStateOf(formatArgbHexColor(initialColor).removePrefix("#").takeLast(6))
    }
    // Defer SV plane + hue bar one frame so ModalBottomSheet can animate in first (iOS first-open).
    var pickerReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        pickerReady = true
    }

    fun previewArgb(): Int = hsvToArgb(hue, sat, value, alpha)

    fun syncHexFromHsv() {
        hexDraft = formatArgbHexColor(previewArgb()).removePrefix("#").takeLast(6)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Align with TextContent / Template / Export sheets (not rounded Material default).
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(Res.string.tips_choose_color_dialog),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // SV plane + hue bar (skydoves-like core). Placeholder keeps sheet height stable.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            ) {
                if (pickerReady) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SaturationValuePanel(
                            hue = hue,
                            saturation = sat,
                            value = value,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp)),
                            onChange = { s, v ->
                                sat = s
                                value = v
                                syncHexFromHsv()
                            },
                        )
                        HueBar(
                            hue = hue,
                            modifier = Modifier
                                .width(28.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp)),
                            onChange = { h ->
                                hue = h
                                syncHexFromHsv()
                            },
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    )
                }
            }

            // Preview + hex
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CheckerboardPreview(
                    color = Color(previewArgb()),
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
                OutlinedTextField(
                    value = hexDraft,
                    onValueChange = { raw ->
                        hexDraft = raw.filter { it.isLetterOrDigit() }.take(6).uppercase()
                        parseArgbHexColor(hexDraft)?.let { parsed ->
                            val (h, s, v) = argbToHsv(parsed)
                            hue = h
                            sat = s
                            value = v
                        }
                    },
                    label = { Text("#RRGGBB") },
                    singleLine = true,
                    shape = RectangleShape,
                    modifier = Modifier.weight(1f),
                )
            }

            // Alpha — same design slider as opacity / quality / degree.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "A",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(28.dp),
                )
                SliderOption(
                    currentValue = (alpha * 100f).coerceIn(0f, 100f),
                    valueRange = 0f..100f,
                    // Same default integer snap as editor opacity slider.
                    onValueChange = { alpha = (it / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                )
            }

            // CTA row: Cancel text + full-width brand Confirm (export / text-edit pattern).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    shape = RectangleShape,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.tips_cancel_dialog))
                }
                Button(
                    onClick = { onConfirm(previewArgb()) },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DesignBrand,
                        contentColor = DesignEditorBg,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text(stringResource(Res.string.tips_confirm_dialog))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SV 2D panel
// ---------------------------------------------------------------------------

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    modifier: Modifier = Modifier,
    onChange: (saturation: Float, value: Float) -> Unit,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    // Hue brush color only; gradient rebuilds when hue changes (not every drag on S/V).
    val pureHue = remember(hue) { Color(hsvToArgb(hue, 1f, 1f, 1f)) }
    val thumbFill = remember(hue, saturation, value) {
        Color(hsvToArgb(hue, saturation, value, 1f))
    }

    fun applyOffset(x: Float, y: Float) {
        val w = sizePx.width.coerceAtLeast(1).toFloat()
        val h = sizePx.height.coerceAtLeast(1).toFloat()
        val s = (x / w).coerceIn(0f, 1f)
        val v = (1f - y / h).coerceIn(0f, 1f)
        onChange(s, v)
    }

    Box(
        modifier = modifier
            .onSizeChanged { sizePx = it }
            .pointerInput(Unit) {
                detectTapGestures { offset -> applyOffset(offset.x, offset.y) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    applyOffset(change.position.x, change.position.y)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, pureHue),
                ),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                ),
            )
            val tx = saturation * size.width
            val ty = (1f - value) * size.height
            val r = with(density) { 10.dp.toPx() }
            drawCircle(
                color = Color.White,
                radius = r + with(density) { 1.5.dp.toPx() },
                center = Offset(tx, ty),
                style = Stroke(width = with(density) { 2.dp.toPx() }),
            )
            drawCircle(
                color = thumbFill,
                radius = r,
                center = Offset(tx, ty),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = r,
                center = Offset(tx, ty),
                style = Stroke(width = with(density) { 1.dp.toPx() }),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Hue bar
// ---------------------------------------------------------------------------

@Composable
private fun HueBar(
    hue: Float,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val hues = remember {
        listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color(hsvToArgb(it, 1f, 1f, 1f)) }
    }
    val hueBrush = remember(hues) { Brush.verticalGradient(colors = hues) }

    fun applyY(y: Float) {
        val h = sizePx.height.coerceAtLeast(1).toFloat()
        onChange((y / h * 360f).coerceIn(0f, 360f))
    }

    Box(
        modifier = modifier
            .onSizeChanged { sizePx = it }
            .pointerInput(Unit) {
                detectTapGestures { offset -> applyY(offset.y) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    applyY(change.position.y)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush = hueBrush)
            val ty = (hue / 360f) * size.height
            val barHalf = with(density) { 3.dp.toPx() }
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(0f, ty - barHalf),
                size = androidx.compose.ui.geometry.Size(size.width, barHalf * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHalf, barHalf),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.4f),
                topLeft = Offset(0f, ty - barHalf),
                size = androidx.compose.ui.geometry.Size(size.width, barHalf * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barHalf, barHalf),
                style = Stroke(width = with(density) { 1.dp.toPx() }),
            )
        }
    }
}

@Composable
private fun CheckerboardPreview(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        CheckerboardBackground(Modifier.fillMaxSize())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        )
    }
}

@Composable
private fun CheckerboardBackground(modifier: Modifier = Modifier) {
    // Larger cells → fewer drawRect calls on first open.
    val light = Color(0xFFE0E0E0)
    val dark = Color(0xFFBDBDBD)
    Canvas(modifier) {
        val cell = 12.dp.toPx()
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                drawRect(
                    color = if ((row + col) % 2 == 0) light else dark,
                    topLeft = Offset(col * cell, row * cell),
                    size = androidx.compose.ui.geometry.Size(cell, cell),
                )
            }
        }
    }
}

// --- HSV / ARGB helpers ---

internal fun argbToHsv(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val h = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else delta / max
    return Triple(h, s, max)
}

internal fun hsvToArgb(h: Float, s: Float, v: Float, a: Float = 1f): Int {
    val hh = ((h % 360f) + 360f) % 360f
    val c = v * s
    val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
    val m = v - c
    val (rp, gp, bp) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val r = ((rp + m) * 255f).roundToInt().coerceIn(0, 255)
    val g = ((gp + m) * 255f).roundToInt().coerceIn(0, 255)
    val b = ((bp + m) * 255f).roundToInt().coerceIn(0, 255)
    val alphaByte = (a.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    return (alphaByte shl 24) or (r shl 16) or (g shl 8) or b
}
