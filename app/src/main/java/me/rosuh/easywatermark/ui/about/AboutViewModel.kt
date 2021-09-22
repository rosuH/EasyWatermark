package me.rosuh.easywatermark.ui.about

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import me.rosuh.easywatermark.repo.UserConfigRepository
import javax.inject.Inject

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val userRepo: UserConfigRepository
) : ViewModel() {

    val userPreferences = userRepo.userPreferences.asLiveData()

    val outputFormat: Bitmap.CompressFormat
        get() = userPreferences.value?.outputFormat ?: Bitmap.CompressFormat.JPEG

    val compressLevel: Int
        get() = userPreferences.value?.compressLevel ?: UserConfigRepository.DEFAULT_COMPRESS_LEVEL

    fun saveOutput(format: Bitmap.CompressFormat, level: Int) {
        viewModelScope.launch {
            userRepo.updateFormat(format)
            userRepo.updateCompressLevel(level)
        }
    }
}
