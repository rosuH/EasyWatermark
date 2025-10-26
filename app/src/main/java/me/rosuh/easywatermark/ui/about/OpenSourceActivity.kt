package me.rosuh.easywatermark.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import me.rosuh.easywatermark.databinding.ActivityOpenSourceBinding
import me.rosuh.easywatermark.utils.ktx.doOnApplyWindowInsets
import me.rosuh.easywatermark.utils.ktx.inflate
import me.rosuh.easywatermark.utils.ktx.openLink

class OpenSourceActivity : AppCompatActivity() {

    private val binding by inflate<ActivityOpenSourceBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(binding.root)
        binding.root.doOnApplyWindowInsets { view, insets, initialPadding ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                left = initialPadding.left + systemBars.left,
                top = initialPadding.top + systemBars.top,
                right = initialPadding.right + systemBars.right,
                bottom = initialPadding.bottom + systemBars.bottom
            )
        }
        setSupportActionBar(binding.myToolbar)
        binding.myToolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        binding.cardColorPicker.setOnClickListener {
            openLink("https://github.com/skydoves/ColorPickerView")
        }

        binding.cardGlideLibrary.setOnClickListener {
            openLink("https://github.com/bumptech/glide")
        }

        binding.cardMaterialComponents.setOnClickListener {
            openLink("https://github.com/material-components/material-components-android")
        }

        binding.cardMaterialCompressor.setOnClickListener {
            openLink("https://github.com/zetbaitsu/Compressor/")
        }
    }
}
