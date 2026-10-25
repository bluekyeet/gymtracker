package com.gymtracker.data.repository

import androidx.room.withTransaction
import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.OutboxEntity
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Split
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.SplitDayExercise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class SplitRepository(private val db: AppDatabase) {
    fun observeAll(): Flow<List<Split>> = db.splitDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getActive(): Split? = db.splitDao().getActive()?.toDomain()

    suspend fun getSplit(id: UUID): Split? = db.splitDao().getById(id)?.toDomain()

    suspend fun save(split: Split) {
        db.withTransaction {
            db.splitDao().upsert(split.toEntity())
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "split",
                    entityId = split.id,
                    type = "upsert",
                    baseRevision = split.revision,
                    dataJson = Json.encodeToString(split)
                )
            )
        }
    }

    suspend fun setActive(id: UUID) {
        db.withTransaction {
            db.splitDao().setActive(id)
            val updated = db.splitDao().getById(id)?.toDomain()
            if (updated != null) {
                db.outboxDao().insert(
                    OutboxEntity(
                        opId = UUID.randomUUID(),
                        entity = "split",
                        entityId = updated.id,
                        type = "upsert",
                        baseRevision = updated.revision,
                        dataJson = Json.encodeToString(updated)
                    )
                )
            }
        }
    }

    suspend fun softDeleteSplit(id: UUID) {
        val now = java.time.Instant.now()
        db.withTransaction {
            db.splitDao().softDelete(id, now, now)
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "split",
                    entityId = id,
                    type = "delete",
                    baseRevision = 0,
                    dataJson = null
                )
            )
        }
    }

    fun observeDays(splitId: UUID): Flow<List<SplitDay>> =
        db.splitDayDao().getBySplit(splitId).map { it.map { e -> e.toDomain() } }

    suspend fun getDay(id: UUID): SplitDay? = db.splitDayDao().getById(id)?.toDomain()

    suspend fun saveDay(day: SplitDay) {
        db.withTransaction {
            db.splitDayDao().upsert(day.toEntity())
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "split_day",
                    entityId = day.id,
                    type = "upsert",
                    baseRevision = day.revision,
                    dataJson = Json.encodeToString(day)
                )
            )
        }
    }

    suspend fun softDeleteDay(id: UUID) {
        val now = java.time.Instant.now()
        db.withTransaction {
            db.splitDayDao().softDelete(id, now, now)
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "split_day",
                    entityId = id,
                    type = "delete",
                    baseRevision = 0,
                    dataJson = null
                )
            )
        }
    }

    fun observeDayExercises(splitDayId: UUID): Flow<List<SplitDayExercise>> =
        db.splitDayExerciseDao().getBySplitDay(splitDayId).map { it.map { e -> e.toDomain() } }

    suspend fun saveDayExercise(e: SplitDayExercise) {
        db.withTransaction {
            db.splitDayExerciseDao().upsert(e.toEntity())
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "split_day_exercise",
                    entityId = e.id,
                    type = "upsert",
                    baseRevision = e.revision,
                    dataJson = Json.encodeToString(e)
                )
            )
        }
    }

    suspend fun softDeleteDayExercise(id: UUID) {
        val now = java.time.Instant.now()
        db.withTransaction {
            db.splitDayExerciseDao().softDelete(id, now, now)
            db.outboxDao().insert(
                OutboxEntity(
                    opId = UUID.randomUUID(),
                    entity = "split_day_exercise",
                    entityId = id,
                    type = "delete",
                    baseRevision = 0,
                    dataJson = null
                )
            )
        }
    }

    /** Copies a split, its days and their exercises under fresh ids. The copy is never the active one. */
    suspend fun duplicateSplit(id: UUID) {
        val source = db.splitDao().getById(id) ?: return
        val copyId = UUID.randomUUID()
        val copySplit = source.copy(
            id = copyId,
            name = "${source.name} copy",
            isActive = false,
            revision = 0,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
        )
        save(copySplit.toDomain())

        for (day in db.splitDayDao().getBySplit(id).first()) {
            val dayCopyId = UUID.randomUUID()
            val copyDay = day.copy(id = dayCopyId, splitId = copyId, revision = 0)
            saveDay(copyDay.toDomain())

            for (e in db.splitDayExerciseDao().getBySplitDay(day.id).first()) {
                val copyEx = e.copy(id = UUID.randomUUID(), splitDayId = dayCopyId, revision = 0)
                saveDayExercise(copyEx.toDomain())
            }
        }
    }

    /** Queues any locally existing exercises, splits, days, and day exercises to the outbox if outbox is empty or missing entities */
    suspend fun backfillExistingSplitsToOutbox() {
        db.withTransaction {
            val existingEntityIds = db.outboxDao().getAll().map { it.entityId }.toSet()
            val exercises = db.exerciseDao().getAll().first()
            for (exerciseEntity in exercises) {
                val ex = exerciseEntity.toDomain()
                if (ex.id !in existingEntityIds) {
                    db.outboxDao().insert(
                        OutboxEntity(
                            opId = UUID.randomUUID(),
                            entity = "exercise",
                            entityId = ex.id,
                            type = "upsert",
                            baseRevision = ex.revision,
                            dataJson = Json.encodeToString(ex)
                        )
                    )
                }
            }
            val splits = db.splitDao().getAll().first()
            for (splitEntity in splits) {
                val split = splitEntity.toDomain()
                if (split.id !in existingEntityIds) {
                    db.outboxDao().insert(
                        OutboxEntity(
                            opId = UUID.randomUUID(),
                            entity = "split",
                            entityId = split.id,
                            type = "upsert",
                            baseRevision = split.revision,
                            dataJson = Json.encodeToString(split)
                        )
                    )
                }
                for (dayEntity in db.splitDayDao().getBySplit(split.id).first()) {
                    val day = dayEntity.toDomain()
                    if (day.id !in existingEntityIds) {
                        db.outboxDao().insert(
                            OutboxEntity(
                                opId = UUID.randomUUID(),
                                entity = "split_day",
                                entityId = day.id,
                                type = "upsert",
                                baseRevision = day.revision,
                                dataJson = Json.encodeToString(day)
                            )
                        )
                    }
                    for (deEntity in db.splitDayExerciseDao().getBySplitDay(day.id).first()) {
                        val de = deEntity.toDomain()
                        if (de.id !in existingEntityIds) {
                            db.outboxDao().insert(
                                OutboxEntity(
                                    opId = UUID.randomUUID(),
                                    entity = "split_day_exercise",
                                    entityId = de.id,
                                    type = "upsert",
                                    baseRevision = de.revision,
                                    dataJson = Json.encodeToString(de)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
