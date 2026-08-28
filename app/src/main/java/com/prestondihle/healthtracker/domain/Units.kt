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

    /**
     * Kilograms as whole pounds, rounded rather than truncated.
     *
     * For the deadlift, whose scoring table is published in pounds and stepped
     * in tens of them while the value is stored in kilograms like every other
     * weight here. 150 lb round-trips to 149.99999 in floating point, and a
     * truncating conversion would read that as 149 and fail a Soldier who hit
     * the minimum exactly -- the same rounding trap as [mlToWholeOz], with a
     * pass mark riding on it.
     */
    fun kgToWholeLbs(kg: Float): Int = kgToLbs(kg).roundToInt()

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

    /**
     * Metres to miles, for the session distances Health Connect records in metres.
     *
     * Here rather than at the point of use for the reason every other conversion
     * is: the storage unit is metric and the display unit is not, and a division
     * written inline is a second place for the constant to be wrong.
     */
    fun metresToMiles(metres: Double): Double = metres / RunPace.METRES_PER_MILE
}
