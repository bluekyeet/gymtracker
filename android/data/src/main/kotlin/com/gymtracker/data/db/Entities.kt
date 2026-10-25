package com.gymtracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gymtracker.shared.domain.Equipment
import com.gymtracker.shared.domain.MuscleGroup
import java.time.Instant
import java.util.UUID

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: UUID,
    val userId: UUID? = null,
    val name: String,
    val muscleGroup: MuscleGroup? = null,
    val customGroup: String? = null,
    val equipment: Equipment? = null,
    val isCustom: Boolean = false,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

@Entity(tableName = "splits")
data class SplitEntity(
    @PrimaryKey val id: UUID,
    val userId: UUID,
    val name: String,
    val isActive: Boolean = false,
    val rotationPosition: Int = 0,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

@Entity(tableName = "split_days", foreignKeys = [androidx.room.ForeignKey(entity = SplitEntity::class, parentColumns = ["id"], childColumns = ["splitId"], onDelete = androidx.room.ForeignKey.CASCADE)])
data class SplitDayEntity(
    @PrimaryKey val id: UUID,
    val splitId: UUID,
    val name: String,
    val weekdays: String? = null,
    val sortKey: Double = 0.0,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

@Entity(tableName = "split_day_exercises", foreignKeys = [androidx.room.ForeignKey(entity = SplitDayEntity::class, parentColumns = ["id"], childColumns = ["splitDayId"], onDelete = androidx.room.ForeignKey.CASCADE)])
data class SplitDayExerciseEntity(
    @PrimaryKey val id: UUID,
    val splitDayId: UUID,
    val exerciseId: UUID? = null,
    val sortKey: Double = 0.0,
    val targetSets: Int? = null,
    val targetReps: Int? = null,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey val id: UUID,
    val userId: UUID,
    val splitDayId: UUID? = null,
    val dayName: String? = null,
    val startedAt: Instant = Instant.now(),
    val endedAt: Instant? = null,
    val notes: String? = null,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

@Entity(tableName = "workout_exercises", foreignKeys = [androidx.room.ForeignKey(entity = WorkoutSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = androidx.room.ForeignKey.CASCADE)])
data class WorkoutExerciseEntity(
    @PrimaryKey val id: UUID,
    val sessionId: UUID,
    val exerciseId: UUID? = null,
    val exerciseName: String,
    val sortKey: Double = 0.0,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

@Entity(tableName = "sets", foreignKeys = [androidx.room.ForeignKey(entity = WorkoutExerciseEntity::class, parentColumns = ["id"], childColumns = ["workoutExerciseId"], onDelete = androidx.room.ForeignKey.CASCADE)])
data class SetEntity(
    @PrimaryKey val id: UUID,
    val workoutExerciseId: UUID,
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int,
    val rpe: Double? = null,
    val setType: com.gymtracker.shared.domain.SetType = com.gymtracker.shared.domain.SetType.WORKING,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

@Entity(tableName = "body_weights")
data class BodyWeightEntity(
    @PrimaryKey val id: UUID,
    val userId: UUID,
    val date: Instant,
    val weightKg: Double,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)
