package com.gymtracker.data.repository

import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class WorkoutRepository(private val db: AppDatabase) {
    fun observeSessions(): Flow<List<WorkoutSession>> =
        db.workoutSessionDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getSession(id: UUID): WorkoutSession? = db.workoutSessionDao().getById(id)?.toDomain()

    suspend fun saveSession(s: WorkoutSession) = db.workoutSessionDao().upsert(s.toEntity())

    fun observeExercises(sessionId: UUID): Flow<List<WorkoutExercise>> =
        db.workoutExerciseDao().getBySession(sessionId).map { it.map { e -> e.toDomain() } }

    suspend fun saveExercise(e: WorkoutExercise) = db.workoutExerciseDao().upsert(e.toEntity())

    suspend fun softDeleteExercise(id: UUID) {
        val now = java.time.Instant.now()
        db.workoutExerciseDao().softDelete(id, now, now)
    }

    fun observeSets(workoutExerciseId: UUID): Flow<List<SetEntry>> =
        db.setDao().getByWorkoutExercise(workoutExerciseId).map { it.map { e -> e.toDomain() } }

    suspend fun saveSet(s: SetEntry) = db.setDao().upsert(s.toEntity())

    suspend fun softDeleteSet(id: UUID) {
        val now = java.time.Instant.now()
        db.setDao().softDelete(id, now, now)
    }
}
