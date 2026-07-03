package me.rosuh.easywatermark.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image as FoundationImage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/**
 * Shared CMP gallery grid/card shell.
 *
 * Android still supplies the thumbnail renderer because image loading is a platform edge today.
 */
@Composable
fun GalleryImageGrid(
    images: List<Image>,
    checkIcon: Painter,
    modifier: Modifier = Modifier,
    onImageSelected: (Image, Int, Boolean) -> Unit,
    thumbnail: @Composable (image: Image, contentDescription: String, modifier: Modifier) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        itemsIndexed(images, key = { _: Int, item: Image ->
            item.id
        }) { index, image ->
            GalleryImageCard(
                image = image,
                checkIcon = checkIcon,
                thumbnail = thumbnail,
                onCheckedChange = {
                    onImageSelected(image, index, it)
                },
            )
        }
    }
}

@Composable
private fun GalleryImageCard(
    image: Image,
    checkIcon: Painter,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit = {},
    thumbnail: @Composable (image: Image, contentDescription: String, modifier: Modifier) -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surface)
            .clickable {
                onCheckedChange(image.check.not())
            },
    ) {
        val padding by animateDpAsState(
            targetValue = if (image.check) 10.dp else 0.dp,
            label = "padding",
        )
        val clip by animateDpAsState(targetValue = if (image.check) 10.dp else 0.dp, label = "clip")
        thumbnail(
            image,
            image.name,
            Modifier
                .fillMaxSize()
                .padding(padding)
                .clip(RoundedCornerShape(clip)),
        )

        CircleCheckBox(
            selected = image.check,
            checkIcon = checkIcon,
            onClick = {
                onCheckedChange(image.check.not())
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(19.dp),
        )
    }
}

@Composable
private fun CircleCheckBox(
    selected: Boolean,
    checkIcon: Painter,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val color = MaterialTheme.colorScheme
    val border = if (selected) {
        BorderStroke(0.dp, color.onSurface.copy(alpha = 0.6f))
    } else {
        BorderStroke(2.dp, color.onSurface.copy(alpha = 0.6f))
    }
    val boxModifier = if (selected) {
        modifier.background(color.secondary, shape = CircleShape)
    } else {
        modifier.border(border, shape = CircleShape)
    }
    Box(
        modifier = boxModifier
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
    ) {
        AnimatedVisibility(visible = selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            FoundationImage(
                painter = checkIcon,
                contentDescription = "check box",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
