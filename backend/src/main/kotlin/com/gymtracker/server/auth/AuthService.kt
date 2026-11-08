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
    companion object {
        const val REFRESH_GRACE_SECONDS = 30L
    }

    private val argon2 = Argon2Factory.create()

    fun verifyPassword(hash: String, password: String): Boolean = argon2.verify(hash, password.toCharArray())

    fun hashPassword(password: String): String = argon2.hash(3, 65536, 1, password.toCharArray())

    fun enrollDevice(deviceName: String, deviceKind: String): TokenPair {
        return transaction {
            // insertIgnore: phone and watch enrolling at the same moment must not race on the first insert.
            val user = Users.selectAll().orderBy(Users.createdAt).firstOrNull() ?: run {
                val newUserId = UUID.fromString("00000000-0000-0000-0000-000000000001")
                Users.insertIgnore {
                    it[id] = newUserId
                    it[email] = "owner@powertrack.local"
                    it[passwordHash] = "enrolled_device_user_placeholder"
                    it[lastSeq] = 0
                    it[unitPreference] = "kg"
                    it[timezone] = "UTC"
                    it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
                    it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                }
                Users.selectAll().where { Users.id eq newUserId }.single()
            }
            val userId = user[Users.id]
            val deviceId = UUID.randomUUID()
            Devices.insert {
                it[id] = deviceId
                it[this.userId] = userId
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
            TokenPair(createAccessToken(userId, deviceId), refresh, deviceId)
        }
    }

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
            val row = RefreshTokens.selectAll().where { RefreshTokens.tokenHash eq hash }.forUpdate().singleOrNull()
                ?: return@transaction null
            val now = Instant.now()
            if (row[RefreshTokens.used]) {
                // A replay seconds after rotation is a response that never arrived (or two syncs that
                // raced), not theft: hand out a fresh token instead of locking the device out.
                // A token revoked on purpose has usedAt cleared, so it never qualifies.
                val rotatedAt = row[RefreshTokens.usedAt]?.toInstant()
                val withinGrace = rotatedAt != null && rotatedAt.isAfter(now.minusSeconds(REFRESH_GRACE_SECONDS))
                if (!withinGrace) {
                    val family = row[RefreshTokens.familyId]
                    RefreshTokens.update({ RefreshTokens.familyId eq family }) {
                        it[used] = true
                        it[usedAt] = null
                    }
                    return@transaction null
                }
            }
            if (row[RefreshTokens.expiresAt].toInstant().isBefore(now)) return@transaction null
            val deviceId = row[RefreshTokens.deviceId]
            val device = Devices.selectAll().where { Devices.id eq deviceId }.singleOrNull() ?: return@transaction null
            if (device[Devices.revokedAt] != null || device[Devices.deletedAt] != null) return@transaction null
            if (!row[RefreshTokens.used]) {
                RefreshTokens.update({ RefreshTokens.id eq row[RefreshTokens.id] }) {
                    it[used] = true
                    it[usedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                }
            }
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

    /** Revokes [deviceId] only if it belongs to [userId]. Returns false when there is no such device. */
    fun revokeDevice(userId: UUID, deviceId: UUID): Boolean = transaction {
        val owned = Devices.selectAll().where { (Devices.id eq deviceId) and (Devices.userId eq userId) }.any()
        if (!owned) return@transaction false
        Devices.update({ Devices.id eq deviceId }) {
            it[revokedAt] = OffsetDateTime.now(ZoneOffset.UTC)
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
        RefreshTokens.update({ RefreshTokens.deviceId eq deviceId }) {
            it[used] = true
            it[usedAt] = null
        }
        true
    }

    fun isDeviceActive(deviceId: UUID): Boolean = transaction {
        Devices.selectAll().where { Devices.id eq deviceId }.singleOrNull()
            ?.let { it[Devices.revokedAt] == null && it[Devices.deletedAt] == null } ?: false
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

