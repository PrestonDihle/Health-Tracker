package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.health.HealthDataSource
import com.prestondihle.healthtracker.health.MealSample
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting a meal has to survive the next sync.
 *
 * This is the whole reason a synced meal is hidden rather than removed, and it
 * is not something the type system enforces -- a later change that "tidies up"
 * the flag by deleting the row instead would pass every other test in the suite
 * and quietly resurrect the meal the next time the screen refreshed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MealDeletionTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** A source that reports the same single meal every time it is asked. */
    private class OneStubbornMeal(
        private val delegate: MockHealthDataSource,
        private val at: Instant,
    ) : HealthDataSource by delegate {
        override suspend fun readMeals(from: Instant, to: Instant): List<MealSample> =
            if (at.isBefore(from) || !at.isBefore(to)) emptyList()
            else
                listOf(
                    MealSample(
                        time = at,
                        calories = 602,
                        proteinGrams = 30f,
                        carbGrams = 16.5f,
                        fatGrams = 20f,
                        externalId = "hc-stubborn",
                    )
                )
    }

    private fun repository(source: HealthDataSource): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), source, zone)
    }

    @Test
    fun `a deleted synced meal does not come back on the next sync`() = runBlocking {
        val now = Instant.now()
        val from = now.minus(Duration.ofHours(6))
        val eaten = now.minus(Duration.ofHours(2))
        val repository = repository(OneStubbornMeal(MockHealthDataSource(zone), eaten))

        repository.syncTimeSeries(from, now)
        val synced = repository.getMealsSince(from).first()
        assertEquals(1, synced.size)

        repository.deleteMeal(synced.first())
        assertEquals(0, repository.getMealsSince(from).first().size)

        // Health Connect still holds the record and hands it over again. The
        // hidden row is the only thing standing between that and its return.
        repository.syncTimeSeries(from, now)
        assertEquals(0, repository.getMealsSince(from).first().size)
    }

    @Test
    fun `a hand-entered meal is removed outright`() = runBlocking {
        val now = Instant.now()
        val from = now.minus(Duration.ofHours(6))
        val repository = repository(OneStubbornMeal(MockHealthDataSource(zone), now.plusSeconds(1)))

        repository.addMeal(
            at = now.minus(Duration.ofHours(1)),
            calories = 450,
            proteinGrams = 20f,
            carbGrams = 40f,
            fatGrams = 15f,
        )
        val logged = repository.getMealsSince(from).first()
        assertEquals(1, logged.size)

        // Nothing upstream can bring this one back, so nothing has to be kept to
        // hold it down -- the row goes.
        repository.deleteMeal(logged.first())
        assertEquals(0, repository.getMealsSince(from).first().size)
    }

    @Test
    fun `a hand-entered meal is not mistaken for a synced duplicate`() = runBlocking {
        // Manual rows carry no externalId, and SQLite treats NULLs as distinct.
        // Logging the same thing twice on purpose has to stay possible.
        val now = Instant.now()
        val from = now.minus(Duration.ofHours(6))
        val repository = repository(OneStubbornMeal(MockHealthDataSource(zone), now.plusSeconds(1)))

        repeat(2) {
            repository.addMeal(
                at = now.minus(Duration.ofHours(1)),
                calories = 450,
                proteinGrams = 20f,
                carbGrams = 40f,
                fatGrams = 15f,
            )
        }

        assertEquals(2, repository.getMealsSince(from).first().size)
    }
}
