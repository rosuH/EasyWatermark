package me.rosuh.easywatermark.utils.ktx

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Long?.formatDate(pattern: String = "yyy_MM_dd_hh_mm"): String {
    val netDate = if (this == null) {
        Date(System.currentTimeMillis())
    } else {
        Date(this)
    }
    val sdf = SimpleDateFormat(pattern, Locale.getDefault())

    return sdf.format(netDate)
}
