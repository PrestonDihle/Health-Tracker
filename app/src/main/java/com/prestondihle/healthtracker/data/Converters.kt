package com.prestondihle.healthtracker.data

import androidx.room.TypeConverter
import com.prestondihle.healthtracker.domain.SleepStage
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class Converters {
    @TypeConverter
    fun fromLocalDate(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun toLocalDate(epochDay: Long?): LocalDate? = epochDay?.let { LocalDate.ofEpochDay(it) }

    @TypeConverter
    fun fromInstant(instant: Instant?): Long? = instant?.toEpochMilli()

    @TypeConverter
    fun toInstant(millis: Long?): Instant? = millis?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun fromFastingType(type: FastingType?): String? = type?.name

    @TypeConverter
    fun toFastingType(name: String?): FastingType? = name?.let { FastingType.valueOf(it) }

    @TypeConverter
    fun fromDataSourceEnum(source: DataSourceEnum?): String? = source?.name

    @TypeConverter
    fun toDataSourceEnum(name: String?): DataSourceEnum? = name?.let { DataSourceEnum.valueOf(it) }

    @TypeConverter
    fun fromUnitSystemEnum(system: UnitSystemEnum?): String? = system?.name

    @TypeConverter
    fun toUnitSystemEnum(name: String?): UnitSystemEnum? = name?.let { UnitSystemEnum.valueOf(it) }

    @TypeConverter
    fun fromDayOfWeek(day: DayOfWeek?): String? = day?.name

    @TypeConverter
    fun toDayOfWeek(name: String?): DayOfWeek? = name?.let { DayOfWeek.valueOf(it) }

    /** Stored as second-of-day so plan windows sort and compare numerically. */
    @TypeConverter
    fun fromLocalTime(time: LocalTime?): Int? = time?.toSecondOfDay()

    @TypeConverter
    fun toLocalTime(secondOfDay: Int?): LocalTime? = secondOfDay?.let { LocalTime.ofSecondOfDay(it.toLong()) }

    @TypeConverter
    fun fromSupplementSlot(slot: SupplementSlot?): String? = slot?.name

    @TypeConverter
    fun toSupplementSlot(name: String?): SupplementSlot? = name?.let { SupplementSlot.valueOf(it) }

    @TypeConverter
    fun fromMovementType(movement: MovementType?): String? = movement?.name

    @TypeConverter
    fun toMovementType(name: String?): MovementType? = name?.let { MovementType.valueOf(it) }

    @TypeConverter
    fun fromSleepStage(stage: SleepStage?): String? = stage?.name

    @TypeConverter
    fun toSleepStage(name: String?): SleepStage? = name?.let { SleepStage.valueOf(it) }
}
