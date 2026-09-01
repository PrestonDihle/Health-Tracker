package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.domain.SleepStage
import com.prestondihle.healthtracker.domain.SleepStageInterval
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.health.SleepSessionSample
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What happens to a night across a sync, which is where sleep differs from every
 * other cache here.
 *
 * A tracker scores a night when it ends and **re-scores it** after the morning's
 * processing, and the second scoring routinely has fewer stretches than the
 * first. Upsert alone cannot express that: the stretches that no longer exist
 * survive, and the hypnogram comes out flicking between two readings of the same
 * hour. Nothing in the type system prevents a later change from "simplifying"
 * the delete away, and every pure-JVM test would still pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SleepSyncTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val bedtime: Instant = Instant.parse("2026-08-24T23:00:00Z")

    /** A source offering one night, whose stages the test controls. */
    private class OneNight(
        private val delegate: MockHealthDataSource,
        var night: SleepSessionSample,
    ) : HealthDataSource by delegate {
        override suspend fun readSleepSessions(from: Instant, to: Instant): List<SleepSessionSample> =
            if (night.end.isAfter(from) && night.start.isBefore(to)) listOf(night) else emptyList()
    }

    private fun repository(source: HealthDataSource): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), source, zone)
    }

    /** Stretches laid end to end from bedtime. */
    private fun stages(vararg spec: Pair<SleepStage, Long>): List<SleepStageInterval> {
        var cursor = bedtime
        return spec.map { (stage, minutes) ->
            val end = cursor.plus(Duration.ofMinutes(minutes))
            SleepStageInterval(cursor, end, stage).also { cursor = end }
        }
    }

    private fun sessionOf(stages: List<SleepStageInterval>) =
        SleepSessionSample(
            start = bedtime,
            end = stages.last().end,
            stages = stages,
            externalId = "hc-night",
        )

    private val window: Pair<Instant, Instant>
        get() = bedtime.minus(Duration.ofHours(1)) to bedtime.plus(Duration.ofHours(12))

    @Test
    fun `a synced night arrives with its stages`() {
        val first = stages(SleepStage.LIGHT to 60L, SleepStage.DEEP to 45L, SleepStage.REM to 30L)
        val repository = repository(OneNight(MockHealthDataSource(zone), sessionOf(first)))

        runBlocking {
            val (from, to) = window
            repository.syncTimeSeries(from, to)

            val night = repository.getSleepNight().first()
            assertNotNull(night)
            assertEquals(bedtime, night!!.start)
            assertEquals(3, night.stages.size)
            assertEquals(Duration.ofMinutes(135), night.totalAsleep)
            assertEquals(Duration.ofMinutes(45), night.deep)
        }
    }

    @Test
    fun `syncing the same night twice does not double its stages`() {
        val only = stages(SleepStage.LIGHT to 60L, SleepStage.DEEP to 45L)
        val repository = repository(OneNight(MockHealthDataSource(zone), sessionOf(only)))

        runBlocking {
            val (from, to) = window
            repository.syncTimeSeries(from, to)
            repository.syncTimeSeries(from, to)

            val night = repository.getSleepNight().first()
            assertEquals(2, night!!.stages.size)
            // The figure that would give a doubled table away on the card.
            assertEquals(Duration.ofMinutes(105), night.totalAsleep)
        }
    }

    @Test
    fun `a re-scored night replaces its stages rather than accumulating them`() {
        // The failure this whole delete-before-rewrite exists for. The morning's
        // re-scoring merges the two light stretches into one, so the new reading
        // has *fewer* rows than the old -- and an upsert keyed on the stage start
        // leaves the vanished stretch behind, where it is counted twice over.
        val asScoredOvernight =
            stages(
                SleepStage.LIGHT to 30L,
                SleepStage.AWAKE to 10L,
                SleepStage.LIGHT to 20L,
                SleepStage.DEEP to 60L,
            )
        val source = OneNight(MockHealthDataSource(zone), sessionOf(asScoredOvernight))
        val repository = repository(source)

        runBlocking {
            val (from, to) = window
            repository.syncTimeSeries(from, to)
            assertEquals(4, repository.getSleepNight().first()!!.stages.size)

            // Re-scored: the waking is gone and the light sleep is one stretch.
            source.night = sessionOf(stages(SleepStage.LIGHT to 60L, SleepStage.DEEP to 60L))
            repository.syncTimeSeries(from, to)

            val night = repository.getSleepNight().first()!!
            assertEquals(2, night.stages.size)
            assertEquals(Duration.ZERO, night.awake)
            assertEquals(Duration.ofMinutes(60), night.light)
            assertEquals(Duration.ofMinutes(120), night.totalAsleep)
        }
    }

    @Test
    fun `a night is found by a window it only overlaps`() {
        // The query anchors on overlap, not on containment. A morning window
        // opening after a night began must still find it, or the master graph
        // shades nothing on exactly the zoom where it explains most.
        val only = stages(SleepStage.LIGHT to 120L, SleepStage.DEEP to 60L)
        val repository = repository(OneNight(MockHealthDataSource(zone), sessionOf(only)))

        runBlocking {
            val (from, to) = window
            repository.syncTimeSeries(from, to)

            // Starts an hour *after* the night began and runs past its end.
            val nights =
                repository.getSleepNightsSince(bedtime.plus(Duration.ofHours(1))).first()

            assertEquals(1, nights.size)
            // Returned whole, not trimmed to the window: the shade has to be able
            // to run to the plot's edge rather than start inside it.
            assertEquals(bedtime, nights.first().start)
            assertTrue(nights.first().stages.isNotEmpty())
        }
    }
}
