package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.StepMerge
import com.prestondihle.healthtracker.health.HourlySteps
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when more than one app counts the same legs.
 *
 * Both of the obvious answers are wrong, in opposite directions and by
 * thousands: summing counts a walk twice, pinning one app loses whatever only
 * the others saw. These pin the third answer, and the case that matters most is
 * the last one -- the real day this was diagnosed against, where the pinned
 * figure and the summed figure sit either side of the truth.
 */
class StepMergeTest {

    private val midnight: Instant = Instant.parse("2026-08-31T00:00:00Z")

    private fun slice(quarter: Int, steps: Int) =
        HourlySteps(midnight.plus(Duration.ofMinutes(15L * quarter)), steps)

    @Test
    fun `two origins reporting the same quarter hour are not added together`() {
        val merged = StepMerge.merge(listOf(listOf(slice(4, 620)), listOf(slice(4, 604))))

        // Both watched the same walk from opposite wrists. 1,224 would be a
        // second walk that never happened.
        assertEquals(1, merged.size)
        assertEquals(620, merged.single().steps)
    }

    @Test
    fun `a stretch only one origin saw is kept whole`() {
        val watch = listOf(slice(0, 300), slice(1, 250))
        val phone = listOf(slice(2, 4_100), slice(3, 3_500))

        val merged = StepMerge.merge(listOf(watch, phone))

        // Disjoint in time: the union, not the intersection and not one of them.
        assertEquals(listOf(300, 250, 4_100, 3_500), merged.map { it.steps })
        assertEquals(8_150, merged.sumOf { it.steps })
    }

    @Test
    fun `three origins are folded together, not just the first two`() {
        val merged =
            StepMerge.merge(
                listOf(listOf(slice(0, 100)), listOf(slice(0, 900)), listOf(slice(0, 400)))
            )

        // AllTrails and a fitness app both hold Health Connect access on the
        // phone this was written for, so two writers is the floor rather than
        // the ceiling.
        assertEquals(900, merged.single().steps)
    }

    @Test
    fun `an origin reporting zero for a quarter hour does not erase another's steps`() {
        val merged = StepMerge.merge(listOf(listOf(slice(6, 0)), listOf(slice(6, 780))))

        assertEquals(780, merged.single().steps)
    }

    @Test
    fun `a quarter hour every origin reports as zero survives as a zero`() {
        val merged = StepMerge.merge(listOf(listOf(slice(6, 0)), listOf(slice(6, 0))))

        // Dropped instead, the cache could never overwrite a stale figure for
        // that slice: "no steps here" is a statement, and absence upstream
        // already means something else.
        assertEquals(1, merged.size)
        assertEquals(0, merged.single().steps)
    }

    @Test
    fun `nothing in means nothing out`() {
        assertTrue(StepMerge.merge(emptyList()).isEmpty())
        assertTrue(StepMerge.merge(listOf(emptyList(), emptyList())).isEmpty())
    }

    @Test
    fun `one origin alone is returned as it stands`() {
        val only = listOf(slice(0, 120), slice(1, 340))

        assertEquals(only, StepMerge.merge(listOf(only)))
    }

    @Test
    fun `slices come back in time order however the origins were ordered`() {
        val merged =
            StepMerge.merge(listOf(listOf(slice(9, 5), slice(1, 5)), listOf(slice(4, 5))))

        assertEquals(merged.map { it.hourStart }, merged.map { it.hourStart }.sorted())
    }

    /**
     * 31 August 2026, the day the bug was reported on.
     *
     * The watch's own app displayed 12,656. Health Connect's Garmin origin held
     * 5,607, because Garmin writes no step records for a tracked activity and
     * that evening carried a 35-minute run. The unfiltered sum came to 13,265 on
     * a day the phone mostly sat on a desk.
     *
     * The assertion worth having is not a target figure -- Garmin's total is not
     * reachable from Health Connect and pretending otherwise is ground rule 7 --
     * but the *ordering*: merged has to land strictly between the two numbers the
     * app could previously produce, which is the one claim that says it fixed
     * something rather than traded one error for another.
     */
    @Test
    fun `the day this was diagnosed on lands between the pinned figure and the sum`() {
        // The watch: 5,607 spread evenly across sixteen waking quarter hours.
        val watch = (24..39).map { slice(it, 350) } + slice(40, 7)
        // The phone: a modest daytime trickle under the same hours, then the run
        // window the watch has nothing at all for.
        val phoneDaytime = (24..39).map { slice(it, 40) }
        val phoneRun = (91..95).map { slice(it, 1_420) }

        val merged = StepMerge.merge(listOf(watch, phoneDaytime + phoneRun))
        val mergedTotal = merged.sumOf { it.steps }

        val pinned = watch.sumOf { it.steps }
        val summed = pinned + (phoneDaytime + phoneRun).sumOf { it.steps }

        assertEquals(5_607, pinned)
        assertTrue("merged must beat the pinned figure", mergedTotal > pinned)
        assertTrue("merged must not reach the double-counted sum", mergedTotal < summed)

        // The watch's whole day plus the run it never wrote, with the phone's
        // duplicate daytime trickle dropped rather than added.
        assertEquals(12_707, mergedTotal)
        assertEquals(13_347, summed)
    }
}
