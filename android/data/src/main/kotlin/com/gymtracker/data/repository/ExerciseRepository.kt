package com.gymtracker.data.repository

import androidx.room.withTransaction
import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.put
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.MuscleGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class ExerciseRepository(private val db: AppDatabase) {
    fun observeAll(): Flow<List<Exercise>> = db.exerciseDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getById(id: UUID): Exercise? = db.exerciseDao().getById(id)?.toDomain()

    suspend fun save(exercise: Exercise) {
        val entity = exercise.toEntity()
        db.withTransaction {
            db.exerciseDao().upsert(entity)
            db.outboxDao().put(
                com.gymtracker.data.db.OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "exercise",
                    entityId = exercise.id,
                    type = "upsert",
                    baseRevision = exercise.revision,
                    dataJson = Json.encodeToString(exercise)
                )
            )
        }
    }

    /**
     * Seeds the built-in exercises that are missing.
     *
     * Ids are derived from the name, so the phone and the watch seed the *same* rows and sync
     * merges them instead of listing "Bench Press" twice. A built-in is looked up by name,
     * deleted rows included, so deleting one (or all of them) never brings it back on the next launch.
     */
    suspend fun seedBuiltinsIfNeeded() {
        builtinExercises().forEach { builtin ->
            if (db.exerciseDao().countBuiltinNamed(builtin.name) == 0) save(builtin)
        }
    }

    suspend fun softDelete(id: UUID) {
        val now = java.time.Instant.now()
        db.withTransaction {
            db.exerciseDao().softDelete(id, now, now)
            db.outboxDao().put(
                com.gymtracker.data.db.OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "exercise",
                    entityId = id,
                    type = "delete",
                    baseRevision = 0,
                    dataJson = null
                )
            )
        }
    }

    private fun builtin(name: String, group: MuscleGroup) = Exercise(
        id = UUID.nameUUIDFromBytes("powertrack:builtin:$name".toByteArray()),
        name = name,
        muscleGroup = group,
        isCustom = false
    )

    private fun builtinExercises() = listOf(
        builtin("Bench Press", MuscleGroup.CHEST),
        builtin("Squat", MuscleGroup.LEGS),
        builtin("Deadlift", MuscleGroup.BACK),
        builtin("Overhead Press", MuscleGroup.SHOULDERS),
        builtin("Barbell Row", MuscleGroup.BACK)
    )
}
