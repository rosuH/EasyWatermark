package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rosuh.easywatermark.data.model.WatermarkFontRef
import me.rosuh.easywatermark.font.FontEntry
import me.rosuh.easywatermark.font.FontImportProgress
import me.rosuh.easywatermark.font.FontSourceTab
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.cd_font_close
import me.rosuh.easywatermark.shared.generated.resources.cd_font_selected
import me.rosuh.easywatermark.shared.generated.resources.font_android_legacy_system
import me.rosuh.easywatermark.shared.generated.resources.font_candidate_loading
import me.rosuh.easywatermark.shared.generated.resources.font_import_cancel
import me.rosuh.easywatermark.shared.generated.resources.font_import_from_folder
import me.rosuh.easywatermark.shared.generated.resources.font_import_hide_failures
import me.rosuh.easywatermark.shared.generated.resources.font_import_show_failures
import me.rosuh.easywatermark.shared.generated.resources.font_import_summary
import me.rosuh.easywatermark.shared.generated.resources.font_import_truncated
import me.rosuh.easywatermark.shared.generated.resources.font_imported_empty
import me.rosuh.easywatermark.shared.generated.resources.font_imported_loading
import me.rosuh.easywatermark.shared.generated.resources.font_importing
import me.rosuh.easywatermark.shared.generated.resources.font_no_system_fonts
import me.rosuh.easywatermark.shared.generated.resources.font_panel_done
import me.rosuh.easywatermark.shared.generated.resources.font_panel_title
import me.rosuh.easywatermark.shared.generated.resources.font_sample_fallback
import me.rosuh.easywatermark.shared.generated.resources.font_system_default
import me.rosuh.easywatermark.shared.generated.resources.font_system_loading
import me.rosuh.easywatermark.shared.generated.resources.font_tab_imported
import me.rosuh.easywatermark.shared.generated.resources.font_tab_system
import me.rosuh.easywatermark.shared.generated.resources.font_unavailable
import me.rosuh.easywatermark.ui.FontPanelEvent
import me.rosuh.easywatermark.ui.FontPanelUiState
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.ui.compose.DesignChoiceChips
import me.rosuh.easywatermark.ui.compose.DesignChoiceOption
import org.jetbrains.compose.resources.stringResource

internal const val FONT_PANEL_TAG = "editorFontPanel"
internal const val FONT_DEFAULT_ROW_TAG = "editorFontDefault"
internal const val FONT_IMPORT_BUTTON_TAG = "editorFontImport"
internal const val FONT_DONE_BUTTON_TAG = "editorFontDone"

@Composable
fun FontPanel(
    state: FontPanelUiState,
    onEvent: (FontPanelEvent) -> Unit,
    modifier: Modifier = Modifier,
    useLargeDialog: Boolean = false,
    sampleFamilies: Map<String, FontFamily> = emptyMap(),
) {
    val closeCd = stringResource(Res.string.cd_font_close)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (useLargeDialog) Modifier else Modifier.navigationBarsPadding())
            .padding(horizontal = 20.dp)
            .padding(bottom = 16.dp)
            .testTag(FONT_PANEL_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.font_panel_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(
                onClick = { onEvent(FontPanelEvent.Dismiss) },
                modifier = Modifier
                    .testTag("editorFontClose")
                    .semantics { contentDescription = closeCd },
            ) {
                Icon(
                    painter = SharedProductDrawables.closePainter(),
                    contentDescription = closeCd,
                )
            }
        }

        state.unavailableMessage?.let { message ->
            Text(
                text = message.ifBlank { stringResource(Res.string.font_unavailable) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("editorFontUnavailable"),
            )
        }
        state.styleHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .testTag("editorFontStyleHint"),
            )
        }

        DefaultFontRow(
            selected = state.selectedRef is WatermarkFontRef.Default && state.pendingRef == null,
            pending = state.pendingRef is WatermarkFontRef.Default,
            onClick = { onEvent(FontPanelEvent.Select(WatermarkFontRef.Default)) },
        )

        DesignChoiceChips(
            options = listOf(
                DesignChoiceOption(
                    label = stringResource(Res.string.font_tab_system),
                    value = FontSourceTab.System,
                ),
                DesignChoiceOption(
                    label = stringResource(Res.string.font_tab_imported),
                    value = FontSourceTab.Imported,
                ),
            ),
            selected = state.sourceTab,
            onSelected = { onEvent(FontPanelEvent.SourceTab(it)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("editorFontSourceTabs"),
            equalWidth = true,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val listMax = maxHeight.takeIf { it != androidx.compose.ui.unit.Dp.Infinity }
                ?.coerceAtMost(360.dp)
                ?: 360.dp
            FontListBody(
                state = state,
                sampleFamilies = sampleFamilies,
                onSelect = { onEvent(FontPanelEvent.Select(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = listMax.coerceAtLeast(120.dp)),
            )
        }

        ImportFooter(
            state = state,
            onEvent = onEvent,
        )
    }
}

@Composable
private fun DefaultFontRow(
    selected: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val selectedCd = stringResource(Res.string.cd_font_selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .testTag(FONT_DEFAULT_ROW_TAG)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "System default"
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.font_system_default),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        when {
            pending -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            selected -> Text(
                text = "✓",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { contentDescription = selectedCd },
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun FontListBody(
    state: FontPanelUiState,
    sampleFamilies: Map<String, FontFamily>,
    onSelect: (WatermarkFontRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sampleFallback = stringResource(Res.string.font_sample_fallback)
    val sample = state.sampleText.ifBlank { sampleFallback }.replace('\n', ' ')
    when (state.sourceTab) {
        FontSourceTab.System -> {
            if (state.systemListRestricted) {
                Text(
                    text = stringResource(Res.string.font_android_legacy_system),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag("editorFontLegacyNote"),
                )
            }
            when {
                state.systemLoading && state.systemFonts.isEmpty() -> {
                    StatusLine(stringResource(Res.string.font_system_loading), "editorFontSystemLoading")
                }
                state.systemError != null && state.systemFonts.isEmpty() -> {
                    StatusLine(state.systemError, "editorFontSystemError", error = true)
                }
                state.systemFonts.isEmpty() -> {
                    StatusLine(stringResource(Res.string.font_no_system_fonts), "editorFontSystemEmpty")
                }
                else -> {
                    FontEntryList(
                        entries = state.systemFonts,
                        selectedRef = state.selectedRef,
                        pendingRef = state.pendingRef,
                        sample = sample,
                        sampleFamilies = sampleFamilies,
                        onSelect = onSelect,
                        modifier = modifier.testTag("editorFontSystemList"),
                    )
                }
            }
        }
        FontSourceTab.Imported -> {
            when {
                state.importedLoading && state.importedFonts.isEmpty() -> {
                    StatusLine(stringResource(Res.string.font_imported_loading), "editorFontImportedLoading")
                }
                state.importedError != null && state.importedFonts.isEmpty() -> {
                    StatusLine(state.importedError, "editorFontImportedError", error = true)
                }
                state.importedFonts.isEmpty() -> {
                    StatusLine(stringResource(Res.string.font_imported_empty), "editorFontImportedEmpty")
                }
                else -> {
                    FontEntryList(
                        entries = state.importedFonts,
                        selectedRef = state.selectedRef,
                        pendingRef = state.pendingRef,
                        sample = sample,
                        sampleFamilies = sampleFamilies,
                        onSelect = onSelect,
                        modifier = modifier.testTag("editorFontImportedList"),
                    )
                }
            }
        }
    }
}

@Composable
private fun FontEntryList(
    entries: List<FontEntry>,
    selectedRef: WatermarkFontRef,
    pendingRef: WatermarkFontRef?,
    sample: String,
    sampleFamilies: Map<String, FontFamily>,
    onSelect: (WatermarkFontRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedCd = stringResource(Res.string.cd_font_selected)
    val loadingCd = stringResource(Res.string.font_candidate_loading)
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(entries, key = { it.ref.fingerprint() }) { entry ->
            val selected = pendingRef == null && entry.ref == selectedRef
            val pending = entry.ref == pendingRef
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(enabled = entry.available) { onSelect(entry.ref) }
                    .testTag("editorFontEntry-${entry.ref.fingerprint()}")
                    .semantics {
                        role = Role.RadioButton
                        this.selected = selected
                        contentDescription = entry.displayName
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sample,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = sampleFamilies[entry.ref.fingerprint()],
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    pending -> CircularProgressIndicator(
                        modifier = Modifier
                            .size(18.dp)
                            .semantics { contentDescription = loadingCd },
                        strokeWidth = 2.dp,
                    )
                    selected -> Text(
                        text = "✓",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { contentDescription = selectedCd },
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportFooter(
    state: FontPanelUiState,
    onEvent: (FontPanelEvent) -> Unit,
) {
    val importing = state.importProgress is FontImportProgress.Running
    val done = state.importProgress as? FontImportProgress.Done
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            importing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(Res.string.font_importing),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .testTag("editorFontImporting"),
                    )
                }
            }
            done != null -> {
                val result = done.result
                Text(
                    text = stringResource(
                        Res.string.font_import_summary,
                        result.added,
                        result.duplicates,
                        result.failed.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("editorFontImportSummary"),
                )
                result.truncateReason?.let { reason ->
                    Text(
                        text = stringResource(Res.string.font_import_truncated, reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (result.failed.isNotEmpty()) {
                    TextButton(onClick = { onEvent(FontPanelEvent.ToggleImportFailures) }) {
                        Text(
                            text = stringResource(
                                if (state.showImportFailures) {
                                    Res.string.font_import_hide_failures
                                } else {
                                    Res.string.font_import_show_failures
                                },
                            ),
                        )
                    }
                    if (state.showImportFailures) {
                        result.failed.take(8).forEach { failure ->
                            Text(
                                text = "${failure.fileName}: ${failure.reason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (importing) {
                OutlinedButton(
                    onClick = { onEvent(FontPanelEvent.CancelImport) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(Res.string.font_import_cancel))
                }
            } else {
                OutlinedButton(
                    onClick = { onEvent(FontPanelEvent.ImportFolder) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(FONT_IMPORT_BUTTON_TAG),
                ) {
                    Text(stringResource(Res.string.font_import_from_folder))
                }
            }
            Button(
                onClick = { onEvent(FontPanelEvent.Dismiss) },
                modifier = Modifier
                    .weight(1f)
                    .testTag(FONT_DONE_BUTTON_TAG),
            ) {
                Text(stringResource(Res.string.font_panel_done))
            }
        }
    }
}

@Composable
private fun StatusLine(text: String, tag: String, error: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
