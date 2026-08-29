package com.prestondihle.healthtracker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.prestondihle.healthtracker.data.AppDatabase
import com.prestondihle.healthtracker.health.MockHealthDataSource
import com.prestondihle.healthtracker.repository.TrackerRepository
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
 * Where a card sits and whether it is folded, which share one row.
 *
 * They share a row because they are one fact about one card on one tab, and the
 * cost of that is the thing this pins: the write is a whole-row upsert, so a
 * caller that passed only the order would rewrite every row with `collapsed`
 * back at its default. Moving one card would silently unfold the whole tab --
 * and it would look like the fold never saved rather than like the reorder broke
 * it, which is the kind of bug that gets blamed on the wrong control.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CardFoldTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun repository(): TrackerRepository {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        return TrackerRepository(db.trackerDao(), MockHealthDataSource(), ZoneId.of("UTC"))
    }

    private val order = listOf("weightTrend", "waistTrend", "sleepTrend")

    @Test
    fun `a fold survives the card being moved`() = runBlocking {
        val repository = repository()
        repository.setCardState("wellness", order, collapsed = setOf("waistTrend"))

        // The reorder carries the folds it was handed, rather than defaulting
        // them away.
        val moved = listOf("waistTrend", "weightTrend", "sleepTrend")
        repository.setCardState("wellness", moved, collapsed = setOf("waistTrend"))

        val rows = repository.getCardEntries("wellness").first()
        assertEquals(moved, rows.sortedBy { it.position }.map { it.cardId })
        assertTrue(rows.single { it.cardId == "waistTrend" }.collapsed)
        assertTrue(rows.none { it.cardId != "waistTrend" && it.collapsed })
    }

    @Test
    fun `a fold survives another card being folded`() = runBlocking {
        val repository = repository()
        repository.setCardState("wellness", order, collapsed = setOf("weightTrend"))
        repository.setCardState("wellness", order, collapsed = setOf("weightTrend", "sleepTrend"))

        val folded = repository.getCardEntries("wellness").first().filter { it.collapsed }

        assertEquals(setOf("weightTrend", "sleepTrend"), folded.map { it.cardId }.toSet())
    }

    @Test
    fun `unfolding leaves the order alone`() = runBlocking {
        val repository = repository()
        repository.setCardState("wellness", order, collapsed = setOf("weightTrend"))
        repository.setCardState("wellness", order, collapsed = emptySet())

        val rows = repository.getCardEntries("wellness").first()

        assertEquals(order, rows.sortedBy { it.position }.map { it.cardId })
        assertTrue(rows.none { it.collapsed })
    }

    @Test
    fun `folds are per tab`() = runBlocking {
        val repository = repository()
        // The same card id appears on more than one tab -- the whole reason the
        // primary key is the pair. A fold on one must not reach the other.
        repository.setCardState("wellness", order, collapsed = setOf("weightTrend"))
        repository.setCardState("log", listOf("weightTrend"), collapsed = emptySet())

        assertTrue(
            repository.getCardEntries("wellness").first().single { it.cardId == "weightTrend" }
                .collapsed
        )
        assertTrue(
            repository.getCardEntries("log").first().none { it.collapsed }
        )
    }

    @Test
    fun `a tab nobody has touched has no folds and no rows`() = runBlocking {
        val repository = repository()

        assertTrue(repository.getCardEntries("activity").first().isEmpty())
    }
}
