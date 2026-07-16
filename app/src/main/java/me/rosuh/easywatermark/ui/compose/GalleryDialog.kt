package me.rosuh.easywatermark.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.action_pick
import me.rosuh.easywatermark.shared.generated.resources.cd_add_more_images
import me.rosuh.easywatermark.shared.generated.resources.cd_back
import me.rosuh.easywatermark.ui.GalleryDialogShell
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.utils.ktx.toUri
import org.jetbrains.compose.resources.stringResource

@Preview
@Composable
private fun GalleryDialogPreview() {
    GalleryDialog(emptyList(), {}, {}, { _, _, _ -> })
}

@Composable
fun GalleryDialog(
    images: List<Image>,
    onLoadImages: () -> Unit,
    onDismiss: (selected: Boolean) -> Unit = {},
    onImageSelected: (image: Image, index: Int, isSelected: Boolean) -> Unit,
    onPickImageViaSystem: () -> Unit = {},
) {
    GalleryDialogShell(
        images = images,
        title = stringResource(Res.string.action_pick),
        closeIcon = SharedProductDrawables.closePainter(),
        searchIcon = SharedProductDrawables.searchPainter(),
        checkIcon = SharedProductDrawables.galleryCheckPainter(),
        selectedCountIcon = SharedProductDrawables.saveDonePainter(),
        closeContentDescription = stringResource(Res.string.cd_back),
        searchContentDescription = stringResource(Res.string.cd_add_more_images),
        selectedCountContentDescription = stringResource(Res.string.cd_add_more_images),
        backHandler = { onBack ->
            BackHandler(onBack = onBack)
        },
        onLoadImages = onLoadImages,
        onDismiss = onDismiss,
        onImageSelected = onImageSelected,
        onPickImageViaSystem = onPickImageViaSystem,
    ) { image, contentDescription, modifier ->
        GalleryThumbnail(
            image = image,
            contentDescription = contentDescription,
            modifier = modifier,
        )
    }
}

@Composable
private fun GalleryThumbnail(
    image: Image,
    contentDescription: String,
    modifier: Modifier,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(image.uri.toUri())
            .allowRgb565(true)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = SharedProductDrawables.galleryPlaceholderPainter(),
    )
}
