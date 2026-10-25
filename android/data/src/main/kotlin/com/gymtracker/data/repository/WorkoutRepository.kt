package com.gymtracker.data.repository

import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Calculations
import com.gymtracker.shared.domain.ExerciseHistoryRow
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

class WorkoutRepository(private val db: AppDatabase) {
    fun observeSessions(): Flow<List<WorkoutSession>> =
        db.workoutSessionDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getSession(id: UUID): WorkoutSession? = db.workoutSessionDao().getById(id)?.toDomain()

    suspend fun getInProgress(): WorkoutSession? = db.workoutSessionDao().getInProgress()?.toDomain()

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

    /** The sets logged for [exerciseId] in the most recent session started before [before]. */
    suspend fun lastSessionSets(exerciseId: UUID, before: Instant): Pair<WorkoutSession, List<SetEntry>>? {
        val sessionId = db.workoutExerciseDao().getLatestSessionIdFor(exerciseId, before) ?: return null
        val session = db.workoutSessionDao().getById(sessionId)?.toDomain() ?: return null
        val sets = db.workoutExerciseDao().getBySession(sessionId).first()
            .filter { it.exerciseId == exerciseId }
            .flatMap { db.setDao().listByWorkoutExercise(it.id) }
            .map { it.toDomain() }
        return session to sets
    }

    /** Every past workout of [exerciseId], oldest first, with its top set and best e1RM. */
    suspend fun historyForExercise(
        exerciseId: UUID,
        excludeSessionId: UUID? = null
    ): List<ExerciseHistoryRow> {
        val rows = mutableListOf<ExerciseHistoryRow>()
        for (session in db.workoutSessionDao().getAll().first()) {
            if (session.id == excludeSessionId) continue
            val sets = db.workoutExerciseDao().getBySession(session.id).first()
                .filter { it.exerciseId == exerciseId }
                .flatMap { db.setDao().listByWorkoutExercise(it.id) }
                .map { it.toDomain() }
            if (sets.isEmpty()) continue
            val topSet = sets.filter { it.setType != SetType.WARMUP }.maxByOrNull { it.weightKg }
            rows += ExerciseHistoryRow(
                session = session.toDomain(),
                sets = sets,
                topSet = topSet,
                e1rm = Calculations.bestEstimatedOneRepMax(sets)
            )
        }
        return rows.sortedBy { it.session.startedAt }
    }
}
