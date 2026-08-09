package me.rosuh.easywatermark.ui.compose

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.action_pick
import me.rosuh.easywatermark.shared.generated.resources.cd_add_more_images
import me.rosuh.easywatermark.shared.generated.resources.cd_back
import me.rosuh.easywatermark.shared.generated.resources.tips_gallery_partial_access
import me.rosuh.easywatermark.shared.generated.resources.tips_gallery_partial_access_settings
import me.rosuh.easywatermark.ui.GalleryDialogShell
import me.rosuh.easywatermark.ui.Image as GalleryImage
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.ui.theme.DesignChipSelected
import me.rosuh.easywatermark.utils.ktx.hasPartialMediaAccessOnly
import me.rosuh.easywatermark.utils.ktx.toUri
import org.jetbrains.compose.resources.stringResource

@Preview
@Composable
private fun GalleryDialogPreview() {
    GalleryDialog(emptyList(), {}, {})
}

@Composable
fun GalleryDialog(
    images: List<GalleryImage>,
    onLoadImages: () -> Unit,
    onDismiss: (selectedImages: List<GalleryImage>) -> Unit = {},
    onPickImageViaSystem: () -> Unit = {},
) {
    // Cell is ~1/4 screen; decode only a small MediaStore thumb (not the full still).
    val density = LocalDensity.current
    val thumbPx = remember(density.density) {
        with(density) { 96.dp.roundToPx() }.coerceIn(128, 256)
    }
    val context = LocalContext.current
    val imageLoader = remember(context) { context.galleryImageLoader() }
    val placeholderPainter = SharedProductDrawables.galleryPlaceholderPainter()

    val loadImages = rememberUpdatedState(onLoadImages)
    var partialOnly by remember { mutableStateOf(context.hasPartialMediaAccessOnly()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                partialOnly = context.hasPartialMediaAccessOnly()
                loadImages.value()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val thumbnail: @Composable (GalleryImage, String, Modifier) -> Unit =
        remember(thumbPx, imageLoader, placeholderPainter) {
            { image, contentDescription, modifier ->
                GalleryThumbnail(
                    image = image,
                    contentDescription = contentDescription,
                    modifier = modifier,
                    thumbPx = thumbPx,
                    imageLoader = imageLoader,
                    placeholderPainter = placeholderPainter,
                )
            }
        }

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
        backHandler = { onBack -> BackHandler(onBack = onBack) },
        onLoadImages = onLoadImages,
        onDismiss = onDismiss,
        onPickImageViaSystem = onPickImageViaSystem,
        banner = if (!partialOnly) null else {
            {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(Res.string.tips_gallery_partial_access),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            },
                        ) {
                            Text(stringResource(Res.string.tips_gallery_partial_access_settings))
                        }
                    }
                }
            }
        },
        thumbnail = thumbnail,
    )
}

@Composable
private fun GalleryThumbnail(
    image: GalleryImage,
    contentDescription: String,
    modifier: Modifier,
    thumbPx: Int,
    imageLoader: ImageLoader,
    placeholderPainter: Painter,
) {
    val context = LocalContext.current
    val request = remember(image.uri.value, thumbPx) {
        ImageRequest.Builder(context)
            .data(MediaStoreThumbnail(image.uri.toUri(), thumbPx))
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.DISABLED) // MediaStore already thumbs; skip double disk cache
            .crossfade(false)
            .build()
    }
    Box(
        modifier = modifier.background(DesignChipSelected),
    ) {
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            imageLoader = imageLoader,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholder = placeholderPainter,
            error = placeholderPainter,
        )
    }
}
