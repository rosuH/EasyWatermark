package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface

/**
 * v2.10.0 Style option body: Fill/Stroke + typeface.
 *
 * Compact (64dp slot): one horizontally scrollable row.
 * Form inspector: stacked rows.
 */
@Composable
fun TextStyleAppearanceOption(
    paintStyle: TextPaintStyle,
    typeface: TextTypeface,
    modifier: Modifier = Modifier,
    formPath: Boolean = false,
    onPaintStyleChange: (TextPaintStyle) -> Unit,
    onTypefaceChange: (TextTypeface) -> Unit,
) {
    val labels = rememberTextPaintStyleLabels()
    if (formPath) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            TextPaintStyleOption(
                labels = labels,
                style = paintStyle,
                modifier = Modifier.testTag("editorPaintStyle"),
                onValueChange = onPaintStyleChange,
            )
            TextTypeface(
                typeface = typeface,
                onValueChange = onTypefaceChange,
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextPaintStyleOption(
                labels = labels,
                style = paintStyle,
                modifier = Modifier.testTag("editorPaintStyle"),
                fillMaxWidth = false,
                onValueChange = onPaintStyleChange,
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
            )
            TextTypeface(
                typeface = typeface,
                fillMaxWidth = false,
                onValueChange = onTypefaceChange,
            )
        }
    }
}
