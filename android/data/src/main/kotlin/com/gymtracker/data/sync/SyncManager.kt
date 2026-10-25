package com.gymtracker.data.sync

import android.content.Context
import androidx.room.withTransaction
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.util.UUID

class SyncAuthException(message: String) : Exception(message)

sealed class SyncResult {
    object Success : SyncResult()
    data class NetworkError(val message: String) : SyncResult()
    data class AuthError(val message: String) : SyncResult()
    data class OtherError(val message: String) : SyncResult()
}

class SyncManager(private val context: Context, private val db: AppDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    fun getCursor(): String = prefs.getString("cursor", "0") ?: "0"
    fun setCursor(value: String) = prefs.edit().putString("cursor", value).apply()

    suspend fun pushPending(baseUrl: String, token: String, deviceId: UUID, schemaVersion: Int, client: HttpClient) = withContext(Dispatchers.IO) {
        com.gymtracker.data.repository.SplitRepository(db).backfillExistingSplitsToOutbox()
        val outbox = db.outboxDao().getAll()
        if (outbox.isEmpty()) return@withContext
        val entityOrder = mapOf("exercise" to 1, "split" to 2, "split_day" to 3, "split_day_exercise" to 4)
        val sortedOutbox = outbox.sortedBy { entityOrder[it.entity] ?: 5 }
        val ops = sortedOutbox.map { o ->
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
        val response = client.post("$baseUrl/v1/sync/push") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw SyncAuthException("Unauthorized")
        }
        if (!response.status.isSuccess()) {
            throw Exception("Push failed: ${response.status.value}")
        }
        val resp = response.body<SyncPushResponse>()
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
            val response = client.get("$baseUrl/v1/sync/pull") {
                bearerAuth(token)
                parameter("cursor", cursor)
                parameter("limit", 100)
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                throw SyncAuthException("Unauthorized")
            }
            if (!response.status.isSuccess()) {
                throw Exception("Pull failed: ${response.status.value}")
            }
            val resp = response.body<SyncPullResponse>()
            db.withTransaction {
                resp.changes.forEach { c -> applyChange(c) }
            }
            cursor = resp.nextCursor
            setCursor(cursor)
            hasMore = resp.hasMore
        }
    }

    suspend fun performFullSync(serverClient: ServerClient, client: HttpClient): SyncResult {
        val rawUrl = serverClient.serverUrl.trim()
        if (rawUrl.isBlank()) {
            return SyncResult.OtherError("Server URL not configured")
        }
        val cleanUrl = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) "http://$rawUrl" else rawUrl
        val baseUrl = cleanUrl.removeSuffix("/")

        val token = serverClient.ensureValidToken(client)
        if (token.isNullOrBlank()) {
            return SyncResult.AuthError("Device not paired")
        }

        val devId = serverClient.deviceId?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        } ?: UUID.randomUUID().also { serverClient.deviceId = it.toString() }

        return try {
            pushPending(baseUrl, token, devId, 1, client)
            pull(baseUrl, token, client)
            val nowTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            serverClient.lastSyncTime = nowTime
            SyncResult.Success
        } catch (e: SyncAuthException) {
            val refreshed = serverClient.refreshAuth(client)
            var newToken = serverClient.accessToken
            if (!refreshed || newToken.isNullOrBlank()) {
                try {
                    serverClient.accessToken = null
                    newToken = serverClient.ensureValidToken(client)
                } catch (_: Exception) {}
            }
            if (!newToken.isNullOrBlank()) {
                try {
                    pushPending(baseUrl, newToken, devId, 1, client)
                    pull(baseUrl, newToken, client)
                    val nowTime = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    serverClient.lastSyncTime = nowTime
                    SyncResult.Success
                } catch (retryEx: SyncAuthException) {
                    SyncResult.AuthError("Session expired, please reconnect")
                } catch (retryEx: Exception) {
                    if (retryEx is java.io.IOException || retryEx is java.nio.channels.UnresolvedAddressException || retryEx::class.simpleName?.contains("Timeout") == true) {
                        SyncResult.NetworkError("Server unavailable")
                    } else {
                        SyncResult.OtherError(retryEx.localizedMessage ?: "Sync error")
                    }
                }
            } else {
                SyncResult.AuthError("Authentication expired")
            }
        } catch (e: Exception) {
            if (e is java.io.IOException || e is java.nio.channels.UnresolvedAddressException || e::class.simpleName?.contains("Timeout") == true) {
                SyncResult.NetworkError("Server unavailable")
            } else {
                SyncResult.OtherError(e.localizedMessage ?: "Sync error")
            }
        }
    }

    private suspend fun applyChange(c: ChangeDto) {
        val id = UUID.fromString(c.entityId)
        val now = java.time.Instant.now()
        when (c.entity) {
            "exercise" -> {
                if (c.deleted) {
                    db.exerciseDao().softDelete(id, now, now)
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<Exercise>(), it) }
                    if (m != null) db.exerciseDao().upsert(m.toEntity())
                }
            }
            "split" -> {
                if (c.deleted) {
                    db.splitDao().softDelete(id, now, now)
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<Split>(), it) }
                    if (m != null) db.splitDao().upsert(m.toEntity())
                }
            }
            "split_day" -> {
                if (c.deleted) {
                    db.splitDayDao().softDelete(id, now, now)
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<SplitDay>(), it) }
                    if (m != null) db.splitDayDao().upsert(m.toEntity())
                }
            }
            "split_day_exercise" -> {
                if (c.deleted) {
                    db.splitDayExerciseDao().softDelete(id, now, now)
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<SplitDayExercise>(), it) }
                    if (m != null) db.splitDayExerciseDao().upsert(m.toEntity())
                }
            }
            else -> {}
        }
    }
}
