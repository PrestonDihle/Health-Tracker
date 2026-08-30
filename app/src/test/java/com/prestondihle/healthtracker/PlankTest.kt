package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.trends.TrendsRange
import com.prestondihle.healthtracker.ui.trends.TrendsUiState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a day's plank figure means, which is not what the rep counters beside it
 * mean.
 *
 * Two rules carry the whole feature and both are the kind that look like
 * arbitrary choices until the wrong one is drawn: the day's figure is its
 * **longest** hold rather than the sum of them, and a day with no plank is
 * **null** rather than zero. Get either backwards and the chart still draws, and
 * still looks like a plausible training record.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlankTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.now(zone)

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun repository(): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), MockHealthDataSource(zone), zone)
    }

    private fun at(date: LocalDate, hour: Long): Instant =
        date.atStartOfDay(zone).toInstant().plus(Duration.ofHours(hour))

    @Test
    fun `the day's figure is the longest hold, not the sum of them`() = runBlocking {
        val repository = repository()
        // Three easy holds against one hard one. Summed, this day reports 195
        // seconds and outranks a day that actually set a two-minute record --
        // which is the exercise being measured backwards.
        repository.addPlank(45, at(today, 7))
        repository.addPlank(60, at(today, 12))
        repository.addPlank(90, at(today, 18))

        assertEquals(90, repository.getBestPlankSecondsForDate(today).first())
    }

    @Test
    fun `a day with no plank has no figure rather than a zero`() = runBlocking {
        val repository = repository()
        repository.addPlank(90, at(today.minusDays(1), 9))

        // Null, not 0. Nobody's plank capacity fell to zero seconds on a day they
        // did not train -- it went unmeasured, which is what this chart has to be
        // able to say. A zero would draw the line to the floor and read as a
        // collapse in strength on every rest day.
        assertNull(repository.getBestPlankSecondsForDate(today).first())
    }

    @Test
    fun `the trend breaks on untrained days and peaks on the best hold`() = runBlocking {
        val repository = repository()
        repository.addPlank(60, at(today.minusDays(3), 9))
        repository.addPlank(30, at(today.minusDays(1), 9))
        repository.addPlank(120, at(today.minusDays(1), 19))

        val planks = repository.getPlanksBetween(today.minusDays(6), today).first()
        val state =
            TrendsUiState(
                range = TrendsRange.WEEK,
                startDate = today.minusDays(6),
                endDate = today,
                planks = planks,
                zoneId = zone,
            )
        val byDate = state.plankSeries.associate { it.date to it.value }

        assertEquals(60f, byDate[today.minusDays(3)])
        // The best of the two held that day, not their total.
        assertEquals(120f, byDate[today.minusDays(1)])
        // The days either side of them are holes in the line, not points on it.
        assertNull(byDate[today.minusDays(2)])
        assertNull(byDate[today])
    }

    @Test
    fun `two holds in one session are two rows`() = runBlocking {
        val repository = repository()
        // Same length, a minute apart. Nothing dedupes these and nothing should:
        // planks are hand-timed end to end, so there is no upstream record to
        // arrive twice, and a second identical hold is a second identical hold.
        repository.addPlank(60, at(today, 9))
        repository.addPlank(60, at(today, 9).plus(Duration.ofMinutes(1)))

        assertEquals(2, repository.getPlanksForDate(today).first().size)
        assertEquals(60, repository.getBestPlankSecondsForDate(today).first())
    }

    /**
     * The clock format, which `formatDuration` cannot supply.
     *
     * That one floors to whole minutes -- right for a fast measured in hours and
     * useless for a plank, where it renders every hold between one and two
     * minutes as the same `1m`.
     */
    @Test
    fun `a hold is formatted to the second`() {
        assertEquals("0:45", Units.formatHold(45))
        assertEquals("1:30", Units.formatHold(90))
        assertEquals("2:05", Units.formatHold(125))
        assertEquals("0:00", Units.formatHold(0))
    }
}
