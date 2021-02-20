package me.rosuh.easywatermark.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewbinding.ViewBinding

abstract class BaseBindingActivity<VB : ViewBinding> : AppCompatActivity() {

    private val lazyViewBinding = lazy {
        initViewBinding()
    }

    protected val binding: VB by lazyViewBinding

    abstract fun initViewBinding(): VB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
    }

    fun VB.setUp(action: VB.() -> Unit) {
        action.invoke(this)
    }
}