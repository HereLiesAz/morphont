package com.hereliesaz.transfontmation

import kotlin.math.abs
import kotlin.math.round

/** `String.format("%.2f", ...)` isn't available outside the JVM -- this is the KMP-safe equivalent. */
fun fmt2(v: Float): String {
    val sign = if (v < 0) "-" else ""
    val scaled = round(abs(v) * 100f).toInt()
    val whole = scaled / 100
    val frac = scaled % 100
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$sign$whole.$fracStr"
}
