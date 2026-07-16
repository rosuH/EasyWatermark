package me.rosuh.easywatermark.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import me.rosuh.easywatermark.data.model.FuncType
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.bg_avatar_dev
import me.rosuh.easywatermark.shared.generated.resources.ic_about
import me.rosuh.easywatermark.shared.generated.resources.ic_avatar_tovi
import me.rosuh.easywatermark.shared.generated.resources.ic_back
import me.rosuh.easywatermark.shared.generated.resources.ic_baseline_image_search_24
import me.rosuh.easywatermark.shared.generated.resources.ic_btn_color_picker
import me.rosuh.easywatermark.shared.generated.resources.ic_bug_report
import me.rosuh.easywatermark.shared.generated.resources.ic_close_24dp
import me.rosuh.easywatermark.shared.generated.resources.ic_func_angle
import me.rosuh.easywatermark.shared.generated.resources.ic_func_color
import me.rosuh.easywatermark.shared.generated.resources.ic_func_layour_horizontal
import me.rosuh.easywatermark.shared.generated.resources.ic_func_layout_vertical
import me.rosuh.easywatermark.shared.generated.resources.ic_func_opacity
import me.rosuh.easywatermark.shared.generated.resources.ic_func_size
import me.rosuh.easywatermark.shared.generated.resources.ic_func_sticker
import me.rosuh.easywatermark.shared.generated.resources.ic_func_text
import me.rosuh.easywatermark.shared.generated.resources.ic_func_typeface
import me.rosuh.easywatermark.shared.generated.resources.ic_gallery_item_placeholder
import me.rosuh.easywatermark.shared.generated.resources.ic_gallery_radio_button
import me.rosuh.easywatermark.shared.generated.resources.ic_go_template_list
import me.rosuh.easywatermark.shared.generated.resources.ic_log_transparent
import me.rosuh.easywatermark.shared.generated.resources.ic_logo_about_page
import me.rosuh.easywatermark.shared.generated.resources.ic_logo_tool_bar
import me.rosuh.easywatermark.shared.generated.resources.ic_open_source
import me.rosuh.easywatermark.shared.generated.resources.ic_picker_image
import me.rosuh.easywatermark.shared.generated.resources.ic_privacy_cn
import me.rosuh.easywatermark.shared.generated.resources.ic_privacy_en
import me.rosuh.easywatermark.shared.generated.resources.ic_rate
import me.rosuh.easywatermark.shared.generated.resources.ic_remove_item
import me.rosuh.easywatermark.shared.generated.resources.ic_save
import me.rosuh.easywatermark.shared.generated.resources.ic_save_done
import me.rosuh.easywatermark.shared.generated.resources.ic_template_list_item_edit
import me.rosuh.easywatermark.shared.generated.resources.ic_template_list_item_remove
import me.rosuh.easywatermark.shared.generated.resources.ic_tile_mode
import me.rosuh.easywatermark.shared.generated.resources.ic_update_log
import me.rosuh.easywatermark.shared.generated.resources.ic_version
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * S-i18n-3: product UI drawables from composeResources (not Android R.drawable).
 */
fun FuncType.toDrawableResource(): DrawableResource = when (this) {
    FuncType.Text -> Res.drawable.ic_func_text
    FuncType.Icon -> Res.drawable.ic_func_sticker
    FuncType.TileMode -> Res.drawable.ic_tile_mode
    FuncType.TextSize -> Res.drawable.ic_func_size
    FuncType.TextTypeFace -> Res.drawable.ic_func_typeface
    FuncType.Color -> Res.drawable.ic_func_color
    FuncType.Alpha -> Res.drawable.ic_func_opacity
    FuncType.Degree -> Res.drawable.ic_func_angle
    FuncType.Horizon -> Res.drawable.ic_func_layour_horizontal
    FuncType.Vertical -> Res.drawable.ic_func_layout_vertical
}

@Composable
fun FuncType.iconPainter(): Painter = painterResource(toDrawableResource())

object SharedProductDrawables {
    val about: DrawableResource get() = Res.drawable.ic_about
    val back: DrawableResource get() = Res.drawable.ic_back
    val brandLogo: DrawableResource get() = Res.drawable.ic_log_transparent
    val logoToolbar: DrawableResource get() = Res.drawable.ic_logo_tool_bar
    val logoAbout: DrawableResource get() = Res.drawable.ic_logo_about_page
    val pickerImage: DrawableResource get() = Res.drawable.ic_picker_image
    val save: DrawableResource get() = Res.drawable.ic_save
    val templateList: DrawableResource get() = Res.drawable.ic_go_template_list
    val templateEdit: DrawableResource get() = Res.drawable.ic_template_list_item_edit
    val templateDelete: DrawableResource get() = Res.drawable.ic_template_list_item_remove
    val version: DrawableResource get() = Res.drawable.ic_version
    val rate: DrawableResource get() = Res.drawable.ic_rate
    val feedback: DrawableResource get() = Res.drawable.ic_bug_report
    val updateLog: DrawableResource get() = Res.drawable.ic_update_log
    val openSource: DrawableResource get() = Res.drawable.ic_open_source
    val privacyZh: DrawableResource get() = Res.drawable.ic_privacy_cn
    val privacyEn: DrawableResource get() = Res.drawable.ic_privacy_en
    val avatarDev: DrawableResource get() = Res.drawable.bg_avatar_dev
    val avatarTovi: DrawableResource get() = Res.drawable.ic_avatar_tovi
    val close: DrawableResource get() = Res.drawable.ic_close_24dp
    val search: DrawableResource get() = Res.drawable.ic_baseline_image_search_24
    val galleryCheck: DrawableResource get() = Res.drawable.ic_gallery_radio_button
    val saveDone: DrawableResource get() = Res.drawable.ic_save_done
    val colorPicker: DrawableResource get() = Res.drawable.ic_btn_color_picker
    val galleryPlaceholder: DrawableResource get() = Res.drawable.ic_gallery_item_placeholder
    val removeItem: DrawableResource get() = Res.drawable.ic_remove_item

    @Composable fun aboutPainter(): Painter = painterResource(about)
    @Composable fun backPainter(): Painter = painterResource(back)
    @Composable fun brandLogoPainter(): Painter = painterResource(brandLogo)
    @Composable fun logoToolbarPainter(): Painter = painterResource(logoToolbar)
    @Composable fun logoAboutPainter(): Painter = painterResource(logoAbout)
    @Composable fun pickerImagePainter(): Painter = painterResource(pickerImage)
    @Composable fun savePainter(): Painter = painterResource(save)
    @Composable fun templateListPainter(): Painter = painterResource(templateList)
    @Composable fun templateEditPainter(): Painter = painterResource(templateEdit)
    @Composable fun templateDeletePainter(): Painter = painterResource(templateDelete)
    @Composable fun versionPainter(): Painter = painterResource(version)
    @Composable fun ratePainter(): Painter = painterResource(rate)
    @Composable fun feedbackPainter(): Painter = painterResource(feedback)
    @Composable fun updateLogPainter(): Painter = painterResource(updateLog)
    @Composable fun openSourcePainter(): Painter = painterResource(openSource)
    @Composable fun privacyZhPainter(): Painter = painterResource(privacyZh)
    @Composable fun privacyEnPainter(): Painter = painterResource(privacyEn)
    @Composable fun avatarDevPainter(): Painter = painterResource(avatarDev)
    @Composable fun avatarToviPainter(): Painter = painterResource(avatarTovi)
    @Composable fun closePainter(): Painter = painterResource(close)
    @Composable fun searchPainter(): Painter = painterResource(search)
    @Composable fun galleryCheckPainter(): Painter = painterResource(galleryCheck)
    @Composable fun saveDonePainter(): Painter = painterResource(saveDone)
    @Composable fun colorPickerPainter(): Painter = painterResource(colorPicker)
    @Composable fun galleryPlaceholderPainter(): Painter = painterResource(galleryPlaceholder)
    @Composable fun removeItemPainter(): Painter = painterResource(removeItem)
}
