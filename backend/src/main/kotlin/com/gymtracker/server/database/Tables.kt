package com.gymtracker.server.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Users : Table("users") {
    val id = uuid("id")
    val email = text("email").uniqueIndex()
    val passwordHash = text("password_hash")
    val lastSeq = long("last_seq").default(0)
    val unitPreference = text("unit_preference").default("kg")
    val timezone = text("timezone").default("UTC")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Devices : Table("devices") {
    val id = uuid("id")
    val userId = uuid("user_id") references Users.id
    val name = text("name")
    val kind = text("kind")
    val lastSeen = timestampWithTimeZone("last_seen").nullable()
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RefreshTokens : Table("refresh_tokens") {
    val id = uuid("id")
    val deviceId = uuid("device_id") references Devices.id
    val tokenHash = text("token_hash")
    val familyId = uuid("family_id")
    val used = bool("used").default(false)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}
