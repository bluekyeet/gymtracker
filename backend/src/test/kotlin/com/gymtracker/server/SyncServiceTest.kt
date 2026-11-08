package com.gymtracker.server

import com.gymtracker.server.database.Sets
import com.gymtracker.server.sync.SyncService
import com.gymtracker.shared.sync.SyncOperation
import com.gymtracker.shared.sync.SyncPushRequest
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID

class SyncServiceTest {
    private val sync = SyncService()

    companion object {
        @JvmStatic
        @BeforeAll
        fun db() = TestDb.ensure()
    }

    private fun push(user: UUID, vararg ops: SyncOperation) =
        sync.push(user, SyncPushRequest(UUID.randomUUID().toString(), 1, ops.toList())).results

    /** A user with one session and one workout exercise, ready for sets. */
    private fun userWithWorkoutExercise(): Triple<UUID, UUID, UUID> {
        val user = TestDb.newUser()
        val session = UUID.randomUUID()
        val we = UUID.randomUUID()
        val r = push(user, sessionOp(session, user), workoutExerciseOp(we, session))
        assertEquals(listOf("applied", "applied"), r.map { it.status })
        return Triple(user, session, we)
    }

    private fun liveSetNumbers(workoutExerciseId: UUID): List<Int> = transaction {
        Sets.selectAll().where { (Sets.workoutExerciseId eq workoutExerciseId) and Sets.deletedAt.isNull() }
            .map { it[Sets.setNumber] }.sorted()
    }

    // --- #1: server faults must never look like rejections ---------------------------------------

    @Test
    fun serverFaultIsRetryNotRejectedAndNothingIsRemembered() {
        val user = TestDb.newUser()
        val dangling = UUID.randomUUID() // no such split_day: the insert violates a foreign key
        val opId = UUID.randomUUID()
        val bad = sessionOp(UUID.randomUUID(), user, splitDayId = dangling).copy(opId = opId.toString())

        val r = push(user, bad).single()

        assertEquals("retry", r.status)
        assertNull(TestDb.storedVerdict(opId), "a fault must not be stored as a verdict")
    }

    @Test
    fun faultingOpDoesNotRollBackOthersInTheSameBatch() {
        val user = TestDb.newUser()
        val bad = sessionOp(UUID.randomUUID(), user, splitDayId = UUID.randomUUID())
        val goodId = UUID.randomUUID()
        val results = push(user, bad, splitOp(goodId, user))
        assertEquals(listOf("retry", "applied"), results.map { it.status })
    }

    @Test
    fun invalidPayloadIsRejectedPermanently() {
        val (user, _, we) = userWithWorkoutExercise()
        val opId = UUID.randomUUID()
        val r = push(user, setOp(UUID.randomUUID(), we, 1, weight = 5000.0).copy(opId = opId.toString())).single()
        assertEquals("rejected", r.status)
        assertEquals("invalid_data", r.reason)
        assertEquals("rejected:invalid_data", TestDb.storedVerdict(opId))
    }

    @Test
    fun malformedIdsAreRejectedWithoutFailingTheBatch() {
        val user = TestDb.newUser()
        val broken = SyncOperation("not-a-uuid", "split", UUID.randomUUID().toString(), "upsert", 0, null)
        val badEntity = SyncOperation(UUID.randomUUID().toString(), "split", "nope", "delete", 0, null)
        val results = push(user, broken, badEntity, splitOp(UUID.randomUUID(), user))
        assertEquals(listOf("rejected", "rejected", "applied"), results.map { it.status })
        assertEquals("invalid_id", results[0].reason)
    }

    @Test
    fun unknownEntityIsRejected() {
        val user = TestDb.newUser()
        val r = push(user, op("body_weight", UUID.randomUUID(), kotlinx.serialization.json.JsonObject(emptyMap()))).single()
        assertEquals("rejected", r.status)
        assertEquals("unsupported_entity", r.reason)
    }

    @Test
    fun retryingAnAppliedPushIsIdempotent() {
        val user = TestDb.newUser()
        val split = splitOp(UUID.randomUUID(), user)
        val first = push(user, split).single()
        val second = push(user, split).single()
        assertEquals("applied", first.status)
        assertEquals(first.revision, second.revision)
        val pulled = sync.pull(user, "0", 100).changes
        assertEquals(1, pulled.size)
    }

    @Test
    fun editAfterDeleteIsRejected() {
        val user = TestDb.newUser()
        val id = UUID.randomUUID()
        push(user, splitOp(id, user))
        push(user, op("split", id, null, type = "delete"))
        val r = push(user, splitOp(id, user, name = "Revived")).single()
        assertEquals("rejected", r.status)
        assertEquals("deleted", r.reason)
    }

    // --- #2: two devices adding the same set number -----------------------------------------------

    @Test
    fun concurrentSetNumbersAreRenumberedInsteadOfLost() {
        val (user, _, we) = userWithWorkoutExercise()
        push(user, setOp(UUID.randomUUID(), we, 1), setOp(UUID.randomUUID(), we, 2))
        // The watch and the phone both added "set 3" while offline.
        val phone = UUID.randomUUID()
        val watch = UUID.randomUUID()
        val r1 = push(user, setOp(phone, we, 3)).single()
        val r2 = push(user, setOp(watch, we, 3)).single()

        assertEquals("applied", r1.status)
        assertEquals("applied", r2.status)
        assertEquals(listOf(1, 2, 3, 4), liveSetNumbers(we))
    }

    @Test
    fun editingASetKeepsItsNumberAndDoesNotCollideWithItself() {
        val (user, _, we) = userWithWorkoutExercise()
        val id = UUID.randomUUID()
        push(user, setOp(id, we, 1, weight = 60.0))
        val r = push(user, setOp(id, we, 1, weight = 62.5)).single()
        assertEquals("applied", r.status)
        assertEquals(listOf(1), liveSetNumbers(we))
    }

    // --- #5: ownership ----------------------------------------------------------------------------

    @Test
    fun cannotEditAnotherUsersRecords() {
        val owner = TestDb.newUser()
        val intruder = TestDb.newUser()
        val splitId = UUID.randomUUID()
        push(owner, splitOp(splitId, owner, name = "Mine"))

        val edit = push(intruder, splitOp(splitId, intruder, name = "Stolen")).single()
        val delete = push(intruder, op("split", splitId, null, type = "delete")).single()

        assertEquals("forbidden", edit.reason)
        assertEquals("forbidden", delete.reason)
        val mine = sync.pull(owner, "0", 100).changes
        assertTrue(mine.none { it.deleted })
    }

    @Test
    fun cannotAttachChildrenToAnotherUsersParents() {
        val owner = TestDb.newUser()
        val intruder = TestDb.newUser()
        val splitId = UUID.randomUUID()
        push(owner, splitOp(splitId, owner))

        val day = push(intruder, dayOp(UUID.randomUUID(), splitId)).single()
        assertEquals("rejected", day.status)
        assertEquals("forbidden", day.reason)

        val session = UUID.randomUUID()
        val we = UUID.randomUUID()
        push(owner, sessionOp(session, owner), workoutExerciseOp(we, session))
        val set = push(intruder, setOp(UUID.randomUUID(), we, 1)).single()
        assertEquals("forbidden", set.reason)
        val exercise = push(intruder, workoutExerciseOp(UUID.randomUUID(), session)).single()
        assertEquals("forbidden", exercise.reason)
    }

    @Test
    fun missingParentIsRetryBecauseItMayArriveLater() {
        val user = TestDb.newUser()
        val r = push(user, dayOp(UUID.randomUUID(), UUID.randomUUID())).single()
        assertEquals("retry", r.status)
        assertEquals("parent_missing", r.reason)
    }

    @Test
    fun childrenPushedAfterTheirParentInOneBatchApply() {
        val user = TestDb.newUser()
        val split = UUID.randomUUID()
        val results = push(user, splitOp(split, user), dayOp(UUID.randomUUID(), split))
        assertEquals(listOf("applied", "applied"), results.map { it.status })
    }

    @Test
    fun pullReturnsOnlyTheCallersChanges() {
        val a = TestDb.newUser()
        val b = TestDb.newUser()
        push(a, splitOp(UUID.randomUUID(), a))
        assertTrue(sync.pull(b, "0", 100).changes.isEmpty())
        assertEquals(1, sync.pull(a, "0", 100).changes.size)
    }

    @Test
    fun cursorAdvancesPageByPage() {
        val user = TestDb.newUser()
        push(user, splitOp(UUID.randomUUID(), user), splitOp(UUID.randomUUID(), user), splitOp(UUID.randomUUID(), user))
        val p1 = sync.pull(user, "0", 2)
        assertTrue(p1.hasMore)
        val p2 = sync.pull(user, p1.nextCursor, 2)
        assertEquals(1, p2.changes.size)
        assertFalse(p2.hasMore)
        assertTrue(sync.pull(user, p2.nextCursor, 2).changes.isEmpty())
    }

    // --- #8: stable server identity ---------------------------------------------------------------

    @Test
    fun serverIdIsStableAcrossCalls() {
        TestDb.ensure()
        assertEquals(loadServerId(), loadServerId())
        UUID.fromString(loadServerId())
    }
}
