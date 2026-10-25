package com.gymtracker.data.repository

import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.MuscleGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

class ExerciseRepository(private val db: AppDatabase) {
    fun observeAll(): Flow<List<Exercise>> = db.exerciseDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getById(id: UUID): Exercise? = db.exerciseDao().getById(id)?.toDomain()

    suspend fun save(exercise: Exercise) = db.exerciseDao().upsert(exercise.toEntity())

    /** Seeds the built-in exercise library the first time the app runs. */
    suspend fun seedBuiltinsIfNeeded() {
        if (db.exerciseDao().getAll().first().isNotEmpty()) return
        builtinExercises().forEach { save(it) }
    }

    suspend fun softDelete(id: UUID) {
        val now = java.time.Instant.now()
        db.exerciseDao().softDelete(id, now, now)
    }

    private fun builtinExercises() = listOf(
        Exercise(id = UUID.randomUUID(), name = "Bench Press", muscleGroup = MuscleGroup.CHEST, isCustom = false),
        Exercise(id = UUID.randomUUID(), name = "Squat", muscleGroup = MuscleGroup.LEGS, isCustom = false),
        Exercise(id = UUID.randomUUID(), name = "Deadlift", muscleGroup = MuscleGroup.BACK, isCustom = false),
        Exercise(id = UUID.randomUUID(), name = "Overhead Press", muscleGroup = MuscleGroup.SHOULDERS, isCustom = false),
        Exercise(id = UUID.randomUUID(), name = "Barbell Row", muscleGroup = MuscleGroup.BACK, isCustom = false)
    )
}
