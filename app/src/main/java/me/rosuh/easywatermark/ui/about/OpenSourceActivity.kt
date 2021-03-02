package me.rosuh.easywatermark.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.databinding.ActivityOpenSourceBinding
import me.rosuh.easywatermark.ktx.inflate
import me.rosuh.easywatermark.ktx.openLink

class OpenSourceActivity : AppCompatActivity() {

    private val binding by inflate<ActivityOpenSourceBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_source)
        setSupportActionBar(findViewById(R.id.my_toolbar))
        supportActionBar?.apply {
            title = getString(R.string.about_title_open_source)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.cardColorPicker.setOnClickListener {
            openLink("https://github.com/skydoves/ColorPickerView")
        }

        binding.cardMaterialAboutLibrary.setOnClickListener {
            openLink("https://github.com/daniel-stoneuk/material-about-library")
        }

        binding.cardMaterialComponents.setOnClickListener {
            openLink("https://github.com/material-components/material-components-android")
        }

        binding.cardMaterialCompressor.setOnClickListener {
            openLink("https://github.com/zetbaitsu/Compressor/")
        }
    }
}