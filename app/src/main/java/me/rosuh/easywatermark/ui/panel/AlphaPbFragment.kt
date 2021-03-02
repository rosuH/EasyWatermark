package me.rosuh.easywatermark.ui.panel

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.base.BasePBFragment
import me.rosuh.easywatermark.ktx.commitWithAnimation
import me.rosuh.easywatermark.model.WaterMarkConfig

class AlphaPbFragment : BasePBFragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.slideContentSize.valueFrom = 0f
        binding.slideContentSize.valueTo = 100f
    }


    override fun doOnChange(slider: Slider, value: Float, fromUser: Boolean) {
        if (fromUser) {
            shareViewModel.updateAlpha((value / 100 * 255).toInt())
        }
    }

    override fun formatValue(config: WaterMarkConfig?): Float {
        return rebuildAlpha(config).toFloat().coerceAtLeast(0f).coerceAtMost(100f)
    }

    private fun rebuildAlpha(config: WaterMarkConfig?): Int {
        if (config?.alpha == null) {
            return 0
        }
        return (config.alpha.toFloat() / 255 * 100).toInt().coerceAtLeast(0).coerceAtMost(100)
    }

    override fun formatValueTips(config: WaterMarkConfig?): String {
        return "${rebuildAlpha(config)}%"
    }

    companion object {
        const val TAG = "AlphaPbFragment"

        fun replaceShow(fa: FragmentActivity, containerId: Int) {
            val f = fa.supportFragmentManager.findFragmentByTag(TAG)
            if (f?.isVisible == true || f?.isAdded == true) {
                return
            }
            fa.commitWithAnimation {
                replace(
                    containerId,
                    AlphaPbFragment(),
                    TAG
                )
            }
        }
    }
}