package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.DataSourceEnum
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.domain.MealDuplicates
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What may and may not be merged.
 *
 * The collapse changes what a day's macros add up to, so the line between "one
 * meal written twice" and "two similar meals" has to be pinned rather than left
 * to whichever fields the implementation happened to compare.
 */
class MealDuplicatesTest {

    private val noon: Instant = Instant.parse("2026-08-02T12:00:00Z")

    private fun meal(
        id: Long,
        at: Instant = noon,
        calories: Int? = 602,
        protein: Float? = 30f,
        carb: Float? = 16.5f,
        fat: Float? = 20f,
        name: String? = null,
    ) =
        MealEntry(
            id = id,
            timestamp = at,
            calories = calories,
            proteinGrams = protein,
            carbGrams = carb,
            fatGrams = fat,
            name = name,
            source = DataSourceEnum.HEALTH_CONNECT,
            externalId = "hc-$id",
        )

    @Test
    fun `identical records at the same instant collapse to one`() {
        // The shape found in real data: one meal written three times, each with a
        // Health Connect id of its own, so the unique index sees three rows.
        val collapsed = MealDuplicates.collapse(listOf(meal(1), meal(2), meal(3)))

        assertEquals(1, collapsed.size)
        assertEquals(1L, collapsed.first().id)
    }

    @Test
    fun `the survivor is the first one given`() {
        // Callers hand this a list already ordered for display, so the row that
        // stays should be the one they would otherwise have seen.
        val collapsed = MealDuplicates.collapse(listOf(meal(7), meal(2), meal(5)))

        assertEquals(listOf(7L), collapsed.map { it.id })
    }

    @Test
    fun `a meal differing in any macro survives`() {
        val meals =
            listOf(
                meal(1),
                meal(2, calories = 603),
                meal(3, protein = 31f),
                meal(4, carb = 16.6f),
                meal(5, fat = 21f),
                meal(6, name = "Lunch"),
            )

        assertEquals(meals.map { it.id }, MealDuplicates.collapse(meals).map { it.id })
    }

    @Test
    fun `the same meal at a different time is two meals`() {
        // Second helpings are real. Only an exact timestamp match can be a
        // duplicate, which is what keeps a genuinely repeated meal on the chart.
        val meals = listOf(meal(1), meal(2, at = noon.plus(Duration.ofHours(6))))

        assertEquals(2, MealDuplicates.collapse(meals).size)
    }

    @Test
    fun `a missing macro is not the same as a zero one`() {
        // Different statements: one says nothing was recorded, the other says
        // none was eaten. Merging them would silently pick one.
        val meals = listOf(meal(1, protein = null), meal(2, protein = 0f))

        assertEquals(2, MealDuplicates.collapse(meals).size)
    }

    @Test
    fun `candidates already stored are not inserted again`() {
        val stored = listOf(meal(1))
        val candidates = listOf(meal(0, at = noon), meal(0, at = noon.plus(Duration.ofHours(3))))

        val unseen = MealDuplicates.notAlreadyStored(candidates, stored)

        assertEquals(1, unseen.size)
        assertEquals(noon.plus(Duration.ofHours(3)), unseen.first().timestamp)
    }

    @Test
    fun `one sync carrying all three copies still inserts one`() {
        // Filtering only against what is on disk would let the other two through
        // on the very first sync, before anything was stored to compare with.
        val unseen =
            MealDuplicates.notAlreadyStored(listOf(meal(0), meal(0), meal(0)), emptyList())

        assertEquals(1, unseen.size)
    }
}
