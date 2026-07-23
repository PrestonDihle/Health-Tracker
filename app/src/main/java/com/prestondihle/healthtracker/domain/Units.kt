package com.prestondihle.healthtracker.domain

import java.time.Duration
import kotlin.math.roundToInt

/**
 * Conversions between the metric values in the database and the imperial values
 * shown in the UI.
 *
 * Storage is metric because that is what Health Connect speaks; converting once
 * at the display boundary keeps rounding error out of the stored data.
 */
object Units {
    const val CM_PER_INCH = 2.54
    const val ML_PER_FL_OZ = 29.5735
    const val KG_PER_LB = 0.45359237

    fun cmToInches(cm: Float): Float = (cm / CM_PER_INCH).toFloat()

    fun inchesToCm(inches: Float): Float = (inches * CM_PER_INCH).toFloat()

    fun mlToFlOz(ml: Int): Float = (ml / ML_PER_FL_OZ).toFloat()

    /**
     * Millilitres as whole fluid ounces, rounded rather than truncated.
     *
     * The 100 oz default goal is stored as 2957 ml, which divides to 99.99 --
     * truncating displayed that as "99 oz".
     */
    fun mlToWholeOz(ml: Int): Int = mlToFlOz(ml).roundToInt()

    fun flOzToMl(flOz: Float): Int = (flOz * ML_PER_FL_OZ).roundToInt()

    fun kgToLbs(kg: Float): Float = (kg / KG_PER_LB).toFloat()

    fun lbsToKg(lbs: Float): Float = (lbs * KG_PER_LB).toFloat()

    /** Snaps to the nearest quarter, so the waist stepper never drifts off its grid. */
    fun roundToQuarter(value: Float): Float = (value * 4f).roundToInt() / 4f

    /**
     * Renders a waist measurement as whole inches plus a vulgar fraction --
     * `42"`, `42 1/4"` -- which reads faster than `42.25"` on a tape measure.
     */
    fun formatInches(inches: Float): String {
        val snapped = roundToQuarter(inches)
        val whole = snapped.toInt()
        return when (((snapped - whole) * 4f).roundToInt()) {
            1 -> "$whole 1/4\""
            2 -> "$whole 1/2\""
            3 -> "$whole 3/4\""
            else -> "$whole\""
        }
    }

    /** `18h 42m`, or `42m` under an hour. Used for fast duration. */
    fun formatDuration(duration: Duration): String {
        val totalMinutes = duration.toMinutes().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** `7:42` from a mile time in seconds. */
    fun formatPace(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    /** `6h 20m` from a minute count, for sleep. */
    fun formatMinutes(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
