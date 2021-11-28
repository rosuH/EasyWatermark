package me.rosuh.easywatermark.utils.ktx

import android.content.Context
import androidx.palette.graphics.Palette

fun Palette?.bgColor(context: Context): Int {
    if (this == null) {
        return context.colorSurfaceVariant
    }
    return if (context.isNight() || !context.supportNight()) {
        (this.darkMutedSwatch?.rgb ?: this.mutedSwatch?.rgb) ?: context.colorSurfaceVariant
    } else {
        (this.lightMutedSwatch?.rgb ?: this.mutedSwatch?.rgb) ?: context.colorSurfaceVariant
    }
}

fun Palette?.titleTextColor(context: Context): Int {
    if (this == null) {
        return context.colorOnSurfaceVariant
    }
    return if (context.isNight() || !context.supportNight()) {
        (this.darkMutedSwatch?.titleTextColor ?: this.mutedSwatch?.titleTextColor)
            ?: context.colorOnSurfaceVariant
    } else {
        (this.lightMutedSwatch?.titleTextColor ?: this.mutedSwatch?.titleTextColor)
            ?: context.colorOnSurfaceVariant
    }
}