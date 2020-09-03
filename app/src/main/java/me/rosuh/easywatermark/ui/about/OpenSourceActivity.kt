package me.rosuh.easywatermark.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_open_source.*
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.ktx.openLink

class OpenSourceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_open_source)
        setSupportActionBar(findViewById(R.id.my_toolbar))
        supportActionBar?.apply {
            title = getString(R.string.about_title_open_source)
            setDisplayHomeAsUpEnabled(true)
        }

        card_color_picker.setOnClickListener {
            openLink("https://github.com/skydoves/ColorPickerView")
        }

        card_material_about_library.setOnClickListener {
            openLink("https://github.com/daniel-stoneuk/material-about-library")
        }

        card_material_components.setOnClickListener {
            openLink("https://github.com/material-components/material-components-android")
        }

        card_material_compressor.setOnClickListener {
            openLink("https://github.com/zetbaitsu/Compressor/")
        }
    }
}