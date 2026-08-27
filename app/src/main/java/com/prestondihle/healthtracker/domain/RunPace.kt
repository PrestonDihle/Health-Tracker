package com.prestondihle.healthtracker.domain

/**
 * Average pace over a distance, from runs that were at least that long.
 *
 * Health Connect has no split concept: a session carries a total distance and a
 * start and end, and nothing about how the pace moved inside it. So the only
 * honest figure available is **elapsed time divided by distance, normalised to
 * the target** -- an average over the whole run rather than the best stretch of
 * it. On a long run with a warmup in it that reads slower than a real effort
 * over the distance, and on an interval session it reads slower still.
 *
 * That is why everything derived from this is labelled a projection and never a
 * time. It is a model, and the same rule that keeps a modelled curve dashed
 * applies to a number: it has to say what it is.
 */
object RunPace {

    /** Metres in a statute mile. */
    const val METRES_PER_MILE = 1609.344

    /** The AFT's two miles, which a projected 2MR score is normalised to. */
    const val TWO_MILE_METRES = 2 * METRES_PER_MILE

    /**
     * Seconds this run implies over [targetMetres], or null if it says nothing.
     *
     * Null on three counts, all of them "no answer" rather than a bad one: a run
     * with no distance recorded, a run shorter than the distance being asked
     * about, and a run with no elapsed time. **Extrapolating a shorter run up to
     * the distance is the one thing this must not do** -- a fast half mile says
     * very little about two, and a projected score built from it would be a
     * confident number about a distance nobody ran.
     */
    fun normalizedSeconds(metres: Double?, elapsedSeconds: Long, targetMetres: Double): Int? {
        if (metres == null || metres < targetMetres) return null
        if (elapsedSeconds <= 0L) return null
        return (elapsedSeconds.toDouble() * targetMetres / metres).toInt()
    }

    /**
     * The quickest pace any of these runs implies over [targetMetres].
     *
     * Best rather than most recent: a projection is about what the runner can
     * do, and the slowest of a fortnight's easy runs is not that. Runs too short
     * to speak to the distance are ignored rather than counted as failures.
     */
    fun bestNormalizedSeconds(
        runs: List<Pair<Double?, Long>>,
        targetMetres: Double = TWO_MILE_METRES,
    ): Int? = runs.mapNotNull { (metres, elapsed) -> normalizedSeconds(metres, elapsed, targetMetres) }.minOrNull()
}
