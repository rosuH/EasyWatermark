package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.base.BasePBFragment
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.model.WaterMarkConfig

class TextSizePbFragment : BasePBFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.slideContentSize.valueFrom = 0f
        binding.slideContentSize.valueTo = WaterMarkConfig.MAX_TEXT_SIZE
    }

    override fun doOnChange(slider: Slider, value: Float, fromUser: Boolean) {
        shareViewModel.updateTextSize(value)
    }

    override fun formatValue(config: WaterMarkConfig?): Float {
        return config?.textSize?.toInt()?.toFloat() ?: 1f
    }

    override fun formatValueTips(config: WaterMarkConfig?): String {
        return "${config?.textSize?.toInt()?.toFloat() ?: 1f}"
    }

    companion object {
        const val TAG = "TextSizePbFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true || f?.isAdded == true) {
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