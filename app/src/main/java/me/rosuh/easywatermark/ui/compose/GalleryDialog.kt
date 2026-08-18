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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rosuh.easywatermark.shared.generated.resources.Res
import me.rosuh.easywatermark.shared.generated.resources.action_pick
import me.rosuh.easywatermark.shared.generated.resources.cd_add_more_images
import me.rosuh.easywatermark.shared.generated.resources.cd_back
import me.rosuh.easywatermark.shared.generated.resources.tips_gallery_partial_access
import me.rosuh.easywatermark.shared.generated.resources.tips_gallery_partial_access_settings
import me.rosuh.easywatermark.ui.GalleryDialogShell
import me.rosuh.easywatermark.ui.Image as GalleryImage
import me.rosuh.easywatermark.ui.SharedProductDrawables
import me.rosuh.easywatermark.ui.image.ProductAsyncImage
import me.rosuh.easywatermark.ui.image.ProductThumb
import me.rosuh.easywatermark.ui.image.ProductThumbLoadPolicy
import me.rosuh.easywatermark.ui.theme.DesignChipSelected
import me.rosuh.easywatermark.utils.ktx.hasPartialMediaAccessOnly
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
    // Cell is ~1/4 screen; ADR-0028 ProductThumb → MediaStore Fetcher (shared UI edge).
    val density = LocalDensity.current
    val thumbPx = remember(density.density) {
        with(density) { 96.dp.roundToPx() }.coerceIn(
            ProductThumb.UI_THUMB_MAX_EDGE,
            256,
        )
    }
    val context = LocalContext.current
    val placeholderPainter = SharedProductDrawables.galleryPlaceholderPainter()

    val loadImages = rememberUpdatedState(onLoadImages)
    var partialOnly by remember { mutableStateOf(context.hasPartialMediaAccessOnly()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Registering against an already-RESUMED owner replays ON_RESUME immediately, which
        // used to fire a second full-library MediaStore query on top of the shell's first-
        // composition load. Only re-query on a genuine return (e.g. from Settings).
        var initialResumeConsumed = false
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                if (!initialResumeConsumed) {
                    initialResumeConsumed = true
                    return@LifecycleEventObserver
                }
                partialOnly = context.hasPartialMediaAccessOnly()
                loadImages.value()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // MediaStore hands out rows whose bytes are gone or undecodable (interrupted downloads,
    // files deleted behind the scanner). SIZE/MIME filtering catches most of them at query
    // time; the rest only surface as a load failure, so drop those cells once we know.
    val unavailableIds = remember { mutableStateMapOf<Int, Unit>() }
    val onThumbnailUnavailable: (GalleryImage) -> Unit = remember {
        { image -> unavailableIds[image.id] = Unit }
    }
    val offeredImages = remember(images, unavailableIds.size) {
        if (unavailableIds.isEmpty()) images else images.filterNot { it.id in unavailableIds }
    }

    val thumbnail: @Composable (GalleryImage, String, Modifier) -> Unit =
        remember(thumbPx, placeholderPainter) {
            { image, contentDescription, modifier ->
                GalleryThumbnail(
                    image = image,
                    contentDescription = contentDescription,
                    modifier = modifier,
                    thumbPx = thumbPx,
                    placeholderPainter = placeholderPainter,
                    onUnavailable = onThumbnailUnavailable,
                )
            }
        }

    GalleryDialogShell(
        images = offeredImages,
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
    placeholderPainter: Painter,
    onUnavailable: (GalleryImage) -> Unit,
) {
    // Chip bg only while loading/error; do not stretch the glyph to full-cell (reads as ugly
    // stacked billboards). Center a small muted Phosphor Image instead.
    var showChrome by remember(image.uri.value) { mutableStateOf(true) }
    // ProductAsyncImage retries transient errors internally; one Error past that budget means
    // the row cannot be rendered at all.
    val errorCount = remember(image.uri.value) { intArrayOf(0) }
    Box(
        modifier = modifier.background(DesignChipSelected),
        contentAlignment = Alignment.Center,
    ) {
        ProductAsyncImage(
            thumb = ProductThumb(ref = image.uri, maxEdgePx = thumbPx),
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            onState = { state ->
                showChrome = state !is AsyncImagePainter.State.Success
                if (state is AsyncImagePainter.State.Error) {
                    errorCount[0]++
                    if (errorCount[0] > ProductThumbLoadPolicy.MAX_RETRIES) {
                        onUnavailable(image)
                    }
                }
            },
        )
        if (showChrome) {
            Icon(
                painter = placeholderPainter,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color.White.copy(alpha = 0.32f),
            )
        }
    }
}
