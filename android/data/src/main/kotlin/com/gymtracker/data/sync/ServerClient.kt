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
    private val json = Json { ignoreUnknownKeys = true }
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

    suspend fun checkHealth(url: String, client: HttpClient): HealthResponse {
        val resp = client.get("$url/v1/health")
        return resp.body<HealthResponse>()
    }

    suspend fun enroll(url: String, enrollmentKey: String, deviceName: String, client: HttpClient): EnrollResponse {
        val resp = client.post("$url/v1/devices/enroll") {
            contentType(ContentType.Application.Json)
            setBody(EnrollRequest(enrollmentKey, deviceName, "phone"))
        }
        val data = resp.body<EnrollResponse>()
        serverUrl = url
        accessToken = data.accessToken
        refreshToken = data.refreshToken
        deviceId = data.deviceId
        return data
    }
}
