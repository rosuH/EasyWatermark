package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.style_alpha
import me.rosuh.easywatermark.shared.generated.resources.title_horizon_layout
import me.rosuh.easywatermark.shared.generated.resources.title_text_color
import me.rosuh.easywatermark.shared.generated.resources.title_text_rotate
import me.rosuh.easywatermark.shared.generated.resources.title_text_size
import me.rosuh.easywatermark.shared.generated.resources.title_text_style
import me.rosuh.easywatermark.shared.generated.resources.title_tile_mode
import me.rosuh.easywatermark.shared.generated.resources.title_vertical_layout
import me.rosuh.easywatermark.shared.generated.resources.water_mark_mode_image
import me.rosuh.easywatermark.shared.generated.resources.water_mark_mode_text
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** S-i18n-2: product option labels from composeResources (not Android @StringRes). */
fun FuncType.toStringResource(): StringResource = when (this) {
    FuncType.Text -> Res.string.water_mark_mode_text
    FuncType.Icon -> Res.string.water_mark_mode_image
    FuncType.TileMode -> Res.string.title_tile_mode
    FuncType.TextSize -> Res.string.title_text_size
    FuncType.TextTypeFace -> Res.string.title_text_style
    FuncType.Color -> Res.string.title_text_color
    FuncType.Alpha -> Res.string.style_alpha
    FuncType.Degree -> Res.string.title_text_rotate
    FuncType.Horizon -> Res.string.title_horizon_layout
    FuncType.Vertical -> Res.string.title_vertical_layout
}

@Composable
fun FuncType.label(): String = stringResource(toStringResource())
