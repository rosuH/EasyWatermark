package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.base.BasePBFragment
import me.rosuh.easywatermark.utils.ktx.commitWithAnimation

class TextSizePbFragment : BasePBFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding?.slideContentSize?.valueFrom = 1f
        binding?.slideContentSize?.valueTo = WaterMarkRepository.MAX_TEXT_SIZE
    }

    override fun doOnChange(slider: Slider, value: Float, fromUser: Boolean) {
        shareViewModel.updateTextSize(value)
    }

    override fun formatValue(config: WaterMark?): Float {
        return (config?.textSize?.toInt()?.toFloat() ?: 1f).coerceAtLeast(1f).coerceAtMost(WaterMarkRepository.MAX_TEXT_SIZE)
    }

    override fun formatValueTips(config: WaterMark?): String {
        val size = (config?.textSize?.toInt()?.toFloat() ?: 1f).coerceAtLeast(1f).coerceAtMost(WaterMarkRepository.MAX_TEXT_SIZE)
        return "$size"
    }

    companion object {
        const val TAG = "TextSizePbFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true) {
                return
            }
            fa.commitWithAnimation {
                replace(
                    containerId,
                    TextSizePbFragment(),
                    TAG
                )
            }
        }
    }
}
