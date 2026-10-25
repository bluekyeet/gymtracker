package com.gymtracker.shared.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SyncOperation(
    val opId: String,
    val entity: String,
    val entityId: String,
    val type: String,
    val baseRevision: Long = 0,
    val data: JsonElement? = null
)

@Serializable
data class SyncPushRequest(
    val deviceId: String,
    val schemaVersion: Int,
    val operations: List<SyncOperation>
)

@Serializable
data class OpResult(
    val opId: String,
    val status: String,
    val revision: Long? = null,
    val reason: String? = null
)

@Serializable
data class SyncPushResponse(
    val results: List<OpResult>
)

@Serializable
data class ChangeDto(
    val seq: Long,
    val entity: String,
    val entityId: String,
    val revision: Long,
    val deleted: Boolean,
    val data: JsonElement? = null
)

@Serializable
data class SyncPullResponse(
    val changes: List<ChangeDto>,
    val nextCursor: String,
    val hasMore: Boolean
)
