package com.gymtracker.data.sync

import android.content.Context
import com.gymtracker.data.OwnerId
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

    var deviceKind: String
        get() = prefs.getString("device_kind", "phone") ?: "phone"
        set(value) = prefs.edit().putString("device_kind", value).apply()

    private fun store(data: EnrollResponse) {
        accessToken = data.accessToken
        refreshToken = data.refreshToken
        deviceId = data.deviceId
        // Rows created from now on carry the server's id for this user.
        OwnerId.adoptFromAccessToken(data.accessToken)
    }

    /**
     * Trades the refresh token for a new pair. Returns false when the server refuses the token (it is
     * no longer valid). A server that is down or overloaded is NOT a refusal: it throws, so the caller
     * reports "unavailable" instead of treating the device as unpaired and re-enrolling.
     */
    suspend fun refreshAuth(client: HttpClient): Boolean {
        val url = ServerUrl.normalize(serverUrl)
        val token = refreshToken ?: return false
        val resp = client.post("$url/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("refreshToken" to token))
        }
        return when {
            resp.status == HttpStatusCode.OK -> {
                store(resp.body<EnrollResponse>())
                true
            }
            resp.status.value >= 500 || resp.status == HttpStatusCode.TooManyRequests ->
                throw java.io.IOException("Server unavailable (${resp.status.value})")
            else -> false
        }
    }

    /** An access token that is valid for at least another half minute, renewing it first if needed. */
    suspend fun ensureValidToken(client: HttpClient): String? {
        val currentToken = accessToken
        if (!currentToken.isNullOrBlank() && !isExpiring(currentToken)) {
            return currentToken
        }
        if (!refreshToken.isNullOrBlank() && refreshAuth(client)) {
            return accessToken
        }
        return reenroll(client)
    }

    /** Called after the server answered 401 to a token we thought was fine: refresh, else re-enroll. */
    suspend fun renewToken(client: HttpClient): String? {
        if (!refreshToken.isNullOrBlank() && refreshAuth(client)) return accessToken
        return reenroll(client)
    }

    private fun isExpiring(token: String): Boolean {
        val exp = OwnerId.expiryOf(token) ?: return false // unreadable: let the server decide
        return exp - System.currentTimeMillis() / 1000 < 30
    }

    // Re-enroll automatically only with the key the user actually entered for this device.
    // There is no safe default enrollment key to fall back to — every real deployment sets
    // its own (see README / init-secrets.sh) — so without one, the device just stays
    // unpaired until the user enrolls it from Settings.
    private suspend fun reenroll(client: HttpClient): String? {
        val url = serverUrl.trim()
        val key = enrollmentKey.trim()
        if (url.isBlank() || key.isBlank()) return null
        return try {
            enroll(url, key, android.os.Build.MODEL, client, deviceKind).accessToken
        } catch (e: java.io.IOException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun checkHealth(url: String, client: HttpClient): HealthResponse {
        val finalUrl = ServerUrl.normalize(url)
        return client.get("$finalUrl/v1/health").body<HealthResponse>()
    }

    suspend fun enroll(url: String, enrollmentKey: String, deviceName: String, client: HttpClient, deviceKind: String = "phone"): EnrollResponse {
        val finalUrl = ServerUrl.normalize(url)
        val resp = client.post("$finalUrl/v1/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(EnrollRequest(enrollmentKey, deviceName, deviceKind))
        }
        when {
            resp.status == HttpStatusCode.Unauthorized -> throw IllegalStateException("Invalid enrollment key")
            resp.status == HttpStatusCode.Forbidden -> throw IllegalStateException("Enrollment is turned off on this server")
            resp.status == HttpStatusCode.TooManyRequests -> throw IllegalStateException("Too many attempts. Wait a minute and try again.")
            !resp.status.isSuccess() -> throw IllegalStateException("Enrollment failed (HTTP ${resp.status.value})")
        }
        val data = resp.body<EnrollResponse>()
        serverUrl = finalUrl
        this.enrollmentKey = enrollmentKey
        this.deviceKind = deviceKind
        store(data)
        return data
    }
}
