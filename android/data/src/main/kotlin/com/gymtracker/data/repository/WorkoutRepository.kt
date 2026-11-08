package com.gymtracker.data.repository

import androidx.room.withTransaction
import com.gymtracker.data.OwnerId
import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.OutboxEntity
import com.gymtracker.data.db.put
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Calculations
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.ExerciseHistoryRow
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SplitDay
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
            db.outboxDao().put(
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

    /**
     * Starts a workout from [day]: the session, a snapshot of the day's exercises (names and order
     * are copied, so later plan edits never touch this workout) and the recommended sets, all in
     * ONE transaction with their outbox entries. Either the whole workout exists or none of it does,
     * and a day with many exercises costs one commit instead of dozens.
     */
    suspend fun startWorkout(day: SplitDay): WorkoutSession = db.withTransaction {
        val session = WorkoutSession(
            id = UUID.randomUUID(),
            userId = OwnerId.current,
            splitDayId = day.id,
            dayName = day.name,
            startedAt = Instant.now()
        )
        db.workoutSessionDao().upsert(session.toEntity())
        db.outboxDao().put(upsertOp("workout_session", session.id, session.revision, json.encodeToString(session)))

        for (planned in db.splitDayExerciseDao().listBySplitDay(day.id)) {
            val exercise = planned.exerciseId?.let { db.exerciseDao().getById(it) }
            val workoutExercise = WorkoutExercise(
                id = UUID.randomUUID(),
                sessionId = session.id,
                exerciseId = planned.exerciseId,
                exerciseName = exercise?.name ?: "Exercise",
                sortKey = planned.sortKey
            )
            db.workoutExerciseDao().upsert(workoutExercise.toEntity())
            db.outboxDao().put(upsertOp("workout_exercise", workoutExercise.id, workoutExercise.revision, json.encodeToString(workoutExercise)))

            // Pre-fill the recommended sets so the workout opens ready to log. They carry 0 kg until
            // the user enters a weight, and every statistic ignores 0 kg sets.
            val targetSets = planned.targetSets
            if (targetSets != null && targetSets in 1..50) {
                repeat(targetSets) { index ->
                    val set = SetEntry(
                        id = UUID.randomUUID(),
                        workoutExerciseId = workoutExercise.id,
                        setNumber = index + 1,
                        weightKg = 0.0,
                        reps = planned.targetReps ?: 0,
                        setType = SetType.WORKING
                    )
                    db.setDao().upsert(set.toEntity())
                    db.outboxDao().put(upsertOp("set", set.id, set.revision, json.encodeToString(set)))
                }
            }
        }
        session
    }

    /**
     * Starts a workout from an explicit exercise list (the watch's "quick workout" and its
     * "today's exercises" shortcut): same single-transaction guarantee, no pre-filled sets.
     */
    suspend fun startWorkout(dayId: UUID?, dayName: String?, exercises: List<Exercise>): WorkoutSession = db.withTransaction {
        val session = WorkoutSession(
            id = UUID.randomUUID(),
            userId = OwnerId.current,
            splitDayId = dayId,
            dayName = dayName,
            startedAt = Instant.now()
        )
        db.workoutSessionDao().upsert(session.toEntity())
        db.outboxDao().put(upsertOp("workout_session", session.id, session.revision, json.encodeToString(session)))
        exercises.forEachIndexed { index, exercise ->
            val workoutExercise = WorkoutExercise(
                id = UUID.randomUUID(),
                sessionId = session.id,
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                sortKey = index.toDouble()
            )
            db.workoutExerciseDao().upsert(workoutExercise.toEntity())
            db.outboxDao().put(upsertOp("workout_exercise", workoutExercise.id, workoutExercise.revision, json.encodeToString(workoutExercise)))
        }
        session
    }

    private fun upsertOp(entity: String, id: UUID, revision: Long, dataJson: String) = OutboxEntity(
        opId = UUID.randomUUID(),
        entity = entity,
        entityId = id,
        type = "upsert",
        baseRevision = revision,
        dataJson = dataJson
    )

    suspend fun softDeleteSession(id: UUID) {
        val now = Instant.now()
        db.withTransaction {
            db.workoutSessionDao().softDelete(id, now, now)
            db.outboxDao().put(
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
            db.outboxDao().put(
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
            db.outboxDao().put(
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
            db.outboxDao().put(
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
            db.outboxDao().put(
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
                    db.outboxDao().put(
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
                        db.outboxDao().put(
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
                            db.outboxDao().put(
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

    /**
     * Every past workout of [exerciseId], oldest first, with its top set and best e1RM.
     *
     * Two queries in total, however long the history is (it used to be one per session plus one per
     * exercise in it). Sets at 0 kg are kept in [ExerciseHistoryRow.sets] but never count as the
     * top set or toward the e1RM: they are planned-but-unlogged or bodyweight sets.
     */
    suspend fun historyForExercise(
        exerciseId: UUID,
        excludeSessionId: UUID? = null
    ): List<ExerciseHistoryRow> {
        val setsBySession = db.workoutExerciseDao().setsForExercise(exerciseId)
            .groupBy({ it.sessionId }, { it.set.toDomain() })
        return db.workoutExerciseDao().sessionsForExercise(exerciseId)
            .filter { it.id != excludeSessionId }
            .mapNotNull { session ->
                val sets = setsBySession[session.id].orEmpty()
                if (sets.isEmpty()) return@mapNotNull null
                ExerciseHistoryRow(
                    session = session.toDomain(),
                    sets = sets,
                    topSet = sets.filter { it.setType != SetType.WARMUP && it.weightKg > 0.0 }.maxByOrNull { it.weightKg },
                    e1rm = Calculations.bestEstimatedOneRepMax(sets.filter { it.setType != SetType.WARMUP })
                )
            }
    }
}
