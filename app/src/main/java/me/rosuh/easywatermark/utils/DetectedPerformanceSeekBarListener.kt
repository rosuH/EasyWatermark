package me.rosuh.easywatermark.utils

import android.widget.SeekBar
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
open class DetectedPerformanceSeekBarListener(
    private val config: WaterMarkConfig?
) : SeekBar.OnSeekBarChangeListener {

    var inTimeAction: (SeekBar?, Int, Boolean) -> Unit = { _, _, _ -> }

    var postAction: (SeekBar?, Int, Boolean) -> Unit = { _, _, _ -> }

    private var isHighPerformancePredicate: () -> Boolean = {
        config?.markMode == WaterMarkConfig.MarkMode.Text
                || !getAvailableMemory(MyApp.instance).lowMemory
    }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (isHighPerformancePredicate()) {
            seekBar?.progress?.let { postAction.invoke(seekBar, it, fromUser) }
        }
        inTimeAction.invoke(seekBar, progress, fromUser)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {

    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {
        if (!isHighPerformancePredicate()) {
            seekBar?.progress?.let { postAction.invoke(seekBar, it, true) }
        }
    }

    companion object {
        const val HIGH_PERFORMANCE_MEMORY = 20
    }
}