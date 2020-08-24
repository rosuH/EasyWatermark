package me.rosuh.easywatermark.ktx

import java.text.SimpleDateFormat
import java.util.*

fun Long?.formatDate(): String {
    val sdf = SimpleDateFormat("yyy_MM_dd_hh_mm", Locale.getDefault())
    val netDate = Date(System.currentTimeMillis())
    return sdf.format(netDate)
}