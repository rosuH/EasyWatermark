package me.rosuh.easywatermark.repo

import androidx.lifecycle.MutableLiveData
import me.rosuh.easywatermark.model.UserConfig

object UserConfigRepo {
    val userConfig: MutableLiveData<UserConfig> = MutableLiveData(UserConfig.pull())
}