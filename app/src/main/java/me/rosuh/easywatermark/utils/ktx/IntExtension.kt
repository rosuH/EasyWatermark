package me.rosuh.easywatermark.utils.ktx

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Resources
import android.util.TypedValue

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
    doOnUpdate: (it: ValueAnimator) -> Unit = {}
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
        duration = 250
        if (autoStart) start()
    }
}