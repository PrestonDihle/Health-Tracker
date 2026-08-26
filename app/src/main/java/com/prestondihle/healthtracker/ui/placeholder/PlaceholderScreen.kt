package com.prestondihle.healthtracker.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A tab that exists in the bottom bar before its cards have been moved into it.
 *
 * Scaffolding for the six-tab reorganisation, deleted once every tab is filled.
 * It says which tab it is and where its cards currently live, because a blank
 * screen and a broken screen look identical -- and during the reorganisation the
 * reader is the author, who needs to know whether to go looking for a bug.
 */
@Composable
fun PlaceholderScreen(name: String, comingFrom: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text(
            comingFrom,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
