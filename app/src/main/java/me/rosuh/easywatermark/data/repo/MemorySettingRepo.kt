package me.rosuh.easywatermark.data.repo

import androidx.palette.graphics.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.MyApp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemorySettingRepo @Inject constructor() {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _palette: MutableStateFlow<Palette?> = MutableStateFlow(null)

    val paletteFlow = _palette.stateIn(MyApp.applicationScope, SharingStarted.Eagerly, null)

    fun updatePalette(palette: Palette?) {
        scope.launch {
            _palette.emit(palette)
        }
    }
}