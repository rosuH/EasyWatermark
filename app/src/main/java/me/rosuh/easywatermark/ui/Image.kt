package me.rosuh.easywatermark.ui

import android.net.Uri

data class Image(
    val id: Int,
    val uri: Uri,
    val name: String,
    val size: Long,
    val date: Long,
    var check: Boolean = false
)