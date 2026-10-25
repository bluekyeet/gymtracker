package com.gymtracker.data.sync

import android.content.Context
import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.*
import com.gymtracker.shared.sync.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import androidx.room.withTransaction

class SyncManager(private val context: Context, private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun getCursor(): String = prefs.getString("cursor", "0") ?: "0"
    fun setCursor(value: String) = prefs.edit().putString("cursor", value).apply()

    suspend fun pushPending(baseUrl: String, token: String, deviceId: UUID, schemaVersion: Int, client: HttpClient) = withContext(Dispatchers.IO) {
        val outbox = db.outboxDao().getAll()
        if (outbox.isEmpty()) return@withContext
        val ops = outbox.map { o ->
            SyncOperation(
                opId = o.opId.toString(),
                entity = o.entity,
                entityId = o.entityId.toString(),
                type = o.type,
                baseRevision = o.baseRevision,
                data = o.dataJson?.let { json.parseToJsonElement(it) }
            )
        }
        val req = SyncPushRequest(deviceId.toString(), schemaVersion, ops)
        val resp = client.post("$baseUrl/v1/sync/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body<SyncPushResponse>()
        resp.results.forEach { r ->
            val id = UUID.fromString(r.opId)
            when (r.status) {
                "applied", "rejected" -> db.outboxDao().delete(id)
                "retry" -> { /* leave for retry */ }
            }
        }
    }

    suspend fun pull(baseUrl: String, token: String, client: HttpClient) = withContext(Dispatchers.IO) {
        var cursor = getCursor()
        var hasMore = true
        while (hasMore) {
            val resp = client.get("$baseUrl/v1/sync/pull") {
                bearerAuth(token)
                parameter("cursor", cursor)
                parameter("limit", 100)
            }.body<SyncPullResponse>()
            db.withTransaction {
                resp.changes.forEach { c -> applyChange(c) }
            }
            cursor = resp.nextCursor
            setCursor(cursor)
            hasMore = resp.hasMore
        }
    }

    private suspend fun applyChange(c: ChangeDto) {
        val id = UUID.fromString(c.entityId)
        when (c.entity) {
            "exercise" -> {
                if (c.deleted) {
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<Exercise>(), it) }
                    if (m != null) db.exerciseDao().upsert(m.toEntity())
                }
            }
            "split" -> {
                if (!c.deleted) {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<Split>(), it) }
                    if (m != null) db.splitDao().upsert(m.toEntity())
                }
            }
            else -> {}
        }
    }
}
