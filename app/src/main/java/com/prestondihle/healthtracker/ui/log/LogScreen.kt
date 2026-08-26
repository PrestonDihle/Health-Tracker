package com.prestondihle.healthtracker.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.ui.components.CardGap
import com.prestondihle.healthtracker.ui.dashboard.BloodPressureCard
import com.prestondihle.healthtracker.ui.dashboard.BodyCard
import com.prestondihle.healthtracker.ui.dashboard.DashboardViewModel
import com.prestondihle.healthtracker.ui.dashboard.GripStrengthCard
import com.prestondihle.healthtracker.ui.dashboard.MoodCard
import com.prestondihle.healthtracker.ui.dashboard.ReadingCard
import kotlinx.coroutines.launch

/**
 * The logging tab: every hand-entered measurement in one place.
 *
 * These are the controls that used to be scattered down the Wellness screen next
 * to their charts. Wellness now shows the trends; the writing is done here. All
 * of it is manual entry -- nothing on this screen reads Health Connect -- so it
 * shares [DashboardViewModel] but never touches its sync.
 */
@Composable
fun LogScreen(viewModel: DashboardViewModel, snackbarHostState: SnackbarHostState) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        contentPadding = PaddingValues(vertical = CardGap),
    ) {
        item { BodyCard(state = state, onWaistChange = viewModel::setWaistCm) }

        item {
            GripStrengthCard(
                state = state,
                onLog = { dominant, lbs ->
                    viewModel.logGripStrengthKg(dominant, Units.lbsToKg(lbs))
                    val hand = if (dominant) "dominant" else "non-dominant"
                    toast("Logged ${lbs.toInt()} lb $hand grip")
                },
            )
        }

        item {
            BloodPressureCard(
                state = state,
                onSubmit = { systolic, diastolic ->
                    viewModel.addBloodPressure(systolic, diastolic)
                    toast("Logged $systolic/$diastolic")
                },
            )
        }

        item {
            MoodCard(
                state = state,
                onSubmit = { vibe, energy, focus ->
                    viewModel.submitMood(vibe, energy, focus)
                    toast("Saved vibe $vibe, energy $energy, focus $focus")
                },
            )
        }

        item {
            ReadingCard(
                state = state,
                onLogPages = {
                    viewModel.logPages(it)
                    toast("Logged $it pages")
                },
                onSetPages = viewModel::setPages,
            )
        }

        item { Spacer(Modifier.height(8.dp)) }
    }
}
