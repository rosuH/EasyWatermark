package me.rosuh.easywatermark.ui.compose

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import me.rosuh.easywatermark.data.model.Sticker
import me.rosuh.easywatermark.data.model.WaterMark
import java.io.ByteArrayOutputStream

@Composable
fun IconOption(
    waterMark: WaterMark,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    useLargeDialog: Boolean = false,
    stickers: List<Sticker> = emptyList(),
    onApplySticker: (Sticker) -> Unit = {},
    onDeleteSticker: (Sticker) -> Unit = {},
    onIconPicked: (Uri) -> Unit,
    onApplyBuiltInPng: (key: String, bytes: ByteArray) -> Unit = { _, _ -> },
    onClear: (() -> Unit)? = null,
    onDeleteStickers: ((List<Sticker>) -> Unit)? = null,
) {
    val singlePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(onIconPicked) },
    )
    StickerPanelOnIcon(
        waterMark = waterMark,
        userStickers = stickers,
        modifier = modifier,
        useLargeDialog = useLargeDialog,
        onAdd = {
            singlePhotoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        },
        onApplyBuiltIn = { builtIn, bitmap ->
            onApplyBuiltInPng(builtIn.key, bitmap.toPngBytes())
        },
        onApplyUser = onApplySticker,
        onDeleteUser = onDeleteSticker,
        onClear = onClear,
        onDeleteUsers = onDeleteStickers,
    )
}

private fun ImageBitmap.toPngBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}
