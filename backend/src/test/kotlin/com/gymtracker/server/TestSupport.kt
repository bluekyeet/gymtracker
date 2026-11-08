package com.gymtracker.server

import com.gymtracker.server.database.AppliedOps
import com.gymtracker.server.database.DatabaseFactory
import com.gymtracker.server.database.Users
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.Split
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import com.gymtracker.shared.sync.SyncOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Tests run against a real PostgreSQL (see docs/BUILD_AND_TEST.md); every test uses fresh users and ids. */
object TestDb {
    val config = ServerConfig(
        enrollmentKey = "test-enrollment-key-not-a-real-secret",
        jwtSecret = "test-signing-key-test-signing-key-test-signing-key"
    )

    init {
        DatabaseFactory.init(
            System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/gymtracker",
            System.getenv("POSTGRES_USER") ?: "gymtracker",
            System.getenv("POSTGRES_PASSWORD") ?: "change-me"
        )
    }

    fun ensure() = Unit

    fun newUser(): UUID = transaction {
        val id = UUID.randomUUID()
        Users.insert {
            it[Users.id] = id
            it[email] = "t-$id@example.com"
            it[passwordHash] = "x"
            it[lastSeq] = 0
            it[unitPreference] = "kg"
            it[timezone] = "UTC"
            it[createdAt] = OffsetDateTime.now(ZoneOffset.UTC)
            it[updatedAt] = OffsetDateTime.now(ZoneOffset.UTC)
        }
        id
    }

    fun storedVerdict(opId: UUID): String? = transaction {
        AppliedOps.selectAll().where { AppliedOps.opId eq opId }.singleOrNull()?.get(AppliedOps.result)
    }
}

private val json = Json { encodeDefaults = true }

fun op(entity: String, id: UUID, data: JsonElement?, type: String = "upsert", opId: UUID = UUID.randomUUID()) =
    SyncOperation(opId.toString(), entity, id.toString(), type, 0, data)

fun splitOp(id: UUID, owner: UUID, name: String = "Push/Pull", opId: UUID = UUID.randomUUID()) =
    op("split", id, json.encodeToJsonElement(Split(id, owner, name)), opId = opId)

fun dayOp(id: UUID, splitId: UUID, name: String = "Push") =
    op("split_day", id, json.encodeToJsonElement(SplitDay(id, splitId, name)))

fun sessionOp(id: UUID, owner: UUID, splitDayId: UUID? = null) =
    op("workout_session", id, json.encodeToJsonElement(WorkoutSession(id, owner, splitDayId, "Push")))

fun workoutExerciseOp(id: UUID, sessionId: UUID) =
    op("workout_exercise", id, json.encodeToJsonElement(WorkoutExercise(id, sessionId, null, "Bench Press")))

fun setOp(id: UUID, workoutExerciseId: UUID, number: Int, weight: Double = 60.0, reps: Int = 8) =
    op("set", id, json.encodeToJsonElement(SetEntry(id, workoutExerciseId, number, weight, reps, setType = SetType.WORKING)))
