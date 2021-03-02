package me.rosuh.easywatermark.ui.about

import android.graphics.Bitmap
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import me.rosuh.easywatermark.model.UserConfig
import me.rosuh.easywatermark.repo.UserConfigRepo

class AboutViewModel : ViewModel() {

    private val repo = UserConfigRepo
    val userConfig: MutableLiveData<UserConfig> = repo.userConfig

    fun saveOutput(format: Bitmap.CompressFormat, level: Int) {
        userConfig.value?.apply {
            outputFormat = format
            compressLevel = level
        }
        userConfig.value = userConfig.value
    }
}