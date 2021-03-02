package me.rosuh.easywatermark.base

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import me.rosuh.easywatermark.model.WaterMarkConfig
import me.rosuh.easywatermark.ui.MainViewModel

open class BaseFragment : Fragment() {

    protected val shareViewModel: MainViewModel by activityViewModels()

    protected val config: WaterMarkConfig?
        get() {
            return shareViewModel.config.value
        }
}