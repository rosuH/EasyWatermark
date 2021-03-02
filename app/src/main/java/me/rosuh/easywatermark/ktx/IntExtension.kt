package me.rosuh.easywatermark.ktx

import android.util.TypedValue
import me.rosuh.easywatermark.MyApp

val Int.dp
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(), MyApp.instance.resources.displayMetrics
    )
