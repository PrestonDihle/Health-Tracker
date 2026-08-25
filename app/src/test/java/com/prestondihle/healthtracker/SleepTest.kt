package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.Sleep
import com.prestondihle.healthtracker.domain.SleepNight
import com.prestondihle.healthtracker.domain.SleepStage
import com.prestondihle.healthtracker.domain.SleepStageInterval
import com.prestondihle.healthtracker.domain.level
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a night adds up to, and what the hypnogram is allowed to draw.
 *
 * Two failure modes are worth pinning at once. Counting waking time as sleep
 * flatters every night by however long was spent staring at the ceiling, and
 * drawing an unstaged stretch at a stage's height reports a measurement the
 * source never made -- and both would look entirely plausible on the card.
 */
class SleepTest {

    private val bedtime: Instant = Instant.parse("2026-08-24T23:00:00Z")

    /** Stretches laid end to end from bedtime, as a real night arrives. */
    private fun night(vararg stages: Pair<SleepStage, Long>): SleepNight {
        var cursor = bedtime
        val intervals =
            stages.map { (stage, minutes) ->
                val end = cursor.plus(Duration.ofMinutes(minutes))
                SleepStageInterval(cursor, end, stage).also { cursor = end }
            }
        return SleepNight(start = bedtime, end = cursor, stages = intervals)
    }

    @Test
    fun `time asleep excludes waking, and time in bed does not`() {
        // The distinction the whole card rests on: eight hours between the
        // covers with forty minutes of waking in it is a seven-twenty night.
        val slept =
            night(
                SleepStage.LIGHT to 200L,
                SleepStage.AWAKE to 40L,
                SleepStage.DEEP to 120L,
                SleepStage.REM to 120L,
            )

        assertEquals(Duration.ofMinutes(480), slept.timeInBed)
        assertEquals(Duration.ofMinutes(440), slept.totalAsleep)
        assertEquals(Duration.ofMinutes(40), slept.awake)
    }

    @Test
    fun `each stage totals only its own stretches`() {
        val slept =
            night(
                SleepStage.LIGHT to 30L,
                SleepStage.DEEP to 45L,
                SleepStage.LIGHT to 25L,
                SleepStage.REM to 20L,
                SleepStage.DEEP to 15L,
            )

        assertEquals(Duration.ofMinutes(55), slept.light)
        assertEquals(Duration.ofMinutes(60), slept.deep)
        assertEquals(Duration.ofMinutes(20), slept.rem)
        // The three named stages are the total here, since nothing was awake or
        // unstaged -- which is what makes the sum worth asserting.
        assertEquals(Duration.ofMinutes(135), slept.totalAsleep)
    }

    @Test
    fun `unstaged sleep counts toward the total but is never drawn`() {
        // A writer is allowed to say "asleep" without saying which stage. That
        // is sleep and has to be counted, and it is not light sleep and must not
        // be plotted as though it were.
        val slept = night(SleepStage.ASLEEP to 300L, SleepStage.REM to 60L)

        assertEquals(Duration.ofMinutes(360), slept.totalAsleep)
        assertEquals(Duration.ofMinutes(300), slept.unstaged)
        assertEquals(Duration.ZERO, slept.light)
        assertNull(SleepStage.ASLEEP.level)

        // Two points, both from the REM stretch: the unstaged five hours
        // contribute nothing to the trace.
        val plotted = Sleep.hypnogram(slept.stages)
        assertEquals(2, plotted.size)
        plotted.forEach { assertEquals(SleepStage.REM.level, it.value) }
    }

    @Test
    fun `a stretch is drawn as a tread, and the join between two is the riser`() {
        // The hypnogram has no step primitive behind it -- it is an ordinary line
        // through two points per stretch. That only draws a step if consecutive
        // stretches share an instant, so the pairing is load-bearing rather than
        // incidental.
        val slept = night(SleepStage.LIGHT to 30L, SleepStage.DEEP to 30L)

        val plotted = Sleep.hypnogram(slept.stages)

        assertEquals(4, plotted.size)
        // The tread: two points at one height, at the two ends of the stretch.
        assertEquals(plotted[0].value, plotted[1].value)
        assertNotEquals(plotted[0].time, plotted[1].time)
        // The riser: two points at one instant, at two heights.
        assertEquals(plotted[1].time, plotted[2].time)
        assertNotEquals(plotted[1].value, plotted[2].value)
    }

    @Test
    fun `deep sits below light, which sits below REM, which sits below awake`() {
        // A hypnogram read upside down says the opposite of what happened, and
        // every published one falls as sleep deepens. Nothing else in the app
        // would notice this being reversed.
        val deep = SleepStage.DEEP.level!!
        val light = SleepStage.LIGHT.level!!
        val rem = SleepStage.REM.level!!
        val awake = SleepStage.AWAKE.level!!

        assertTrue(deep < light)
        assertTrue(light < rem)
        assertTrue(rem < awake)
        // Both ends have to be inside the axis or the outermost stage is clipped
        // off the plot and simply never appears.
        assertTrue(deep >= Sleep.PLOT_MIN)
        assertTrue(awake <= Sleep.PLOT_MAX)
    }

    @Test
    fun `every drawable stage has a label in the gutter`() {
        // The axis prints names rather than numbers, and a level with no name
        // prints an empty string -- which reads as a missing gridline, not as a
        // missing label.
        SleepStage.entries.mapNotNull { it.level }.forEach {
            assertTrue(Sleep.formatLevel(it).isNotBlank())
        }
    }

    @Test
    fun `the stages are read in order however they arrive`() {
        // Health Connect does not promise an order, and a trace drawn in the
        // order received would zigzag back through the night.
        val third = SleepStageInterval(bedtime.plusSeconds(7200), bedtime.plusSeconds(9000), SleepStage.REM)
        val first = SleepStageInterval(bedtime, bedtime.plusSeconds(3600), SleepStage.LIGHT)
        val second = SleepStageInterval(bedtime.plusSeconds(3600), bedtime.plusSeconds(7200), SleepStage.DEEP)

        val plotted = Sleep.hypnogram(listOf(third, first, second))

        assertEquals(plotted.map { it.time }, plotted.map { it.time }.sorted())
        assertEquals(SleepStage.LIGHT.level, plotted.first().value)
        assertEquals(SleepStage.REM.level, plotted.last().value)
    }

    @Test
    fun `a night with no stages is still a night`() {
        // A source may record a session and nothing finer. The card falls back to
        // time in bed rather than showing a chart of nothing and a zero total, so
        // neither of those may throw.
        val bare = SleepNight(start = bedtime, end = bedtime.plusSeconds(28_800), stages = emptyList())

        assertEquals(Duration.ofHours(8), bare.timeInBed)
        assertEquals(Duration.ZERO, bare.totalAsleep)
        assertTrue(Sleep.hypnogram(bare.stages).isEmpty())
    }

    @Test
    fun `durations read as hours and minutes, and drop the hour when there is none`() {
        assertEquals("7h 24m", Sleep.formatDuration(Duration.ofMinutes(444)))
        assertEquals("44m", Sleep.formatDuration(Duration.ofMinutes(44)))
        assertEquals("0m", Sleep.formatDuration(Duration.ZERO))
        // Eight hours dead has to say the minutes, or it reads as a rounded
        // figure rather than a measured one.
        assertEquals("8h 0m", Sleep.formatDuration(Duration.ofHours(8)))
    }
}
