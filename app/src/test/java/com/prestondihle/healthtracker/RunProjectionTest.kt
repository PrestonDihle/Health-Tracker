package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.health.RunSession
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The join between a run session and the two-mile projection.
 *
 * `RunPaceTest` covers the arithmetic; what this covers is the mapping, which is
 * where the mistakes actually live: taking elapsed time from the session's own
 * bounds, passing a null distance through as null rather than as zero, and
 * ignoring runs too short to speak to the distance instead of counting them.
 * A sign error or a swapped start and end here would produce a plausible number
 * from the same correct maths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RunProjectionTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-08-27T12:00:00Z")

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** A source with a fixed set of runs and everything else delegated. */
    private class Runs(private val delegate: MockHealthDataSource, private val runs: List<RunSession>) :
        HealthDataSource by delegate {
        override suspend fun readRuns(from: Instant, to: Instant): List<RunSession> =
            runs.filter { !it.start.isBefore(from) && !it.end.isAfter(to) }
    }

    private fun repository(runs: List<RunSession>): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), Runs(MockHealthDataSource(zone), runs), zone)
    }

    private fun run(startedHoursAgo: Long, minutes: Long, metres: Double?) =
        RunSession(
            start = now.minus(Duration.ofHours(startedHoursAgo)),
            end = now.minus(Duration.ofHours(startedHoursAgo)).plus(Duration.ofMinutes(minutes)),
            distanceMeters = metres,
        )

    @Test
    fun `elapsed time comes from the session's own bounds`() = runBlocking {
        // A 5 km in 25 minutes: two miles at that average is 16:05.
        val repository = repository(listOf(run(startedHoursAgo = 24, minutes = 25, metres = 5_000.0)))

        val projected =
            repository.getBestTwoMileSeconds(now.minus(Duration.ofDays(90)), now)

        assertEquals(965, projected)
    }

    @Test
    fun `the quickest qualifying run wins`() = runBlocking {
        val repository =
            repository(
                listOf(
                    run(startedHoursAgo = 72, minutes = 60, metres = 10_000.0),
                    run(startedHoursAgo = 48, minutes = 23, metres = 5_000.0),
                    run(startedHoursAgo = 24, minutes = 30, metres = 5_000.0),
                )
            )

        // 23 minutes over 5 km is the quickest of the three.
        assertEquals(888, repository.getBestTwoMileSeconds(now.minus(Duration.ofDays(90)), now))
    }

    /**
     * A fast mile must not become a two-mile time.
     *
     * The failure this guards is the one that would look most convincing: a hard
     * mile extrapolated up projects a score nobody ran, and it would sit on the
     * card indistinguishable from one that was earned.
     */
    @Test
    fun `runs shorter than two miles are ignored, not extrapolated`() = runBlocking {
        val repository =
            repository(
                listOf(
                    run(startedHoursAgo = 24, minutes = 5, metres = 1_609.344),
                    run(startedHoursAgo = 48, minutes = 8, metres = 2_500.0),
                )
            )

        assertNull(repository.getBestTwoMileSeconds(now.minus(Duration.ofDays(90)), now))
    }

    @Test
    fun `a run with no distance recorded projects nothing`() = runBlocking {
        val repository = repository(listOf(run(startedHoursAgo = 24, minutes = 25, metres = null)))

        assertNull(repository.getBestTwoMileSeconds(now.minus(Duration.ofDays(90)), now))
    }

    @Test
    fun `runs outside the window do not count`() = runBlocking {
        // Anything older than the window is a memory rather than a projection.
        val repository =
            repository(listOf(run(startedHoursAgo = 24 * 200, minutes = 20, metres = 5_000.0)))

        assertNull(repository.getBestTwoMileSeconds(now.minus(Duration.ofDays(90)), now))
    }
}
