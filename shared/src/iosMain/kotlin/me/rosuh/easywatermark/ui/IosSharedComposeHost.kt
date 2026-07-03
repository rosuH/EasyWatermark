package me.rosuh.easywatermark.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import me.rosuh.easywatermark.ui.theme.AppTheme
import platform.UIKit.UIViewController

/**
 * iOS host boundary for shared Compose Multiplatform UI.
 *
 * SwiftUI remains the app entry/system-UI glue, but it can now embed this UIViewController to
 * render a real commonMain CMP shell from the `Shared.framework`.
 */
object IosSharedComposeHost {
    fun editorPreviewFrameWitness(): UIViewController = ComposeUIViewController {
        AppTheme {
            EditorPreviewFrame(
                hasImage = true,
                emptyText = "No preview",
                modifier = Modifier.fillMaxSize(),
                preview = {
                    Text("Shared CMP preview frame")
                },
            )
        }
    }
}
