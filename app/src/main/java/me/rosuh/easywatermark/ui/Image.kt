package me.rosuh.easywatermark.ui

import android.net.Uri
import androidx.compose.ui.tooling.preview.PreviewParameterProvider

data class Image(
    val id: Int,
    val uri: Uri,
    val name: String,
    val size: Long,
    val date: Long,
    val check: Boolean = false
):PreviewParameterProvider<Image> {
    override val values: Sequence<Image>
        get() = sequenceOf(
            Image(
                1,
                Uri.parse("https://www.baidu.com/img/bd_logo1.png"),
                "test",
                100,
                100
            )
        )
}