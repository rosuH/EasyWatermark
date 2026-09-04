package me.rosuh.easywatermark.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rosuh.easywatermark.data.model.WatermarkTileMode
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.tile_mode_title_decal
import me.rosuh.easywatermark.shared.generated.resources.tile_mode_title_repeat
import org.jetbrains.compose.resources.stringResource

/** S-i18n-2: labels from composeResources. Design chips (not Material SegmentedButton). */
@Composable
fun TileMode(
    mode: WatermarkTileMode,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (WatermarkTileMode) -> Unit,
) {
    DesignChoiceChips(
        options = listOf(
            DesignChoiceOption(
                label = stringResource(Res.string.tile_mode_title_repeat),
                value = WatermarkTileMode.REPEAT,
            ),
            DesignChoiceOption(
                label = stringResource(Res.string.tile_mode_title_decal),
                value = WatermarkTileMode.CLAMP,
            ),
        ),
        selected = mode,
        onSelected = onValueChange,
        modifier = modifier,
        enabled = enabled,
    )
}
