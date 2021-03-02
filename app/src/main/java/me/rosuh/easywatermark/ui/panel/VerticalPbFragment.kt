package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.base.BasePBFragment
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.model.WaterMarkConfig

class VerticalPbFragment : BasePBFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.slideContentSize.valueFrom = 0f
        binding.slideContentSize.valueTo = 500f
    }

    override fun doOnChange(slider: Slider, value: Float, fromUser: Boolean) {
        shareViewModel.updateVertical(value.toInt())
    }

    override fun formatValue(config: WaterMarkConfig?): Float {
        return (config?.verticalGapPercent?.toFloat() ?: 1f).coerceAtLeast(0f)
            .coerceAtMost(WaterMarkConfig.MAX_VERTICAL_GAP.toFloat())
    }

    override fun formatValueTips(config: WaterMarkConfig?): String {
        return "${config?.verticalGapPercent?: 1f}"
    }

    companion object {
        const val TAG = "VerticalPbFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true || f?.isAdded == true) {
                return
            }
            fa.commitWithAnimation {
                replace(
                    containerId,
                    VerticalPbFragment(),
                    TAG
                )
            }
        }
    }
}