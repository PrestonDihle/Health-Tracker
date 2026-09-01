package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.HealthDay
import com.prestondihle.healthtracker.health.HourlySteps
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A day that ended while nobody was looking, and the read that goes back for it.
 *
 * `syncHealthData` was for a long time only ever called with *today*, from the
 * two screens that sync. So a day's snapshot held whatever Health Connect had at
 * the last moment the app happened to be open on it, and nothing ever asked
 * again. On the author's own phone all thirty-one cached days had been written
 * before their own midnight -- two hours short of it on average -- and their step
 * counts sat thousands under what the watch's app reported for the same dates.
 * The fault can only undercount, and it is silent: a frozen figure looks exactly
 * like a figure.
 *
 * These tests pin both halves of the fix. A finished day only read part-way
 * through is read again; one already read properly is not, and that second half
 * is what keeps this from being a full re-sync of the week on every refresh.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FinishedDaySyncTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.now(zone)
    private val yesterday: LocalDate = today.minusDays(1)

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * A source whose step count grows between reads, and which counts them.
     *
     * The growth stands in for the rest of a day arriving after the app was last
     * open on it. Counting the reads is what separates "went back for it" from
     * "goes back for it every time", which is the difference between a fix and a
     * week of Health Connect round trips on every refresh.
     */
    private class CountingSource(private val zone: ZoneId) :
        HealthDataSource by MockHealthDataSource(zone) {
        val reads = mutableListOf<LocalDate>()
        private val stepReads = mutableListOf<LocalDate>()

        override suspend fun readDay(date: LocalDate): HealthDay {
            reads += date
            return HealthDay(date = date)
        }

        /**
         * Steps come from here rather than from [readDay] now, because the day's
         * total is the sum of its merged slices.
         *
         * One slice per read, worth 1,000 the first time a given day is asked
         * for and 2,000 the second -- the growth standing in for the rest of a
         * day arriving after the app was last open on it. Counted separately
         * from [reads] so the two assertions stay independent of which of the
         * two the sync happens to call first.
         */
        override suspend fun readStepsByHour(
            from: Instant,
            to: Instant,
            preferredStepsPackage: String?,
        ): List<HourlySteps> {
            val date = from.atZone(zone).toLocalDate()
            stepReads += date
            return listOf(HourlySteps(from, 1_000 * stepReads.count { it == date }))
        }
    }

    private fun repository(source: HealthDataSource): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), source, zone)
    }

    /** Mid-afternoon yesterday: the last time the app was opened that day. */
    private val yesterdayAfternoon: Instant
        get() = yesterday.atStartOfDay(zone).toInstant().plus(Duration.ofHours(15))

    private val thisMorning: Instant
        get() = today.atStartOfDay(zone).toInstant().plus(Duration.ofHours(7))

    @Test
    fun `a day only read part-way through is read again once it has ended`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)

        // Yesterday as it stood at three in the afternoon -- which is where it
        // stayed, because nothing ever asked about it again.
        repository.syncHealthData(yesterday, now = yesterdayAfternoon)
        assertEquals(1_000, repository.getHealthSnapshot(yesterday).first()?.steps)

        val recovered = repository.resyncFinishedDays(today, thisMorning).getOrThrow()

        // Seven rather than one: yesterday because it froze mid-afternoon, and
        // the six before it because a fresh cache holds no row for them at all.
        // Both are the same fault from either end -- a day nobody asked about.
        assertEquals(7, recovered)
        assertEquals(2, source.reads.count { it == yesterday })
        assertEquals(2_000, repository.getHealthSnapshot(yesterday).first()?.steps)
    }

    @Test
    fun `a day already read after it ended is not read again`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)

        repository.syncHealthData(yesterday, now = yesterdayAfternoon)
        assertEquals(7, repository.resyncFinishedDays(today, thisMorning).getOrThrow())

        // The guard is what makes this cheap enough to run on every refresh: a
        // re-read stamps a time after the day's own end, so the day stops
        // qualifying and every later pass costs one local query and stops.
        val readsAfterFirstPass = source.reads.size
        assertEquals(0, repository.resyncFinishedDays(today, thisMorning).getOrThrow())
        assertEquals(0, repository.resyncFinishedDays(today, thisMorning).getOrThrow())
        assertEquals(readsAfterFirstPass, source.reads.size)
    }

    @Test
    fun `today is left alone, because it has not finished`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)

        repository.syncHealthData(today, now = thisMorning)
        repository.resyncFinishedDays(today, thisMorning)

        // Today's snapshot is *supposed* to be a running total. Re-reading it
        // here would be a second read of the same figures a moment apart, and it
        // could never stop qualifying however often the sweep ran.
        assertEquals(1, source.reads.count { it == today })
    }

    @Test
    fun `a day the app was never opened on is filled in rather than left blank`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)

        // No snapshot at all: nothing ran that day, so no row was ever written.
        // That draws as a hole on every chart fed by the daily cache, which
        // reads as a day that did not happen rather than one nobody asked about.
        val threeDaysAgo = today.minusDays(3)
        assertNull(repository.getHealthSnapshot(threeDaysAgo).first())

        repository.resyncFinishedDays(today, thisMorning)

        assertEquals(1_000, repository.getHealthSnapshot(threeDaysAgo).first()?.steps)
    }

    @Test
    fun `the sweep reaches a week back and no further`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)

        repository.resyncFinishedDays(today, thisMorning)

        val read = source.reads.toSet()
        assertEquals(7, read.size)
        assertTrue(today.minusDays(7) in read)
        assertTrue(today.minusDays(8) !in read)
        assertTrue(today !in read)
    }

    /**
     * The stamp that both proves the re-read happened and stops it happening
     * twice.
     *
     * Steps are the figure this was reported against, but the fault is in the row
     * rather than in the column: every field on the snapshot froze together, and
     * `syncedAt` is the one that says so.
     */
    @Test
    fun `a re-read day is stamped past its own end, and so is finished with`() = runBlocking {
        val repository = repository(MockHealthDataSource(zone))
        val midnight = today.atStartOfDay(zone).toInstant()

        repository.syncHealthData(yesterday, now = yesterdayAfternoon)
        assertTrue(repository.getHealthSnapshot(yesterday).first()!!.syncedAt.isBefore(midnight))

        repository.resyncFinishedDays(today, thisMorning).getOrThrow()

        assertTrue(repository.getHealthSnapshot(yesterday).first()!!.syncedAt.isAfter(midnight))
    }
}
