package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.Sex
import com.prestondihle.healthtracker.domain.AftEvent
import com.prestondihle.healthtracker.domain.AftLane
import com.prestondihle.healthtracker.domain.AftScorecard
import com.prestondihle.healthtracker.domain.AftScoring
import com.prestondihle.healthtracker.domain.AftTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AFT conversion tables, pinned against the numbers the Army publishes.
 *
 * These anchors are transcribed by hand from HQDA EXORD 218-25 Annex B rather
 * than generated alongside [AftTables], which is the only arrangement that makes
 * them worth having: a test built from the same extraction as the table it is
 * checking agrees with itself no matter how badly both are wrong. Every figure
 * below was read off the published scale.
 *
 * One anchor per event at 100, at the 60-point pass mark, and somewhere in the
 * middle -- the three places a scale can be wrong in different ways. A table
 * shifted by a row still gets 100 right; a table read from the wrong column
 * still gets the shape right; only checking all three catches both.
 */
class AftScoringTest {

    /** Seconds, written the way the tables print them. */
    private fun mmss(minutes: Int, seconds: Int) = minutes * 60 + seconds

    private fun male(event: AftEvent, raw: Int, age: Int = 24) =
        AftScoring.score(event, raw, age, Sex.MALE)

    private fun female(event: AftEvent, raw: Int, age: Int = 24) =
        AftScoring.score(event, raw, age, Sex.FEMALE)

    @Test
    fun `each event scores its hundred-point row`() {
        // 22-26 male, the band the Army quotes in its own prose.
        assertEquals(100, male(AftEvent.DEADLIFT, 350))
        assertEquals(100, male(AftEvent.PUSH_UP, 61))
        assertEquals(100, male(AftEvent.SPRINT_DRAG_CARRY, mmss(1, 30)))
        assertEquals(100, male(AftEvent.PLANK, mmss(3, 35)))
        assertEquals(100, male(AftEvent.TWO_MILE_RUN, mmss(13, 25)))
    }

    @Test
    fun `each event scores its sixty-point row`() {
        // The published minimums for a 22-26 male: 150 lb, 14 repetitions,
        // 2:31, 1:25 and 19:45. These are the figures a Soldier actually plans
        // against, and the row most worth getting right.
        assertEquals(60, male(AftEvent.DEADLIFT, 150))
        assertEquals(60, male(AftEvent.PUSH_UP, 14))
        assertEquals(60, male(AftEvent.SPRINT_DRAG_CARRY, mmss(2, 31)))
        assertEquals(60, male(AftEvent.PLANK, mmss(1, 25)))
        assertEquals(60, male(AftEvent.TWO_MILE_RUN, mmss(19, 45)))
    }

    @Test
    fun `each event scores a row from the middle of the scale`() {
        assertEquals(81, male(AftEvent.DEADLIFT, 250))
        assertEquals(80, male(AftEvent.PUSH_UP, 37))
        assertEquals(80, male(AftEvent.SPRINT_DRAG_CARRY, mmss(1, 53)))
        assertEquals(80, male(AftEvent.PLANK, mmss(2, 30)))
        assertEquals(81, male(AftEvent.TWO_MILE_RUN, mmss(17, 8)))
    }

    @Test
    fun `the female column is a different scale, not the male one shifted`() {
        assertEquals(100, female(AftEvent.DEADLIFT, 230))
        assertEquals(82, female(AftEvent.DEADLIFT, 160))
        assertEquals(60, female(AftEvent.DEADLIFT, 120))

        assertEquals(100, female(AftEvent.PUSH_UP, 50))
        assertEquals(80, female(AftEvent.PUSH_UP, 23))
        assertEquals(60, female(AftEvent.PUSH_UP, 11))

        assertEquals(100, female(AftEvent.SPRINT_DRAG_CARRY, mmss(1, 55)))
        assertEquals(80, female(AftEvent.SPRINT_DRAG_CARRY, mmss(2, 29)))
        assertEquals(60, female(AftEvent.SPRINT_DRAG_CARRY, mmss(3, 15)))

        assertEquals(100, female(AftEvent.TWO_MILE_RUN, mmss(15, 30)))
        assertEquals(80, female(AftEvent.TWO_MILE_RUN, mmss(19, 25)))
        assertEquals(60, female(AftEvent.TWO_MILE_RUN, mmss(22, 45)))
    }

    @Test
    fun `a middle age band is scored on its own numbers`() {
        // 42-46, far enough from 22-26 that reading the wrong band shows up.
        assertEquals(76, male(AftEvent.DEADLIFT, 220, age = 44))
        assertEquals(75, male(AftEvent.PUSH_UP, 29, age = 44))
        assertEquals(75, male(AftEvent.SPRINT_DRAG_CARRY, mmss(2, 13), age = 44))
        assertEquals(75, male(AftEvent.TWO_MILE_RUN, mmss(18, 35), age = 44))
        assertEquals(75, female(AftEvent.PUSH_UP, 18, age = 44))
    }

    /**
     * The combat lane is the male column, whoever is reading it.
     *
     * This is the whole of what "sex-neutral" means here, and it is the claim
     * most likely to be got wrong by assuming a third set of tables exists. The
     * published scales carry one `M | C` column and one `F` column per band --
     * there is no separate combat scale to look up.
     */
    @Test
    fun `the combat lane scores a woman on the male column`() {
        AftEvent.entries.forEach { event ->
            val raw =
                when (event) {
                    AftEvent.DEADLIFT -> 250
                    AftEvent.PUSH_UP -> 37
                    AftEvent.SPRINT_DRAG_CARRY -> mmss(1, 53)
                    AftEvent.PLANK -> mmss(2, 30)
                    AftEvent.TWO_MILE_RUN -> mmss(17, 8)
                }
            assertEquals(
                "combat ${event.abbreviation} should read the male column",
                AftScoring.score(event, raw, 24, Sex.MALE, AftLane.GENERAL),
                AftScoring.score(event, raw, 24, Sex.FEMALE, AftLane.COMBAT),
            )
        }
    }

    @Test
    fun `the scale steps rather than interpolating`() {
        // 22-26 male deadlift lists 350 at 100, 340 at 99 and 330 at 97. Nothing
        // between two steps earns the value in between: 345 is worth what 340 is
        // worth, and 335 what 330 is. Interpolating would award a 98 the Army
        // does not have a row for.
        assertEquals(99, male(AftEvent.DEADLIFT, 345))
        assertEquals(97, male(AftEvent.DEADLIFT, 335))
        assertEquals(97, male(AftEvent.DEADLIFT, 339))
    }

    @Test
    fun `falling short of the floor scores below sixty rather than nothing`() {
        // 130 lb is the 50-point row. A Soldier under the minimum has still
        // lifted something, and a zero would say they did not turn up.
        assertEquals(50, male(AftEvent.DEADLIFT, 130))
        assertEquals(50, male(AftEvent.DEADLIFT, 135))
        assertEquals(0, male(AftEvent.DEADLIFT, 80))
        assertEquals(0, male(AftEvent.DEADLIFT, 20))
    }

    /**
     * An unset sex has no general column to read, and says so.
     *
     * Null rather than a guess or a zero: the profile ships with sex unset, and
     * defaulting it would quietly score everyone against one scale and look
     * exactly like a real result.
     */
    @Test
    fun `an unset sex blocks the general lane but not the combat one`() {
        assertNull(AftScoring.score(AftEvent.DEADLIFT, 250, 24, Sex.UNSPECIFIED))
        assertNull(AftScoring.score(AftEvent.DEADLIFT, 250, 24, Sex.UNSPECIFIED, AftLane.GENERAL))
        // Sex-neutral means it genuinely does not need to ask.
        assertEquals(81, AftScoring.score(AftEvent.DEADLIFT, 250, 24, Sex.UNSPECIFIED, AftLane.COMBAT))
    }

    @Test
    fun `an unset age cannot be scored at all`() {
        assertNull(AftScoring.score(AftEvent.DEADLIFT, 250, null, Sex.MALE))
        assertNull(AftScoring.score(AftEvent.DEADLIFT, 250, null, Sex.MALE, AftLane.COMBAT))
    }

    @Test
    fun `age bands clamp at both ends`() {
        // The table starts at 17 and its top band is open-ended, so both ends
        // are clamps rather than refusals.
        assertEquals(AftScoring.bandIndex(17), AftScoring.bandIndex(15))
        assertEquals(AftScoring.bandIndex(62), AftScoring.bandIndex(81))
        assertEquals(0, AftScoring.bandIndex(21))
        assertEquals(1, AftScoring.bandIndex(22))
        assertEquals(9, AftScoring.bandIndex(62))
    }

    /**
     * The plank is one scale for everyone, and that is the table's own doing.
     *
     * Checked across every band and row rather than sampled, because it is the
     * kind of fact that is true until an update quietly makes it false -- and a
     * plank card built on the assumption would then be scoring women wrongly
     * with nothing to show for it.
     */
    @Test
    fun `the plank scale is identical for both sexes`() {
        AftTables.PLK_M.indices.forEach { band ->
            assertTrue(
                "plank band $band should match between columns",
                AftTables.PLK_M[band].contentEquals(AftTables.PLK_F[band]),
            )
        }
    }

    /**
     * Two rows of the published run tables are out of order, and running faster
     * must never cost points.
     *
     * Female 47-51 lists 21:45 at 71 points and 21:40 at 70; female 52-56 lists
     * 24:01 at 61 and 24:00 at 60. Both look like an adjacent pair transposed in
     * the source. Taking the best row a Soldier qualifies for reads them the
     * only way that cannot punish a faster time, which is why 21:40 scores the
     * 71 and not the 70.
     *
     * The second one straddles the pass mark, so 24:01 scores 61 and passes.
     * That is what the table says. Pinned rather than silently corrected,
     * because a correction here would fail a Soldier the Army's own scorecard
     * passes -- and if the table is reissued, this test is what notices.
     */
    @Test
    fun `the two inverted rows in the run table never cost a faster runner points`() {
        assertEquals(71, female(AftEvent.TWO_MILE_RUN, mmss(21, 45), age = 49))
        assertEquals(71, female(AftEvent.TWO_MILE_RUN, mmss(21, 40), age = 49))
        assertEquals(72, female(AftEvent.TWO_MILE_RUN, mmss(21, 37), age = 49))

        assertEquals(61, female(AftEvent.TWO_MILE_RUN, mmss(24, 1), age = 54))
        assertEquals(61, female(AftEvent.TWO_MILE_RUN, mmss(24, 0), age = 54))
        assertEquals(59, female(AftEvent.TWO_MILE_RUN, mmss(24, 3), age = 54))
    }

    @Test
    fun `every published series runs the right way`() {
        // A table read from the wrong column or off by a row usually stops being
        // monotonic somewhere, which no single anchor would catch. The two known
        // inversions above are the only ones allowed.
        val series =
            mapOf(
                AftEvent.DEADLIFT to (AftTables.MDL_M to AftTables.MDL_F),
                AftEvent.PUSH_UP to (AftTables.HRP_M to AftTables.HRP_F),
                AftEvent.SPRINT_DRAG_CARRY to (AftTables.SDC_M to AftTables.SDC_F),
                AftEvent.PLANK to (AftTables.PLK_M to AftTables.PLK_F),
                AftEvent.TWO_MILE_RUN to (AftTables.TWO_MILE_M to AftTables.TWO_MILE_F),
            )
        var inversions = 0
        series.forEach { (event, columns) ->
            listOf(columns.first, columns.second).forEach { column ->
                column.forEach { band ->
                    var i = 0
                    while (i + 3 < band.size) {
                        // Rows descend in points, so the next row's requirement
                        // must be easier than this one's.
                        val easier =
                            if (event.higherIsBetter) band[i + 3] <= band[i + 1]
                            else band[i + 3] >= band[i + 1]
                        if (!easier) inversions++
                        i += 2
                    }
                }
            }
        }
        assertEquals("only the two documented run-table errata may be out of order", 2, inversions)
    }

    @Test
    fun `a scorecard is unjudged until all five events are in`() {
        val partial =
            AftScorecard(AftLane.GENERAL, mapOf(AftEvent.DEADLIFT to 80, AftEvent.PUSH_UP to 80))
        assertNull(partial.passes)
        // The running total is still reported -- it is just not a verdict.
        assertEquals(160, partial.total)
    }

    @Test
    fun `one failed event fails the test however high the total`() {
        val scores =
            mapOf(
                AftEvent.DEADLIFT to 100,
                AftEvent.PUSH_UP to 100,
                AftEvent.SPRINT_DRAG_CARRY to 100,
                AftEvent.PLANK to 100,
                AftEvent.TWO_MILE_RUN to 59,
            )
        val card = AftScorecard(AftLane.GENERAL, scores)
        assertEquals(459, card.total)
        assertEquals(false, card.passes)
        assertEquals(listOf(AftEvent.TWO_MILE_RUN), card.failedEvents)
    }

    @Test
    fun `the two lanes differ on the total and nothing else`() {
        val allSixty = AftEvent.entries.associateWith { 60 }
        assertEquals(300, AftScorecard(AftLane.GENERAL, allSixty).total)
        // Exactly the general minimum, and thirty short of the combat one.
        assertEquals(true, AftScorecard(AftLane.GENERAL, allSixty).passes)
        assertEquals(false, AftScorecard(AftLane.COMBAT, allSixty).passes)

        val seventies = AftEvent.entries.associateWith { 70 }
        assertEquals(350, AftScorecard(AftLane.COMBAT, seventies).total)
        assertEquals(true, AftScorecard(AftLane.COMBAT, seventies).passes)
    }

    @Test
    fun `the weakest event is the one with least room over the floor`() {
        val card =
            AftScorecard(
                AftLane.GENERAL,
                mapOf(
                    AftEvent.DEADLIFT to 90,
                    AftEvent.PUSH_UP to 64,
                    AftEvent.SPRINT_DRAG_CARRY to 88,
                    AftEvent.PLANK to 100,
                    AftEvent.TWO_MILE_RUN to 72,
                ),
            )
        assertEquals(AftEvent.PUSH_UP to 4, card.weakestEvent)
    }
}
