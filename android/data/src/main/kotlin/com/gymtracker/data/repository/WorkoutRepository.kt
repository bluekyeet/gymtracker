package com.gymtracker.data.repository

import androidx.room.withTransaction
import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.OutboxEntity
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

class WorkoutRepository(private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun observeSessions(): Flow<List<WorkoutSession>> =
        db.workoutSessionDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getSession(id: UUID): WorkoutSession? = db.workoutSessionDao().getById(id)?.toDomain()

    suspend fun getInProgress(): WorkoutSession? = db.workoutSessionDao().getInProgress()?.toDomain()

    suspend fun saveSession(s: WorkoutSession) {
        db.withTransaction {
            db.workoutSessionDao().upsert(s.toEntity())
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "workout_session",
                    entityId = s.id,
                    type = "upsert",
                    baseRevision = s.revision,
                    dataJson = json.encodeToString(s)
                )
            )
        }
    }

    suspend fun softDeleteSession(id: UUID) {
        val now = Instant.now()
        db.withTransaction {
            db.workoutSessionDao().softDelete(id, now, now)
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "workout_session",
                    entityId = id,
                    type = "delete",
                    baseRevision = 0,
                    dataJson = null
                )
            )
        }
    }

    fun observeExercises(sessionId: UUID): Flow<List<WorkoutExercise>> =
        db.workoutExerciseDao().getBySession(sessionId).map { it.map { e -> e.toDomain() } }

    suspend fun saveExercise(e: WorkoutExercise) {
        db.withTransaction {
            db.workoutExerciseDao().upsert(e.toEntity())
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "workout_exercise",
                    entityId = e.id,
                    type = "upsert",
                    baseRevision = e.revision,
                    dataJson = json.encodeToString(e)
                )
            )
        }
    }

    suspend fun softDeleteExercise(id: UUID) {
        val now = Instant.now()
        db.withTransaction {
            db.workoutExerciseDao().softDelete(id, now, now)
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "workout_exercise",
                    entityId = id,
                    type = "delete",
                    baseRevision = 0,
                    dataJson = null
                )
            )
        }
    }

    fun observeSets(workoutExerciseId: UUID): Flow<List<SetEntry>> =
        db.setDao().getByWorkoutExercise(workoutExerciseId).map { it.map { e -> e.toDomain() } }

    suspend fun saveSet(s: SetEntry) {
        db.withTransaction {
            db.setDao().upsert(s.toEntity())
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "set",
                    entityId = s.id,
                    type = "upsert",
                    baseRevision = s.revision,
                    dataJson = json.encodeToString(s)
                )
            )
        }
    }

    suspend fun softDeleteSet(id: UUID) {
        val now = Instant.now()
        db.withTransaction {
            db.setDao().softDelete(id, now, now)
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "set",
                    entityId = id,
                    type = "delete",
                    baseRevision = 0,
                    dataJson = null
                )
            )
        }
    }

    /** Queues any locally existing workout sessions, exercises, and sets to the outbox if missing */
    suspend fun backfillExistingWorkoutsToOutbox() {
        db.withTransaction {
            val existingEntityIds = db.outboxDao().getAll().map { it.entityId }.toSet()
            val sessions = db.workoutSessionDao().getAll().first()
            for (sessionEntity in sessions) {
                val session = sessionEntity.toDomain()
                if (session.id !in existingEntityIds) {
                    db.outboxDao().insert(
                        OutboxEntity(
                            opId = UUID.randomUUID(),
                            entity = "workout_session",
                            entityId = session.id,
                            type = "upsert",
                            baseRevision = session.revision,
                            dataJson = json.encodeToString(session)
                        )
                    )
                }
                for (weEntity in db.workoutExerciseDao().getBySession(session.id).first()) {
                    val we = weEntity.toDomain()
                    if (we.id !in existingEntityIds) {
                        db.outboxDao().insert(
                            OutboxEntity(
                                opId = UUID.randomUUID(),
                                entity = "workout_exercise",
                                entityId = we.id,
                                type = "upsert",
                                baseRevision = we.revision,
                                dataJson = json.encodeToString(we)
                            )
                        )
                    }
                    for (setEntity in db.setDao().listByWorkoutExercise(we.id)) {
                        val set = setEntity.toDomain()
                        if (set.id !in existingEntityIds) {
                            db.outboxDao().insert(
                                OutboxEntity(
                                    opId = UUID.randomUUID(),
                                    entity = "set",
                                    entityId = set.id,
                                    type = "upsert",
                                    baseRevision = set.revision,
                                    dataJson = json.encodeToString(set)
                                )
                            )
                        }
                    }
                }
            }
        }
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
