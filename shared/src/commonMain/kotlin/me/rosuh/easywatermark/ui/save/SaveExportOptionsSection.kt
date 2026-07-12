package me.rosuh.easywatermark.ui.save

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.ImageFormat

/**
 * Shared CMP output settings section for the save/export sheet.
 *
 * Callers provide localized text at the platform edge.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveExportOptionsSection(
    title: String,
    formatLabel: String,
    qualityLabel: String,
    selectedFormat: ImageFormat,
    quality: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
) {
    val isQualityVisible: Boolean = remember(selectedFormat) {
        selectedFormat == ImageFormat.JPEG
    }
    var formatMenuExpanded by remember {
        mutableStateOf(false)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        ExposedDropdownMenuBox(
            expanded = formatMenuExpanded,
            onExpandedChange = {
                if (enabled) {
                    formatMenuExpanded = formatMenuExpanded.not()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
        ) {
            OutlinedTextField(
                value = selectedFormat.toString(),
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                label = {
                    Text(text = formatLabel)
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatMenuExpanded)
                },
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedTrailingIconColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )

            ExposedDropdownMenu(
                expanded = formatMenuExpanded,
                onDismissRequest = {
                    formatMenuExpanded = false
                },
            ) {
                DropdownMenuItem(
                    text = {
                        Text("JPEG")
                    },
                    enabled = enabled,
                    onClick = {
                        formatMenuExpanded = false
                        onFormatClick(ImageFormat.JPEG)
                    },
                )

                DropdownMenuItem(
                    text = {
                        Text("PNG")
                    },
                    enabled = enabled,
                    onClick = {
                        formatMenuExpanded = false
                        onFormatClick(ImageFormat.PNG)
                    },
                )
            }
        }

        if (isQualityVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = qualityLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Text(
                    text = quality.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Continuous track (production look). Snap to 20-step grid on release (ADR-0014).
            Slider(
                value = quality.toFloat().coerceIn(20f, 100f),
                onValueChange = { raw ->
                    onQualityChange(raw.toInt().coerceIn(20, 100))
                },
                onValueChangeFinished = {
                    val snapped = ((quality + 10) / 20) * 20
                    onQualityChange(snapped.coerceIn(20, 100))
                },
                enabled = enabled,
                valueRange = 20f..100f,
                steps = 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}
