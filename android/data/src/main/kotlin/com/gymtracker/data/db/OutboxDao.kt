package com.gymtracker.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.UUID

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<OutboxEntity>

    // The outbox has no foreign keys, so there are no children for REPLACE to cascade over; op ids
    // are random and never collide anyway. IGNORE keeps the "never REPLACE" rule absolute.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: OutboxEntity)

    @Query("DELETE FROM outbox WHERE opId = :opId")
    suspend fun delete(opId: UUID)

    @Query("DELETE FROM outbox WHERE opId IN (:opIds)")
    suspend fun deleteMany(opIds: List<UUID>)

    /** Queued upserts for [entityId]; a newer upsert carries the full row, so older ones are dead weight. */
    @Query("DELETE FROM outbox WHERE entityId = :entityId AND type = 'upsert'")
    suspend fun deleteUpsertsFor(entityId: UUID)

    /** Entities with something still waiting to be pushed. */
    @Query("SELECT DISTINCT entityId FROM outbox")
    suspend fun pendingEntityIds(): List<UUID>
}

/**
 * Queues [entity]. An upsert replaces any upsert already queued for the same row: typing "102.5"
 * into a set used to queue five ops, now it is always one. Deletes are never coalesced away.
 * Call inside the same transaction as the local write.
 */
suspend fun OutboxDao.put(entity: OutboxEntity) {
    if (entity.type == "upsert") deleteUpsertsFor(entity.entityId)
    insert(entity)
}
