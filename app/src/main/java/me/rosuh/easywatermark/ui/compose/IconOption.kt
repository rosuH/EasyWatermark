package me.rosuh.easywatermark.ui.compose

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.action_pick
import me.rosuh.easywatermark.shared.generated.resources.water_mark_mode_image
import me.rosuh.easywatermark.ui.image.ProductAsyncImage
import me.rosuh.easywatermark.ui.image.ProductThumb
import org.jetbrains.compose.resources.stringResource

@Preview
@Composable
private fun IconOptionPreview() {
    IconOption(
        waterMark = WaterMark.default,
    ) { }
}

@Composable
fun IconOption(
    waterMark: WaterMark,
    modifier: Modifier = Modifier,
    // Keep the transient picker Uri inside the Android host until it has been copied to the
    // app-private icon store. Only the resulting app-owned MediaRef enters shared config.
    onIconPicked: (Uri) -> Unit,
) {
    val singlePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(onIconPicked) })
    IconWatermarkOption(
        hasIcon = waterMark.iconUri.isEmpty().not(),
        pickLabel = stringResource(Res.string.action_pick),
        modifier = modifier,
        onPick = {
            singlePhotoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        },
        preview = {
            // ADR-0028: ProductThumb (app-owned file / content ref) — not bare Uri AsyncImage.
            ProductAsyncImage(
                thumb = ProductThumb(
                    ref = waterMark.iconUri,
                    maxEdgePx = ProductThumb.UI_THUMB_MAX_EDGE,
                ),
                contentScale = ContentScale.Crop,
                contentDescription = stringResource(Res.string.water_mark_mode_image),
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
