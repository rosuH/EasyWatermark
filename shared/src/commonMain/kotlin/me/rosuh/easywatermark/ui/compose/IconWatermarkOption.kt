package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignEditorBg

/**
 * Shared CMP shell for the image-watermark picker option.
 *
 * Platform hosts supply permission / picker launch. No inline icon preview here — the fixed
 * 64dp control slot only fits the pick CTA; the editor canvas already shows the sticker.
 *
 * [hasIcon] / [preview] remain for call-site source compatibility but are not rendered.
 */
@Composable
fun IconWatermarkOption(
    hasIcon: Boolean,
    pickLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPick: () -> Unit,
    preview: @Composable () -> Unit = {},
) {
    // Keep parameters so Android/Desktop/iOS call sites stay source-compatible.
    @Suppress("UNUSED_VARIABLE")
    val ignoredHasIcon = hasIcon
    @Suppress("UNUSED_VARIABLE")
    val ignoredPreview = preview

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("sharedComposeIconWatermarkOption"),
        contentAlignment = Alignment.Center,
    ) {
        Button(
            enabled = enabled,
            onClick = onPick,
            shape = RectangleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = DesignBrand,
                contentColor = DesignEditorBg,
                disabledContainerColor = DesignBrand.copy(alpha = 0.4f),
                disabledContentColor = DesignEditorBg.copy(alpha = 0.6f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(40.dp),
        ) {
            Text(text = pickLabel)
        }
    }
}
