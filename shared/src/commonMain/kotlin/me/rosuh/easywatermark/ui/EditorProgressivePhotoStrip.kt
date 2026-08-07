package me.rosuh.easywatermark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.ios_import_failed
import me.rosuh.easywatermark.shared.generated.resources.ios_import_failed_retry
import me.rosuh.easywatermark.shared.generated.resources.ios_import_pending
import me.rosuh.easywatermark.shared.generated.resources.ios_import_ready
import me.rosuh.easywatermark.shared.generated.resources.ios_import_remove
import me.rosuh.easywatermark.shared.generated.resources.ios_import_retry
import me.rosuh.easywatermark.ui.theme.MotionPolicy
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import org.jetbrains.compose.resources.stringResource

/**
 * Internal host injection for progressive picker slots. It deliberately has no Session-facing
 * `MediaRef` for Pending/Failed cells, so shared editor chrome cannot accidentally export one.
 */
internal data class EditorProgressiveSlotPresentation(
    val state: EditorMediaSlotState,
    val importInProgress: Boolean,
    val onSelectReady: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onPrioritize: (String) -> Unit,
    /** Actual filmstrip cell long-edge pixels (from layout); drives 128/160/192 buckets. */
    val measuredCellPx: Int = 0,
    val onCellPxMeasured: (Int) -> Unit = {},
    /**
     * Host mono clock (ms) for computing Pending elapsed from [EditorMediaSlot.Pending.attemptStartedAtMs].
     * Tests inject a fixed clock; production uses the same clock that stamped the slot.
     */
    val nowMs: () -> Long = { 0L },
)

internal val LocalEditorProgressiveSlotPresentation =
    compositionLocalOf<EditorProgressiveSlotPresentation?> { null }

/**
 * Progressive filmstrip: fixed-order Pending/Ready/Failed cells rendered **inside** the legacy
 * [EditorFilmstripScaffold] (56dp rail, 40×40 content, fixed 48×48 center frame, snap/settle).
 *
 * Ready cells are image-only. Pending shows a centered loading animation (reduced-motion static
 * mark; no text label, no visible chip — remove stays a11y-only). Failed keeps retry/remove recovery.
 */
@Composable
internal fun EditorProgressivePhotoStrip(
    presentation: EditorProgressiveSlotPresentation,
    thumbnail: @Composable (ImageInfo, String, Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pendingLabel = stringResource(Res.string.ios_import_pending)
    val readyLabel = stringResource(Res.string.ios_import_ready)
    val failedLabel = stringResource(Res.string.ios_import_failed)
    val failedHint = stringResource(Res.string.ios_import_failed_retry)
    val retryLabel = stringResource(Res.string.ios_import_retry)
    val removeLabel = stringResource(Res.string.ios_import_remove)
    val frameShape = RoundedCornerShape(EditorFilmstripMetrics.FrameRadius)

    val focusedImportId = presentation.state.focusedImportId
    val selectedKey = focusedImportId?.takeIf { id ->
        presentation.state.slot(id) is EditorMediaSlot.Ready
    }

    EditorFilmstripScaffold(
        items = presentation.state.slots,
        keyOf = { it.importId },
        selectedKey = selectedKey,
        // Settle + Ready selection only. Pending/Failed use in-cell taps (no Session mutation).
        canSelect = { EditorFilmstripInteraction.canSelectSlot(it) },
        onItemSelected = { slot ->
            val ready = slot as? EditorMediaSlot.Ready ?: return@EditorFilmstripScaffold
            presentation.onSelectReady(ready.importId)
        },
        modifier = modifier,
        testTag = "progressiveImportFilmstrip",
        itemContent = { slot, contentModifier ->
            when (slot) {
                is EditorMediaSlot.Ready -> {
                    // Exact legacy Ready appearance: image only, no badge/chip/overlay.
                    Box(
                        modifier = contentModifier
                            .size(EditorFilmstripMetrics.ContentSize)
                            .semantics { contentDescription = readyLabel }
                            .testTag("progressiveImportSlot:${slot.importId}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { size ->
                                    val px = maxOf(size.width, size.height)
                                    if (px > 0) presentation.onCellPxMeasured(px)
                                }
                                .clip(frameShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            thumbnail(
                                slot.image,
                                readyLabel,
                                Modifier
                                    .fillMaxSize()
                                    .clip(frameShape),
                            )
                        }
                    }
                }

                is EditorMediaSlot.Pending -> {
                    val motion = currentMotionPolicy()
                    val reduceMotion =
                        motion == MotionPolicy.Reduced || motion == MotionPolicy.Off
                    val a11yActions = listOf(
                        CustomAccessibilityAction(removeLabel) {
                            presentation.onRemove(slot.importId)
                            true
                        },
                    )
                    Box(
                        modifier = contentModifier
                            .size(EditorFilmstripMetrics.ContentSize)
                            .semantics {
                                contentDescription = pendingLabel
                                customActions = a11yActions
                            }
                            .testTag("progressiveImportSlot:${slot.importId}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        PendingCellChrome(
                            pending = slot,
                            reduceMotion = reduceMotion,
                            nowMs = presentation.nowMs,
                            shape = frameShape,
                            onPrioritize = { presentation.onPrioritize(slot.importId) },
                            onCellPxMeasured = presentation.onCellPxMeasured,
                        )
                        // No visible remove chip on Pending (owner 2026-08-07).
                    }
                }

                is EditorMediaSlot.Failed -> {
                    val a11yActions = listOf(
                        CustomAccessibilityAction(retryLabel) {
                            presentation.onRetry(slot.importId)
                            true
                        },
                        CustomAccessibilityAction(removeLabel) {
                            presentation.onRemove(slot.importId)
                            true
                        },
                    )
                    Box(
                        modifier = contentModifier
                            .size(EditorFilmstripMetrics.ContentSize)
                            .semantics {
                                contentDescription = "$failedLabel. $failedHint"
                                customActions = a11yActions
                            }
                            .testTag("progressiveImportSlot:${slot.importId}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(frameShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .clickable { presentation.onRetry(slot.importId) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = failedLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 2.dp),
                            )
                        }
                        PendingFailedRemoveChip(
                            label = removeLabel,
                            onRemove = { presentation.onRemove(slot.importId) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .testTag("progressiveImportRemove:${slot.importId}"),
                        )
                    }
                }
            }
        },
    )
}

/** Compact remove control for Failed recovery only — never drawn on Ready or Pending. */
@Composable
private fun PendingFailedRemoveChip(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(1.dp)
            .size(14.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .clickable(onClick = onRemove)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = SharedProductDrawables.closePainter(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(10.dp),
        )
    }
}

@Composable
private fun PendingCellChrome(
    pending: EditorMediaSlot.Pending,
    reduceMotion: Boolean,
    nowMs: () -> Long,
    shape: RoundedCornerShape,
    onPrioritize: () -> Unit = {},
    onCellPxMeasured: (Int) -> Unit,
) {
    // Phase is derived from **state-owned** attemptStartedAtMs + attemptId, not composable recycle.
    var phase by remember(pending.importId, pending.attemptId) {
        mutableStateOf(
            ProgressivePendingChrome.phase(
                (nowMs() - pending.attemptStartedAtMs).coerceAtLeast(0L),
                reduceMotion,
            ),
        )
    }
    LaunchedEffect(pending.importId, pending.attemptId, reduceMotion, pending.attemptStartedAtMs) {
        val showAt = if (reduceMotion) {
            ProgressivePendingChrome.SILENT_UNTIL_MS
        } else {
            ProgressivePendingChrome.LOADING_FROM_MS
        }
        val elapsed0 = (nowMs() - pending.attemptStartedAtMs).coerceAtLeast(0L)
        phase = ProgressivePendingChrome.phase(elapsed0, reduceMotion)
        if (elapsed0 >= showAt) return@LaunchedEffect
        delay(showAt - elapsed0)
        phase = ProgressivePendingChrome.phase(
            (nowMs() - pending.attemptStartedAtMs).coerceAtLeast(0L),
            reduceMotion,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val px = maxOf(size.width, size.height)
                if (px > 0) onCellPxMeasured(px)
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onPrioritize)
            .testTag("progressivePendingChrome:${pending.importId}:$phase"),
        contentAlignment = Alignment.Center,
    ) {
        when (phase) {
            ProgressivePendingChrome.Phase.Silent -> Unit
            ProgressivePendingChrome.Phase.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .testTag("progressivePendingSpinner:${pending.importId}"),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            ProgressivePendingChrome.Phase.StaticLoading -> {
                // Reduced motion: static non-animated ring (never continuous spin).
                CircularProgressIndicator(
                    progress = { 0.28f },
                    modifier = Modifier
                        .size(18.dp)
                        .testTag("progressivePendingStatic:${pending.importId}"),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                )
            }
        }
    }
}
