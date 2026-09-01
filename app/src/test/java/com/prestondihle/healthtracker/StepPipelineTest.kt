package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.UserSettings
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.HealthDay
import com.prestondihle.healthtracker.health.HourlySteps
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The day's step count and the chart under it, which used to be two numbers.
 *
 * The Activity card read `HealthDaySnapshot.steps`, written from a day-level
 * aggregate by the daily sync; the bars beneath it were drawn from `StepBucket`,
 * written by the rolling time-series sync. Two writers with two refresh policies
 * agreed only when they happened to run in the same minute, and on the author's
 * phone they had drifted 9,040 steps apart on one day (2,043 against 11,083).
 * The snapshot's figure is now the sum of that day's buckets by construction,
 * and the first test here is what says so.
 *
 * The rest are about which apps' steps go into those buckets. The mock writes
 * two origins that overlap all day and diverge in the evening -- a watch that
 * records nothing for a tracked activity, and a phone that does -- which is the
 * shape the real fault had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StepPipelineTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.now(zone)
    private val yesterday: LocalDate = today.minusDays(1)

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun repository(
        source: HealthDataSource = MockHealthDataSource(zone)
    ): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), source, zone)
    }

    /** The buckets stored for one local day, which is what the chart draws. */
    private suspend fun bucketsOn(repository: TrackerRepository, date: LocalDate): List<Int> {
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return repository
            .getStepBucketsSince(date.atStartOfDay(zone).toInstant())
            .first()
            .filter { it.bucketStartMillis < end }
            .map { it.steps }
    }

    @Test
    fun `the day's steps are the sum of the day's own buckets`() = runBlocking {
        val repository = repository()

        repository.syncHealthData(yesterday)

        val snapshot = repository.getHealthSnapshot(yesterday).first()
        val buckets = bucketsOn(repository, yesterday)

        assertTrue("the day should have written some buckets", buckets.isNotEmpty())
        assertNotNull(snapshot?.steps)
        assertEquals(buckets.sum(), snapshot?.steps)
    }

    /**
     * The regression this pins is not that the two numbers are close -- it is
     * that one read produces both. A second sync of the same day must leave them
     * equal rather than moving one of them.
     */
    @Test
    fun `re-syncing a day rewrites the card and the chart together`() = runBlocking {
        val repository = repository()

        repository.syncHealthData(yesterday)
        repository.syncHealthData(yesterday)

        val snapshot = repository.getHealthSnapshot(yesterday).first()
        assertEquals(bucketsOn(repository, yesterday).sum(), snapshot?.steps)
    }

    @Test
    fun `merging beats pinning the watch and does not reach the sum of both apps`() = runBlocking {
        val watchOnly = repository()
        watchOnly.upsertUserSettings(
            UserSettings(preferredStepsPackage = MockHealthDataSource.WATCH_PACKAGE)
        )
        watchOnly.syncHealthData(yesterday)
        val pinned = watchOnly.getHealthSnapshot(yesterday).first()?.steps

        val merged = repository()
        merged.syncHealthData(yesterday)
        val mergedSteps = merged.getHealthSnapshot(yesterday).first()?.steps

        val summed = merged.stepSources(yesterday).sumOf { it.steps }

        assertNotNull(pinned)
        assertNotNull(mergedSteps)
        // The evening the watch wrote nothing for is the whole gap: pinned to it,
        // the app cannot see a tracked activity at all.
        assertTrue("merged should recover what the watch missed", mergedSteps!! > pinned!!)
        // And the phone's duplicate daytime trickle is dropped rather than added,
        // which is the error pointing the other way.
        assertTrue("merged must not double-count the shared hours", mergedSteps < summed)
    }

    @Test
    fun `pinning an app answers for that app alone`() = runBlocking {
        val repository = repository()
        repository.upsertUserSettings(
            UserSettings(preferredStepsPackage = MockHealthDataSource.WATCH_PACKAGE)
        )
        repository.syncHealthData(yesterday)

        val watchTotal =
            repository.stepSources(yesterday).single {
                it.packageName == MockHealthDataSource.WATCH_PACKAGE
            }
        assertEquals(watchTotal.steps, repository.getHealthSnapshot(yesterday).first()?.steps)
    }

    /**
     * A day Health Connect has nothing for is a hole, not a day of no walking.
     *
     * Zero would put a floor point on the trend, break the step-goal streak and
     * pull every weekly mean down -- and it would look exactly like a day spent
     * sitting still, which is ground rule 6's whole argument.
     */
    @Test
    fun `a day the source returns nothing for leaves the steps null`() = runBlocking {
        val silent =
            object : HealthDataSource by MockHealthDataSource(zone) {
                override suspend fun readDay(date: LocalDate) = HealthDay(date = date)

                override suspend fun readStepsByHour(
                    from: Instant,
                    to: Instant,
                    preferredStepsPackage: String?,
                ): List<HourlySteps> = emptyList()
            }
        val repository = repository(silent)

        repository.syncHealthData(yesterday)

        assertNull(repository.getHealthSnapshot(yesterday).first()?.steps)
        assertTrue(bucketsOn(repository, yesterday).isEmpty())
    }

    /**
     * A failed read must not empty a chart that already has a day on it.
     *
     * The delete before the rewrite is bounded by what the read actually
     * returned, so nothing to write means nothing to delete.
     */
    @Test
    fun `a failed read leaves the buckets already stored alone`() = runBlocking {
        var silent = false
        val flaky =
            object : HealthDataSource by MockHealthDataSource(zone) {
                override suspend fun readStepsByHour(
                    from: Instant,
                    to: Instant,
                    preferredStepsPackage: String?,
                ): List<HourlySteps> =
                    if (silent) emptyList()
                    else MockHealthDataSource(zone).readStepsByHour(from, to, preferredStepsPackage)
            }
        val repository = repository(flaky)

        repository.syncHealthData(yesterday)
        val before = bucketsOn(repository, yesterday)

        silent = true
        repository.syncHealthData(yesterday)

        assertEquals(before, bucketsOn(repository, yesterday))
        // And the card keeps reporting what the chart is still drawing. Reading
        // the total off the failed read instead would blank one half of the
        // screen and leave the other half showing the walk.
        assertEquals(before.sum(), repository.getHealthSnapshot(yesterday).first()?.steps)
    }
}
