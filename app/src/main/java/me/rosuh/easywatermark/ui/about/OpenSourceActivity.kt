package me.rosuh.easywatermark.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.rosuh.easywatermark.databinding.ActivityOpenSourceBinding
import me.rosuh.easywatermark.utils.ktx.inflate
import me.rosuh.easywatermark.utils.ktx.openLink

class OpenSourceActivity : AppCompatActivity() {

    private val binding by inflate<ActivityOpenSourceBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
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
