package me.rosuh.easywatermark.widget.utils

import android.content.Context
import android.widget.EdgeEffect
import androidx.recyclerview.widget.RecyclerView

class BounceEdgeEffectFactory(
    val context: Context,
    val recyclerView: RecyclerView
) : RecyclerView.EdgeEffectFactory() {

    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        return SimpleOverScrollEdgeEffect(recyclerView, direction, context)
    }

    companion object {
        const val TAG = "RvEdgeEffect"

        /** The magnitude of translation distance while the list is over-scrolled. */
        private const val OVERSCROLL_TRANSLATION_MAGNITUDE = 0.5f

        /** The magnitude of translation distance when the list reaches the edge on fling. */
        private const val FLING_TRANSLATION_MAGNITUDE = 0.5f
    }
}