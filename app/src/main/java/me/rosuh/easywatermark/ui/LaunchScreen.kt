package me.rosuh.easywatermark.ui

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ui.about.AboutActivity
import me.rosuh.easywatermark.ui.widget.ColoredImageVIew
import androidx.core.os.BuildCompat
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.WaterMark

@BuildCompat.PrereleaseSdkCheck
@Composable
fun LaunchScreen(
    onGoDialog: () -> Unit,
) {
    LaunchScreenContent(onGoDialog)
}

@Composable
@OptIn(ExperimentalPermissionsApi::class)
private fun LaunchScreenContent(
    onShowGalleryDialog: () -> Unit = { },
) {
    val context = LocalContext.current

    var startLogoAnimation by remember {
        mutableStateOf(true)
    }

    val mediaPermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(
            Manifest.permission.READ_MEDIA_IMAGES
        )
    } else {
        rememberPermissionState(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        LogoView(
            modifier = Modifier
                .padding(top = maxHeight * 0.2f)
                .align(Alignment.TopCenter),
            startLogoAnimation
        )

        Button(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = maxHeight * 0.3f),
            onClick = {
                startLogoAnimation = false
                if (mediaPermissionState.status.isGranted) {
                    onShowGalleryDialog()
                } else {
                    mediaPermissionState.launchPermissionRequest()
                }
            }) {
            Text(stringResource(R.string.tips_pick_image))
        }

        IconButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = maxHeight * 0.03f),
            onClick = {
                context.startActivity(Intent(context, AboutActivity::class.java))
            }) {
            Icon(
                painter = painterResource(id = R.drawable.ic_about),
                contentDescription = stringResource(
                    id = R.string.about_title_about
                )
            )
        }
    }
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

data class LaunchScreenState(
    val uiState: LaunchScreenUiState = LaunchScreenUiState.Launch,
    val imageList: List<Image> = emptyList(),
    val selectedImageList: List<ImageInfo> = emptyList(),
    val waterMark: WaterMark = WaterMark.default,
    val curImageInfo: ImageInfo? = selectedImageList.firstOrNull(),
) {
    companion object {
        fun default() = LaunchScreenState()
    }
}

sealed class LaunchScreenUiState {
    object Launch : LaunchScreenUiState()
    object GalleryDialog : LaunchScreenUiState()
    object Editor : LaunchScreenUiState()
}

sealed class Action {
    data class ChooseImage(val resolver: ContentResolver) : Action()
    data class DialogDismiss(val isSelected: Boolean) : Action()

    data class GalleryImageSelected(val image: Image, val index: Int, val isCheck: Boolean) : Action()

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