package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

data class TextColorOptionStrings(
    val customLabel: String,
    val applyCustomButton: String,
)

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

fun parseArgbHexColor(text: String): Int? {
    val raw = text.trim().removePrefix("#")
    val isHex = raw.isNotEmpty() && raw.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    if (!isHex) return null
    return when (raw.length) {
        6 -> raw.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        8 -> raw.toLongOrNull(16)?.toInt()
        else -> null
    }
}

@Composable
fun TextColorOption(
    currentColor: Int,
    customText: String,
    strings: TextColorOptionStrings,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    palette: List<Int> = DefaultTextColorPalette,
    showCustomInput: Boolean = true,
    onColorSelected: (Int) -> Unit,
    onCustomTextChange: (String) -> Unit,
    onApplyCustomText: () -> Unit,
) {
    val visibleColors = if (currentColor in palette) {
        palette
    } else {
        palette + currentColor
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleColors.distinct().forEach { color ->
                ColorSwatch(
                    color = color,
                    selected = color == currentColor,
                    enabled = enabled,
                    onClick = { onColorSelected(color) },
                )
            }
        }
        if (showCustomInput) {
            OutlinedTextField(
                value = customText,
                onValueChange = onCustomTextChange,
                enabled = enabled,
                label = { Text(strings.customLabel) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = enabled,
                onClick = onApplyCustomText,
            ) {
                Text(strings.applyCustomButton)
            }
        }
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
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(color))
            .semantics { contentDescription = "Text color ${formatArgbHexColor(color)}" }
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
    )
}
