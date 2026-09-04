package me.rosuh.easywatermark.utils.ktx

import android.app.Activity
import android.content.Intent
import android.net.Uri

fun Activity.openLink(url: String, failedCallback: (() -> Unit)? = null) {
    openLink(Uri.parse(url), failedCallback)
}

fun Activity.openLink(uri: Uri, failedCallback: (() -> Unit)? = null) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
        i.data = uri
        startActivity(i)
    } catch (e: Exception) {
        e.printStackTrace()
        failedCallback?.invoke()
    }
}
