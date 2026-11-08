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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    companion object {
        private const val PUSH_CHUNK_SIZE = 200

        /**
         * One sync at a time per process. The periodic worker, the foreground one-off worker, the
         * connectivity trigger and the Settings button all call [performFullSync]; without this they
         * run concurrently, push the same outbox twice and race to rotate the same refresh token
         * (which the server reads as token theft).
         */
        private val syncLock = Mutex()

        private val syncTimeFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.getDefault())
    }

    fun getCursor(): String = prefs.getString("cursor", "0") ?: "0"
    fun setCursor(value: String) = prefs.edit().putString("cursor", value).apply()

    // Backfill scans every row in the local DB to catch pre-outbox data. It only needs to run
    // once ever per install — repositories queue new writes to the outbox themselves. Running it
    // on every sync made push time grow with total history size instead of just pending changes.
    private fun isBackfillDone(): Boolean = prefs.getBoolean("backfill_done", false)
    private fun setBackfillDone() = prefs.edit().putBoolean("backfill_done", true).apply()

    /**
     * Compares the server's persistent id with the one this device last synced with.
     *
     * A different id means the server's database was restored from an older backup or recreated.
     * Our cursor then points past anything it has, so pulls would return nothing forever. Start the
     * cursor over and queue every local row for pushing again so the server is repopulated.
     * Returns true when a reset happened.
     */
    internal fun reconcileServerIdentity(serverId: String): Boolean {
        val known = prefs.getString("server_id", null)
        prefs.edit().putString("server_id", serverId).apply()
        if (known == null || known == serverId) return false
        prefs.edit().putString("cursor", "0").putBoolean("backfill_done", false).apply()
        return true
    }

    suspend fun pushPending(baseUrl: String, token: String, deviceId: UUID, schemaVersion: Int, client: HttpClient) = withContext(Dispatchers.IO) {
        if (!isBackfillDone()) {
            com.gymtracker.data.repository.SplitRepository(db).backfillExistingSplitsToOutbox()
            com.gymtracker.data.repository.WorkoutRepository(db).backfillExistingWorkoutsToOutbox()
            setBackfillDone()
        }
        val outbox = db.outboxDao().getAll()
        if (outbox.isEmpty()) return@withContext
        val entityOrder = mapOf("exercise" to 1, "split" to 2, "split_day" to 3, "split_day_exercise" to 4, "workout_session" to 5, "workout_exercise" to 6, "set" to 7)
        val sortedOutbox = outbox.sortedBy { entityOrder[it.entity] ?: 5 }

        // Push in chunks instead of one request for the whole outbox. A single giant request
        // (e.g. thousands of pending ops after being offline) is slow to build, slow to send on a
        // weak radio (the watch), and all-or-nothing if it times out. Chunking makes a huge
        // backlog resumable and lets progress show up incrementally; each op is still idempotent
        // by opId so re-pushing a chunk that partially succeeded is always safe.
        for (chunk in sortedOutbox.chunked(PUSH_CHUNK_SIZE)) {
            val ops = chunk.map { o ->
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
            // Only a final verdict removes an op. "retry" (server fault, parent not there yet) keeps it
            // queued: dropping it would silently lose the user's change.
            val done = resp.results
                .filter { it.status == "applied" || it.status == "rejected" }
                .mapNotNull { runCatching { UUID.fromString(it.opId) }.getOrNull() }
            if (done.isNotEmpty()) db.outboxDao().deleteMany(done)
        }
    }

    suspend fun pull(baseUrl: String, token: String, client: HttpClient) = withContext(Dispatchers.IO) {
        var cursor = getCursor()
        var hasMore = true
        while (hasMore) {
            val response = client.get("$baseUrl/v1/sync/pull") {
                bearerAuth(token)
                parameter("cursor", cursor)
                // 500 per page instead of 100: now that the server batches its row lookups per
                // page (see SyncService.fetchEntityDataBulk), a bigger page means fewer HTTP round
                // trips to catch up on a large backlog — the main cost on a weak radio like the watch.
                parameter("limit", 500)
            }
            if (response.status == HttpStatusCode.Unauthorized) {
                throw SyncAuthException("Unauthorized")
            }
            if (!response.status.isSuccess()) {
                throw Exception("Pull failed: ${response.status.value}")
            }
            val resp = response.body<SyncPullResponse>()
            // The cursor moves only after the whole page is applied: a crash mid-page re-pulls the
            // page instead of skipping part of it.
            db.withTransaction {
                // A row with a change still waiting in the outbox is newer here than on the server.
                // Applying the server's copy would silently revert the user's edit; it will be
                // pushed, and the next pull brings back whatever the server settled on.
                val pending = db.outboxDao().pendingEntityIds().toSet()
                resp.changes.forEach { c ->
                    if (UUID.fromString(c.entityId) !in pending) applyChange(c)
                }
            }
            cursor = resp.nextCursor
            setCursor(cursor)
            hasMore = resp.hasMore
        }
    }

    suspend fun performFullSync(serverClient: ServerClient, client: HttpClient): SyncResult =
        syncLock.withLock { syncLocked(serverClient, client) }

    private suspend fun syncLocked(serverClient: ServerClient, client: HttpClient): SyncResult {
        if (serverClient.serverUrl.isBlank()) {
            return SyncResult.OtherError("Server URL not configured")
        }
        // Refuses plain http to anything outside the user's own network: tokens ride on every request.
        val baseUrl = try {
            ServerUrl.normalize(serverClient.serverUrl)
        } catch (e: IllegalArgumentException) {
            return SyncResult.OtherError(e.message ?: "Invalid server URL")
        }

        return try {
            val token = serverClient.ensureValidToken(client)
            if (token.isNullOrBlank()) {
                return SyncResult.AuthError("Device not paired")
            }
            val devId = serverClient.deviceId?.let {
                try { UUID.fromString(it) } catch (_: Exception) { null }
            } ?: UUID.randomUUID().also { serverClient.deviceId = it.toString() }

            try {
                syncOnce(baseUrl, token, devId, serverClient, client)
            } catch (e: SyncAuthException) {
                // The server rejected the token (revoked, or expired between our check and the call):
                // renew it once and go again.
                val renewed = serverClient.renewToken(client)
                if (renewed.isNullOrBlank()) return SyncResult.AuthError("Session expired, please reconnect")
                try {
                    syncOnce(baseUrl, renewed, devId, serverClient, client)
                } catch (_: SyncAuthException) {
                    return SyncResult.AuthError("Session expired, please reconnect")
                }
            }
            SyncResult.Success
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is java.io.IOException || e is java.nio.channels.UnresolvedAddressException || e::class.simpleName?.contains("Timeout") == true) {
                SyncResult.NetworkError("Server unavailable")
            } else {
                SyncResult.OtherError(e.localizedMessage ?: "Sync error")
            }
        }
    }

    private suspend fun syncOnce(baseUrl: String, token: String, deviceId: UUID, serverClient: ServerClient, client: HttpClient) {
        val health = serverClient.checkHealth(baseUrl, client)
        reconcileServerIdentity(health.serverId)
        pushPending(baseUrl, token, deviceId, 1, client)
        pull(baseUrl, token, client)
        serverClient.lastSyncTime = java.time.LocalDateTime.now().format(syncTimeFormat)
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
            "workout_session" -> {
                if (c.deleted) {
                    db.workoutSessionDao().softDelete(id, now, now)
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<WorkoutSession>(), it) }
                    if (m != null) db.workoutSessionDao().upsert(m.toEntity())
                }
            }
            "workout_exercise" -> {
                if (c.deleted) {
                    db.workoutExerciseDao().softDelete(id, now, now)
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<WorkoutExercise>(), it) }
                    if (m != null) db.workoutExerciseDao().upsert(m.toEntity())
                }
            }
            "set" -> {
                if (c.deleted) {
                    db.setDao().softDelete(id, now, now)
                } else {
                    val m = c.data?.let { json.decodeFromJsonElement(serializer<SetEntry>(), it) }
                    if (m != null) db.setDao().upsert(m.toEntity())
                }
            }
            else -> {}
        }
    }
}
