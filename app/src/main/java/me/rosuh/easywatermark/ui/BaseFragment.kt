package me.rosuh.easywatermark.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import me.rosuh.easywatermark.MainViewModel

open class BaseFragment : Fragment() {
    protected val shareViewModel: MainViewModel by activityViewModels()
}