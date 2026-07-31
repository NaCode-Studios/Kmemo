package dev.kmemo.internal

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * The two bits of number formatting the reports need, without `String.format`.
 *
 * `String.format` is a JVM API and these strings are read by people, not parsed, so the requirement is
 * small: a fixed number of decimals, always with `.` as the separator, and columns that line up. A
 * locale-aware formatter would be worse here, not better — a calibration table whose decimal separator
 * follows the machine's locale is a table that reads differently on two developers' laptops.
 */
internal object Format {

    /** [value] with exactly [decimals] places, rounded half-up, `.` as the separator. */
    fun fixed(value: Double, decimals: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"

        val scale = 10.0.pow(decimals)
        val scaled = (abs(value) * scale).roundToLong()
        val whole = scaled / scale.toLong()
        val fraction = scaled % scale.toLong()
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return if (decimals == 0) "$sign$whole" else "$sign$whole.${fraction.toString().padStart(decimals, '0')}"
    }

    /** [text] padded with spaces on the left to [width], for a column that lines up. */
    fun padStart(text: String, width: Int): String = text.padStart(width)
}
