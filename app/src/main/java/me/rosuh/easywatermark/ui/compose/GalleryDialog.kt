package me.rosuh.easywatermark.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.placeholder
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.GalleryDialogShell
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.utils.ktx.toUri


@Preview
@Composable
fun GalleryDialogPreview() {
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
        title = stringResource(R.string.action_pick),
        closeIcon = painterResource(R.drawable.ic_close_24dp),
        searchIcon = painterResource(R.drawable.ic_baseline_image_search_24),
        checkIcon = painterResource(R.drawable.ic_gallery_radio_button),
        selectedCountIcon = painterResource(R.drawable.ic_save_done),
        closeContentDescription = "close dialog",
        searchContentDescription = "search",
        selectedCountContentDescription = "add",
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
            .placeholder(R.drawable.ic_gallery_item_placeholder_container)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    )
}
