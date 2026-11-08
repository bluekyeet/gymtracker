package com.gymtracker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.UUID

/**
 * The id of the person who owns this install's data, stamped on rows as `userId`.
 *
 * The server never trusts the id in a request body (it uses the token), so this only has to be
 * stable and, once the device is enrolled, equal to the server's user id. Until then a random id
 * is generated once and kept, instead of a fresh random UUID per row.
 */
object OwnerId {
    private const val PREFS = "owner"
    private const val KEY = "owner_id"

    @Volatile
    private var id: UUID? = null
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (id != null) return
        synchronized(this) {
            if (id != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs = p
            id = p.getString(KEY, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.randomUUID().also { p.edit().putString(KEY, it.toString()).apply() }
        }
    }

    val current: UUID
        get() = id ?: synchronized(this) { id ?: UUID.randomUUID().also { id = it } }

    fun adopt(value: UUID) {
        id = value
        prefs?.edit()?.putString(KEY, value.toString())?.apply()
    }

    /** Takes the user id from an access token's `sub` claim. Anything unreadable is ignored. */
    fun adoptFromAccessToken(token: String) {
        subjectOf(token)?.let(::adopt)
    }

    internal fun subjectOf(token: String): UUID? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(token.split(".")[1]))
        UUID.fromString(Json.parseToJsonElement(payload).jsonObject["sub"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()

    /** Seconds since the epoch at which the token expires, or null if it cannot be read. */
    internal fun expiryOf(token: String): Long? = runCatching {
        val payload = String(Base64.getUrlDecoder().decode(token.split(".")[1]))
        Json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.contentOrNull?.toLong()
    }.getOrNull()
}
