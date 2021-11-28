package me.rosuh.easywatermark.utils.ktx

import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide

fun ImageView.loadSmall(uri: Uri) {
    Glide.with(this)
        .asBitmap()
        .override(measuredWidth, measuredHeight)
        .load(uri)
        .into(this)
}