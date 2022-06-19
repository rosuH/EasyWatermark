package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.base.BasePBFragment
import me.rosuh.easywatermark.utils.ktx.commitWithAnimation

class DegreePbFragment : BasePBFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.slideContentSize?.valueFrom = 0f
        binding?.slideContentSize?.valueTo = WaterMarkRepository.MAX_DEGREE
    }

    override fun doOnChange(slider: Slider, value: Float, fromUser: Boolean) {
        shareViewModel.updateDegree(value)
    }

    override fun formatValue(config: WaterMark?): Float {
        return (config?.degree ?: 0f).coerceAtLeast(0f).coerceAtMost(WaterMarkRepository.MAX_DEGREE)
    }

    override fun formatValueTips(config: WaterMark?): String {
        return "${config?.degree ?: 1f}"
    }

    companion object {
        const val TAG = "DegreePbFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true) {
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
