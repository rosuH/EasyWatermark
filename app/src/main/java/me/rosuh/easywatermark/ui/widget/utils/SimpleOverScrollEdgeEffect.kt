package me.rosuh.easywatermark.ui.widget.utils

import android.content.Context
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.EdgeEffectFactory.DIRECTION_LEFT
import me.rosuh.easywatermark.ui.base.BaseViewHolder

class SimpleOverScrollEdgeEffect(
    val recyclerView: RecyclerView,
    private val direction: Int,
    val context: Context
) : EdgeEffect(context) {

    override fun onPull(deltaDistance: Float, displacement: Float) {
        super.onPull(deltaDistance, displacement)
        handlePull(deltaDistance)
    }

    override fun onPull(deltaDistance: Float) {
        super.onPull(deltaDistance)
        handlePull(deltaDistance)
    }

    private fun handlePull(deltaDistance: Float) {
        // This is called on every touch event while the list is scrolled with a finger.
        // We simply update the view properties without animation.
        val sign = if (direction == DIRECTION_LEFT) 1 else -1
        val translationXDelta =
            sign * recyclerView.height * deltaDistance * OVERSCROLL_TRANSLATION_MAGNITUDE
        recyclerView.forEachVisibleHolder<BaseViewHolder> { holder ->
            holder.itemView.translationX += translationXDelta
            holder.translationX.cancel()
        }
    }

    override fun onRelease() {
        super.onRelease()
        recyclerView.forEachVisibleHolder<BaseViewHolder> {
            it.translationX.start()
        }
    }

    override fun onAbsorb(velocity: Int) {
        super.onAbsorb(velocity)
        val sign = if (direction == DIRECTION_LEFT) 1 else -1
        // The list has reached the edge on fling.
        val translationVelocity = sign * velocity * FLING_TRANSLATION_MAGNITUDE
        recyclerView.forEachVisibleHolder<BaseViewHolder> {
            it.translationX
                .setStartVelocity(translationVelocity)
                .start()
        }
    }

    private inline fun <reified T : RecyclerView.ViewHolder> RecyclerView.forEachVisibleHolder(
        action: (T) -> Unit
    ) {
        for (i in 0 until childCount) {
            action(getChildViewHolder(getChildAt(i)) as T)
        }
    }

    companion object {
        const val TAG = "ChatEdgeEffect"

        /** The magnitude of translation distance while the list is over-scrolled. */
        private const val OVERSCROLL_TRANSLATION_MAGNITUDE = 0.35f

        /** The magnitude of translation distance when the list reaches the edge on fling. */
        private const val FLING_TRANSLATION_MAGNITUDE = 0.5f
    }
}
