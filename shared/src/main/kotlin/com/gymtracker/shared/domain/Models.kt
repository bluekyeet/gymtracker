package com.gymtracker.shared.domain

import java.time.Instant
import java.util.UUID

data class Exercise(
    val id: UUID,
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

data class Split(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val isActive: Boolean = false,
    val rotationPosition: Int = 0,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

data class SplitDay(
    val id: UUID,
    val splitId: UUID,
    val name: String,
    val weekdays: List<Int> = emptyList(),
    val sortKey: Double = 0.0,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

data class SplitDayExercise(
    val id: UUID,
    val splitDayId: UUID,
    val exerciseId: UUID?,
    val sortKey: Double = 0.0,
    val targetSets: Int? = null,
    val targetReps: Int? = null,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

data class WorkoutSession(
    val id: UUID,
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

data class WorkoutExercise(
    val id: UUID,
    val sessionId: UUID,
    val exerciseId: UUID? = null,
    val exerciseName: String,
    val sortKey: Double = 0.0,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

data class SetEntry(
    val id: UUID,
    val workoutExerciseId: UUID,
    val setNumber: Int,
    val weightKg: Double,
    val reps: Int,
    val rpe: Double? = null,
    val setType: SetType = SetType.WORKING,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

data class BodyWeight(
    val id: UUID,
    val userId: UUID,
    val date: Instant,
    val weightKg: Double,
    val revision: Long = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null
)

/** One past workout of a single exercise, as the exercise detail screen shows it. */
data class ExerciseHistoryRow(
    val session: WorkoutSession,
    val sets: List<SetEntry>,
    val topSet: SetEntry?,
    val e1rm: Double?
)
