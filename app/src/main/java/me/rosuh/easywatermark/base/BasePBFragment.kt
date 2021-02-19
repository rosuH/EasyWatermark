package me.rosuh.easywatermark.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.databinding.FragemntBasePbBinding
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.utils.VibrateHelper

abstract class BasePBFragment : BaseBindFragment<FragemntBasePbBinding>() {

    private lateinit var vibrateHelper: VibrateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrateHelper = VibrateHelper.init(requireContext())
    }

    override fun bindView(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragemntBasePbBinding {
        val b = FragemntBasePbBinding.inflate(layoutInflater, container, false)

        b.slideContentSize.apply {
            value = formatValue(shareViewModel.config.value)
            addOnChangeListener { slider, value, fromUser ->
                if (fromUser) {
                    vibrateHelper.doVibrate(1L, 1)
                }
                doOnChange(slider, value, fromUser)
            }
        }

        b.tvProgressVertical.apply {
            text = "${formatValue(shareViewModel.config.value)}"
        }

        return b
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shareViewModel.config.observe(viewLifecycleOwner, {
            binding.tvProgressVertical.text = formatValueTips(it)
        })
    }

    abstract fun doOnChange(slider: Slider, value: Float, fromUser: Boolean)

    abstract fun formatValue(config: WaterMarkConfig?): Float

    abstract fun formatValueTips(config: WaterMarkConfig?): String

}