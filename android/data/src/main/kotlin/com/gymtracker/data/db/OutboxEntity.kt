package com.gymtracker.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gymtracker.shared.sync.SyncOperation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val opId: UUID,
    val entity: String,
    val entityId: UUID,
    val type: String,
    val baseRevision: Long,
    val dataJson: String?,
    val createdAt: Long = System.currentTimeMillis()
)
