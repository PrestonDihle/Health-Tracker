package com.prestondihle.healthtracker.domain

import kotlin.math.floor

/**
 * Body composition by waist-to-height ratio, the standard in force since
 * 1 January 2026.
 *
 * Source: the Secretary of War's 30 September 2025 direction on military fitness
 * standards, and the Under Secretary's follow-up memorandum of 18 December 2025
 * setting the implementation date across the Joint Force. **Height and weight
 * tables are no longer used to evaluate body composition** -- the AR 600-9
 * screening table this was originally going to implement has been retired, not
 * supplemented.
 *
 * The whole standard is one division and one comparison, which is the point of
 * it: waist circumference divided by height must be **less than 0.55**. There is
 * no table, no age bracket and no sex column, so unlike the AFT this can be
 * computed from a profile that has set neither.
 *
 * Two details are load-bearing and easy to lose:
 *
 * - **Both measurements are recorded in inches, rounded *down* to the nearest
 *   half inch.** Down, not to nearest -- and applied to both, which pulls the
 *   ratio in opposite directions: flooring the waist makes it smaller and
 *   flooring the height makes it larger. Rounding either the convenient way
 *   would move a real pass or fail.
 * - **The limit is strictly less than 0.55.** Exactly 0.55 is over it. A `<=`
 *   here would pass somebody the standard fails.
 *
 * Where the tape goes is part of the standard too, and it is not the navel: the
 * midpoint between the last palpable rib and the top of the iliac crest, which
 * usually lands at or just above the belly button. That belongs in the UI beside
 * the entry rather than here, but it is why a waist logged for a trouser size is
 * not the measurement this wants.
 */
object BodyComposition {

    /** The upper limit, which the ratio must be strictly under. */
    const val MAX_RATIO = 0.55

    /**
     * How much slack the half-inch floor allows for the centimetre round trip.
     *
     * In half-inch units, so a thousandth of an inch. See [recordedInches].
     */
    private const val CONVERSION_TOLERANCE = 0.002

    /**
     * A measurement as the standard records it: inches, floored to the half.
     *
     * The tolerance is a defence against the conversion rather than a second
     * rounding. Everything is stored in centimetres, so an exact 42.5 inches
     * comes back out of `Float` as 42.4999988 -- and flooring *that* to the half
     * gives 42.0, losing half an inch to arithmetic noise in the direction that
     * flatters the reader.
     *
     * A thousandth of an inch is far below anything a tape can show and far
     * above the error being absorbed, so it only ever rescues a value that was
     * already on a half. Snapping to a quarter first would also have worked on
     * that case and been wrong on others: it rounds, so a genuine 75.4 would
     * come back 75.5, which is the one thing "rounded down" rules out.
     */
    fun recordedInches(cm: Float): Double {
        val inches = Units.cmToInches(cm).toDouble()
        return floor(inches * 2.0 + CONVERSION_TOLERANCE) / 2.0
    }

    /**
     * Waist divided by height, both as recorded, or null if either is unknown.
     *
     * Null rather than a default: an unmeasured waist is not a passing one, and
     * a made-up height would produce a verdict about a body nobody measured.
     */
    fun ratio(waistCm: Float?, heightCm: Float?): Double? {
        val waist = waistCm?.let { recordedInches(it) } ?: return null
        val height = heightCm?.let { recordedInches(it) } ?: return null
        if (height <= 0.0 || waist <= 0.0) return null
        return waist / height
    }

    /** Strictly under the limit. Exactly 0.55 is over. */
    fun passes(ratio: Double): Boolean = ratio < MAX_RATIO

    /**
     * The largest recorded waist that still passes at this height, in inches.
     *
     * The threshold itself is `0.55 x height`, but a waist is only ever recorded
     * on a half inch -- so the number worth showing is the largest half inch
     * strictly under it. At 75 inches the threshold is 41.25 and the answer is
     * 41.0, because 41.5 divides to 0.5533 and fails.
     */
    fun maxPassingWaistInches(heightCm: Float?): Double? {
        val height = heightCm?.let { recordedInches(it) } ?: return null
        if (height <= 0.0) return null
        val threshold = MAX_RATIO * height
        val halves = floor(threshold * 2.0) / 2.0
        // A threshold landing exactly on a half inch is itself over the limit,
        // so the last passing measurement is the half below it.
        return if (halves >= threshold) halves - 0.5 else halves
    }
}
