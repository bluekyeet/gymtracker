package com.gymtracker.data.sync

import android.content.Context
import com.gymtracker.data.db.AppDatabase
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HealthResponse(
    val status: String,
    val version: String,
    val minClientVersion: String,
    val serverId: String
)

@Serializable
data class EnrollRequest(
    val enrollmentKey: String,
    val deviceName: String,
    val deviceKind: String
)

@Serializable
data class EnrollResponse(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String
)

class ServerClient(private val context: Context, private val db: AppDatabase) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }
    private val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value).apply()

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(value) = prefs.edit().putString("device_id", value).apply()

    var pairedWatchDeviceId: String?
        get() = prefs.getString("paired_watch_device_id", null)
        set(value) = prefs.edit().putString("paired_watch_device_id", value).apply()

    // The enrollment key the user actually typed/scanned for this device. Used to (re)enroll
    // automatically if the stored tokens are ever cleared. Previously this fell back silently to
    // the compose.yaml default key, which fails with "Invalid enrollment key" on any server that
    // changed ENROLLMENT_KEY (every real deployment, per README) — the device never had a way to
    // tell the server its real key.
    var enrollmentKey: String
        get() = prefs.getString("enrollment_key", "") ?: ""
        set(value) = prefs.edit().putString("enrollment_key", value).apply()

    var lastSyncTime: String?
        get() = prefs.getString("last_sync_time", null)
        set(value) = prefs.edit().putString("last_sync_time", value).apply()

    var restTimerEnabled: Boolean
        get() = prefs.getBoolean("rest_timer_enabled", true)
        set(value) = prefs.edit().putBoolean("rest_timer_enabled", value).apply()

    suspend fun refreshAuth(client: HttpClient): Boolean {
        val url = serverUrl.trim().removeSuffix("/")
        val token = refreshToken ?: return false
        return try {
            val resp = client.post("$url/v1/auth/refresh") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("refreshToken" to token))
            }
            if (resp.status == HttpStatusCode.OK) {
                val data = resp.body<EnrollResponse>()
                accessToken = data.accessToken
                refreshToken = data.refreshToken
                deviceId = data.deviceId
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun ensureValidToken(client: HttpClient): String? {
        val currentToken = accessToken
        if (!currentToken.isNullOrBlank()) {
            return currentToken
        }
        if (!refreshToken.isNullOrBlank()) {
            if (refreshAuth(client)) {
                return accessToken
            }
        }
        // Re-enroll automatically only with the key the user actually entered for this device.
        // There is no safe default enrollment key to fall back to — every real deployment sets
        // its own (see README / init-secrets.sh) — so without one, the device just stays
        // unpaired until the user enrolls it from Settings.
        val url = serverUrl.trim()
        val key = enrollmentKey.trim()
        if (url.isNotBlank() && key.isNotBlank()) {
            try {
                val enrolled = enroll(url, key, android.os.Build.MODEL, client)
                return enrolled.accessToken
            } catch (_: Exception) {}
        }
        return null
    }

    suspend fun checkHealth(url: String, client: HttpClient): HealthResponse {
        val trimmed = url.trim()
        val cleanUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) "http://$trimmed" else trimmed
        val finalUrl = cleanUrl.removeSuffix("/")
        return client.get("$finalUrl/v1/health").body<HealthResponse>()
    }

    suspend fun enroll(url: String, enrollmentKey: String, deviceName: String, client: HttpClient): EnrollResponse {
        val trimmed = url.trim()
        val cleanUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) "http://$trimmed" else trimmed
        val finalUrl = cleanUrl.removeSuffix("/")
        val resp = client.post("$finalUrl/v1/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(EnrollRequest(enrollmentKey, deviceName, "phone"))
        }
        val data = resp.body<EnrollResponse>()
        serverUrl = finalUrl
        this.enrollmentKey = enrollmentKey
        accessToken = data.accessToken
        refreshToken = data.refreshToken
        deviceId = data.deviceId
        return data
    }
}
