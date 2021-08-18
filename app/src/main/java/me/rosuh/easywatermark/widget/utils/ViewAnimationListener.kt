package me.rosuh.easywatermark.widget.utils

import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation

interface ViewAnimationListener {
    fun applyBeforeStart(view: View, animation: SpringAnimation?)

    fun applyAfterEnd(view: View, animation: SpringAnimation?)
}

private typealias ApplyOnStart = (view: View, animation: SpringAnimation?) -> Unit

private typealias ApplyOnEnd = (view: View, animation: SpringAnimation?) -> Unit

class ViewAnimationListenerBuilder : ViewAnimationListener {

    private var applyBeforeStart: ApplyOnStart? = null
    private var applyAfterEnd: ApplyOnEnd? = null

    override fun applyBeforeStart(view: View, animation: SpringAnimation?) {
        applyBeforeStart?.invoke(view, animation)
    }

    override fun applyAfterEnd(view: View, animation: SpringAnimation?) {
        applyAfterEnd?.invoke(view, animation)
    }

    fun applyBeforeStart(applyOnStart: ApplyOnStart) {
        this.applyBeforeStart = applyOnStart
    }

    fun applyAfterEnd(applyOnEnd: ApplyOnEnd) {
        this.applyAfterEnd = applyOnEnd
    }

}