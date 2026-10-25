package com.gymtracker.server.auth

import com.gymtracker.server.database.Devices
import com.gymtracker.server.database.RefreshTokens
import com.gymtracker.server.database.Users
import de.mkammerer.argon2.Argon2Factory
import io.ktor.server.auth.jwt.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class TokenPair(val accessToken: String, val refreshToken: String, val deviceId: UUID)

class AuthService(private val jwtSecret: String) {
    private val argon2 = Argon2Factory.create()

    fun verifyPassword(hash: String, password: String): Boolean = argon2.verify(hash, password.toCharArray())

    fun hashPassword(password: String): String = argon2.hash(3, 65536, 1, password.toCharArray())

    fun login(email: String, password: String, deviceName: String, deviceKind: String): TokenPair? {
        return transaction {
            val user = Users.selectAll().where { Users.email eq email }.singleOrNull() ?: return@transaction null
            if (!verifyPassword(user[Users.passwordHash], password)) return@transaction null
            val deviceId = UUID.randomUUID()
            Devices.insert {
                it[id] = deviceId
                it[userId] = user[Users.id]
                it[name] = deviceName
                it[kind] = deviceKind
                it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
            val familyId = UUID.randomUUID()
            val refresh = UUID.randomUUID().toString()
            RefreshTokens.insert {
                it[id] = UUID.randomUUID()
                it[this.deviceId] = deviceId
                it[tokenHash] = sha256(refresh)
                it[this.familyId] = familyId
                it[used] = false
                it[expiresAt] = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)
                it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
            TokenPair(createAccessToken(user[Users.id], deviceId), refresh, deviceId)
        }
    }

    fun refresh(refreshToken: String): TokenPair? {
        val hash = sha256(refreshToken)
        return transaction {
            val row = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }.singleOrNull() ?: return@transaction null
            if (row[RefreshTokens.used]) {
                // reuse detected: revoke family
                val family = row[RefreshTokens.familyId]
                RefreshTokens.update({ RefreshTokens.familyId eq family }) { it[used] = true }
                return@transaction null
            }
            if (row[RefreshTokens.expiresAt].toInstant().isBefore(Instant.now())) return@transaction null
            // mark used
            RefreshTokens.update({ RefreshTokens.id eq row[RefreshTokens.id] }) { it[used] = true }
            val deviceId = row[RefreshTokens.deviceId]
            val device = Devices.selectAll().where { Devices.id eq deviceId }.singleOrNull() ?: return@transaction null
            val userId = device[Devices.userId]
            val familyId = row[RefreshTokens.familyId]
            val newRefresh = UUID.randomUUID().toString()
            RefreshTokens.insert {
                it[id] = UUID.randomUUID()
                it[this.deviceId] = deviceId
                it[tokenHash] = sha256(newRefresh)
                it[this.familyId] = familyId
                it[used] = false
                it[expiresAt] = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)
                it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
            TokenPair(createAccessToken(userId, deviceId), newRefresh, deviceId)
        }
    }

    fun revokeDevice(deviceId: UUID) {
        transaction {
            Devices.update({ Devices.id eq deviceId }) {
                it[revokedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
            // revoke all refresh tokens for device
            RefreshTokens.update({ RefreshTokens.deviceId eq deviceId }) { it[used] = true }
        }
    }

    fun listDevices(userId: UUID): List<DeviceRow> = transaction {
        Devices.selectAll().where { Devices.userId eq userId and Devices.deletedAt.isNull() }.map {
            DeviceRow(it[Devices.id], it[Devices.name], it[Devices.kind], it[Devices.revokedAt])
        }
    }

    fun createAccessToken(userId: UUID, deviceId: UUID): String {
        val now = Instant.now()
        return com.auth0.jwt.JWT.create()
            .withSubject(userId.toString())
            .withClaim("device_id", deviceId.toString())
            .withIssuedAt(java.util.Date.from(now))
            .withExpiresAt(java.util.Date.from(now.plusSeconds(900)))
            .sign(com.auth0.jwt.algorithms.Algorithm.HMAC256(jwtSecret))
    }

    // Actually use JJWT? Not in deps. Let's switch to com.auth0.jwt or manual. We'll adjust.
    fun createUser(email: String, password: String) {
        transaction {
            Users.insert {
                it[id] = UUID.randomUUID()
                it[this.email] = email
                it[passwordHash] = hashPassword(password)
                it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

data class DeviceRow(val id: UUID, val name: String, val kind: String, val revokedAt: OffsetDateTime?)

// Helper to parse JWT later in Ktor
fun verifyAccessToken(token: String, secret: String): Pair<UUID, UUID>? {
    return try {
        val parts = token.split(".")
        // We'll use a simple approach: use io.ktor.server.auth.jwt? Instead implement with com.auth0.jwt if available.
        null
    } catch (e: Exception) { null }
}
