package me.rosuh.easywatermark.ui.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import me.rosuh.easywatermark.data.model.WaterMark
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.platform.DynamicColorCapability
import me.rosuh.easywatermark.utils.ktx.launch


class AboutViewModel (
    private val waterMarkRepository: WaterMarkRepository,
    private val dynamicColorCapability: DynamicColorCapability
) : ViewModel() {

    // StateFlow (CMP-ready, plan C1.1) — was LiveData via asLiveData(); KMP has no LiveData.
    val waterMark: StateFlow<WaterMark?> = waterMarkRepository.waterMark.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    fun toggleBounds(enable: Boolean) {
        launch {
            waterMarkRepository.toggleBounds(enable)
        }
    }

    fun toggleSupportDynamicColor(enable: Boolean) {
        dynamicColorCapability.setForcedSupport(enable)
    }
}
