package com.prestondihle.healthtracker

import com.prestondihle.healthtracker.data.CaffeineIntake
import com.prestondihle.healthtracker.data.DataSourceEnum
import com.prestondihle.healthtracker.data.MealEntry
import com.prestondihle.healthtracker.domain.Macro
import com.prestondihle.healthtracker.ui.master.MasterGraphUiState
import com.prestondihle.healthtracker.ui.master.MasterRange
import com.prestondihle.healthtracker.ui.master.PAN_SNAP
import com.prestondihle.healthtracker.ui.master.pannedTo
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The master graph's window once it stops being anchored to now.
 *
 * Every range on that screen used to end at this instant, which put yesterday's
 * lunch out of reach at every zoom that could have shown it. Dragging the window
 * back is the fix, and the failure it introduces is quiet: anything still
 * measured from `now` rather than from the window's own right edge goes on being
 * drawn where the window no longer is.
 */
class PanWindowTest {

    private val zone = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-08-24T18:00:00Z")

    private fun state(
        panOffset: Duration = Duration.ZERO,
        range: MasterRange = MasterRange.THREE,
        meals: List<MealEntry> = emptyList(),
        caffeine: List<CaffeineIntake> = emptyList(),
    ) =
        MasterGraphUiState(
            range = range,
            now = now,
            panOffset = panOffset,
            meals = meals,
            caffeine = caffeine,
            zoneId = zone,
        )

    private fun meal(at: Instant) =
        MealEntry(
            id = at.toEpochMilli(),
            timestamp = at,
            calories = 600,
            proteinGrams = 30f,
            carbGrams = 60f,
            fatGrams = 20f,
            source = DataSourceEnum.HEALTH_CONNECT,
        )

    @Test
    fun `a live window ends at now`() {
        val uiState = state()

        assertEquals(now, uiState.windowEnd)
        assertEquals(now.minus(Duration.ofHours(3)), uiState.windowStart)
        assertFalse(uiState.isPanned)
    }

    @Test
    fun `a panned window ends where it was dragged to and keeps its width`() {
        // The width is the range and nothing else: panning moves the window, it
        // does not stretch it, or the chips would stop meaning what they say.
        val uiState = state(panOffset = Duration.ofHours(9))

        assertEquals(Instant.parse("2026-08-24T09:00:00Z"), uiState.windowEnd)
        assertEquals(Instant.parse("2026-08-24T06:00:00Z"), uiState.windowStart)
        assertTrue(uiState.isPanned)
    }

    @Test
    fun `the absorption curves stop at the right edge`() {
        // These are sampled between two bounds rather than clipped afterwards, so
        // one taking `now` as its end on a window that closed nine hours earlier
        // does not stop short -- it runs on past the plot's right-hand side, in
        // the same ink as the part of it that belongs there.
        val uiState =
            state(
                panOffset = Duration.ofHours(9),
                meals = listOf(meal(Instant.parse("2026-08-24T06:30:00Z"))),
            )

        Macro.entries.forEach { macro ->
            val curve = uiState.absorptionCurve(macro)
            assertTrue("$macro curve is empty", curve.isNotEmpty())
            assertFalse(
                "$macro curve runs past the right edge",
                curve.last().first.isAfter(uiState.windowEnd),
            )
        }
    }

    @Test
    fun `the caffeine curve stops at the right edge`() {
        val uiState =
            state(
                panOffset = Duration.ofHours(9),
                caffeine =
                    listOf(
                        CaffeineIntake(
                            id = 1,
                            timestamp = Instant.parse("2026-08-24T06:15:00Z"),
                            milligrams = 150,
                        )
                    ),
            )

        val curve = uiState.caffeineCurve

        assertTrue(curve.isNotEmpty())
        assertFalse(curve.last().first.isAfter(uiState.windowEnd))
    }

    @Test
    fun `a meal eaten after the right edge is not listed or marked`() {
        // The meal list and the rules on the plot are the same collection, so a
        // meal past the right edge would be a row in the list with no rule
        // anywhere the reader could look for it.
        val inside = meal(Instant.parse("2026-08-24T07:30:00Z"))
        val later = meal(Instant.parse("2026-08-24T17:30:00Z"))

        val uiState = state(panOffset = Duration.ofHours(9), meals = listOf(inside, later))

        assertEquals(listOf(inside.timestamp), uiState.mealsInWindow.map { it.timestamp })
    }

    @Test
    fun `a meal eaten before the left edge is still not listed`() {
        // The other bound, which the load deliberately reaches past: meals are
        // queried an absorption window early so a curve starts at the right
        // height, and those extra meals must not turn up in the list.
        val early = meal(Instant.parse("2026-08-24T02:00:00Z"))

        val uiState = state(panOffset = Duration.ofHours(9), meals = listOf(early))

        assertTrue(uiState.mealsInWindow.isEmpty())
    }

    @Test
    fun `panning forward stops at now`() {
        // There is nothing to the right of now, and a window reaching into the
        // future is a plot with a blank third and a scale nothing fills.
        assertEquals(Duration.ZERO, pannedTo(Duration.ZERO, Duration.ofHours(-4)))
        assertEquals(Duration.ZERO, pannedTo(Duration.ofHours(1), Duration.ofHours(-4)))
    }

    @Test
    fun `a drag that lands near now returns the window to live`() {
        // A window three minutes short of now looks live and is not, which is the
        // worst of the two states to be in.
        val nudged = pannedTo(PAN_SNAP, Duration.ofSeconds(-30))

        assertEquals(Duration.ZERO, nudged)
    }

    @Test
    fun `a drag past the snap keeps its offset`() {
        val dragged = pannedTo(Duration.ZERO, Duration.ofHours(6))

        assertEquals(Duration.ofHours(6), dragged)
        // And it accumulates, so a second drag carries on from the first rather
        // than restarting from now.
        assertEquals(Duration.ofHours(9), pannedTo(dragged, Duration.ofHours(3)))
    }
}
