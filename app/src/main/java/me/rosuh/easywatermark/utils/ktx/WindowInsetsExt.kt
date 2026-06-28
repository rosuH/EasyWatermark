package me.rosuh.easywatermark.utils.ktx

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

data class InitialPadding(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

fun View.doOnApplyWindowInsets(
    apply: (View, WindowInsetsCompat, InitialPadding) -> Unit
) {
    val initialPadding = InitialPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        apply(view, insets, initialPadding)
        WindowInsetsCompat.CONSUMED
    }
    if (isAttachedToWindow) {
        ViewCompat.requestApplyInsets(this)
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(v)
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }
}
