package me.rosuh.easywatermark.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.os.BuildCompat
import me.rosuh.easywatermark.data.model.ImageInfo

/**
 * Android host for shared [me.rosuh.easywatermark.ui.LaunchScreen].
 * Product layout + logo only — pick routing (Photo Picker vs in-app gallery) lives in
 * [ComposeMainActivity] so default stays zero-permission Photo Picker.
 *
 * File is named AndroidLaunchScreen.kt (not LaunchScreen.kt) so generated
 * `AndroidLaunchScreenKt` does not collide with shared `LaunchScreenKt`.
 */
@BuildCompat.PrereleaseSdkCheck
@Composable
fun AndroidLaunchScreen(
    onPickImage: () -> Unit,
    onGoAbout: () -> Unit = {},
) {
    var startLogoAnimation by remember { mutableStateOf(true) }

    Box(Modifier.fillMaxSize()) {
        LaunchScreen(
            aboutIcon = SharedProductDrawables.aboutPainter(),
            startLogoAnimation = startLogoAnimation,
            logo = { modifier, shouldAnimate ->
                BrandLogo(modifier = modifier, animate = shouldAnimate)
            },
            onPickImage = {
                startLogoAnimation = false
                onPickImage()
            },
            onGoAbout = onGoAbout,
        )
    }
}

/**
 * Android UI edge actions (ADR-0017 Phase 5).
 *
 * Carries [Uri]/[ContentResolver] and is mapped once in
 * [MainViewModel.process] → shared [me.rosuh.easywatermark.session.AppIntent].
 * F2: watermark config uses typed [me.rosuh.easywatermark.data.model.WatermarkConfigChange]
 * via [MainViewModel.applyConfig] — no raw WaterMarkChange Action.
 */
sealed class Action {
    data class DialogDismiss(val isSelected: Boolean) : Action()

    data class GalleryImageSelected(val image: Image, val index: Int, val isCheck: Boolean) : Action()

    data class SystemPickerImageSelected(
        val uriList: List<Uri>,
    ) : Action()

    data class LoadImages(val resolver: ContentResolver) : Action()

    data class EditorImageSelected(val image: ImageInfo) : Action()
}
