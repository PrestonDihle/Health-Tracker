package com.prestondihle.healthtracker.ui.reorder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.prestondihle.healthtracker.ui.components.CardFold
import com.prestondihle.healthtracker.ui.components.LocalCardFold
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The order to actually draw in: the saved order first, then anything new.
 *
 * A saved order can fall out of step with the code -- a card removed in an
 * update, or one added after the order was last saved. Ids the saved order lists
 * but the tab no longer has are dropped; ids the tab has but the save never
 * mentioned are appended in their built-in order, so a new card shows up at the
 * bottom rather than vanishing.
 */
fun effectiveCardOrder(saved: List<String>, default: List<String>): List<String> {
    val known = default.toSet()
    val kept = saved.filter { it in known }
    val keptSet = kept.toSet()
    return kept + default.filter { it !in keptSet }
}

/**
 * The saved order for one tab, and the move that rewrites it.
 *
 * One per tab, keyed by the tab's route. Scoped to the tab's own back-stack entry
 * so its saved order outlives a scroll but not the app.
 */
class CardOrderViewModel(
    private val repository: TrackerRepository,
    private val tab: String,
) : ViewModel() {

    private val entries =
        repository
            .getCardEntries(tab)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val savedOrder: StateFlow<List<String>> =
        repository
            .getCardOrder(tab)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The ids currently folded shut on this tab. */
    val collapsed: StateFlow<Set<String>> =
        entries
            .map { rows -> rows.filter { it.collapsed }.map { it.cardId }.toSet() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Swaps [cardId] with its neighbour in [direction].
     *
     * Computed against the effective order, not the raw save, so the first move
     * on a never-reordered tab still lands where the reader sees the card rather
     * than against an empty list. The folds are carried through untouched --
     * the write rewrites whole rows, so passing only the order would unfold
     * every card on the tab as a side effect of moving one.
     */
    fun move(cardId: String, up: Boolean, defaultOrder: List<String>) {
        viewModelScope.launch {
            val order = effectiveCardOrder(savedOrder.value, defaultOrder).toMutableList()
            val i = order.indexOf(cardId)
            if (i < 0) return@launch
            val j = if (up) i - 1 else i + 1
            if (j !in order.indices) return@launch
            order[i] = order[j].also { order[j] = order[i] }
            repository.setCardState(tab, order, collapsed.value)
        }
    }

    /**
     * Folds [cardId] shut, or opens it again.
     *
     * Writes the whole effective order alongside, which is what makes this work
     * on a tab nobody has ever reordered: there are no rows yet, so the fold has
     * nowhere to be stored until the positions exist to hang it on.
     */
    fun toggleCollapse(cardId: String, defaultOrder: List<String>) {
        viewModelScope.launch {
            val order = effectiveCardOrder(savedOrder.value, defaultOrder)
            if (cardId !in order) return@launch
            val folded = collapsed.value.toMutableSet()
            if (!folded.remove(cardId)) folded.add(cardId)
            repository.setCardState(tab, order, folded)
        }
    }

    companion object {
        fun provideFactory(repository: TrackerRepository, tab: String): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CardOrderViewModel(repository, tab) as T
            }
    }
}

/** One reorderable card: a stable id and the card to draw for it. */
class ReorderableCard(val id: String, val content: @Composable () -> Unit)

/**
 * Adds [cards] to a LazyColumn in the saved order, each under a pair of move
 * arrows.
 *
 * The default order is the order [cards] arrives in, so a tab declares its cards
 * once in the order it wants out of the box and this reconciles that with
 * whatever the reader has since saved. Keyed by card id so Compose keeps each
 * card's own state as they swap places.
 */
fun LazyListScope.reorderableCards(
    cards: List<ReorderableCard>,
    savedOrder: List<String>,
    onMove: (cardId: String, up: Boolean, defaultOrder: List<String>) -> Unit,
    /** Ids currently folded shut. Empty leaves every card open. */
    collapsed: Set<String> = emptySet(),
    /**
     * Folds a card, or opens it. Null makes the whole tab unfoldable, which is
     * what a caller that has no view model to save the state in wants.
     */
    onToggleCollapse: ((cardId: String, defaultOrder: List<String>) -> Unit)? = null,
) {
    val defaultOrder = cards.map { it.id }
    val order = effectiveCardOrder(savedOrder, defaultOrder)
    val byId = cards.associateBy { it.id }
    order.forEachIndexed { index, id ->
        val card = byId[id] ?: return@forEachIndexed
        item(key = id) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { onMove(id, true, defaultOrder) },
                        enabled = index > 0,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowUp,
                            contentDescription = "Move this card up",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onMove(id, false, defaultOrder) },
                        enabled = index < order.size - 1,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Move this card down",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // The fold reaches the card through a local rather than a
                // parameter, so the title stays where it has always been -- drawn
                // by the card itself, which is what lets it survive the fold.
                // Hiding what this owns instead would take the title with it.
                // Remembered, and that is load-bearing rather than tidy.
                // `LocalCardFold` is a `compositionLocalOf`, so it invalidates
                // every reader whenever its value stops comparing equal -- and a
                // `CardFold` built inline carries a fresh lambda each time, which
                // compares by identity and so never equals the last one. Each
                // recomposition would provoke the next: an infinite loop, and it
                // surfaces as the not-idle timeout on whichever test is slowest
                // rather than as anything pointing here.
                val isCollapsed = id in collapsed
                val fold =
                    onToggleCollapse?.let { toggle ->
                        remember(id, isCollapsed, defaultOrder) {
                            CardFold(
                                collapsed = isCollapsed,
                                onToggle = { toggle(id, defaultOrder) },
                            )
                        }
                    }
                CompositionLocalProvider(LocalCardFold provides fold) { card.content() }
            }
        }
    }
}
