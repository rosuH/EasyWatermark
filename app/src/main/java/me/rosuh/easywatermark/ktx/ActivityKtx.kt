package me.rosuh.easywatermark.ktx

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.viewbinding.ViewBinding
import me.rosuh.easywatermark.R

fun Activity.openLink(url: String) {
    val i = Intent(Intent.ACTION_VIEW)
    i.data = Uri.parse(url)
    startActivity(i)
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
            R.anim.fragment_close_out,
            R.anim.fragment_close_in,
            R.anim.fragment_close_out
        )
        body.invoke(this)
    }
}