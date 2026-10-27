package com.gymtracker.server.sync

import com.gymtracker.server.database.*
import com.gymtracker.shared.domain.*
import com.gymtracker.shared.sync.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class SyncService {
    private val json = Json { ignoreUnknownKeys = true }

    fun push(userId: UUID, req: SyncPushRequest): SyncPushResponse {
        return transaction {
            // lock user row
            val userRow = Users.selectAll().where { Users.id eq userId }.forUpdate().single()
            var seq = userRow[Users.lastSeq]
            val results = mutableListOf<OpResult>()
            // One bulk idempotency check instead of a SELECT per op — this used to be the main
            // cost of re-pushing a large outbox (every already-applied op still cost a round trip).
            val opIds = req.operations.map { UUID.fromString(it.opId) }
            val existingResults = if (opIds.isEmpty()) emptyMap() else
                AppliedOps.selectAll().where { AppliedOps.opId inList opIds }
                    .associate { it[AppliedOps.opId] to it[AppliedOps.result] }
            for (op in req.operations) {
                // idempotency
                val stored = existingResults[UUID.fromString(op.opId)]
                if (stored != null) {
                    // stored format "applied:5" or "rejected:deleted"
                    val parts = stored.split(":", limit = 2)
                    results.add(OpResult(op.opId, parts[0], parts.getOrNull(1)?.toLongOrNull(), parts.getOrNull(1)?.takeIf { !it.all { c -> c.isDigit() } }))
                    continue
                }
                // Isolate each op in its own savepoint: one bad op (e.g. a dangling FK)
                // must not roll back every other op already applied in this push batch.
                val savepoint = "sp_${op.opId.replace("-", "")}"
                exec("SAVEPOINT $savepoint")
                try {
                    val applied = processOp(userId, op, seq) { newSeq -> seq = newSeq }
                    exec("RELEASE SAVEPOINT $savepoint")
                    results.add(applied.first)
                    if (applied.second) seq = applied.third
                } catch (e: Exception) {
                    exec("ROLLBACK TO SAVEPOINT $savepoint")
                    storeOp(UUID.fromString(op.opId), userId, "rejected:server_error")
                    results.add(OpResult(op.opId, "rejected", reason = "server_error: ${e.message?.take(200)}"))
                }
            }
            // update user last_seq
            Users.update({ Users.id eq userId }) { it[lastSeq] = seq }
            SyncPushResponse(results)
        }
    }

    // returns (result, seqChanged, newSeq)
    private fun processOp(userId: UUID, op: SyncOperation, currentSeq: Long, onSeq: (Long) -> Unit): Triple<OpResult, Boolean, Long> {
        var seq = currentSeq
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        // determine result
        val result: OpResult
        val seqChanged: Boolean
        when (op.type) {
            "upsert" -> {
                val r = handleUpsert(userId, op, now)
                if (r.first == "applied") {
                    seq++
                    writeChange(userId, seq, op.entity, UUID.fromString(op.entityId), r.second!!, false, now)
                    storeOp(UUID.fromString(op.opId), userId, "applied:${r.second}")
                    result = OpResult(op.opId, "applied", r.second)
                    seqChanged = true
                } else if (r.first == "rejected") {
                    storeOp(UUID.fromString(op.opId), userId, "rejected:${r.third}")
                    result = OpResult(op.opId, "rejected", reason = r.third)
                    seqChanged = false
                } else {
                    result = OpResult(op.opId, "retry")
                    seqChanged = false
                }
            }
            "delete" -> {
                val r = handleDelete(userId, op, now)
                if (r.first == "applied") {
                    seq++
                    writeChange(userId, seq, op.entity, UUID.fromString(op.entityId), r.second!!, true, now)
                    storeOp(UUID.fromString(op.opId), userId, "applied:${r.second}")
                    result = OpResult(op.opId, "applied", r.second)
                    seqChanged = true
                } else if (r.first == "rejected") {
                    storeOp(UUID.fromString(op.opId), userId, "rejected:${r.third}")
                    result = OpResult(op.opId, "rejected", reason = r.third)
                    seqChanged = false
                } else {
                    result = OpResult(op.opId, "retry")
                    seqChanged = false
                }
            }
            else -> {
                result = OpResult(op.opId, "rejected", reason = "unknown_type")
                seqChanged = false
            }
        }
        return Triple(result, seqChanged, seq)
    }

    // returns (status, newRevision, reason)
    private fun handleUpsert(userId: UUID, op: SyncOperation, now: OffsetDateTime): Triple<String, Long?, String?> {
        val data = op.data ?: return Triple("rejected", null, "missing_data")
        return when (op.entity) {
            "exercise" -> {
                val m = json.decodeFromJsonElement<Exercise>(data)
                if (m.userId != null && m.userId != userId) return Triple("rejected", null, "forbidden")
                val existing = Exercises.selectAll().where { Exercises.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing != null && existing[Exercises.deletedAt] != null) return Triple("rejected", null, "deleted")
                val newRev = (existing?.get(Exercises.revision) ?: 0) + 1
                if (existing == null) {
                    Exercises.insert {
                        it[id] = UUID.fromString(op.entityId)
                        it[Exercises.userId] = m.userId ?: userId
                        it[name] = m.name
                        it[muscleGroup] = m.muscleGroup?.name
                        it[customGroup] = m.customGroup
                        it[equipment] = m.equipment?.name
                        it[isCustom] = m.isCustom
                        it[revision] = newRev
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    Exercises.update({ Exercises.id eq UUID.fromString(op.entityId) }) {
                        it[name] = m.name
                        it[muscleGroup] = m.muscleGroup?.name
                        it[customGroup] = m.customGroup
                        it[equipment] = m.equipment?.name
                        it[isCustom] = m.isCustom
                        it[revision] = newRev
                        it[updatedAt] = now
                    }
                }
                Triple("applied", newRev, null)
            }
            "split" -> {
                val m = json.decodeFromJsonElement<Split>(data)
                val existing = Splits.selectAll().where { Splits.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing != null && existing[Splits.deletedAt] != null) return Triple("rejected", null, "deleted")
                val newRev = (existing?.get(Splits.revision) ?: 0) + 1
                if (existing == null) {
                    Splits.insert {
                        it[id] = UUID.fromString(op.entityId)
                        it[Splits.userId] = userId
                        it[name] = m.name
                        it[isActive] = m.isActive
                        it[rotationPosition] = m.rotationPosition
                        it[revision] = newRev
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    Splits.update({ Splits.id eq UUID.fromString(op.entityId) }) {
                        it[name] = m.name
                        it[isActive] = m.isActive
                        it[rotationPosition] = m.rotationPosition
                        it[revision] = newRev
                        it[updatedAt] = now
                    }
                }
                Triple("applied", newRev, null)
            }
            "split_day" -> {
                val m = json.decodeFromJsonElement<SplitDay>(data)
                val existing = SplitDays.selectAll().where { SplitDays.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing != null && existing[SplitDays.deletedAt] != null) return Triple("rejected", null, "deleted")
                val newRev = (existing?.get(SplitDays.revision) ?: 0) + 1
                val weekdaysStr = if (m.weekdays.isEmpty()) null else m.weekdays.joinToString(",")
                if (existing == null) {
                    SplitDays.insert {
                        it[id] = UUID.fromString(op.entityId)
                        it[splitId] = m.splitId
                        it[name] = m.name
                        it[weekdays] = weekdaysStr
                        it[sortKey] = m.sortKey
                        it[revision] = newRev
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    SplitDays.update({ SplitDays.id eq UUID.fromString(op.entityId) }) {
                        it[splitId] = m.splitId
                        it[name] = m.name
                        it[weekdays] = weekdaysStr
                        it[sortKey] = m.sortKey
                        it[revision] = newRev
                        it[updatedAt] = now
                    }
                }
                Triple("applied", newRev, null)
            }
            "split_day_exercise" -> {
                val m = json.decodeFromJsonElement<SplitDayExercise>(data)
                val existing = SplitDayExercises.selectAll().where { SplitDayExercises.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing != null && existing[SplitDayExercises.deletedAt] != null) return Triple("rejected", null, "deleted")
                val newRev = (existing?.get(SplitDayExercises.revision) ?: 0) + 1
                if (existing == null) {
                    SplitDayExercises.insert {
                        it[id] = UUID.fromString(op.entityId)
                        it[splitDayId] = m.splitDayId
                        it[exerciseId] = m.exerciseId
                        it[sortKey] = m.sortKey
                        it[targetSets] = m.targetSets
                        it[targetReps] = m.targetReps
                        it[revision] = newRev
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    SplitDayExercises.update({ SplitDayExercises.id eq UUID.fromString(op.entityId) }) {
                        it[splitDayId] = m.splitDayId
                        it[exerciseId] = m.exerciseId
                        it[sortKey] = m.sortKey
                        it[targetSets] = m.targetSets
                        it[targetReps] = m.targetReps
                        it[revision] = newRev
                        it[updatedAt] = now
                    }
                }
                Triple("applied", newRev, null)
            }
            "workout_session" -> {
                val m = json.decodeFromJsonElement<WorkoutSession>(data)
                val existing = WorkoutSessions.selectAll().where { WorkoutSessions.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing != null && existing[WorkoutSessions.deletedAt] != null) return Triple("rejected", null, "deleted")
                val newRev = (existing?.get(WorkoutSessions.revision) ?: 0) + 1
                if (existing == null) {
                    WorkoutSessions.insert {
                        it[id] = UUID.fromString(op.entityId)
                        it[WorkoutSessions.userId] = userId
                        it[splitDayId] = m.splitDayId
                        it[dayName] = m.dayName
                        it[startedAt] = OffsetDateTime.ofInstant(m.startedAt, ZoneOffset.UTC)
                        it[endedAt] = m.endedAt?.let { t -> OffsetDateTime.ofInstant(t, ZoneOffset.UTC) }
                        it[notes] = m.notes
                        it[revision] = newRev
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    WorkoutSessions.update({ WorkoutSessions.id eq UUID.fromString(op.entityId) }) {
                        it[dayName] = m.dayName
                        it[endedAt] = m.endedAt?.let { t -> OffsetDateTime.ofInstant(t, ZoneOffset.UTC) }
                        it[notes] = m.notes
                        it[revision] = newRev
                        it[updatedAt] = now
                    }
                }
                Triple("applied", newRev, null)
            }
            "workout_exercise" -> {
                val m = json.decodeFromJsonElement<WorkoutExercise>(data)
                val existing = WorkoutExercises.selectAll().where { WorkoutExercises.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing != null && existing[WorkoutExercises.deletedAt] != null) return Triple("rejected", null, "deleted")
                val newRev = (existing?.get(WorkoutExercises.revision) ?: 0) + 1
                if (existing == null) {
                    WorkoutExercises.insert {
                        it[id] = UUID.fromString(op.entityId)
                        it[sessionId] = m.sessionId
                        it[exerciseId] = m.exerciseId
                        it[exerciseName] = m.exerciseName
                        it[sortKey] = m.sortKey
                        it[revision] = newRev
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    WorkoutExercises.update({ WorkoutExercises.id eq UUID.fromString(op.entityId) }) {
                        it[exerciseName] = m.exerciseName
                        it[sortKey] = m.sortKey
                        it[revision] = newRev
                        it[updatedAt] = now
                    }
                }
                Triple("applied", newRev, null)
            }
            "set" -> {
                val m = json.decodeFromJsonElement<SetEntry>(data)
                val existing = Sets.selectAll().where { Sets.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing != null && existing[Sets.deletedAt] != null) return Triple("rejected", null, "deleted")
                val newRev = (existing?.get(Sets.revision) ?: 0) + 1
                if (existing == null) {
                    Sets.insert {
                        it[id] = UUID.fromString(op.entityId)
                        it[workoutExerciseId] = m.workoutExerciseId
                        it[setNumber] = m.setNumber
                        it[weightKg] = m.weightKg.toBigDecimal()
                        it[reps] = m.reps
                        it[rpe] = m.rpe?.toBigDecimal()
                        it[setType] = m.setType.name.lowercase()
                        it[revision] = newRev
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                } else {
                    Sets.update({ Sets.id eq UUID.fromString(op.entityId) }) {
                        it[setNumber] = m.setNumber
                        it[weightKg] = m.weightKg.toBigDecimal()
                        it[reps] = m.reps
                        it[rpe] = m.rpe?.toBigDecimal()
                        it[setType] = m.setType.name.lowercase()
                        it[revision] = newRev
                        it[updatedAt] = now
                    }
                }
                Triple("applied", newRev, null)
            }
            else -> Triple("rejected", null, "unsupported_entity")
        }
    }

    private fun handleDelete(userId: UUID, op: SyncOperation, now: OffsetDateTime): Triple<String, Long?, String?> {
        return when (op.entity) {
            "exercise" -> {
                val existing = Exercises.selectAll().where { Exercises.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing == null) return Triple("applied", 1, null)
                val owner = existing[Exercises.userId]
                if (owner != null && owner != userId) return Triple("rejected", null, "forbidden")
                if (existing[Exercises.deletedAt] != null) return Triple("applied", existing[Exercises.revision], null)
                val newRev = existing[Exercises.revision] + 1
                Exercises.update({ Exercises.id eq UUID.fromString(op.entityId) }) {
                    it[deletedAt] = now
                    it[revision] = newRev
                    it[updatedAt] = now
                }
                Triple("applied", newRev, null)
            }
            "split" -> {
                val existing = Splits.selectAll().where { Splits.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing == null) return Triple("applied", 1, null)
                if (existing[Splits.deletedAt] != null) return Triple("applied", existing[Splits.revision], null)
                val newRev = existing[Splits.revision] + 1
                Splits.update({ Splits.id eq UUID.fromString(op.entityId) }) {
                    it[deletedAt] = now
                    it[revision] = newRev
                    it[updatedAt] = now
                }
                Triple("applied", newRev, null)
            }
            "split_day" -> {
                val existing = SplitDays.selectAll().where { SplitDays.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing == null) return Triple("applied", 1, null)
                if (existing[SplitDays.deletedAt] != null) return Triple("applied", existing[SplitDays.revision], null)
                val newRev = existing[SplitDays.revision] + 1
                SplitDays.update({ SplitDays.id eq UUID.fromString(op.entityId) }) {
                    it[deletedAt] = now
                    it[revision] = newRev
                    it[updatedAt] = now
                }
                Triple("applied", newRev, null)
            }
            "split_day_exercise" -> {
                val existing = SplitDayExercises.selectAll().where { SplitDayExercises.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing == null) return Triple("applied", 1, null)
                if (existing[SplitDayExercises.deletedAt] != null) return Triple("applied", existing[SplitDayExercises.revision], null)
                val newRev = existing[SplitDayExercises.revision] + 1
                SplitDayExercises.update({ SplitDayExercises.id eq UUID.fromString(op.entityId) }) {
                    it[deletedAt] = now
                    it[revision] = newRev
                    it[updatedAt] = now
                }
                Triple("applied", newRev, null)
            }
            "workout_session" -> {
                val existing = WorkoutSessions.selectAll().where { WorkoutSessions.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing == null) return Triple("applied", 1, null)
                if (existing[WorkoutSessions.deletedAt] != null) return Triple("applied", existing[WorkoutSessions.revision], null)
                val newRev = existing[WorkoutSessions.revision] + 1
                WorkoutSessions.update({ WorkoutSessions.id eq UUID.fromString(op.entityId) }) {
                    it[deletedAt] = now
                    it[revision] = newRev
                    it[updatedAt] = now
                }
                Triple("applied", newRev, null)
            }
            "workout_exercise" -> {
                val existing = WorkoutExercises.selectAll().where { WorkoutExercises.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing == null) return Triple("applied", 1, null)
                if (existing[WorkoutExercises.deletedAt] != null) return Triple("applied", existing[WorkoutExercises.revision], null)
                val newRev = existing[WorkoutExercises.revision] + 1
                WorkoutExercises.update({ WorkoutExercises.id eq UUID.fromString(op.entityId) }) {
                    it[deletedAt] = now
                    it[revision] = newRev
                    it[updatedAt] = now
                }
                Triple("applied", newRev, null)
            }
            "set" -> {
                val existing = Sets.selectAll().where { Sets.id eq UUID.fromString(op.entityId) }.singleOrNull()
                if (existing == null) return Triple("applied", 1, null)
                if (existing[Sets.deletedAt] != null) return Triple("applied", existing[Sets.revision], null)
                val newRev = existing[Sets.revision] + 1
                Sets.update({ Sets.id eq UUID.fromString(op.entityId) }) {
                    it[deletedAt] = now
                    it[revision] = newRev
                    it[updatedAt] = now
                }
                Triple("applied", newRev, null)
            }
            else -> Triple("rejected", null, "unsupported_entity")
        }
    }

    private fun writeChange(userId: UUID, seq: Long, entity: String, entityId: UUID, revision: Long, deleted: Boolean, now: OffsetDateTime) {
        Changes.insert {
            it[Changes.userId] = userId
            it[Changes.seq] = seq
            it[Changes.entity] = entity
            it[Changes.entityId] = entityId
            it[Changes.revision] = revision
            it[Changes.deleted] = deleted
            it[Changes.createdAt] = now
        }
    }

    private fun storeOp(opId: UUID, userId: UUID, result: String) {
        AppliedOps.insert {
            it[AppliedOps.opId] = opId
            it[AppliedOps.userId] = userId
            it[AppliedOps.result] = result
            it[AppliedOps.createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
    }

    fun pull(userId: UUID, cursorStr: String, limit: Int): SyncPullResponse = transaction {
        val cursor = cursorStr.toLongOrNull() ?: 0L
        val rows = Changes.selectAll().where { (Changes.userId eq userId) and (Changes.seq greater cursor) }
            .orderBy(Changes.seq, SortOrder.ASC)
            .limit(limit)
            .toList()

        // Batch-fetch the actual row data per entity type (one query per type covering every id
        // in this page) instead of one query per changed row. A page of 100 changes used to be
        // ~100 sequential round trips; now it's at most 6 (one per entity type present).
        val idsByEntity = rows.filterNot { it[Changes.deleted] }
            .groupBy({ it[Changes.entity] }, { it[Changes.entityId] })
        val dataByEntityAndId = idsByEntity.mapValues { (entity, ids) -> fetchEntityDataBulk(entity, ids.distinct()) }

        val changes = rows.map { row ->
            val entity = row[Changes.entity]
            val entityId = row[Changes.entityId]
            val deleted = row[Changes.deleted]
            val revision = row[Changes.revision]
            val data = if (deleted) null else dataByEntityAndId[entity]?.get(entityId)
            ChangeDto(row[Changes.seq], entity, entityId.toString(), revision, deleted, data)
        }
        val nextCursor = if (changes.isEmpty()) cursor else changes.last().seq
        val hasMore = changes.size == limit
        SyncPullResponse(changes, nextCursor.toString(), hasMore)
    }

    private fun fetchEntityDataBulk(entity: String, ids: List<UUID>): Map<UUID, JsonElement> {
        if (ids.isEmpty()) return emptyMap()
        return when (entity) {
            "exercise" -> Exercises.selectAll().where { Exercises.id inList ids }.associate { row ->
                row[Exercises.id] to json.encodeToJsonElement(Exercise(
                    id = row[Exercises.id],
                    userId = row[Exercises.userId],
                    name = row[Exercises.name],
                    muscleGroup = row[Exercises.muscleGroup]?.let { MuscleGroup.valueOf(it) },
                    customGroup = row[Exercises.customGroup],
                    equipment = row[Exercises.equipment]?.let { Equipment.valueOf(it) },
                    isCustom = row[Exercises.isCustom],
                    revision = row[Exercises.revision],
                    createdAt = row[Exercises.createdAt].toInstant(),
                    updatedAt = row[Exercises.updatedAt].toInstant(),
                    deletedAt = row[Exercises.deletedAt]?.toInstant()
                ))
            }
            "split" -> Splits.selectAll().where { Splits.id inList ids }.associate { row ->
                row[Splits.id] to json.encodeToJsonElement(Split(
                    id = row[Splits.id],
                    userId = row[Splits.userId],
                    name = row[Splits.name],
                    isActive = row[Splits.isActive],
                    rotationPosition = row[Splits.rotationPosition],
                    revision = row[Splits.revision],
                    createdAt = row[Splits.createdAt].toInstant(),
                    updatedAt = row[Splits.updatedAt].toInstant(),
                    deletedAt = row[Splits.deletedAt]?.toInstant()
                ))
            }
            "split_day" -> SplitDays.selectAll().where { SplitDays.id inList ids }.associate { row ->
                val weekdaysList = row[SplitDays.weekdays]?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
                row[SplitDays.id] to json.encodeToJsonElement(SplitDay(
                    id = row[SplitDays.id],
                    splitId = row[SplitDays.splitId],
                    name = row[SplitDays.name],
                    weekdays = weekdaysList,
                    sortKey = row[SplitDays.sortKey],
                    revision = row[SplitDays.revision],
                    createdAt = row[SplitDays.createdAt].toInstant(),
                    updatedAt = row[SplitDays.updatedAt].toInstant(),
                    deletedAt = row[SplitDays.deletedAt]?.toInstant()
                ))
            }
            "split_day_exercise" -> SplitDayExercises.selectAll().where { SplitDayExercises.id inList ids }.associate { row ->
                row[SplitDayExercises.id] to json.encodeToJsonElement(SplitDayExercise(
                    id = row[SplitDayExercises.id],
                    splitDayId = row[SplitDayExercises.splitDayId],
                    exerciseId = row[SplitDayExercises.exerciseId],
                    sortKey = row[SplitDayExercises.sortKey],
                    targetSets = row[SplitDayExercises.targetSets],
                    targetReps = row[SplitDayExercises.targetReps],
                    revision = row[SplitDayExercises.revision],
                    createdAt = row[SplitDayExercises.createdAt].toInstant(),
                    updatedAt = row[SplitDayExercises.updatedAt].toInstant(),
                    deletedAt = row[SplitDayExercises.deletedAt]?.toInstant()
                ))
            }
            "workout_session" -> WorkoutSessions.selectAll().where { WorkoutSessions.id inList ids }.associate { row ->
                row[WorkoutSessions.id] to json.encodeToJsonElement(WorkoutSession(
                    id = row[WorkoutSessions.id],
                    userId = row[WorkoutSessions.userId],
                    splitDayId = row[WorkoutSessions.splitDayId],
                    dayName = row[WorkoutSessions.dayName],
                    startedAt = row[WorkoutSessions.startedAt].toInstant(),
                    endedAt = row[WorkoutSessions.endedAt]?.toInstant(),
                    notes = row[WorkoutSessions.notes],
                    revision = row[WorkoutSessions.revision],
                    createdAt = row[WorkoutSessions.createdAt].toInstant(),
                    updatedAt = row[WorkoutSessions.updatedAt].toInstant(),
                    deletedAt = row[WorkoutSessions.deletedAt]?.toInstant()
                ))
            }
            "workout_exercise" -> WorkoutExercises.selectAll().where { WorkoutExercises.id inList ids }.associate { row ->
                row[WorkoutExercises.id] to json.encodeToJsonElement(WorkoutExercise(
                    id = row[WorkoutExercises.id],
                    sessionId = row[WorkoutExercises.sessionId],
                    exerciseId = row[WorkoutExercises.exerciseId],
                    exerciseName = row[WorkoutExercises.exerciseName],
                    sortKey = row[WorkoutExercises.sortKey],
                    revision = row[WorkoutExercises.revision],
                    createdAt = row[WorkoutExercises.createdAt].toInstant(),
                    updatedAt = row[WorkoutExercises.updatedAt].toInstant(),
                    deletedAt = row[WorkoutExercises.deletedAt]?.toInstant()
                ))
            }
            "set" -> Sets.selectAll().where { Sets.id inList ids }.associate { row ->
                row[Sets.id] to json.encodeToJsonElement(SetEntry(
                    id = row[Sets.id],
                    workoutExerciseId = row[Sets.workoutExerciseId],
                    setNumber = row[Sets.setNumber],
                    weightKg = row[Sets.weightKg].toDouble(),
                    reps = row[Sets.reps],
                    rpe = row[Sets.rpe]?.toDouble(),
                    setType = SetType.valueOf(row[Sets.setType].uppercase()),
                    revision = row[Sets.revision],
                    createdAt = row[Sets.createdAt].toInstant(),
                    updatedAt = row[Sets.updatedAt].toInstant(),
                    deletedAt = row[Sets.deletedAt]?.toInstant()
                ))
            }
            else -> emptyMap()
        }
    }
}
