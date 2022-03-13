package me.rosuh.easywatermark.utils.ktx

import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide

fun ImageView.loadSmall(uri: Uri, placeholder: Int = 0) {
    Glide.with(this)
        .asBitmap()
        .placeholder(placeholder)
        .load(uri)
        .into(this)
}