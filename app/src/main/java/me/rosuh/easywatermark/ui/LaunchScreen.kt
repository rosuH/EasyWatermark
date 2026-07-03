package me.rosuh.easywatermark.ui

import android.Manifest
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.widget.ColoredImageVIew
import androidx.core.os.BuildCompat
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.ImageInfo

@BuildCompat.PrereleaseSdkCheck
@Composable
fun LaunchScreen(
    onGoDialog: () -> Unit,
    onGoAbout: () -> Unit = {},
) {
    LaunchScreenContent(onGoDialog, onGoAbout)
}

@Composable
@OptIn(ExperimentalPermissionsApi::class)
private fun LaunchScreenContent(
    onShowGalleryDialog: () -> Unit = { },
    onGoAbout: () -> Unit = { },
) {
    var startLogoAnimation by remember {
        mutableStateOf(true)
    }

    val requestPermissionResult = remember {
        { isGranted: Boolean ->
            if (isGranted) {
                onShowGalleryDialog()
            } else {
                startLogoAnimation = true
            }
        }
    }
    val mediaPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(
            Manifest.permission.READ_MEDIA_IMAGES,
            onPermissionResult = requestPermissionResult
        )
    } else {
        rememberPermissionState(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            onPermissionResult = requestPermissionResult
        )
    }

    LaunchScreenShell(
        pickImageLabel = stringResource(R.string.tips_pick_image),
        aboutContentDescription = stringResource(R.string.about_title_about),
        aboutIcon = painterResource(R.drawable.ic_about),
        startLogoAnimation = startLogoAnimation,
        logo = { modifier, shouldAnimate ->
            LogoView(
                modifier = modifier,
                startLogoAnimation = shouldAnimate,
            )
        },
        onPickImageClick = {
            startLogoAnimation = false
            if (mediaPermissionState.status.isGranted) {
                onShowGalleryDialog()
            } else {
                mediaPermissionState.launchPermissionRequest()
            }
        },
        onGoAbout = onGoAbout,
    )
}

@Composable
fun LogoView(modifier: Modifier = Modifier, startLogoAnimation: Boolean) {
    Layout({
        AndroidView(modifier = Modifier.size(Dp(180f), Dp(180f)), factory = { context ->
            ColoredImageVIew(context).apply {
                setImageResource(R.drawable.ic_log_transparent)
                if (startLogoAnimation) {
                    start()
                } else {
                    stop()
                }
            }
        }, update = {
            if (startLogoAnimation) {
                it.start()
            } else {
                it.stop()
            }
        })
    }, measurePolicy = { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    }, modifier = modifier)
}

sealed class Action {
    data class DialogDismiss(val isSelected: Boolean) : Action()

    data class GalleryImageSelected(val image: Image, val index: Int, val isCheck: Boolean) : Action()

    data class SystemPickerImageSelected(
        val uriList: List<Uri>,
    ) : Action()

    data class LoadImages(val resolver: ContentResolver) : Action()

    data class WaterMarkChange(val item: FuncTitleModel, val any: Any) : Action()

    data class EditorImageSelected(val image: ImageInfo) : Action()

}

@OptIn(ExperimentalPermissionsApi::class)
@Preview
@Composable
fun LaunchScreenPreview() {
    LaunchScreenContent()
}
