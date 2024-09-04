package me.rosuh.easywatermark.ui.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.Image


@Preview
@Composable
fun GalleryDialogPreview() {
    GalleryDialog(emptyList(), {}, {}, { _, _, _ -> })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryDialog(
    images: List<Image>,
    onLoaImages: () -> Unit,
    onDismiss: (selected: Boolean) -> Unit = {},
    onImageSelected: (image: Image, index: Int, isSelected: Boolean) -> Unit,
) {
    var selectedCount by remember {
        mutableIntStateOf(0)
    }
    LaunchedEffect(key1 = images.size) {
        onLoaImages()
    }
    val multiplePhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(),
        onResult = { uris ->

        }
    )
    Scaffold(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(
                    onClick = {
                        onDismiss(false)
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close_24dp),
                        contentDescription = "close dialog",
                    )
                }
                Text(
                    text = stringResource(id = R.string.action_pick),
                    style = MaterialTheme.typography.titleLarge,
                )
                IconButton(onClick = { /*TODO*/ }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_baseline_image_search_24),
                        contentDescription = "search"
                    )
                }
            }

        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // gallery list
            GalleryImageList(images = images) { image, index, isChecked ->
                selectedCount += if (isChecked) +1 else -1
                onImageSelected(image, index, isChecked)
            }

            AnimatedVisibility(
                visible = selectedCount > 0,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(64.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = {
                        onDismiss(true)
                    }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_save_done),
                        contentDescription = "add"
                    )
                    Text(text = "$selectedCount")
                }
            }
        }
    }
}

@Composable
fun GalleryImageList(images: List<Image>, onImageSelected: (Image, Int, Boolean) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        itemsIndexed(images, key = { _: Int, item: Image ->
            item.id
        }) { index, image ->
            ImageCard(image = image) {
                onImageSelected(image, index, it)
            }
        }
    }
}

@Composable
fun ImageCard(
    image: Image,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                onCheckedChange(image.check.not())
            }
    ) {
        val padding by animateDpAsState(
            targetValue = if (image.check) 10.dp else 0.dp,
            label = "padding"
        )
        val clip by animateDpAsState(targetValue = if (image.check) 10.dp else 0.dp, label = "clip")
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(image.uri)
                .allowRgb565(true)
                .crossfade(true)
                .placeholder(R.drawable.ic_gallery_item_placeholder_container)
                .build(),
            contentDescription = image.name,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .clip(RoundedCornerShape(clip)),
            contentScale = ContentScale.Crop,
        )

        CircleCheckBox(
            selected = image.check,
            onClick = {
                onCheckedChange(image.check.not())
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(19.dp)
        )
    }
}

@Composable
fun CircleCheckBox(
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val color = MaterialTheme.colorScheme
    val border = if (selected) {
        BorderStroke(0.dp, color.onSurface.copy(alpha = 0.6f))
    } else {
        BorderStroke(2.dp, color.onSurface.copy(alpha = 0.6f))
    }
    val m = if (selected) {
        modifier.background(color.secondary, shape = CircleShape)
    } else {
        modifier.border(border, shape = CircleShape)
    }
    Box(
        modifier = m
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            )
    ) {
        AnimatedVisibility(visible = selected) {
            Image(
                painter = painterResource(id = R.drawable.ic_gallery_radio_button),
                contentDescription = "check box",
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
