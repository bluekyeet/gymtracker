package com.gymtracker.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.gymtracker.data.db.DatabaseProvider
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/** Runs a full push+pull sync in the background. Scheduled periodically and on app foreground. */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val db = DatabaseProvider.get(applicationContext)
        val serverClient = ServerClient(applicationContext, db)
        if (serverClient.serverUrl.isBlank()) return Result.success() // not configured yet, nothing to do
        val syncManager = SyncManager(applicationContext, db)
        val client = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true })
            }
        }
        return try {
            when (syncManager.performFullSync(serverClient, client)) {
                is SyncResult.Success -> Result.success()
                // Offline or server down: try again with backoff.
                is SyncResult.NetworkError -> Result.retry()
                // Not paired / session revoked / misconfigured: retrying cannot fix it, and the
                // next foreground or connectivity trigger starts a fresh attempt anyway.
                is SyncResult.AuthError, is SyncResult.OtherError -> Result.success()
            }
        } catch (_: Exception) {
            Result.retry()
        } finally {
            client.close()
        }
    }
}
