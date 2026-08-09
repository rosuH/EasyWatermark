package me.rosuh.easywatermark.ui.save

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
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
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_export_list_title
import me.rosuh.easywatermark.shared.generated.resources.dialog_save_retry_failed
import me.rosuh.easywatermark.shared.generated.resources.ic_privacy_shield
import me.rosuh.easywatermark.shared.generated.resources.ic_save
import me.rosuh.easywatermark.shared.generated.resources.ic_save_done
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
 * S-i18n-2: labels from [Res].
 * D5: optional Cancel / Retry failed + progress semantics (not color-only).
 * I0: optional destination / filename-policy / outcome-detail lines (host-localized).
 * I4: privacy confidence as icon residual signal (on-device + EXIF strip / ADR-0009);
 *     full prose stays in contentDescription for a11y.
 *
 * Visual stance: less body copy, more whitespace; status via icons + [ExportProgressOverlay]
 * motion on thumbs; live regions retained for progress/outcome.
 *
 * @param exportListSubtitle argument for [Res.string.dialog_save_export_list_title] (e.g. result summary).
 * @param imageCount used for empty-preview plural string when [items] is empty.
 * @param statusContentDescription screen-reader announcement for progress/final outcome.
 * @param destinationLine host-supplied destination summary (blank = hide).
 * @param filenamePolicyLine host-supplied filename policy (blank = hide).
 * @param countsLine optional distinct processed/succeeded/failed line (blank = hide).
 * @param outcomeDetailLine success-where or localized error detail (blank = hide).
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
    val exportListTitle = stringResource(Res.string.dialog_save_export_list_title, exportListSubtitle)
    val emptyPreviewText = stringResource(Res.string.tips_images_selected, imageCount)
    val openGalleryLabel = stringResource(Res.string.dialog_open_in_gallery)
    val cancelLabel = stringResource(Res.string.dialog_save_cancel_export)
    val retryLabel = stringResource(Res.string.dialog_save_retry_failed)
    val privacyExport = stringResource(Res.string.privacy_confidence_export)
    val statusCd = statusContentDescription.ifBlank { exportListSubtitle }

    val hasDestination = destinationLine.isNotBlank()
    val hasFilenamePolicy = filenamePolicyLine.isNotBlank()
    val hasCounts = countsLine.isNotBlank()
    val hasOutcome = outcomeDetailLine.isNotBlank()
    // Calm idle sheet: status prose only while exporting or when hosts surface counts/outcome.
    val showStatusDetail = isExporting || hasCounts || hasOutcome
    val fadeMs = motionDurationMs(currentMotionPolicy(), EwmTheme.motion.contentSizeMs)
    val fadeSpec = tween<Float>(durationMillis = fadeMs, easing = FastOutSlowInEasing)

    // Destination + policy stay available to a11y even when not painted as body text.
    val destinationCd = buildString {
        if (hasDestination) append(destinationLine)
        if (hasFilenamePolicy) {
            if (isNotEmpty()) append(". ")
            append(filenamePolicyLine)
        }
    }

    EwmModalBottomSheet(
        onDismissRequest = {
            if (!isExporting) onDismiss()
        },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
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

            // Icon meta strip: privacy residual + destination (prose moved to CDs).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_privacy_shield),
                    contentDescription = privacyExport,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .testTag("sharedComposeExportPrivacyConfidence")
                        .semantics { contentDescription = privacyExport },
                )
                if (hasDestination || hasFilenamePolicy) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_save),
                        contentDescription = destinationCd,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(22.dp)
                            .testTag("sharedComposeExportDestination")
                            .semantics { contentDescription = destinationCd },
                    )
                    // Filename policy shares destination icon CD; keep tag for I0 contract.
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
                Spacer(Modifier.weight(1f))
                // Status live region lives on this compact status glyph (not a title wall).
                Icon(
                    painter = painterResource(
                        if (hasOutcome && !isExporting) {
                            Res.drawable.ic_save_done
                        } else {
                            Res.drawable.ic_save
                        },
                    ),
                    contentDescription = statusCd,
                    tint = when {
                        isExporting -> DesignBrand
                        hasOutcome -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    },
                    modifier = Modifier
                        .size(22.dp)
                        .semantics {
                            contentDescription = statusCd
                            liveRegion = LiveRegionMode.Polite
                        }
                        .testTag("sharedComposeExportStatus"),
                )
            }

            AnimatedVisibility(
                visible = showStatusDetail,
                enter = fadeIn(animationSpec = fadeSpec),
                exit = fadeOut(animationSpec = fadeSpec),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    if (isExporting || exportListSubtitle.isNotBlank()) {
                        Text(
                            text = if (isExporting) exportListSubtitle else exportListTitle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = statusCd
                                    liveRegion = LiveRegionMode.Polite
                                },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (hasCounts) {
                        Text(
                            text = countsLine,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .semantics {
                                    contentDescription = countsLine
                                    if (isExporting) liveRegion = LiveRegionMode.Polite
                                }
                                .testTag("sharedComposeExportCounts"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasOutcome) {
                        Text(
                            text = outcomeDetailLine,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .testTag("sharedComposeExportOutcomeDetail"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            // When status block is hidden, still expose counts/outcome tags if hosts send them
            // (structural contract) — zero-size semantics nodes only if lines blank stay untagged.
            if (!showStatusDetail) {
                if (hasCounts) {
                    Spacer(
                        Modifier
                            .size(0.dp)
                            .testTag("sharedComposeExportCounts")
                            .semantics { contentDescription = countsLine },
                    )
                }
                if (hasOutcome) {
                    Spacer(
                        Modifier
                            .size(0.dp)
                            .testTag("sharedComposeExportOutcomeDetail")
                            .semantics { contentDescription = outcomeDetailLine },
                    )
                }
            }

            SaveExportPreviewBox(
                items = items,
                emptyText = emptyPreviewText,
                itemKey = itemKey,
                thumbnail = thumbnail,
                modifier = Modifier.padding(top = 8.dp),
            )

            if (showCancelButton && onCancelClick != null) {
                OutlinedButton(
                    onClick = onCancelClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp)
                        .height(48.dp)
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
                        .padding(top = 28.dp)
                        .height(48.dp)
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

            if (showRetryFailedButton && onRetryFailedClick != null && !isExporting) {
                OutlinedButton(
                    onClick = onRetryFailedClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(48.dp)
                        .testTag("sharedComposeExportRetryFailed")
                        .semantics { contentDescription = retryLabel },
                    shape = RectangleShape,
                ) {
                    Text(retryLabel)
                }
            }

            if (showOpenGallery) {
                TextButton(
                    onClick = onOpenGalleryClick,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 4.dp, bottom = 12.dp),
                    shape = RectangleShape,
                ) {
                    Text(text = openGalleryLabel)
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
