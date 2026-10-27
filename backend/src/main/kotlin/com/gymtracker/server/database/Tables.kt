package com.gymtracker.server.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Exercises : Table("exercises") {
    val id = uuid("id")
    val userId = uuid("user_id").nullable()
    val name = text("name")
    val muscleGroup = text("muscle_group").nullable()
    val customGroup = text("custom_group").nullable()
    val equipment = text("equipment").nullable()
    val isCustom = bool("is_custom").default(false)
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Splits : Table("splits") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val name = text("name")
    val isActive = bool("is_active").default(false)
    val rotationPosition = integer("rotation_position").default(0)
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object SplitDays : Table("split_days") {
    val id = uuid("id")
    val splitId = uuid("split_id")
    val name = text("name")
    val weekdays = text("weekdays").nullable()
    val sortKey = double("sort_key").default(0.0)
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object SplitDayExercises : Table("split_day_exercises") {
    val id = uuid("id")
    val splitDayId = uuid("split_day_id")
    val exerciseId = uuid("exercise_id").nullable()
    val sortKey = double("sort_key").default(0.0)
    val targetSets = integer("target_sets").nullable()
    val targetReps = integer("target_reps").nullable()
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WorkoutSessions : Table("workout_sessions") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val splitDayId = uuid("split_day_id").nullable()
    val dayName = text("day_name").nullable()
    val startedAt = timestampWithTimeZone("started_at")
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val notes = text("notes").nullable()
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object WorkoutExercises : Table("workout_exercises") {
    val id = uuid("id")
    val sessionId = uuid("session_id")
    val exerciseId = uuid("exercise_id").nullable()
    val exerciseName = text("exercise_name")
    val sortKey = double("sort_key").default(0.0)
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Sets : Table("sets") {
    val id = uuid("id")
    val workoutExerciseId = uuid("workout_exercise_id")
    val setNumber = integer("set_number")
    val weightKg = decimal("weight_kg", 7, 2)
    val reps = integer("reps")
    val rpe = decimal("rpe", 3, 1).nullable()
    val setType = text("set_type").default("working")
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object BodyWeights : Table("body_weights") {
    val id = uuid("id")
    val userId = uuid("user_id")
    val date = timestampWithTimeZone("date")
    val weightKg = decimal("weight_kg", 7, 2)
    val revision = long("revision").default(0)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Changes : Table("changes") {
    val id = long("id").autoIncrement()
    val userId = uuid("user_id")
    val seq = long("seq")
    val entity = text("entity")
    val entityId = uuid("entity_id")
    val revision = long("revision")
    val deleted = bool("deleted").default(false)
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AppliedOps : Table("applied_ops") {
    val opId = uuid("op_id")
    val userId = uuid("user_id")
    val result = text("result")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(opId)
}

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
    val userId = uuid("user_id")
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
    val deviceId = uuid("device_id")
    val tokenHash = text("token_hash")
    val familyId = uuid("family_id")
    val used = bool("used").default(false)
    val expiresAt = timestampWithTimeZone("expires_at")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}
