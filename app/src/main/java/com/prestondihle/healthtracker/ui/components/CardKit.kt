package com.prestondihle.healthtracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// The panel every card on every tab is built from, and the pieces that go
// inside one.
//
// These lived three times over -- once on Wellness, once on the master
// graph, once on the fasting screen -- and the regrouping into six tabs would
// have made it five. Three copies had already drifted: the fasting screen's
// panel had grown a subtitle and lost the action slot, and its padding and
// title size were a step larger than the other two. A card that looks like a
// different card depending on which tab it was written for is not a style, it
// is an accident nobody chose.
//
// The compact sizing wins because the tabs are long: Fuel alone carries
// thirteen cards, and two dp of padding per card is a screenful of scrolling
// across a tab.
// ---------------------------------------------------------------------------

/** Space between cards down a screen, and the screen's own horizontal margin. */
internal val CardGap = 8.dp

/** Inside a card, from its edge to its content. */
internal val CardPadding = 10.dp

/** Between the rows within one card. */
internal val CardSpacing = 6.dp

/** Buttons at stock size waste a lot of vertical space when a card has three of them. */
internal val CompactButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/**
 * Thickness of a progress bar inside a card.
 *
 * Shared, unlike ChartHeight: a bar's thickness is a property of the bar and is
 * the same wherever one is drawn, while how tall a chart should be depends on
 * whether it is the main subject of its screen or a thumbnail beside other
 * figures -- which is why that one stays local to each screen.
 */
internal val BarHeight = 5.dp

/**
 * One titled panel, stacked down a screen.
 *
 * [subtitle] is for a card whose title cannot carry its own qualification --
 * what a span covers, or what counts as filled. [action] is the top-right slot,
 * which is where a refresh or an add lives; it sits on the title's row rather
 * than below it so that it cannot be mistaken for part of the content.
 *
 * The content is [ColumnScope] so a card can divide itself. Several do, and the
 * divider is what separates two groups of figures that would otherwise read as
 * one row of six.
 */
/**
 * Whether the card being drawn can be folded, and whether it currently is.
 *
 * A composition local rather than a parameter, and this is the one place in the
 * app where that is the right call: threading it through would mean a `title`
 * and a fold on every one of the ~56 card call sites, and the thing that needs
 * it *is* a composable — which is exactly the reason `ChartColors` is passed
 * explicitly instead (its consumers are enums and draw scopes, which cannot read
 * a local at all).
 *
 * Null means "not foldable", which is the honest default: a card outside
 * `reorderableCards` — the usual row on Log, the summary strip on Today — has
 * nowhere to save a fold and no arrows beside it to suggest one.
 */
internal data class CardFold(val collapsed: Boolean, val onToggle: () -> Unit)

internal val LocalCardFold = compositionLocalOf<CardFold?> { null }

/**
 * The chevron that folds a card, drawn in its title row.
 *
 * In the title row rather than beside the move arrows above it, because the
 * title row is the part that survives folding — a control that disappeared when
 * used would leave no way back.
 */
@Composable
internal fun CardFoldButton(fold: CardFold) {
    IconButton(onClick = fold.onToggle, modifier = Modifier.size(28.dp)) {
        Icon(
            if (fold.collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (fold.collapsed) "Expand this card" else "Collapse this card",
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TrackerCard(
    title: String,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(CardSpacing),
        ) {
            val fold = LocalCardFold.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The card's own action goes away with the body it acts on --
                    // a refresh button on a folded card would sync something the
                    // reader cannot see the result of.
                    if (fold?.collapsed != true) action?.invoke()
                    fold?.let { CardFoldButton(it) }
                }
            }
            if (fold?.collapsed == true) return@Column
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/** One labelled figure, sized to sit several to a row. */
@Composable
internal fun Metric(
    label: String,
    value: String,
    supporting: String? = null,
    /** Null keeps the default text colour; set only where the value itself carries meaning. */
    valueColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: Color.Unspecified,
            maxLines = 1,
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * A commit action sized to sit inline with a [Stepper]'s arrows.
 *
 * Passed as the stepper's `trailingContent`, this collapses what used to be a
 * whole second row -- a right-aligned "Log X" button -- onto the same line as the
 * value being logged. Filled (primary) where the arrows are tonal, so the button
 * that writes a reading stands apart from the two that only nudge it. Each card
 * reclaims a full button-height row, which is most of the point of the dense
 * layout: more metrics above the fold.
 */
@Composable
internal fun InlineLogButton(
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    FilledIconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.Check, contentDescription = contentDescription)
    }
}

/**
 * A compact log action, right-aligned under its inputs.
 *
 * Used where there is no single stepper to sit inline with: the mood card's
 * three sliders, and caffeine, where the button opens a dialog rather than
 * committing a dialled-in value. A small button pinned to the right reads as
 * "commit" without giving a secondary action the weight of a full-width primary
 * button.
 */
@Composable
internal fun LogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClick, enabled = enabled, contentPadding = CompactButtonPadding) {
            Text(text)
        }
    }
}

/**
 * How many rows of an entry list are shown before it is asked to open.
 *
 * Three rather than one, because the row this list exists for is the one just
 * logged wrongly -- a stray tap, a dose typed at the wrong strength -- and that
 * is nearly always the newest row or within a couple of it. One would make the
 * common correction cost a tap to reach; ten would not be a fold.
 */
private const val ENTRY_PREVIEW_ROWS = 3

/**
 * A list of correctable entries inside a card, showing its newest few until
 * asked for the rest.
 *
 * The hydration list is what forced this. It reaches back a week deliberately --
 * a stray 100 ml is spotted a day or two after it is written, and a list ending
 * at midnight would offer the fix only while nobody knew it was needed -- but a
 * reader who logs four drinks a day is then handed thirty rows above everything
 * below them on the tab, for a correction they make about once a month.
 *
 * **This is not the card fold, and neither replaces the other.**
 * [LocalCardFold] takes the whole card down to its title row, which also takes
 * away the buttons that log a drink; the entry list is the one part of these
 * cards nobody needs open. So the card keeps its figures, its chart and its
 * controls, and only the history folds.
 *
 * The count goes in the button rather than above the list, because the number of
 * rows hidden is exactly the thing a reader is deciding on when they consider
 * opening it -- "Show all 31" answers that where a bare "Show all" asks them to
 * open it to find out.
 *
 * State is [rememberSaveable] rather than stored: which way a list happens to be
 * folded is a glance-by-glance convenience, not a preference worth a column and
 * a migration, and it costs one tap to change. It survives rotation and process
 * death, which is as far as it is worth carrying.
 */
@Composable
internal fun <T> EntryList(
    entries: List<T>,
    header: String? = null,
    row: @Composable (T) -> Unit,
) {
    if (entries.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val shown = if (expanded) entries else entries.take(ENTRY_PREVIEW_ROWS)

    if (header != null) {
        Text(
            header,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    shown.forEach { row(it) }

    // Absent rather than disabled where everything already fits: a control that
    // cannot do anything is still a line of the card, and these cards are on the
    // two longest tabs in the app.
    if (entries.size > ENTRY_PREVIEW_ROWS) {
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = CompactButtonPadding,
        ) {
            Text(
                if (expanded) "Show fewer" else "Show all ${entries.size}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
