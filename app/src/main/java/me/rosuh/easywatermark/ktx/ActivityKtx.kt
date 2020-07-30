package me.rosuh.easywatermark.ktx

import android.app.Activity
import android.content.Intent
import android.net.Uri

fun Activity.openLink(url: String) {
    val i = Intent(Intent.ACTION_VIEW)
    i.data = Uri.parse(url)
    startActivity(i)
}