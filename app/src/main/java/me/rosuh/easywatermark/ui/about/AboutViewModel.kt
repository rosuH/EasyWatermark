package me.rosuh.easywatermark.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData

import me.rosuh.cmonet.CMonet
import me.rosuh.easywatermark.data.repo.MemorySettingRepo
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.utils.ktx.launch


class AboutViewModel (
    private val waterMarkRepository: WaterMarkRepository,
    private val memorySettingRepo: MemorySettingRepo
) : ViewModel() {

    val waterMark = waterMarkRepository.waterMark.asLiveData()

    val palette = memorySettingRepo.paletteFlow.asLiveData()

    fun toggleBounds(enable: Boolean) {
        launch {
            waterMarkRepository.toggleBounds(enable)
        }
    }

    fun toggleSupportDynamicColor(enable: Boolean) {
        if (enable) {
            CMonet.forceSupportDynamicColor()
        } else {
            CMonet.disableSupportDynamicColor()
        }
    }
}
