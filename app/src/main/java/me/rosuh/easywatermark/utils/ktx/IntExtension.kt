package me.rosuh.easywatermark.utils.ktx

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Shader
import android.os.Build
import android.util.TypedValue
import me.rosuh.easywatermark.ui.widget.WaterMarkImageView

val Int.dp
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        Resources.getSystem().displayMetrics
    ).toInt()

val Float.dp
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this,
        Resources.getSystem().displayMetrics
    )

fun Int.toColor(
    toColor: Int,
    autoStart: Boolean = true,
    duration: Long = WaterMarkImageView.ANIMATION_DURATION,
    doOnUpdate: (it: ValueAnimator) -> Unit = {},
): ObjectAnimator? {
    return ObjectAnimator.ofInt(
        this,
        "backgroundColor",
        this,
        toColor
    ).apply {
        setEvaluator(ArgbEvaluator())
        addUpdateListener {
            doOnUpdate.invoke(it)
        }
        this.duration = duration
        if (autoStart) start()
    }
}

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