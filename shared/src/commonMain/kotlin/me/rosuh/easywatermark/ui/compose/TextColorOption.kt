package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.color_apply_custom
import me.rosuh.easywatermark.shared.generated.resources.color_custom
import me.rosuh.easywatermark.ui.SharedProductDrawables
import org.jetbrains.compose.resources.stringResource

/** Production v2.10.0 ColorFragment presets (same order as Android [ColorStyleOption]). */
val DefaultTextColorPalette: List<Int> = listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFFFFB800.toInt(),
    0xFFFF3535.toInt(),
    0xFFFF008A.toInt(),
    0xFF00D1FF.toInt(),
    0xFF1BFF3F.toInt(),
)

fun formatArgbHexColor(color: Int): String {
    val unsigned = color.toLong() and 0xFFFFFFFFL
    return "#" + unsigned.toString(16).uppercase().padStart(8, '0')
}

/**
 * Normalize pasted HEX before parse: strip `#` / `0x`, spaces, keep hex digits only (F3).
 * Returns uppercase hex body without `#` (may be empty).
 */
fun normalizeArgbHexInput(text: String): String {
    var raw = text.trim()
    if (raw.startsWith("#")) raw = raw.substring(1)
    if (raw.startsWith("0x", ignoreCase = true) || raw.startsWith("0X")) {
        raw = raw.substring(2)
    }
    return buildString(raw.length) {
        for (c in raw) {
            if (c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F') append(c.uppercaseChar())
        }
    }
}

fun parseArgbHexColor(text: String): Int? {
    val raw = normalizeArgbHexInput(text)
    if (raw.isEmpty()) return null
    return when (raw.length) {
        3 -> {
            // #RGB → #RRGGBB opaque
            val r = raw[0]; val g = raw[1]; val b = raw[2]
            val expanded = "$r$r$g$g$b$b"
            expanded.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        }
        6 -> raw.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> raw.toLongOrNull(16)?.toInt()
        else -> null
    }
}

/**
 * Shared color control: preset swatches + optional custom picker sheet
 * ([CustomColorPickerSheet] — 2D SV plane / hue bar / alpha, skydoves-like on CMP).
 */
@Composable
fun TextColorOption(
    currentColor: Int,
    customText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    palette: List<Int> = DefaultTextColorPalette,
    showCustomPicker: Boolean = true,
    showCustomInput: Boolean = false,
    onColorSelected: (Int) -> Unit,
    onCustomTextChange: (String) -> Unit = {},
    onApplyCustomText: () -> Unit = {},
) {
    val visibleColors = if (currentColor in palette || showCustomPicker) {
        palette
    } else {
        palette + currentColor
    }
    val selectedIsPreset = palette.any { it == currentColor }
    var showPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            overscrollEffect = rememberOverscrollEffect(),
        ) {
            items(visibleColors.distinct()) { color ->
                ColorSwatch(
                    color = color,
                    selected = color == currentColor,
                    enabled = enabled,
                    onClick = { onColorSelected(color) },
                )
            }
            if (showCustomPicker) {
                item(key = "custom_picker") {
                    CustomPickerSwatch(
                        selected = !selectedIsPreset,
                        enabled = enabled,
                        onClick = { showPicker = true },
                    )
                }
            }
        }
        if (showCustomInput) {
            OutlinedTextField(
                value = customText,
                onValueChange = { raw ->
                    val body = normalizeArgbHexInput(raw)
                    onCustomTextChange(if (body.isEmpty()) raw else "#$body")
                },
                enabled = enabled,
                label = { Text(stringResource(Res.string.color_custom)) },
                isError = customText.isNotBlank() && parseArgbHexColor(customText) == null,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                enabled = enabled,
                onClick = onApplyCustomText,
            ) {
                Text(stringResource(Res.string.color_apply_custom))
            }
        }
    }

    if (showPicker) {
        CustomColorPickerSheet(
            initialColor = currentColor,
            onDismiss = { showPicker = false },
            onConfirm = { argb ->
                onColorSelected(argb)
                onCustomTextChange(formatArgbHexColor(argb))
                showPicker = false
            },
        )
    }
}

@Composable
private fun ColorSwatch(
    color: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(width = 2.dp, color = Color.White, shape = CircleShape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.35f),
                        shape = CircleShape,
                    )
                },
            )
            .testTag("colorSwatch-${formatArgbHexColor(color).removePrefix("#")}")
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "Text color ${formatArgbHexColor(color)}"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = ColorPainter(Color(color)),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape),
        )
    }
}

@Composable
private fun CustomPickerSwatch(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.border(width = 2.dp, color = Color.White, shape = CircleShape)
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.35f),
                        shape = CircleShape,
                    )
                },
            )
            .testTag("colorSwatch-custom")
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "color picker"
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = SharedProductDrawables.colorPickerPainter(),
            contentDescription = null,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape),
        )
    }
}
