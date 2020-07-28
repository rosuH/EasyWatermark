package me.rosuh.easywatermark.ui.fragment

import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import me.rosuh.easywatermark.ui.MainViewModel

open class BaseFragment : Fragment() {
    protected val shareViewModel: MainViewModel by activityViewModels()
}