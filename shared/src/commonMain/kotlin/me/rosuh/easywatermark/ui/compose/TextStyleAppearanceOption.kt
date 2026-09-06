package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.data.model.TextPaintStyle
import me.rosuh.easywatermark.data.model.TextTypeface
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.cd_open_font_panel
import me.rosuh.easywatermark.shared.generated.resources.font_row_label
import me.rosuh.easywatermark.shared.generated.resources.font_system_default
import org.jetbrains.compose.resources.stringResource

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
    currentFontName: String = "",
    supportedStyles: Set<TextTypeface> = setOf(
        TextTypeface.Normal,
        TextTypeface.Bold,
        TextTypeface.Italic,
        TextTypeface.BoldItalic,
    ),
    onPaintStyleChange: (TextPaintStyle) -> Unit,
    onTypefaceChange: (TextTypeface) -> Unit,
    onOpenFontPanel: () -> Unit = {},
) {
    val labels = rememberTextPaintStyleLabels()
    val fontLabel = stringResource(Res.string.font_row_label)
    val defaultName = stringResource(Res.string.font_system_default)
    val openCd = stringResource(Res.string.cd_open_font_panel)
    val displayName = currentFontName.ifBlank { defaultName }
    if (formPath) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            FontEntryRow(
                label = fontLabel,
                value = displayName,
                contentDescription = openCd,
                onClick = onOpenFontPanel,
            )
            TextPaintStyleOption(
                labels = labels,
                style = paintStyle,
                modifier = Modifier.testTag("editorPaintStyle"),
                onValueChange = onPaintStyleChange,
            )
            TextTypeface(
                typeface = typeface,
                supportedStyles = supportedStyles,
                onValueChange = onTypefaceChange,
            )
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FontEntryRow(
                label = fontLabel,
                value = displayName,
                contentDescription = openCd,
                onClick = onOpenFontPanel,
            )
            Row(
                modifier = Modifier
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
                    supportedStyles = supportedStyles,
                    onValueChange = onTypefaceChange,
                )
            }
        }
    }
}

@Composable
private fun FontEntryRow(
    label: String,
    value: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag("editorFontEntryRow")
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 4.dp),
        )
        Icon(
            painter = SharedProductDrawables.chevronRightPainter(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}
