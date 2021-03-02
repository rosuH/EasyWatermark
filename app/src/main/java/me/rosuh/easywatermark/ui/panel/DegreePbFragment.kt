package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.base.BasePBFragment
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.model.WaterMarkConfig

class DegreePbFragment : BasePBFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.slideContentSize.valueFrom = 0f
        binding.slideContentSize.valueTo = 360f
    }

    override fun doOnChange(slider: Slider, value: Float, fromUser: Boolean) {
        shareViewModel.updateDegree(value)
    }

    override fun formatValue(config: WaterMarkConfig?): Float {
        return (config?.degree ?: 0f).coerceAtLeast(0f).coerceAtMost(WaterMarkConfig.MAX_DEGREE)
    }

    override fun formatValueTips(config: WaterMarkConfig?): String {
        return "${config?.degree?: 1f}"
    }

    companion object {
        const val TAG = "DegreePbFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true || f?.isAdded == true) {
                return
            }
            fa.commitWithAnimation {
                replace(
                    containerId,
                    DegreePbFragment(),
                    TAG
                )
            }
        }
    }
}