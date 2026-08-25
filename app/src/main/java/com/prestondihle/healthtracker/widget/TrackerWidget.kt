package com.prestondihle.healthtracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.prestondihle.healthtracker.TrackerApp
import com.prestondihle.healthtracker.domain.FastingAdherence
import com.prestondihle.healthtracker.domain.Units
import com.prestondihle.healthtracker.repository.TrackerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/**
 * A home-screen widget for the three things logged without looking.
 *
 * Water, caffeine and the fast are the entries that happen while doing something
 * else -- filling a glass, pouring a coffee, pushing a plate away -- and every
 * one of them currently costs unlocking the phone, finding the app and scrolling
 * to a card. That cost is why they go unlogged, and an unlogged intake is worse
 * than a roughly-logged one because the chart then says zero.
 *
 * Deliberately three buttons and two figures. A widget is glanced at, and
 * anything it shows that has to be *read* belongs on the screen it links to.
 */
class TrackerWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = (context.applicationContext as TrackerApp).container.trackerRepository
        val state = readState(repository)

        provideContent {
            GlanceTheme {
                WidgetBody(state)
            }
        }
    }

    @Composable
    private fun WidgetBody(state: WidgetState) {
        Column(
            modifier =
                GlanceModifier.fillMaxSize()
                    .background(GlanceTheme.colors.widgetBackground)
                    .padding(12.dp),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Text(
                "${state.waterOz} oz  ·  ${state.caffeineMg} mg",
                style =
                    TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    ),
            )
            Text(
                state.fastLabel,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
            Row(modifier = GlanceModifier.padding(top = 8.dp)) {
                WidgetButton("+ Water", actionRunCallback<AddWaterAction>())
                WidgetButton("+ Coffee", actionRunCallback<AddCaffeineAction>())
                WidgetButton(
                    if (state.fasting) "Stop" else "Fast",
                    actionRunCallback<ToggleFastAction>(),
                )
            }
        }
    }

    @Composable
    private fun WidgetButton(label: String, action: androidx.glance.action.Action) {
        Text(
            label,
            modifier =
                GlanceModifier.padding(horizontal = 8.dp, vertical = 6.dp).clickable(action),
            style =
                TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Medium),
        )
    }
}

/** Everything the widget draws, read once so the content is composed from a snapshot. */
private data class WidgetState(
    val waterOz: Int,
    val caffeineMg: Int,
    val fasting: Boolean,
    val fastLabel: String,
)

private suspend fun readState(repository: TrackerRepository): WidgetState {
    val today = LocalDate.now()
    val ml = repository.getHydrationTotalMl(today).first()
    val active = repository.getActiveFastingSession().first()
    val caffeine =
        repository
            .getCaffeineSince(Instant.now().minusSeconds(24 * 3_600))
            .first()
            .sumOf { it.milligrams }
    return WidgetState(
        waterOz = Units.mlToWholeOz(ml),
        caffeineMg = caffeine,
        fasting = active != null,
        fastLabel =
            if (active == null) "Not fasting"
            else {
                val minutes = java.time.Duration.between(active.startInstant, Instant.now()).toMinutes()
                "Fasting ${minutes / 60}h ${minutes % 60}m"
            },
    )
}

/** A quarter-pint, matching the smaller of the two buttons on the Today card. */
private const val WIDGET_WATER_ML = 118

/** The same ordinary cup the dashboard's log button starts at. */
private const val WIDGET_CAFFEINE_MG = 70

/** Fallback when the plan says nothing about today, matching the dashboard's. */
private const val WIDGET_DEFAULT_GOAL_MINUTES = 16 * 60

/**
 * Each action writes through the repository and then asks the widget to redraw.
 *
 * `update` rather than trusting the flow: a widget is not composed while the
 * home screen is idle, so nothing is collecting, and without the explicit
 * refresh the figure stays where it was until something else redrew it.
 */
class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, id: GlanceId, parameters: ActionParameters) {
        val repository = (context.applicationContext as TrackerApp).container.trackerRepository
        repository.addHydration(WIDGET_WATER_ML)
        TrackerWidget().update(context, id)
    }
}

class AddCaffeineAction : ActionCallback {
    override suspend fun onAction(context: Context, id: GlanceId, parameters: ActionParameters) {
        val repository = (context.applicationContext as TrackerApp).container.trackerRepository
        repository.addCaffeine(WIDGET_CAFFEINE_MG)
        TrackerWidget().update(context, id)
    }
}

/**
 * Starts a fast, or stops the running one.
 *
 * One button for both, because the answer to "am I fasting" is already on the
 * widget above it -- two buttons where only ever one applies is a widget mostly
 * made of controls that do nothing.
 */
class ToggleFastAction : ActionCallback {
    override suspend fun onAction(context: Context, id: GlanceId, parameters: ActionParameters) {
        val repository = (context.applicationContext as TrackerApp).container.trackerRepository
        val active = repository.getActiveFastingSession().first()
        if (active != null) {
            repository.endFast(active)
        } else {
            // The goal comes from the plan, exactly as the Today card works it
            // out. A widget that started every fast at some fixed length would
            // quietly score the week's adherence against the wrong target.
            val now = Instant.now()
            val plan = repository.getFastingPlan().first()
            val extended =
                repository
                    .getPlannedExtendedFasts(
                        now.minus(java.time.Duration.ofDays(2)),
                        now.plus(java.time.Duration.ofDays(3)),
                    )
                    .first()
            val goalMinutes =
                FastingAdherence.plannedGoalMinutesAt(plan, extended, now, ZoneId.systemDefault())
                    ?: WIDGET_DEFAULT_GOAL_MINUTES
            repository.startFast(
                FastingAdherence.typeForMinutes(goalMinutes),
                goalMinutes,
                now,
            )
        }
        TrackerWidget().update(context, id)
    }
}

class TrackerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TrackerWidget()
}
