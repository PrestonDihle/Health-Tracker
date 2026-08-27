package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.RunPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a run is allowed to say about a distance it covered, and what it is not.
 *
 * The whole risk here is a confident number about a distance nobody ran. A fast
 * half mile extrapolated to two would produce a projected AFT score that looks
 * exactly like one earned, which is the failure this suite exists to prevent.
 */
class RunPaceTest {

    private val twoMiles = RunPace.TWO_MILE_METRES

    @Test
    fun `an exact two miles reports its own elapsed time`() {
        // Nothing to normalise: the run is the distance being asked about.
        assertEquals(960, RunPace.normalizedSeconds(twoMiles, 960, twoMiles))
    }

    @Test
    fun `a longer run is scaled down to the distance`() {
        // A 5 km in 25 minutes is 8:03 per mile, so two miles at that average
        // pace is a little over sixteen minutes.
        val projected = RunPace.normalizedSeconds(5_000.0, 1_500, twoMiles)!!
        assertEquals(965, projected)
        // Sanity in the other direction: the projection is shorter than the run
        // it came from, because the distance is.
        assert(projected < 1_500)
    }

    /**
     * The rule that matters most: a short run says nothing.
     *
     * Extrapolating up would turn a hard half-mile into a two-mile time nobody
     * ran, and the AFT card would score it. Null is the only honest answer.
     */
    @Test
    fun `a run shorter than the distance projects nothing`() {
        assertNull(RunPace.normalizedSeconds(1_609.344, 400, twoMiles))
        assertNull(RunPace.normalizedSeconds(3_218.0, 900, twoMiles))
        // A hair over is fine -- it is the distance that matters, not a margin.
        assertEquals(900, RunPace.normalizedSeconds(3_218.688, 900, twoMiles))
    }

    @Test
    fun `a run with no distance or no time says nothing`() {
        assertNull(RunPace.normalizedSeconds(null, 900, twoMiles))
        assertNull(RunPace.normalizedSeconds(5_000.0, 0, twoMiles))
        // A session whose end precedes its start is corrupt, not instantaneous.
        assertNull(RunPace.normalizedSeconds(5_000.0, -60, twoMiles))
    }

    @Test
    fun `the best run wins and the short ones are ignored rather than counted`() {
        val runs =
            listOf(
                // A slow long run.
                10_000.0 to 3_600L,
                // A quicker 5 km -- the one that should win.
                5_000.0 to 1_400L,
                // A fast mile, too short to speak to two. Counted, it would win
                // outright and project a time never run.
                1_609.344 to 330L,
                // No distance recorded.
                null to 1_200L,
            )

        val best = RunPace.bestNormalizedSeconds(runs)!!
        assertEquals(901, best)
        // Emphatically not the mile's pace doubled, which would be 660.
        assert(best > 660)
    }

    @Test
    fun `no qualifying run projects nothing at all`() {
        assertNull(RunPace.bestNormalizedSeconds(listOf(1_000.0 to 240L, null to 900L)))
        assertNull(RunPace.bestNormalizedSeconds(emptyList()))
    }

    @Test
    fun `the mile is the same maths at a different distance`() {
        // The existing best-mile stat uses this shape; pinning it here keeps the
        // two from drifting if one is ever moved onto the other.
        assertEquals(482, RunPace.normalizedSeconds(5_000.0, 1_500, RunPace.METRES_PER_MILE))
    }
}
