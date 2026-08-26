package com.prestondihle.healthtracker.ui.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.domain.AdherenceResult
import com.prestondihle.healthtracker.domain.FastingAdherence
import com.prestondihle.healthtracker.domain.FastingDay
import com.prestondihle.healthtracker.domain.FastingStatistics
import com.prestondihle.healthtracker.domain.FastingStats
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** How far back the timeline draws. Two weeks fits a phone without scrolling forever. */
private const val TIMELINE_DAYS = 14L

data class FuelUiState(
    val days: List<FastingPlanDay> = emptyList(),
    val extendedFasts: List<PlannedExtendedFast> = emptyList(),
    val adherence: AdherenceResult? = null,
    val weekStart: LocalDate = LocalDate.now(),
    val timeline: List<FastingDay> = emptyList(),
    val stats: FastingStats = FastingStats(),
) {
    /** Plan rows in weekday order, filling gaps so all seven always render. */
    val orderedDays: List<FastingPlanDay>
        get() {
            val byDay = days.associateBy { it.dayOfWeek }
            return DayOfWeek.values().toList().map {
                byDay[it]
                    ?: FastingPlanDay(
                        it,
                        LocalTime.of(12, 0),
                        LocalTime.of(20, 0),
                        hasFeedingWindow = true,
                    )
            }
        }

    /** Planned fasting hours per week, for the summary line. */
    val plannedHoursPerWeek: Int
        get() =
            orderedDays
                .sumOf { day ->
                    // A no-eating day is a full 24 hours of planned fast.
                    if (!day.hasFeedingWindow) 24L * 60
                    else 24L * 60 - feedingMinutes(day)
                }
                .toInt() / 60

    private fun feedingMinutes(day: FastingPlanDay): Long =
        if (day.feedingEnd.isAfter(day.feedingStart)) {
            Duration.between(day.feedingStart, day.feedingEnd).toMinutes()
        } else {
            // Window wraps past midnight.
            24L * 60 - Duration.between(day.feedingEnd, day.feedingStart).toMinutes()
        }
}

class FuelViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private fun weekBounds(date: LocalDate): Pair<Instant, Instant> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return start.atStartOfDay(zoneId).toInstant() to
            start.plusWeeks(1).atStartOfDay(zoneId).toInstant()
    }

    val uiState: StateFlow<FuelUiState> =
        run {
            val today = LocalDate.now(zoneId)
            val (weekStart, weekEnd) = weekBounds(today)
            // Look four weeks ahead so upcoming extended fasts are visible and editable.
            val horizonEnd = weekEnd.plusSeconds(21 * 24 * 3600)

            combine(
                repository.getFastingPlan(),
                repository.getPlannedExtendedFasts(weekStart, horizonEnd),
                repository.getFastingSessionsOverlapping(weekStart, weekEnd),
                // Stats such as the longest fast are all-time, so this cannot be
                // scoped to the week the adherence score uses.
                repository.getAllFastingSessions(),
            ) { plan, extended, weekSessions, allSessions ->
                val now = Instant.now()
                val timeline =
                    FastingStatistics.daysBetween(
                        sessions = allSessions,
                        from = today.minusDays(TIMELINE_DAYS - 1),
                        to = today,
                        zoneId = zoneId,
                        now = now,
                    )
                // Streaks look further back than the timeline draws, otherwise a
                // 20-day run would report as 14.
                val streakWindow =
                    FastingStatistics.daysBetween(
                        sessions = allSessions,
                        from = today.minusDays(364),
                        to = today,
                        zoneId = zoneId,
                        now = now,
                    )

                FuelUiState(
                    days = plan,
                    extendedFasts = extended,
                    adherence =
                        FastingAdherence.score(
                            plan = plan,
                            extendedFasts = extended,
                            sessions = weekSessions,
                            weekStart = weekStart,
                            weekEnd = weekEnd,
                            now = now,
                            zoneId = zoneId,
                        ),
                    weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    timeline = timeline,
                    stats =
                        FastingStatistics.summarise(
                            sessions = allSessions,
                            days = streakWindow,
                            today = today,
                            zoneId = zoneId,
                            now = now,
                        ),
                )
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FuelUiState(),
            )

    init {
        viewModelScope.launch {
            if (repository.getFastingPlan().first().isEmpty()) {
                repository.upsertFastingPlan(FastingAdherence.defaultPlan())
            }
        }
    }

    fun setFeedingWindow(day: DayOfWeek, start: LocalTime, end: LocalTime) {
        viewModelScope.launch {
            val existing = uiState.value.orderedDays.first { it.dayOfWeek == day }
            repository.upsertFastingPlanDay(
                existing.copy(feedingStart = start, feedingEnd = end)
            )
        }
    }

    /** Off means no eating that day: the full 24 hours become a planned fast. */
    fun setHasFeedingWindow(day: DayOfWeek, hasWindow: Boolean) {
        viewModelScope.launch {
            val existing = uiState.value.orderedDays.first { it.dayOfWeek == day }
            repository.upsertFastingPlanDay(existing.copy(hasFeedingWindow = hasWindow))
        }
    }

    fun addExtendedFast(startDate: LocalDate, type: FastingType) {
        val hours =
            when (type) {
                FastingType.EXTENDED_24 -> 24L
                FastingType.EXTENDED_36 -> 36L
                FastingType.EXTENDED_48 -> 48L
                else -> 24L
            }
        val start = startDate.atStartOfDay(zoneId).toInstant()
        viewModelScope.launch {
            repository.addPlannedExtendedFast(
                PlannedExtendedFast(
                    startInstant = start,
                    endInstant = start.plusSeconds(hours * 3600),
                    type = type,
                )
            )
        }
    }

    fun deleteExtendedFast(fast: PlannedExtendedFast) {
        viewModelScope.launch { repository.deletePlannedExtendedFast(fast) }
    }

    companion object {
        fun provideFactory(repository: TrackerRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    FuelViewModel(repository) as T
            }
    }
}
