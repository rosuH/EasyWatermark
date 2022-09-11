package me.rosuh.easywatermark.data.model

import android.graphics.Shader
import android.net.Uri
import android.os.Build
import androidx.annotation.FloatRange

data class ImageInfo(
    val uri: Uri,
    var width: Int = 1,
    var height: Int = 1,
    var inSample: Int = 1,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
    var result: Result<*>? = null,
    var jobState: JobState = JobState.Ready,
    var isInDelModel: Boolean = false,
    val tileMode: Int = Shader.TileMode.REPEAT.ordinal,
    @FloatRange(from = 0.0, to = 1.0) val offsetX: Float = 0.5f,
    @FloatRange(from = 0.0, to = 1.0) val offsetY: Float = 0.5f,
) {
    val shareUri: Uri?
        get() = result?.data as? Uri?

    fun obtainTileMode(): Shader.TileMode {
        return when (tileMode) {
            Shader.TileMode.CLAMP.ordinal -> Shader.TileMode.CLAMP
            Shader.TileMode.MIRROR.ordinal -> Shader.TileMode.MIRROR
            Shader.TileMode.REPEAT.ordinal -> Shader.TileMode.REPEAT
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Shader.TileMode.DECAL
            } else {
                Shader.TileMode.CLAMP
            }
        }
    }

    fun isSameItem(other: ImageInfo): Boolean {
        return uri == other.uri
                && result == other.result
                && jobState == other.jobState
                && isInDelModel == other.isInDelModel
    }

    companion object {
        fun empty(): ImageInfo {
            return ImageInfo(
                Uri.EMPTY,
                1,
                1,
                1,
                1f,
                1f,
                null,
                JobState.Ready,
                false,
                Shader.TileMode.REPEAT.ordinal,
                0.5f,
                0.5f
            )
        }
    }
}
