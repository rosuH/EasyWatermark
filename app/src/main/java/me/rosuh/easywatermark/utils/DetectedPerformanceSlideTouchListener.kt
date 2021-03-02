package me.rosuh.easywatermark.utils

import android.widget.SeekBar
import com.google.android.material.slider.Slider
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.utils.DetectedPerformanceSeekBarListener.Companion.HIGH_PERFORMANCE_MEMORY

/**
 * An subClass of [SeekBar.OnSeekBarChangeListener] which using [isHighPerformancePredicate] to decide
 * should invoke [postAction] in [onProgressChanged] or in [onStopTrackingTouch].
 * In some low performance devices, invoke [postAction] in [onProgressChanged] would frozen or oom.
 * Because we're doing hard work about the image in the thread, which using much memory.
 * So here we would sacrifice a little real-time reaction, but can avoid OOM problems.
 *
 * ---
 *
 * 牺牲一些 SeekBar 的实时性来避免 OOM 问题。如果判断得可用内存少于[HIGH_PERFORMANCE_MEMORY]，则把 [postAction]
 * 放到 [onStopTrackingTouch] 方法里调用。
 */
open class DetectedPerformanceSlideTouchListener(
    private val config: WaterMarkConfig?
) : Slider.OnSliderTouchListener, Slider.OnChangeListener {

    var inTimeAction: (Slider?, Float, Boolean) -> Unit = { _, _, _ -> }

    var postAction: (Slider?, Float, Boolean) -> Unit = { _, _, _ -> }

    private var isHighPerformancePredicate: () -> Boolean = {
        config?.markMode == WaterMarkConfig.MarkMode.Text
                || !getAvailableMemory(MyApp.instance).lowMemory
    }

    override fun onStartTrackingTouch(slider: Slider) {

    }

    override fun onStopTrackingTouch(slider: Slider) {
        if (!isHighPerformancePredicate()) {
            slider.value.let { postAction.invoke(slider, it, true) }
        }
    }

    override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
        if (isHighPerformancePredicate()) {
            slider.value.let { postAction.invoke(slider, it, fromUser) }
        }
        inTimeAction.invoke(slider, value, fromUser)
    }

    companion object {
        const val HIGH_PERFORMANCE_MEMORY = 20
    }
}