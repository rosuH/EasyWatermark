package me.rosuh.easywatermark.ui.base

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.databinding.FragemntBasePbBinding
import me.rosuh.easywatermark.utils.ktx.toColor

abstract class BasePBFragment : BaseBindFragment<FragemntBasePbBinding>() {

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

        b.slideContentSize.trackTintList =
            ColorStateList.valueOf(
                shareViewModel.colorPalette.value?.darkMutedSwatch?.bodyTextColor ?: Color.WHITE
            )

        return b
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shareViewModel.waterMark.observe(viewLifecycleOwner) {
            binding?.tvProgressVertical?.text = formatValueTips(it)
        }
        shareViewModel.colorPalette.observe(viewLifecycleOwner) { palette ->
            val color = palette.darkMutedSwatch?.bodyTextColor ?: Color.WHITE
            binding?.slideContentSize?.trackTintList?.defaultColor?.toColor(color) {
                binding?.slideContentSize?.trackTintList =
                    ColorStateList.valueOf(it.animatedValue as Int)
            }
        }
    }

    abstract fun doOnChange(slider: Slider, value: Float, fromUser: Boolean)

    abstract fun formatValue(config: WaterMark?): Float

    abstract fun formatValueTips(config: WaterMark?): String
}
