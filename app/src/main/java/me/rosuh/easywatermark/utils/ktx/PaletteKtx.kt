package me.rosuh.easywatermark.utils.ktx

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.palette.graphics.Palette
import com.google.android.material.color.MaterialColors
import me.rosuh.easywatermark.R

fun Palette?.bgColor(context: Context): Int {
    if (this == null) {
        return context.colorSurfaceVariant
    }

    val platteColor = (this.darkMutedSwatch?.rgb ?: this.mutedSwatch?.rgb) ?: context.colorSurfaceVariant
    val harmonizedColor = MaterialColors.harmonize(
        platteColor, ContextCompat.getColor(context, R.color.md_theme_dark_background)
    )
    Log.i("Palette", "platteColor = $platteColor, finalColor $harmonizedColor")
    return harmonizedColor
}

fun Palette?.titleTextColor(context: Context): Int {
    if (this == null) {
        return context.colorOnSurfaceVariant
    }
    return (this.darkMutedSwatch?.titleTextColor ?: this.mutedSwatch?.titleTextColor)
        ?: context.colorOnSurfaceVariant
}