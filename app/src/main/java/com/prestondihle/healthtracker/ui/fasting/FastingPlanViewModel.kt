package com.prestondihle.healthtracker.ui.fasting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.prestondihle.healthtracker.data.FastingPlanDay
import com.prestondihle.healthtracker.data.FastingType
import com.prestondihle.healthtracker.data.PlannedExtendedFast
import com.prestondihle.healthtracker.domain.AdherenceResult
import com.prestondihle.healthtracker.domain.FastingAdherence
import com.prestondihle.healthtracker.repository.TrackerRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class FastingPlanUiState(
    val days: List<FastingPlanDay> = emptyList(),
    val extendedFasts: List<PlannedExtendedFast> = emptyList(),
    val adherence: AdherenceResult? = null,
    val weekStart: LocalDate = LocalDate.now(),
) {
    /** Plan rows in weekday order, filling gaps so all seven always render. */
    val orderedDays: List<FastingPlanDay>
        get() {
            val byDay = days.associateBy { it.dayOfWeek }
            return DayOfWeek.values().toList().map {
                byDay[it]
                    ?: FastingPlanDay(it, LocalTime.of(12, 0), LocalTime.of(20, 0), enabled = true)
            }
        }

    /** Planned fasting hours per week, for the summary line. */
    val plannedHoursPerWeek: Int
        get() =
            orderedDays.sumOf { day ->
                if (!day.enabled) 0L
                else {
                    val feedingMinutes =
                        if (day.feedingEnd.isAfter(day.feedingStart)) {
                            java.time.Duration.between(day.feedingStart, day.feedingEnd).toMinutes()
                        } else {
                            24 * 60 -
                                java.time.Duration.between(day.feedingEnd, day.feedingStart)
                                    .toMinutes()
                        }
                    (24 * 60 - feedingMinutes)
                }
            }
                .toInt() / 60
}

class FastingPlanViewModel(
    private val repository: TrackerRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private fun weekBounds(date: LocalDate): Pair<Instant, Instant> {
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return start.atStartOfDay(zoneId).toInstant() to
            start.plusWeeks(1).atStartOfDay(zoneId).toInstant()
    }

    val uiState: StateFlow<FastingPlanUiState> =
        run {
            val today = LocalDate.now(zoneId)
            val (weekStart, weekEnd) = weekBounds(today)
            // Look four weeks ahead so upcoming extended fasts are visible and editable.
            val horizonEnd = weekEnd.plusSeconds(21 * 24 * 3600)

            combine(
                repository.getFastingPlan(),
                repository.getPlannedExtendedFasts(weekStart, horizonEnd),
                repository.getFastingSessionsOverlapping(weekStart, weekEnd),
            ) { plan, extended, sessions ->
                FastingPlanUiState(
                    days = plan,
                    extendedFasts = extended,
                    adherence =
                        FastingAdherence.score(
                            plan = plan,
                            extendedFasts = extended,
                            sessions = sessions,
                            weekStart = weekStart,
                            weekEnd = weekEnd,
                            now = Instant.now(),
                            zoneId = zoneId,
                        ),
                    weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                )
            }
        }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = FastingPlanUiState(),
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

    fun setDayEnabled(day: DayOfWeek, enabled: Boolean) {
        viewModelScope.launch {
            val existing = uiState.value.orderedDays.first { it.dayOfWeek == day }
            repository.upsertFastingPlanDay(existing.copy(enabled = enabled))
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
                    FastingPlanViewModel(repository) as T
            }
    }
}
