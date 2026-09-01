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
import org.junit.Assert.assertFalse
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

    /**
     * The settle window's near side: a day read an hour after it ended is still
     * open to a later look.
     *
     * This is the fault that made the previous guard wrong. A watch that syncs
     * its evening after the app's first post-midnight open lands *after* the
     * stamp, and under a read-once-ever rule it was invisible for ever. 31
     * August was re-read at 15:47 the next afternoon; anything flushed later
     * that day would never have been seen.
     */
    @Test
    fun `a day read soon after it ended is still read again the next day`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)
        val dayEnd = today.atStartOfDay(zone).toInstant()

        repository.syncHealthData(yesterday, now = dayEnd.plus(Duration.ofHours(1)))
        assertEquals(1_000, repository.getHealthSnapshot(yesterday).first()?.steps)

        assertTrue(repository.syncFinishedDay(yesterday, dayEnd.plus(Duration.ofHours(25))))
        assertEquals(2_000, repository.getHealthSnapshot(yesterday).first()?.steps)
    }

    /**
     * The far side: once a day has been read past its settle window it is
     * finished with, permanently.
     *
     * That half is what keeps this from being a week of Health Connect round
     * trips on every refresh, and it is why the window is a window rather than
     * simply leaving every past day open.
     */
    @Test
    fun `a day read past its settle window is never read again`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)
        val dayEnd = today.atStartOfDay(zone).toInstant()

        repository.syncHealthData(yesterday, now = dayEnd.plus(Duration.ofHours(49)))
        val readsAfterSettling = source.reads.size

        assertFalse(repository.syncFinishedDay(yesterday, dayEnd.plus(Duration.ofHours(50))))
        assertFalse(repository.syncFinishedDay(yesterday, dayEnd.plus(Duration.ofDays(30))))
        assertEquals(readsAfterSettling, source.reads.size)
    }

    /**
     * The steady-state cost, which is the number worth pinning rather than the
     * mechanism.
     *
     * After a full sweep, a second sweep at the same moment re-reads exactly the
     * days still inside their settle window -- yesterday and the day before --
     * and leaves the other five alone. Bounded and constant, which is what makes
     * it affordable on every refresh.
     */
    @Test
    fun `a settled week costs two re-reads a refresh, not seven`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)

        assertEquals(7, repository.resyncFinishedDays(today, thisMorning).getOrThrow())

        assertEquals(2, repository.resyncFinishedDays(today, thisMorning).getOrThrow())
        assertEquals(2, repository.resyncFinishedDays(today, thisMorning).getOrThrow())
        // And they are the two youngest finished days, not any two.
        assertEquals(3, source.reads.count { it == today.minusDays(1) })
        assertEquals(3, source.reads.count { it == today.minusDays(2) })
        assertEquals(1, source.reads.count { it == today.minusDays(3) })
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

    // ----- The history older than the sweep --------------------------------

    /** A snapshot frozen mid-afternoon on every date from [from] back to [to]. */
    private suspend fun freezeHistory(
        repository: TrackerRepository,
        from: Long,
        to: Long,
    ) {
        for (back in from..to) {
            val date = today.minusDays(back)
            repository.syncHealthData(
                date,
                now = date.atStartOfDay(zone).toInstant().plus(Duration.ofHours(15)),
            )
        }
    }

    @Test
    fun `the deep resync spends its budget and no more`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)
        freezeHistory(repository, 8, 40)
        source.reads.clear()

        val recovered = repository.deepResyncStaleDays(today, thisMorning, budget = 10).getOrThrow()

        assertEquals(10, recovered)
        assertEquals(10, source.reads.size)
    }

    /**
     * Successive refreshes converge, which is the whole reason a budget is
     * acceptable: the first run does not have to finish the job.
     */
    @Test
    fun `successive calls walk further back until the history is healed`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)
        freezeHistory(repository, 8, 20)

        var recovered = 0
        repeat(4) {
            recovered += repository.deepResyncStaleDays(today, thisMorning, budget = 10).getOrThrow()
        }

        // Fourteen: the thirteen frozen dates (8 through 20) plus day 7, which
        // the sweep would have covered and which has no row at all here. All
        // healed, and nothing beyond them invented -- the walk stops at the
        // oldest date there is evidence for.
        assertEquals(14, recovered)
        for (back in 8L..20L) {
            assertEquals(2_000, repository.getHealthSnapshot(today.minusDays(back)).first()?.steps)
        }
    }

    /**
     * A converged history spends nothing.
     *
     * The walk still visits every date down to the floor -- an indexed row read
     * each, deliberately, because the obvious "stop after a run of settled dates"
     * shortcut cannot converge (see `deepResyncStaleDays`). What must be zero is
     * the Health Connect round trips, and that is what this counts.
     */
    @Test
    fun `a settled history costs no reads at all`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)
        freezeHistory(repository, 8, 40)
        repeat(6) { repository.deepResyncStaleDays(today, thisMorning, budget = 10).getOrThrow() }

        source.reads.clear()
        val recovered = repository.deepResyncStaleDays(today, thisMorning, budget = 10).getOrThrow()

        assertEquals(0, recovered)
        assertEquals(0, source.reads.size)
    }

    /**
     * The floor is the oldest date anything says was lived, and nothing is
     * walked past it.
     *
     * Health Connect returns nothing from before thirty days prior to the first
     * permission grant, so a walk into the void is round trips for guaranteed
     * nulls.
     */
    @Test
    fun `the walk stops at the oldest date there is evidence for`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)
        freezeHistory(repository, 8, 12)
        source.reads.clear()

        repeat(3) { repository.deepResyncStaleDays(today, thisMorning, budget = 10).getOrThrow() }

        assertTrue(today.minusDays(12) in source.reads)
        assertTrue(today.minusDays(13) !in source.reads)
    }

    @Test
    fun `an empty database gives the walk nothing to do`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)

        assertEquals(0, repository.deepResyncStaleDays(today, thisMorning).getOrThrow())
        assertTrue(source.reads.isEmpty())
    }

    /**
     * Past Health Connect's own horizon a frozen figure beats a hole.
     *
     * This is the one place the catch-up could actively destroy data: walk far
     * enough back and every read comes back empty, and writing that emptiness
     * over a stale-but-real snapshot would turn a wrong number into no number.
     * The stamp still has to advance, or the date never settles and the walk
     * never converges past the horizon.
     */
    @Test
    fun `a read that comes back empty keeps the frozen row and settles it`() = runBlocking {
        var silent = false
        val source =
            object : HealthDataSource by MockHealthDataSource(zone) {
                override suspend fun readDay(date: LocalDate) = HealthDay(date = date)

                override suspend fun readStepsByHour(
                    from: Instant,
                    to: Instant,
                    preferredStepsPackage: String?,
                ): List<HourlySteps> =
                    if (silent) emptyList()
                    else listOf(HourlySteps(from, 4_321))
            }
        val repository = repository(source)
        val old = today.minusDays(40)
        repository.syncHealthData(old, now = old.atStartOfDay(zone).toInstant().plus(Duration.ofHours(15)))
        assertEquals(4_321, repository.getHealthSnapshot(old).first()?.steps)

        silent = true
        repository.deepResyncStaleDays(today, thisMorning, budget = 40).getOrThrow()

        val healed = repository.getHealthSnapshot(old).first()
        assertEquals(4_321, healed?.steps)
        // Stamped anyway, so the date stops qualifying rather than being asked
        // about on every refresh for the rest of the install's life.
        assertTrue(healed!!.syncedAt.isAfter(old.plusDays(1).atStartOfDay(zone).toInstant()))
        assertFalse(repository.syncFinishedDay(old, thisMorning))
    }

    /**
     * The two mechanisms report one figure between them, since the reader is
     * being told one thing: how many past days moved under them.
     */
    @Test
    fun `the sweep's count and the deep walk's count add up`() = runBlocking {
        val source = CountingSource(zone)
        val repository = repository(source)
        freezeHistory(repository, 1, 12)
        source.reads.clear()

        val swept = repository.resyncFinishedDays(today, thisMorning).getOrThrow()
        val deep = repository.deepResyncStaleDays(today, thisMorning, budget = 10).getOrThrow()

        // Seven from the sweep, five from the walk (dates 8 through 12), and
        // each date read exactly once between the two -- no overlap, no gap.
        assertEquals(7, swept)
        assertEquals(5, deep)
        assertEquals(12, source.reads.size)
        assertEquals(12, source.reads.toSet().size)
    }

    /**
     * The stamp that proves the re-read happened, and that the settle window is
     * then measured from.
     *
     * Steps are the figure this was reported against, but the fault is in the row
     * rather than in the column: every field on the snapshot froze together, and
     * `syncedAt` is the one that says so.
     */
    @Test
    fun `a re-read day is stamped past its own end`() = runBlocking {
        val repository = repository(MockHealthDataSource(zone))
        val midnight = today.atStartOfDay(zone).toInstant()

        repository.syncHealthData(yesterday, now = yesterdayAfternoon)
        assertTrue(repository.getHealthSnapshot(yesterday).first()!!.syncedAt.isBefore(midnight))

        repository.resyncFinishedDays(today, thisMorning).getOrThrow()

        assertTrue(repository.getHealthSnapshot(yesterday).first()!!.syncedAt.isAfter(midnight))
    }
}
