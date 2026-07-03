package me.rosuh.easywatermark.ui.compose

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.constraintlayout.motion.widget.KeyTrigger
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.request.placeholder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.GalleryImageGrid
import me.rosuh.easywatermark.ui.Image
import me.rosuh.easywatermark.utils.ktx.toUri


@Preview
@Composable
fun GalleryDialogPreview() {
    GalleryDialog(emptyList(), {}, {}, { _, _, _ -> })
}

private const val ANIMATION_DURATION = 50L

class AnimatedTransitionDialogHelper(
    private val coroutineScope: CoroutineScope,
    private val onDismissFlow: MutableSharedFlow<Any>,
) {
    fun triggerDismiss() {
        coroutineScope.launch {
            onDismissFlow.emit(Any())
        }
    }
}

@Composable
fun AnimatedTransitionDialog(
    onDismissRequest: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (AnimatedTransitionDialogHelper) -> Unit,
) {
    val animateTrigger = remember {
        mutableStateOf(false)
    }
    val onDismissSharedFlow: MutableSharedFlow<Any> = remember { MutableSharedFlow() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        launch {
            delay(ANIMATION_DURATION)
            animateTrigger.value = true
        }
        launch {
            onDismissSharedFlow.asSharedFlow().collectLatest {
                startDismissWithExitAnimation(animateTrigger, onDismissRequest)
            }
        }
    }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = {
        scope.launch {
            startDismissWithExitAnimation(
                animateTrigger = animateTrigger,
                onDismiss = {
                    onDismissRequest()
                }
            )
        }
    }) {
        Box(contentAlignment = contentAlignment, modifier = Modifier.fillMaxSize()) {
            AnimatedSlideInTransition(visible = animateTrigger.value) {
                content(AnimatedTransitionDialogHelper(coroutineScope, onDismissSharedFlow))
            }
        }
    }
}

@Composable
fun AnimatedTransitionView(
    onDismissRequest: () -> Unit,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (AnimatedTransitionDialogHelper) -> Unit,
) {
    val animateTrigger = remember {
        mutableStateOf(false)
    }
    val onDismissSharedFlow: MutableSharedFlow<Any> = remember { MutableSharedFlow() }
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        launch {
            delay(ANIMATION_DURATION)
            animateTrigger.value = true
        }
        launch {
            onDismissSharedFlow.asSharedFlow().collectLatest {
                startDismissWithExitAnimation(animateTrigger, onDismissRequest)
            }
        }
    }
    val scope = rememberCoroutineScope()
    BackHandler {
        scope.launch {
            startDismissWithExitAnimation(
                animateTrigger = animateTrigger,
                onDismiss = {
                    onDismissRequest()
                }
            )
        }
    }
    Box(contentAlignment = contentAlignment, modifier = Modifier.fillMaxSize()) {
        AnimatedSlideInTransition(visible = animateTrigger.value) {
            content(AnimatedTransitionDialogHelper(coroutineScope, onDismissSharedFlow))
        }
    }
}

@Composable
internal fun AnimatedSlideInTransition(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        content = content
    )
}

suspend fun startDismissWithExitAnimation(
    animateTrigger: MutableState<Boolean>,
    onDismiss: () -> Unit,
) {
    animateTrigger.value = false
    delay(300)
    onDismiss()
}

@Composable
fun GalleryDialog(
    images: List<Image>,
    onLoaImages: () -> Unit,
    onDismiss: (selected: Boolean) -> Unit = {},
    onImageSelected: (image: Image, index: Int, isSelected: Boolean) -> Unit,
    onPickImageViaSystem: () -> Unit = {},
) {
    var selectedCount by remember {
        mutableIntStateOf(0)
    }
    AnimatedTransitionView(onDismissRequest = {
        onDismiss(selectedCount > 0)
    }) { dialogHelper ->
        LaunchedEffect(key1 = images.size) {
            onLoaImages()
        }
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
                            dialogHelper.triggerDismiss()
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
                    IconButton(onClick = {
                        onPickImageViaSystem.invoke()
                        dialogHelper.triggerDismiss()
                    }) {
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
                    enter = slideInVertically { fullHeight -> fullHeight } + fadeIn(),
                    exit = slideOutVertically { fullHeight -> fullHeight } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    ExtendedFloatingActionButton(
                        modifier = Modifier.padding(64.dp),
                        onClick = {
                            dialogHelper.triggerDismiss()
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

}

@Composable
fun GalleryImageList(images: List<Image>, onImageSelected: (Image, Int, Boolean) -> Unit) {
    GalleryImageGrid(
        images = images,
        checkIcon = painterResource(R.drawable.ic_gallery_radio_button),
        onImageSelected = onImageSelected,
    ) { image, contentDescription, modifier ->
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
}
