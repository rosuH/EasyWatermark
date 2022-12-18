package me.rosuh.easywatermark.utils.ktx

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Insets
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.WindowInsets
import android.view.WindowMetrics
import androidx.fragment.app.*
import androidx.viewbinding.ViewBinding
import me.rosuh.easywatermark.R

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

inline fun <reified VB : ViewBinding> Activity.inflate() = lazy {
    inflateBinding<VB>(layoutInflater).apply { setContentView(root) }
}

inline fun <reified VB : ViewBinding> Dialog.inflate() = lazy {
    inflateBinding<VB>(layoutInflater).apply { setContentView(root) }
}

@Suppress("UNCHECKED_CAST")
inline fun <reified VB : ViewBinding> inflateBinding(layoutInflater: LayoutInflater) =
    VB::class.java.getMethod("inflate", LayoutInflater::class.java)
        .invoke(null, layoutInflater) as VB

inline fun FragmentActivity.commitWithAnimation(body: FragmentTransaction.() -> Unit) {
    supportFragmentManager.commit {
        setCustomAnimations(
            R.anim.fragment_open_in,
            R.anim.fragment_pop_exit_slide,
            R.anim.fragment_close_in,
            R.anim.fragment_pop_exit_slide
        )
        body.invoke(this)
    }
}

inline fun FragmentManager.commitWithAnimation(body: FragmentTransaction.() -> Unit) {
    this.commitNow(allowStateLoss = true) {
        setCustomAnimations(
            R.anim.fragment_open_in,
            R.anim.fragment_pop_exit_slide,
            R.anim.fragment_close_in,
            R.anim.fragment_pop_exit_slide
        )
        body.invoke(this)
    }
}

fun Activity.getScreenWidth(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowMetrics: WindowMetrics = this.windowManager.currentWindowMetrics
        val insets: Insets = windowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        windowMetrics.bounds.width() - insets.left - insets.right
    } else {
        val displayMetrics = DisplayMetrics()
        this.windowManager.defaultDisplay.getMetrics(displayMetrics)
        displayMetrics.widthPixels
    }
}

fun Activity.getScreenHeight(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val windowMetrics: WindowMetrics = this.windowManager.currentWindowMetrics
        val insets: Insets = windowMetrics.windowInsets
            .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
        windowMetrics.bounds.height() - insets.top - insets.bottom
    } else {
        val displayMetrics = DisplayMetrics()
        this.windowManager.defaultDisplay.getMetrics(displayMetrics)
        displayMetrics.heightPixels
    }
}
