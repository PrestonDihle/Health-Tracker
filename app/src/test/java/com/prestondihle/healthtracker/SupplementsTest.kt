package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.data.Supplement
import com.prestondihle.healthtracker.data.SupplementSlot
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
import com.prestondihle.healthtracker.ui.wellness.WellnessUiState
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
 * The standing supplement stack, and the ticks logged against it.
 *
 * Two tables with no foreign key between them, which is the shape the rest of
 * this schema uses and the reason the interesting behaviour is in the repository
 * rather than in SQLite: nothing cascades on the app's behalf, and nothing stops
 * a second tick on a day that already has one except the primary key being the
 * pair. Both are the sort of thing that works until the day it does not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SupplementsTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 24)

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun repository(): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), MockHealthDataSource(), ZoneId.of("UTC"))
    }

    @Test
    fun `the same thing in the same slot is one entry however often it is added`() =
        runBlocking {
            val repository = repository()
            repository.addSupplement("Vitamin D3", "5000 IU", SupplementSlot.MORNING)
            // Added again, as anyone would after forgetting they had. The unique
            // index absorbs it -- and IGNORE rather than REPLACE, so the row keeps
            // the id every tick already logged against it points at.
            repository.addSupplement("Vitamin D3", "2000 IU", SupplementSlot.MORNING)

            val stack = repository.getSupplements().first()

            assertEquals(1, stack.size)
            assertEquals("5000 IU", stack.single().dose)
        }

    @Test
    fun `the same thing morning and evening is two entries`() = runBlocking {
        // Which is the whole point of keying on the pair: magnesium twice a day
        // is two boxes to tick, not one that has to be ticked twice.
        val repository = repository()
        repository.addSupplement("Magnesium", "200 mg", SupplementSlot.MORNING)
        repository.addSupplement("Magnesium", "200 mg", SupplementSlot.EVENING)

        assertEquals(2, repository.getSupplements().first().size)
    }

    @Test
    fun `the stack comes back in the order the day runs`() = runBlocking {
        // Sorted on the slot's meaning rather than its stored text, which would
        // put EVENING before MIDDAY before MORNING -- the day backwards.
        val repository = repository()
        repository.addSupplement("Zinc", "", SupplementSlot.EVENING)
        repository.addSupplement("Creatine", "", SupplementSlot.MIDDAY)
        repository.addSupplement("Vitamin D3", "", SupplementSlot.MORNING)

        assertEquals(
            listOf(SupplementSlot.MORNING, SupplementSlot.MIDDAY, SupplementSlot.EVENING),
            repository.getSupplements().first().map { it.slot },
        )
    }

    @Test
    fun `ticking twice is one dose and unticking clears it`() = runBlocking {
        val repository = repository()
        repository.addSupplement("Vitamin D3", "5000 IU", SupplementSlot.MORNING)
        val supplement = repository.getSupplements().first().single()

        repository.setSupplementTaken(supplement, today, taken = true)
        repository.setSupplementTaken(supplement, today, taken = true)

        assertEquals(setOf(supplement.id), repository.getSupplementsTakenOn(today).first())

        repository.setSupplementTaken(supplement, today, taken = false)

        assertTrue(repository.getSupplementsTakenOn(today).first().isEmpty())
    }

    @Test
    fun `a tick belongs to the day it was made and no other`() = runBlocking {
        // There is nothing that runs at midnight to clear anything. What makes
        // the card empty again tomorrow is that tomorrow asks a different
        // question, which is the reason the tick is a dated row rather than a
        // flag on the supplement.
        val repository = repository()
        repository.addSupplement("Vitamin D3", "5000 IU", SupplementSlot.MORNING)
        val supplement = repository.getSupplements().first().single()

        repository.setSupplementTaken(supplement, today, taken = true)

        assertTrue(repository.getSupplementsTakenOn(today.plusDays(1)).first().isEmpty())
        assertTrue(repository.getSupplementsTakenOn(today.minusDays(1)).first().isEmpty())
    }

    @Test
    fun `removing a supplement takes its ticks with it`() = runBlocking {
        // No foreign keys anywhere in this schema, so nothing cascades unless the
        // repository does it. Ticks left behind would be keyed on an id nothing
        // can resolve: invisible today, and counted by anything that later learns
        // to read the history.
        val repository = repository()
        repository.addSupplement("Vitamin D3", "5000 IU", SupplementSlot.MORNING)
        val supplement = repository.getSupplements().first().single()
        repository.setSupplementTaken(supplement, today, taken = true)

        repository.deleteSupplement(supplement)

        assertTrue(repository.getSupplements().first().isEmpty())
        assertTrue(repository.getSupplementsTakenOn(today).first().isEmpty())
    }

    @Test
    fun `the count never reports more taken than there are things to take`() {
        // A dose row surviving its supplement would otherwise read as "3 of 2
        // taken today". The state intersects rather than counting tick rows,
        // which is what makes the figure impossible to break from the outside.
        val stack =
            listOf(
                Supplement(id = 1, name = "Vitamin D3", dose = "5000 IU", slot = SupplementSlot.MORNING),
                Supplement(id = 2, name = "Magnesium", dose = "200 mg", slot = SupplementSlot.EVENING),
            )

        val uiState =
            WellnessUiState(supplements = stack, supplementsTaken = setOf(1L, 2L, 99L))

        assertEquals(2, uiState.supplementsTakenCount)
    }
}
