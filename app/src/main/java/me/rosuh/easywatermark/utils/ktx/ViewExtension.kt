package me.rosuh.easywatermark.utils.ktx

import android.view.View
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_NO_BOUNCY
import me.rosuh.easywatermark.ui.widget.utils.ViewAnimation

fun View.appearAnimation(
    dampingRatio: Float = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY,
    stiffness: Float = SpringForce.STIFFNESS_LOW
): SpringAnimation {
    return SpringAnimation(this, SpringAnimation.TRANSLATION_Y, 0f).apply {
        spring = SpringForce()
            .setFinalPosition(0f)
            .setDampingRatio(dampingRatio)
            .setStiffness(stiffness)
    }
}

fun View.disappearAnimation(toPos: Float = 10f): SpringAnimation {
    return SpringAnimation(this, SpringAnimation.TRANSLATION_Y, toPos).apply {
        spring = SpringForce()
            .setFinalPosition(toPos)
            .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
            .setStiffness(SpringForce.STIFFNESS_LOW)
        addUpdateListener { _, _, _ ->
            this@disappearAnimation.isVisible = true
        }
        addEndListener { _, _, _, _ ->
            this@disappearAnimation.isVisible = false
        }
    }
}

fun View.appear(
    fromX: Float = 0f,
    fromY: Float = 10.dp.toFloat(),
    fromAlpha: Float = 0.5f,
    duration: Long = 200
) {
    this.translationY = fromY
    this.translationX = fromX
    this.alpha = fromAlpha
    this.scaleX = 0.75f
    this.scaleY = 0.75f
    this.animate()
        .translationY(0f)
        .translationX(0f)
        .scaleX(1f)
        .scaleY(1f)
        .alpha(1f)
        .setDuration(duration)
        .withStartAction {
            this.isVisible = true
        }
}

fun View.disappear(
    toX: Float = 0f,
    toY: Float = 10.dp.toFloat(),
    toAlpha: Float = 0.5f,
    duration: Long = 200
) {
    this.animate()
        .translationY(toX)
        .translationX(toY)
        .alpha(toAlpha)
        .setDuration(duration)
        .withStartAction {
            this.isVisible = true
        }
        .withEndAction {
            this.isVisible = false
        }
}

fun generateAppearAnimationList(
    vararg views: View
): List<SpringAnimation> {
    return views.map {
        it.appearAnimation()
    }
}

fun generateAppearAnimationList(
    views: Iterable<View>
): List<ViewAnimation> {
    return views.mapIndexed { index, view ->
        ViewAnimation(view, view.appearAnimation(dampingRatio = DAMPING_RATIO_NO_BOUNCY)).apply {
            setListener {
                applyBeforeStart { view, _ ->
                    view.translationY = 20.dp.toFloat() + index * 35.dp
                    view.alpha = 0.1f
                    view.animate()
                        .alpha(1f)
                        .withStartAction {
                            view.isVisible = true
                        }
                        .setDuration(200L)
                        .start()
                }
            }
        }
    }
}

fun generateDisappearAnimationList(
    views: Iterable<View>
): List<ViewAnimation> {
    return views.mapIndexed { _, view ->
        ViewAnimation(view, null).apply {
            setListener {
                applyBeforeStart { view, _ ->
                    view.translationY = 0f
                    view.animate()
                        .alpha(0f)
                        .setDuration(200L)
                        .withEndAction {
                            view.isVisible = false
                        }
                        .start()
                }
            }
        }
    }
}
