package com.prestondihle.healthtracker.domain

import com.prestondihle.healthtracker.data.MealEntry
import java.time.Instant

/**
 * Collapses meals that are the same meal written more than once.
 *
 * ## Why this is not the [MealEntry.externalId] index's job
 *
 * That index stops *this app* importing one Health Connect record twice, and it
 * works. What it cannot touch is a source that writes the same meal repeatedly
 * as several records, each with a stable id of its own: to Health Connect those
 * are three distinct records, and to the unique index three distinct rows.
 *
 * They are visible in real data. One day carried six records that were two
 * meals repeated three times over -- identical calories, identical macros,
 * identical timestamp -- which trebled that day's carbohydrate on the chart and
 * listed each meal three times underneath it.
 *
 * ## What counts as the same meal
 *
 * Every stated field, plus the timestamp: energy, all three macros, and the
 * name. Two records agreeing on all of that are either one meal written twice or
 * two meals eaten at the same instant that also happen to match to the gram --
 * and the second is not a thing that happens. Anything that differs at all is
 * kept, so a genuine second helping logged with even slightly different macros
 * survives.
 *
 * Nulls take part in the comparison rather than being ignored: a record with no
 * protein figure and one recording zero protein are different statements, and
 * merging them would silently pick one.
 *
 * ## Where it runs
 *
 * Both on the way in and on the way out. On the way in it keeps the table from
 * accumulating; on the way out it fixes days already stored, without deleting
 * anything. Nothing here removes a row -- the duplicates stay on disk, they are
 * simply not counted twice, which leaves the decision reversible.
 */
object MealDuplicates {

    /** What two rows must share to be one meal recorded twice. */
    private data class Signature(
        val timestamp: Instant,
        val calories: Int?,
        val proteinGrams: Float?,
        val carbGrams: Float?,
        val fatGrams: Float?,
        val name: String?,
    )

    private fun MealEntry.signature() =
        Signature(timestamp, calories, proteinGrams, carbGrams, fatGrams, name)

    /**
     * The first of each repeated meal, in the order given.
     *
     * Order-preserving because callers hand this a list they have already sorted
     * for display, and the survivor should be the one they would have seen.
     */
    fun collapse(meals: List<MealEntry>): List<MealEntry> {
        val seen = mutableSetOf<Signature>()
        return meals.filter { seen.add(it.signature()) }
    }

    /**
     * Those of [candidates] not already recorded, for the insert path.
     *
     * Collapses [candidates] against themselves too: one sync can perfectly well
     * hand over all three copies at once, and filtering only against what is
     * already stored would let the other two straight through.
     */
    fun notAlreadyStored(
        candidates: List<MealEntry>,
        stored: List<MealEntry>,
    ): List<MealEntry> {
        val known = stored.mapTo(mutableSetOf()) { it.signature() }
        return candidates.filter { known.add(it.signature()) }
    }
}
