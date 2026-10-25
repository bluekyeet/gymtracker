package com.gymtracker.data.db

import androidx.room.TypeConverter
import com.gymtracker.shared.domain.Equipment
import com.gymtracker.shared.domain.MuscleGroup
import com.gymtracker.shared.domain.SetType
import java.time.Instant
import java.util.UUID

class Converters {
    @TypeConverter
    fun fromUuid(value: UUID?): String? = value?.toString()

    @TypeConverter
    fun toUuid(value: String?): UUID? = value?.let { UUID.fromString(it) }

    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun fromSetType(value: SetType?): String? = value?.name

    @TypeConverter
    fun toSetType(value: String?): SetType? = value?.let { SetType.valueOf(it) }

    @TypeConverter
    fun fromMuscleGroup(value: MuscleGroup?): String? = value?.name

    @TypeConverter
    fun toMuscleGroup(value: String?): MuscleGroup? = value?.let { MuscleGroup.valueOf(it) }

    @TypeConverter
    fun fromEquipment(value: Equipment?): String? = value?.name

    @TypeConverter
    fun toEquipment(value: String?): Equipment? = value?.let { Equipment.valueOf(it) }
}
