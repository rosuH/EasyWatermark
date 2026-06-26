package me.rosuh.easywatermark.utils.ktx

import android.graphics.Shader
import android.os.Build

fun Int?.toTileMode(): Shader.TileMode {
    return when {
        this == Shader.TileMode.CLAMP.ordinal -> Shader.TileMode.CLAMP
        this == Shader.TileMode.MIRROR.ordinal -> Shader.TileMode.MIRROR
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && this == Shader.TileMode.DECAL.ordinal -> {
            Shader.TileMode.DECAL
        }
        else -> Shader.TileMode.REPEAT
    }
}
