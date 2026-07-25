package me.rosuh.easywatermark.ui.save

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.ui.compose.DesignChoiceChips
import me.rosuh.easywatermark.ui.compose.DesignChoiceOption
import me.rosuh.easywatermark.ui.compose.SliderOption
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs

/**
 * Shared CMP output settings for the save/export sheet — design-aligned chips + [SliderOption].
 *
 * Quality row is **always composed** (JPEG interactive, PNG muted/disabled) so switching
 * Format does not collapse the sheet and jump the export list / CTA. */
@Composable
fun SaveExportOptionsSection(
    title: String,
    @Suppress("UNUSED_PARAMETER") formatLabel: String,
    qualityLabel: String,
    selectedFormat: ImageFormat,
    quality: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
) {
    val qualityApplies = selectedFormat == ImageFormat.JPEG
    val qualityEnabled = enabled && qualityApplies
    // Soft mute when PNG — layout height stays stable.
    val qualityAlpha = if (qualityApplies) 1f else 0.38f
    // I3: animateContentSize duration follows MotionPolicy (0 when Off).
    val contentSizeMs = motionDurationMs(currentMotionPolicy(), EwmTheme.motion.contentSizeMs)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(durationMillis = contentSizeMs, easing = FastOutSlowInEasing),
            ),
    ) {
        Text(
            text = title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // No secondary "格式" label under 输出格式 — chips alone are enough (less chrome).
        DesignChoiceChips(
            options = listOf(
                DesignChoiceOption(label = "JPEG", value = ImageFormat.JPEG),
                DesignChoiceOption(label = "PNG", value = ImageFormat.PNG),
            ),
            selected = selectedFormat,
            onSelected = { if (enabled) onFormatClick(it) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        // Always reserve this block — no if/else height jump on format change.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(qualityAlpha),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = qualityLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (qualityApplies) quality.toString() else "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Production save dialog: stepSize=20 → 20 / 40 / 60 / 80 / 100 only.
            SliderOption(
                currentValue = quality.toFloat().coerceIn(20f, 100f),
                valueRange = 20f..100f,
                step = 20f,
                enabled = qualityEnabled,
                // I2: quality label for slider name + value semantics.
                label = qualityLabel,
                onValueChange = { raw ->
                    if (qualityEnabled) {
                        onQualityChange(raw.toInt().coerceIn(20, 100))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
