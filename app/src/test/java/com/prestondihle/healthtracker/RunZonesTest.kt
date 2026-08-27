package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.domain.RunZone
import com.prestondihle.healthtracker.domain.RunZones
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the run-intensity zones and the time-in-zone accounting the Runs chart is
 * built from. The boundaries are the part that drifts silently: a `<` slipped to
 * a `<=` moves a whole band of readings into the wrong colour without failing to
 * compile.
 */
class RunZonesTest {

    private val max = 200

    @Test
    fun `zone boundaries are closed at the bottom`() {
        // Below 60% is Easy; 60% starts Moderate; 75% Hard; 90% Intense. A reading
        // exactly on a boundary belongs to the harder zone.
        assertEquals(RunZone.EASY, RunZones.zoneFor(119, max)) // 59.5%
        assertEquals(RunZone.MODERATE, RunZones.zoneFor(120, max)) // 60%
        assertEquals(RunZone.MODERATE, RunZones.zoneFor(149, max)) // 74.5%
        assertEquals(RunZone.HARD, RunZones.zoneFor(150, max)) // 75%
        assertEquals(RunZone.HARD, RunZones.zoneFor(179, max)) // 89.5%
        assertEquals(RunZone.INTENSE, RunZones.zoneFor(180, max)) // 90%
        assertEquals(RunZone.INTENSE, RunZones.zoneFor(220, max)) // over max
    }

    @Test
    fun `a sample holds its zone until the next reading`() {
        val start = Instant.parse("2025-01-01T08:00:00Z")
        val samples =
            listOf(
                start to 100, // 50% -> Easy, held one minute
                start.plus(Duration.ofMinutes(1)) to 160, // 80% -> Hard, held one minute
                start.plus(Duration.ofMinutes(2)) to 190, // 95% -> Intense, held to end
            )
        val run =
            RunZones.breakdown(
                start = start,
                runEnd = start.plus(Duration.ofMinutes(3)),
                distanceMeters = null,
                samples = samples,
                maxHeartRate = max,
            )

        assertEquals(1f, run.easyMinutes, 0.001f)
        assertEquals(0f, run.moderateMinutes, 0.001f)
        assertEquals(1f, run.hardMinutes, 0.001f)
        assertEquals(1f, run.intenseMinutes, 0.001f)
        assertEquals(3f, run.totalMinutes, 0.001f)
    }

    @Test
    fun `a long gap between samples is capped, not credited whole`() {
        // A watch paused at a red light leaves ten minutes between two readings.
        // Crediting all ten to the zone it stopped in would invent a long easy
        // stretch that never happened; the cap holds it to three minutes.
        val start = Instant.parse("2025-01-01T08:00:00Z")
        val samples =
            listOf(
                start to 100, // Easy
                start.plus(Duration.ofMinutes(10)) to 100, // Easy, but the last sample
            )
        val run =
            RunZones.breakdown(
                start = start,
                runEnd = start.plus(Duration.ofMinutes(10)),
                distanceMeters = null,
                samples = samples,
                maxHeartRate = max,
            )

        // The first sample's ten-minute gap to the second is capped at three
        // minutes; the second is the last and `runEnd` is its own timestamp, so it
        // adds nothing. Uncapped the first would have credited the full ten.
        assertEquals(3f, run.easyMinutes, 0.001f)
        assertEquals(3f, run.totalMinutes, 0.001f)
    }

    @Test
    fun `a run with no heart rate comes back empty rather than wrong`() {
        val start = Instant.parse("2025-01-01T08:00:00Z")
        val run =
            RunZones.breakdown(
                start = start,
                runEnd = start.plus(Duration.ofMinutes(30)),
                distanceMeters = 5000.0,
                samples = emptyList(),
                maxHeartRate = max,
            )

        assertEquals(0f, run.totalMinutes, 0.001f)
        assertEquals(listOf(0f, 0f, 0f, 0f), run.segments)
    }
}
