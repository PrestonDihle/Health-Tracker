package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AftAttempt
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.CsvBackup
import com.prestondihle.healthtracker.data.TrackerDao
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Storing an Army Fitness Test attempt.
 *
 * The behaviour worth pinning is all about what a row is allowed to mean. An
 * attempt is a record of one test day, not a running best and not a per-day
 * slot, so two on one date are two attempts; a missing event is missing rather
 * than zero; and the deadlift makes a round trip through kilograms that has a
 * pass mark riding on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AftAttemptTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 27)

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private lateinit var dao: TrackerDao

    private fun repository(): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.trackerDao()
        return TrackerRepository(dao, MockHealthDataSource(), ZoneId.of("UTC"))
    }

    @Test
    fun `a retest on the same day is a second attempt, not a correction`() = runBlocking {
        val repository = repository()
        repository.addAftAttempt(AftAttempt(date = today, hrpReps = 30))
        repository.addAftAttempt(AftAttempt(date = today, hrpReps = 42))

        // Keyed on a generated id rather than the date precisely so this works.
        // A date-keyed row would have swallowed the first attempt, and a record
        // of progress that overwrites itself is not one.
        val stored = repository.getAftAttempts().first()
        assertEquals(2, stored.size)
        assertEquals(listOf(30, 42), stored.map { it.hrpReps })
        assertNotEquals(stored[0].id, stored[1].id)
    }

    @Test
    fun `attempts read back oldest first and the latest is the newest`() = runBlocking {
        val repository = repository()
        repository.addAftAttempt(AftAttempt(date = today.minusDays(90), hrpReps = 20))
        repository.addAftAttempt(AftAttempt(date = today, hrpReps = 40))
        repository.addAftAttempt(AftAttempt(date = today.minusDays(30), hrpReps = 30))

        // Oldest first, because that is the order a trend is plotted in.
        assertEquals(listOf(20, 30, 40), repository.getAftAttempts().first().map { it.hrpReps })
        assertEquals(40, repository.getLatestAftAttempt().first()?.hrpReps)
    }

    @Test
    fun `the latest of two attempts on one day is the one logged second`() = runBlocking {
        val repository = repository()
        repository.addAftAttempt(AftAttempt(date = today, hrpReps = 30))
        repository.addAftAttempt(AftAttempt(date = today, hrpReps = 42))

        // Same date, so the id breaks the tie -- otherwise "latest" would be
        // whichever the database happened to return first on a retest day.
        assertEquals(42, repository.getLatestAftAttempt().first()?.hrpReps)
    }

    @Test
    fun `an unfinished attempt stores nulls rather than zeros`() = runBlocking {
        val repository = repository()
        // Three events done, two still to go -- the ordinary state of a test day
        // halfway through, and the reason every column is nullable.
        repository.addAftAttempt(
            AftAttempt(
                date = today,
                deadliftKg = Units.lbsToKg(250f),
                hrpReps = 37,
                sdcSeconds = 113,
            )
        )

        val stored = repository.getAftAttempts().first().single()
        assertEquals(37, stored.hrpReps)
        // Not zero: zero is a plank held for no time at all, which is a result.
        assertNull(stored.plankSeconds)
        assertNull(stored.twoMileSeconds)
    }

    @Test
    fun `finishing an attempt updates the row rather than adding another`() = runBlocking {
        val repository = repository()
        val id = repository.addAftAttempt(AftAttempt(date = today, hrpReps = 37))

        val started = repository.getAftAttempts().first().single()
        assertEquals(id, started.id)
        repository.updateAftAttempt(started.copy(plankSeconds = 150, twoMileSeconds = 1028))

        val finished = repository.getAftAttempts().first().single()
        assertEquals(id, finished.id)
        assertEquals(37, finished.hrpReps)
        assertEquals(150, finished.plankSeconds)
    }

    @Test
    fun `a deleted attempt is gone for good`() = runBlocking {
        val repository = repository()
        repository.addAftAttempt(AftAttempt(date = today.minusDays(1), hrpReps = 20))
        repository.addAftAttempt(AftAttempt(date = today, hrpReps = 40))

        val mistake = repository.getAftAttempts().first().first { it.hrpReps == 20 }
        repository.deleteAftAttempt(mistake)

        // Hand-entered with nothing upstream to re-offer it, so a real delete --
        // the hydration rule rather than the synced-meal one.
        assertEquals(listOf(40), repository.getAftAttempts().first().map { it.hrpReps })
    }

    /**
     * The deadlift survives the trip through kilograms.
     *
     * Stored metric like every other weight here, scored against a table
     * published in pounds and stepped in tens of them. Every step of that table
     * has to come back the number it went in as: 150 lb reading back as 149
     * would fail a Soldier who hit the minimum exactly, and nothing on the
     * screen would explain why.
     */
    @Test
    fun `every deadlift step round-trips through kilograms unchanged`() = runBlocking {
        val repository = repository()
        val steps = (80..350 step 10).toList()

        steps.forEach { lbs ->
            repository.addAftAttempt(AftAttempt(date = today, deadliftKg = Units.lbsToKg(lbs.toFloat())))
        }

        val readBack =
            repository.getAftAttempts().first().map { Units.kgToWholeLbs(it.deadliftKg!!) }
        assertEquals(steps, readBack)
    }

    @Test
    fun `attempts are carried by the CSV backup without anything being added to it`() =
        runBlocking {
            val repository = repository()
            repository.addAftAttempt(
                AftAttempt(
                    date = today,
                    deadliftKg = Units.lbsToKg(250f),
                    hrpReps = 37,
                    sdcSeconds = 113,
                    plankSeconds = 150,
                    twoMileSeconds = 1028,
                )
            )

            // The exporter reads sqlite_master, so a new table joins the backup
            // by existing. This asserts that it actually did, because the failure
            // mode of a hand-maintained list is silence until somebody needs it.
            assertTrue("AftAttempt missing from the backup", "AftAttempt" in CsvBackup.tableNames(dao))

            val csv = CsvBackup.tableCsv(dao, "AftAttempt")
            assertTrue(csv.startsWith("id,date,deadliftKg,hrpReps,sdcSeconds,plankSeconds,twoMileSeconds"))
            val row = csv.trim().lines().last().split(",")
            assertEquals("37", row[3])
            assertEquals("1028", row[6])
        }
}
