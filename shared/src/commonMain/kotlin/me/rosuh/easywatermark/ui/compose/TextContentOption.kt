package me.rosuh.easywatermark.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.dialog_title_edit_watermark
import me.rosuh.easywatermark.shared.generated.resources.dialog_title_template_title
import me.rosuh.easywatermark.shared.generated.resources.tips_confirm_dialog
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionAllowsDecorativeLoop
import org.jetbrains.compose.resources.stringResource

/**
 * Shared watermark **text** option.
 *
 * Product interaction (owner, Phase B):
 * 1. User taps the **Text** mode button in the Content carousel.
 * 2. A modal sheet opens with a text field to edit the watermark.
 * 3. **Template entry is top-end of the sheet** (not beside a permanent inline field).
 *
 * S-i18n-2: labels from Res. [openSignal]: bumped when Text option is (re)activated via the
 * Carousel. Sheet opens on each positive signal so re-tapping Text reopens the dialog. * `0` means "not opened by signal yet" (initial default selection shows summary only).
 */
@Composable
fun TextContentOption(
    text: String,
    templateIcon: Painter? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
 * Bump when the Text carousel button is activated so the edit sheet opens.
 * Keep `0` for passive display of the current text summary.
     */
    openSignal: Int = 0,
    onTextChange: (String) -> Unit,
    onGoTemplateList: () -> Unit = {},
) {
    var showEditSheet by remember { mutableStateOf(false) }

    LaunchedEffect(openSignal) {
        if (openSignal > 0 && enabled) {
            showEditSheet = true
        }
    }

    // M4: soft caret blink (prod BlinkCursorView). Decorative loop — Full only (MotionPolicy).
    // Always allocate the infinite transition (Compose remember rules); gate the painted alpha.
    val allowBlink = motionAllowsDecorativeLoop(currentMotionPolicy())
    val blinkAlpha by rememberInfiniteTransition(label = "textContentCursor").animateFloat(
        initialValue = 0.55f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = EwmTheme.motion.textCaretBlinkMs,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cursorAlpha",
    )
    val cursorAlpha = if (allowBlink) blinkAlpha else 0.4f
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TEXT_CONTENT_ROW_TAG)
            .clickable(enabled = enabled) { showEditSheet = true }
            .padding(horizontal = 0.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // weight(fill=false): content-sized up to remaining width so the packed row stays centered.
            modifier = Modifier.weight(1f, fill = false),
        )
        // Soft caret: same muted color; blink under Full, static under Reduced/Off.
        Box(
            modifier = Modifier
                .padding(start = 1.dp)
                .width(2.dp)
                .height(18.dp)
                .graphicsLayer { alpha = cursorAlpha }
                .background(muted),
        )
    }

    if (showEditSheet) {
        WatermarkTextEditSheet(
            initialText = text,
            templateIcon = templateIcon,
            enabled = enabled,
            onConfirm = {
                onTextChange(it)
                showEditSheet = false
            },
            onDismiss = { showEditSheet = false },
            onGoTemplateList = {
                // Leave sheet open or dismiss — templates host is separate; dismiss first for focus.
                showEditSheet = false
                onGoTemplateList()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WatermarkTextEditSheet(
    initialText: String,
    templateIcon: Painter?,
    enabled: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onGoTemplateList: () -> Unit,
) {
    var draft by remember { mutableStateOf(initialText) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = DesignEditorBg,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            // Title row + template entry (top-end / 右上角).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.dialog_title_edit_watermark),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (templateIcon != null) {
                    IconButton(
                        onClick = onGoTemplateList,
                        enabled = enabled,
                        modifier = Modifier.testTag(TEXT_CONTENT_TEMPLATE_ICON_TAG),
                    ) {
                        Icon(
                            painter = templateIcon,
                            contentDescription = stringResource(Res.string.dialog_title_template_title),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .testTag(TEXT_CONTENT_EDIT_FIELD_TAG),
                shape = RectangleShape,
            )
            Button(
                onClick = { onConfirm(draft) },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .testTag(TEXT_CONTENT_CONFIRM_TAG),
                shape = RectangleShape,
            ) {
                Text(text = stringResource(Res.string.tips_confirm_dialog))
            }
        }
    }
}

/** Stable Compose testTag ids for XCUITest (not user-facing accessibility strings). */
const val TEXT_CONTENT_ROW_TAG = "watermarkTextContent"
const val TEXT_CONTENT_EDIT_FIELD_TAG = "watermarkTextEditField"
const val TEXT_CONTENT_CONFIRM_TAG = "watermarkTextConfirm"
const val TEXT_CONTENT_TEMPLATE_ICON_TAG = "watermarkTextTemplateIcon"
