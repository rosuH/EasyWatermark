package me.rosuh.easywatermark.ui

import android.Manifest
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.os.BuildCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.ImageInfo

/**
 * Android host for shared [me.rosuh.easywatermark.ui.LaunchScreen].
 * Permission edge only — product layout + logo from commonMain composeResources.
 *
 * File is named AndroidLaunchScreen.kt (not LaunchScreen.kt) so generated
 * `AndroidLaunchScreenKt` does not collide with shared `LaunchScreenKt`.
 */
@BuildCompat.PrereleaseSdkCheck
@Composable
fun AndroidLaunchScreen(
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
    var startLogoAnimation by remember { mutableStateOf(true) }

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
            onPermissionResult = requestPermissionResult,
        )
    } else {
        rememberPermissionState(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            onPermissionResult = requestPermissionResult,
        )
    }

    Box(Modifier.fillMaxSize()) {
        LaunchScreen(
            aboutIcon = SharedProductDrawables.aboutPainter(),
            startLogoAnimation = startLogoAnimation,
            logo = { modifier, shouldAnimate ->
                BrandLogo(modifier = modifier, animate = shouldAnimate)
            },
            onPickImage = {
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
}

/**
 * Android UI edge actions (ADR-0017 Phase 5).
 *
 * Carries [Uri]/[ContentResolver]/[FuncTitleModel] and is mapped once in
 * [MainViewModel.process] → shared [me.rosuh.easywatermark.session.AppIntent].
 */
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
