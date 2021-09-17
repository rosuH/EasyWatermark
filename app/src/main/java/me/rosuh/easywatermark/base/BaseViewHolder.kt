package me.rosuh.easywatermark.base

import android.view.View
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import me.rosuh.easywatermark.utils.ItemClickSupportViewHolder

open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView),
    ItemClickSupportViewHolder {

    /**
     * A [SpringAnimation] for this RecyclerView item. This animation is used to bring the item back
     * after the over-scroll effect.
     */
    val translationX: SpringAnimation by lazy {
        SpringAnimation(itemView, SpringAnimation.TRANSLATION_X)
            .setSpring(
                SpringForce()
                    .setFinalPosition(0f)
                    .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                    .setStiffness(SpringForce.STIFFNESS_LOW)
            )
    }
}
