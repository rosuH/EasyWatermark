package me.rosuh.easywatermark.ui.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.databinding.FragemntBasePbBinding
import me.rosuh.easywatermark.utils.VibrateHelper

abstract class BasePBFragment : BaseBindFragment<FragemntBasePbBinding>() {

    private val vibrateHelper: VibrateHelper by lazy { VibrateHelper.get() }

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragemntBasePbBinding {
        val b = FragemntBasePbBinding.inflate(layoutInflater, container, false)

        b.slideContentSize.apply {
            value = formatValue(shareViewModel.waterMark.value)
            addOnChangeListener { slider, value, fromUser ->
                doOnChange(slider, value, fromUser)
            }
        }

        b.tvProgressVertical.apply {
            text = "${formatValue(shareViewModel.waterMark.value)}"
        }

        return b
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shareViewModel.waterMark.observe(
            viewLifecycleOwner,
            {
                binding.tvProgressVertical.text = formatValueTips(it)
            }
        )
    }

    abstract fun doOnChange(slider: Slider, value: Float, fromUser: Boolean)

    abstract fun formatValue(config: WaterMark?): Float

    abstract fun formatValueTips(config: WaterMark?): String
}
