package com.prestondihle.healthtracker

import androidx.health.connect.client.records.ExerciseSessionRecord
import com.prestondihle.healthtracker.domain.TrainingSession
import com.prestondihle.healthtracker.domain.TrainingType
import com.prestondihle.healthtracker.domain.TrainingVolumes
import com.prestondihle.healthtracker.health.trainingTypeOf
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grouping a week of sessions into per-kind volumes, and the mapping that
 * decides which kind each one is.
 *
 * The mapping is worth pinning even though it is a `when`: a mis-mapped type is
 * invisible on screen. The session still appears, with a correct duration, under
 * the wrong heading — and the row it should have been on is quietly short.
 */
class TrainingVolumeTest {

    private val monday: Instant = Instant.parse("2026-08-24T06:00:00Z")

    private fun session(
        type: TrainingType,
        startHour: Long,
        minutes: Long,
        metres: Double? = null,
    ) =
        TrainingSession(
            type = type,
            start = monday.plus(Duration.ofHours(startHour)),
            end = monday.plus(Duration.ofHours(startHour)).plus(Duration.ofMinutes(minutes)),
            distanceMeters = metres,
        )

    @Test
    fun `sessions of one kind are summed into a single row`() {
        val volumes =
            TrainingVolumes.over(
                listOf(
                    session(TrainingType.STRENGTH, startHour = 0, minutes = 45),
                    session(TrainingType.STRENGTH, startHour = 48, minutes = 50),
                )
            )

        assertEquals(1, volumes.size)
        assertEquals(2, volumes[0].sessions)
        assertEquals(95, volumes[0].totalMinutes)
    }

    @Test
    fun `the longest kind of training comes first`() {
        // The question the card answers is "where did this week go", so the answer
        // belongs at the top rather than wherever the enum happens to put it.
        val volumes =
            TrainingVolumes.over(
                listOf(
                    session(TrainingType.WALK, startHour = 0, minutes = 20, metres = 1_600.0),
                    session(TrainingType.RUCK, startHour = 5, minutes = 120, metres = 9_000.0),
                    session(TrainingType.STRENGTH, startHour = 30, minutes = 55),
                )
            )

        assertEquals(
            listOf(TrainingType.RUCK, TrainingType.STRENGTH, TrainingType.WALK),
            volumes.map { it.type },
        )
    }

    @Test
    fun `a kind with no sessions is absent rather than listed at zero`() {
        val volumes = TrainingVolumes.over(listOf(session(TrainingType.RUN, 0, 30, 5_000.0)))

        assertEquals(listOf(TrainingType.RUN), volumes.map { it.type })
    }

    @Test
    fun `strength has no distance, and that is null rather than zero`() {
        // Ground rule 6 where it bites: rendered as 0.0 mi this would be a
        // measurement nobody made, and it would sit next to real ones.
        val volumes = TrainingVolumes.over(listOf(session(TrainingType.STRENGTH, 0, 45)))

        assertNull(volumes[0].totalMeters)
        assertNull(volumes[0].paceSecondsPerMile)
    }

    @Test
    fun `a group is not credited with a distance because one session had a GPS`() {
        // Two walks, one with a lock and one without. The distance is the one that
        // was recorded -- not a total that quietly implies both were measured.
        val volumes =
            TrainingVolumes.over(
                listOf(
                    session(TrainingType.WALK, startHour = 0, minutes = 30, metres = 3_000.0),
                    session(TrainingType.WALK, startHour = 4, minutes = 30, metres = null),
                )
            )

        assertEquals(3_000.0, volumes[0].totalMeters!!, 0.001)
        assertEquals(60, volumes[0].totalMinutes)
    }

    @Test
    fun `pace is the group's whole time over its whole distance`() {
        // Not a mean of per-session paces: a twenty-minute stroll would otherwise
        // weigh as much as a two-hour ruck in the figure meant to describe the ruck.
        val volumes =
            TrainingVolumes.over(
                listOf(
                    // 3 miles in 60 minutes, then 1 mile in 10. Four miles, 70
                    // minutes: 17:30 a mile, not the 20:00 the two paces average to.
                    session(
                        TrainingType.RUCK,
                        startHour = 0,
                        minutes = 60,
                        metres = 3 * 1609.344,
                    ),
                    session(
                        TrainingType.RUCK,
                        startHour = 8,
                        minutes = 10,
                        metres = 1609.344,
                    ),
                )
            )

        assertEquals(70 * 60 / 4, volumes[0].paceSecondsPerMile)
    }

    @Test
    fun `pace is withheld off foot even when a distance was recorded`() {
        // A cycling "pace" in minutes per mile invites being read as a run.
        val volumes =
            TrainingVolumes.over(
                listOf(session(TrainingType.CYCLE, 0, 60, metres = 30_000.0))
            )

        assertEquals(30_000.0, volumes[0].totalMeters!!, 0.001)
        assertNull(volumes[0].paceSecondsPerMile)
    }

    @Test
    fun `a zero distance yields no pace rather than an enormous one`() {
        val volumes = TrainingVolumes.over(listOf(session(TrainingType.RUCK, 0, 60, metres = 0.0)))

        assertNull(volumes[0].paceSecondsPerMile)
    }

    @Test
    fun `the fenix types map to the kinds the card groups by`() {
        assertEquals(TrainingType.RUN, trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals(
            TrainingType.RUN,
            trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL),
        )
        // The one that matters most here: a ruck reaches the watch as a hike, and
        // hiking mapping anywhere else makes rucks vanish from the card built for
        // them.
        assertEquals(TrainingType.RUCK, trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_HIKING))
        assertEquals(TrainingType.WALK, trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals(
            TrainingType.STRENGTH,
            trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING),
        )
        assertEquals(
            TrainingType.STRENGTH,
            trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING),
        )
        assertEquals(TrainingType.CYCLE, trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_BIKING))
        assertEquals(
            TrainingType.SWIM,
            trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL),
        )
        assertEquals(
            TrainingType.HIIT,
            trainingTypeOf(
                ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
            ),
        )
    }

    @Test
    fun `an unmapped type lands in Other rather than being dropped`() {
        // An hour of training that happened and is not shown is a worse error than
        // an hour shown under a vague heading.
        assertEquals(
            TrainingType.OTHER,
            trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT),
        )
        assertEquals(TrainingType.OTHER, trainingTypeOf(ExerciseSessionRecord.EXERCISE_TYPE_YOGA))
    }

    @Test
    fun `rucks and runs are told apart rather than pooled as exercise`() {
        // The whole point of widening the read: before this, a ruck reached the app
        // as nothing at all.
        val volumes =
            TrainingVolumes.over(
                listOf(
                    session(TrainingType.RUN, 0, 30, metres = 5_000.0),
                    session(TrainingType.RUCK, 6, 90, metres = 7_000.0),
                )
            )

        assertEquals(2, volumes.size)
        assertTrue(volumes.any { it.type == TrainingType.RUCK && it.totalMinutes == 90 })
        assertTrue(volumes.any { it.type == TrainingType.RUN && it.totalMinutes == 30 })
    }
}
