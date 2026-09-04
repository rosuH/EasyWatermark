package me.rosuh.easywatermark.ui.compose

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rosuh.easywatermark.ui.theme.EwmTheme

/**
 * Product ModalBottomSheet: olive panel chrome from [EwmTheme.panel] /
 * [me.rosuh.easywatermark.ui.theme.AppTheme] shapes.
 *
 * Prefer this over raw [ModalBottomSheet] for editor product sheets so shape / DesignEditorBg /
 * zero elevation cannot regress to stock M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EwmModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = EwmTheme.panel.shape,
        containerColor = EwmTheme.panel.containerColor,
        tonalElevation = EwmTheme.panel.tonalElevation,
        content = content,
    )
}
