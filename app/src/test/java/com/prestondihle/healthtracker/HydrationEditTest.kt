package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.fuel.FuelUiState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Correcting a logged drink, which for a long time could not be done at all.
 *
 * A mis-scaled tap over the Today screen once wrote a 100 ml entry into live
 * data, and no screen in the app could remove it. What makes that fixable is
 * that hydration is hand-entered end to end: there is no upstream record to
 * arrive again on the next sync, so unlike a synced meal the row is deleted
 * outright rather than hidden. That difference is the thing worth pinning --
 * a later tidy-up that made the two consistent with each other would pass every
 * other test in this suite and quietly make the entry unremovable again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HydrationEditTest {

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

    /** Mid-morning today, so every entry lands inside the day the card reads. */
    private val morning: Instant
        get() = today.atStartOfDay(zone).toInstant().plus(Duration.ofHours(9))

    @Test
    fun `a stray entry is deleted outright and does not come back`() = runBlocking {
        val repository = repository()
        repository.addHydration(500, morning)
        repository.addHydration(100, morning.plus(Duration.ofMinutes(30)))

        val stray = repository.getHydrationForDate(today).first().single { it.milliliters == 100 }
        repository.deleteHydration(stray)

        // Gone from the table, not merely hidden from a filtered read. A synced
        // meal keeps its row precisely because the next sync would re-offer it;
        // nothing upstream has ever heard of this one.
        val remaining = repository.getHydrationForDate(today).first()
        assertEquals(listOf(500), remaining.map { it.milliliters })

        // And a sync of the day it belonged to does not resurrect it, which is
        // the failure the meal tables' hidden flag exists to prevent.
        repository.syncHealthData(today)
        assertEquals(listOf(500), repository.getHydrationForDate(today).first().map { it.milliliters })
    }

    @Test
    fun `an entry can be corrected in place rather than deleted and re-added`() = runBlocking {
        val repository = repository()
        repository.addHydration(100, morning)

        val entry = repository.getHydrationForDate(today).first().single()
        val corrected = entry.copy(milliliters = 250, timestamp = morning.plus(Duration.ofHours(2)))
        repository.updateHydration(corrected)

        // Same row, new contents. Re-adding instead would leave the day holding
        // two entries where one was drunk, which is the bug the edit path avoids.
        val after = repository.getHydrationForDate(today).first()
        assertEquals(1, after.size)
        assertEquals(entry.id, after.single().id)
        assertEquals(250, after.single().milliliters)
        assertEquals(morning.plus(Duration.ofHours(2)), after.single().timestamp)
    }

    @Test
    fun `the day's total is the list, so removing an entry moves it`() = runBlocking {
        val repository = repository()
        repository.addHydration(500, morning)
        repository.addHydration(100, morning.plus(Duration.ofMinutes(30)))

        val before = state(repository)
        assertEquals(600, before.hydrationMl)

        val stray = before.hydration.single { it.milliliters == 100 }
        repository.deleteHydration(stray)

        // The headline used to be its own SUM query over the same table. Two
        // reads can disagree, and a total that had not caught up with the list
        // under it is the first thing a reader would notice and the last thing
        // they would trust.
        assertEquals(500, state(repository).hydrationMl)
    }

    /**
     * The list reaches back a week; the total does not.
     *
     * This is the pair that makes the card correct rather than either half on
     * its own. A stray tap writes 100 ml, which is also the ordinary dose, so it
     * is spotted a day or two later from a figure that looks too high -- the row
     * has to still be reachable. But an older row counted into today's figure
     * would be the same bug pointing the other way, and it would show up as a
     * goal that was already met on waking.
     */
    @Test
    fun `an earlier day's entry stays listed but is not counted into today`() = runBlocking {
        val repository = repository()
        repository.addHydration(100, morning)
        repository.addHydration(250, morning.minus(Duration.ofDays(2)))

        val state = state(repository)
        assertEquals(setOf(100, 250), state.hydration.map { it.milliliters }.toSet())
        assertEquals(100, state.hydrationMl)
    }

    @Test
    fun `a drink older than the window is out of reach`() = runBlocking {
        val repository = repository()
        repository.addHydration(100, morning)
        repository.addHydration(250, morning.minus(Duration.ofDays(30)))

        // Not a correction that never expires: the query is bounded, and a list
        // growing without limit would eventually be the reason nobody scrolls it.
        assertEquals(listOf(100), state(repository).hydration.map { it.milliliters })
    }

    @Test
    fun `a day with nothing logged has no total and no list`() = runBlocking {
        val repository = repository()

        val state = state(repository)
        assertTrue(state.hydration.isEmpty())
        assertEquals(0, state.hydrationMl)
    }

    /**
     * The card's state, assembled the way the view model assembles it.
     *
     * `today` and `zoneId` are passed rather than defaulted because the total
     * filters on midnight in that zone, and a state built with the machine's own
     * zone against entries seeded in UTC would move the boundary with wherever
     * this happened to run.
     */
    private suspend fun state(repository: TrackerRepository): FuelUiState =
        FuelUiState(
            today = today,
            zoneId = zone,
            hydration = repository.getHydrationBetween(today.minusDays(6), today).first(),
        )
}
