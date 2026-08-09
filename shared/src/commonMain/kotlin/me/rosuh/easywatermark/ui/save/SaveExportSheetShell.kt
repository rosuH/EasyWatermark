package me.rosuh.easywatermark.ui.save

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.ImageFormat
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.about_title_output
import me.rosuh.easywatermark.shared.generated.resources.dialog_open_in_gallery
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_cancel_export
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_config_format
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_config_quality
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_counts
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_retry_failed
import me.rosuh.easywatermark.shared.generated.resources.ic_export_count_fail
import me.rosuh.easywatermark.shared.generated.resources.ic_export_count_success
import me.rosuh.easywatermark.shared.generated.resources.ic_export_count_total
import me.rosuh.easywatermark.shared.generated.resources.privacy_confidence_export
import me.rosuh.easywatermark.shared.generated.resources.tips_images_selected
import me.rosuh.easywatermark.ui.compose.EwmModalBottomSheet
import me.rosuh.easywatermark.ui.theme.DesignBrand
import me.rosuh.easywatermark.ui.theme.DesignEditorBg
import me.rosuh.easywatermark.ui.theme.EwmTheme
import me.rosuh.easywatermark.ui.theme.currentMotionPolicy
import me.rosuh.easywatermark.ui.theme.motionDurationMs
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Shared CMP shell for the save/export modal sheet.
 *
 * Idle: format/quality + preview thumbs + primary CTA (no orphan icon chrome).
 * Exporting/finished: fixed icon counts (total / success / fail) — no Processed prose,
 * no “Saved N to Destination…” outcome line. a11y + I0 tags stay on zero-size nodes
 * when not painted. Thumb progress uses [ExportProgressOverlay].
 *
 * @param exportTotalCount fixed list size for 总量 icon (blank/0 = hide count row).
 * @param exportSuccessCount success count for ✓ icon.
 * @param exportFailureCount failure count for ✕ icon.
 * @param countsLine retained for I0 API/a11y CD; not painted as prose.
 * @param outcomeDetailLine retained for I0 API/a11y CD; never painted (owner: no Saved-to-destination).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SaveExportSheetShell(
    items: List<T>,
    selectedFormat: ImageFormat,
    quality: Int,
    primaryActionLabel: String,
    primaryActionEnabled: Boolean = true,
    showOpenGallery: Boolean = true,
    exportListSubtitle: String = "",
    imageCount: Int = items.size,
    /** D5: true while Session export is running — enables Cancel. */
    isExporting: Boolean = false,
    showCancelButton: Boolean = isExporting,
    onCancelClick: (() -> Unit)? = null,
    showRetryFailedButton: Boolean = false,
    onRetryFailedClick: (() -> Unit)? = null,
    statusContentDescription: String = exportListSubtitle,
    destinationLine: String = "",
    filenamePolicyLine: String = "",
    countsLine: String = "",
    outcomeDetailLine: String = "",
    exportTotalCount: Int = 0,
    exportSuccessCount: Int = 0,
    exportFailureCount: Int = 0,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onFormatClick: (newFormat: ImageFormat) -> Unit,
    onQualityChange: (Int) -> Unit,
    onExportClick: () -> Unit,
    onOpenGalleryClick: () -> Unit,
    itemKey: ((T) -> Any)? = null,
    thumbnail: @Composable (item: T, modifier: Modifier) -> Unit,
) {
    val outputTitle = stringResource(Res.string.about_title_output)
    val formatLabel = stringResource(Res.string.dialog_save_config_format)
    val qualityLabel = stringResource(Res.string.dialog_save_config_quality)
    val emptyPreviewText = stringResource(Res.string.tips_images_selected, imageCount)
    val openGalleryLabel = stringResource(Res.string.dialog_open_in_gallery)
    val cancelLabel = stringResource(Res.string.dialog_save_cancel_export)
    val retryLabel = stringResource(Res.string.dialog_save_retry_failed)
    val privacyExport = stringResource(Res.string.privacy_confidence_export)
    val statusCd = statusContentDescription.ifBlank { exportListSubtitle }

    val hasDestination = destinationLine.isNotBlank()
    val hasFilenamePolicy = filenamePolicyLine.isNotBlank()
    val hasOutcome = outcomeDetailLine.isNotBlank()
    val total = exportTotalCount.coerceAtLeast(0)
    val success = exportSuccessCount.coerceAtLeast(0)
    val failure = exportFailureCount.coerceAtLeast(0)
    // Prefer structured ints; fall back to countsLine presence for older hosts mid-migrate.
    val showCounts = isExporting || total > 0 || countsLine.isNotBlank()
    // Always reserve count-row height when the sheet has a selection — avoids ModalBottomSheet
    // height jumps when counts appear at export start / hide on idle re-open.
    val reserveCountsSlot = imageCount > 0 || total > 0 || showCounts
    val fadeMs = motionDurationMs(currentMotionPolicy(), EwmTheme.motion.contentSizeMs)
    val countsAlpha by animateFloatAsState(
        targetValue = if (showCounts) 1f else 0f,
        animationSpec = tween(durationMillis = fadeMs, easing = FastOutSlowInEasing),
        label = "exportCountsAlpha",
    )

    val destinationCd = buildString {
        if (hasDestination) append(destinationLine)
        if (hasFilenamePolicy) {
            if (isNotEmpty()) append(". ")
            append(filenamePolicyLine)
        }
    }
    val countsCd = countsLine.ifBlank {
        if (total > 0 || success > 0 || failure > 0) {
            stringResource(Res.string.dialog_save_export_counts, total, success, failure)
        } else {
            ""
        }
    }

    val exporting = rememberUpdatedState(isExporting)
    val dismiss = rememberUpdatedState(onDismiss)
    EwmModalBottomSheet(
        onDismissRequest = {
            // Block dismiss while exporting (and during the eager isSaving frame before UI catches up).
            if (!exporting.value) dismiss.value()
        },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { value ->
                // Prevent scrim/drag hide while exporting — stops Retry-under-finger sheet flash.
                !(exporting.value && value == SheetValue.Hidden)
            },
        ),
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            SaveExportOptionsSection(
                title = outputTitle,
                formatLabel = formatLabel,
                qualityLabel = qualityLabel,
                selectedFormat = selectedFormat,
                quality = quality,
                enabled = primaryActionEnabled && !isExporting,
                onFormatClick = onFormatClick,
                onQualityChange = onQualityChange,
            )

            // a11y + structural tags only — no idle visual icon strip.
            Spacer(
                Modifier
                    .size(0.dp)
                    .testTag("sharedComposeExportPrivacyConfidence")
                    .semantics { contentDescription = privacyExport },
            )
            if (hasDestination || hasFilenamePolicy) {
                Spacer(
                    Modifier
                        .size(0.dp)
                        .testTag("sharedComposeExportDestination")
                        .semantics { contentDescription = destinationCd },
                )
                Spacer(
                    Modifier
                        .size(0.dp)
                        .testTag("sharedComposeExportFilenamePolicy")
                        .semantics {
                            contentDescription =
                                if (hasFilenamePolicy) filenamePolicyLine else destinationCd
                        },
                )
            }
            Spacer(
                Modifier
                    .size(0.dp)
                    .testTag("sharedComposeExportStatus")
                    .semantics {
                        contentDescription = statusCd
                        liveRegion = LiveRegionMode.Polite
                    },
            )
            // outcomeDetailLine never painted (no Saved-to-destination); keep tag + CD for I0.
            if (hasOutcome) {
                Spacer(
                    Modifier
                        .size(0.dp)
                        .testTag("sharedComposeExportOutcomeDetail")
                        .semantics { contentDescription = outcomeDetailLine },
                )
            }

            if (reserveCountsSlot) {
                val displayTotal = when {
                    total > 0 -> total
                    imageCount > 0 -> imageCount
                    else -> (success + failure).coerceAtLeast(0)
                }
                // Fixed-height slot: fade chips in place (no slide/scale layout pass on the sheet).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(CountRowHeight),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = countsAlpha }
                            .testTag("sharedComposeExportCounts")
                            .semantics {
                                contentDescription = countsCd.ifBlank { statusCd }
                                if (showCounts) liveRegion = LiveRegionMode.Polite
                            },
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ExportCountChip(
                            painter = painterResource(Res.drawable.ic_export_count_total),
                            value = displayTotal,
                            contentDescription = "total $displayTotal",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ExportCountChip(
                            painter = painterResource(Res.drawable.ic_export_count_success),
                            value = success,
                            contentDescription = "success $success",
                            tint = DesignBrand,
                        )
                        ExportCountChip(
                            painter = painterResource(Res.drawable.ic_export_count_fail),
                            value = failure,
                            contentDescription = "failed $failure",
                            tint = if (failure > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            } else if (countsCd.isNotBlank()) {
                Spacer(
                    Modifier
                        .size(0.dp)
                        .testTag("sharedComposeExportCounts")
                        .semantics { contentDescription = countsCd },
                )
            }

            SaveExportPreviewBox(
                items = items,
                emptyText = emptyPreviewText,
                itemKey = itemKey,
                thumbnail = thumbnail,
                modifier = Modifier.padding(top = 20.dp),
            )

            // Fixed primary + secondary action chrome so Cancel/Retry/Share/Open-gallery
            // never change ModalBottomSheet measured height.
            Spacer(Modifier.height(28.dp))
            if (showCancelButton && onCancelClick != null) {
                OutlinedButton(
                    onClick = onCancelClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PrimaryButtonHeight)
                        .testTag("sharedComposeExportCancel")
                        .semantics { contentDescription = cancelLabel },
                    shape = RectangleShape,
                ) {
                    Text(cancelLabel)
                }
            } else {
                Button(
                    onClick = onExportClick,
                    enabled = primaryActionEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(PrimaryButtonHeight)
                        .testTag("sharedComposeExportPrimary")
                        .semantics { contentDescription = primaryActionLabel },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DesignBrand,
                        contentColor = DesignEditorBg,
                        disabledContainerColor = DesignBrand.copy(alpha = 0.4f),
                        disabledContentColor = DesignEditorBg.copy(alpha = 0.6f),
                    ),
                ) {
                    Text(primaryActionLabel)
                }
            }

            // Always reserve secondary column (Retry + Open gallery) — empty Spacer when idle.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SecondaryActionsHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onRetryFailedClick != null && showRetryFailedButton && !isExporting) {
                    OutlinedButton(
                        onClick = onRetryFailedClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .height(PrimaryButtonHeight)
                            .testTag("sharedComposeExportRetryFailed")
                            .semantics { contentDescription = retryLabel },
                        shape = RectangleShape,
                    ) {
                        Text(retryLabel)
                    }
                } else {
                    Spacer(Modifier.height(12.dp + PrimaryButtonHeight))
                }
                if (showOpenGallery) {
                    TextButton(
                        onClick = onOpenGalleryClick,
                        modifier = Modifier.padding(top = 4.dp),
                        shape = RectangleShape,
                    ) {
                        Text(text = openGalleryLabel)
                    }
                }
            }
        }
    }
}

private val CountRowHeight = 28.dp
private val PrimaryButtonHeight = 48.dp
/** Retry (12+48) + open-gallery text row ≈ 12+48+40. */
private val SecondaryActionsHeight = 100.dp

@Composable
private fun ExportCountChip(
    painter: Painter,
    value: Int,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // No per-tick scale pulse: each success used to restart Animatable on the count row while
    // every thumb also recomposed via exportTick — main-thread jank. Numbers update in place.
    Row(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
