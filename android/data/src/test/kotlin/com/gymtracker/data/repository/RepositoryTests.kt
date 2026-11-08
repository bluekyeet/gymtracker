package com.gymtracker.data.repository

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.gymtracker.data.db.AppDatabase
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.MuscleGroup
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.Split
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.SplitDayExercise
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RepositoryTests {
    private lateinit var db: AppDatabase
    private lateinit var workouts: WorkoutRepository
    private lateinit var splits: SplitRepository
    private lateinit var exercises: ExerciseRepository
    private val owner = UUID.randomUUID()

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        workouts = WorkoutRepository(db)
        splits = SplitRepository(db)
        exercises = ExerciseRepository(db)
    }

    @After
    fun teardown() = db.close()

    private fun session(at: Instant = Instant.now()) = WorkoutSession(UUID.randomUUID(), owner, null, "Push", startedAt = at)

    private suspend fun workoutWith(exerciseId: UUID, at: Instant, vararg sets: Pair<Double, Int>, type: SetType = SetType.WORKING): UUID {
        val s = session(at)
        workouts.saveSession(s)
        val we = WorkoutExercise(UUID.randomUUID(), s.id, exerciseId, "Bench Press")
        workouts.saveExercise(we)
        sets.forEachIndexed { i, (kg, reps) ->
            workouts.saveSet(SetEntry(UUID.randomUUID(), we.id, i + 1, kg, reps, setType = type))
        }
        return s.id
    }

    // --- local write first: row and outbox entry commit together ------------------------------------

    @Test
    fun everyWriteLandsInTheOutboxWithTheRow() = runBlocking {
        val split = Split(UUID.randomUUID(), owner, "PPL")
        splits.save(split)
        assertNotNull(db.splitDao().getById(split.id))
        val queued = db.outboxDao().getAll()
        assertEquals(listOf("split"), queued.map { it.entity })
        assertEquals("upsert", queued.single().type)
    }

    @Test
    fun aRolledBackTransactionLeavesNeitherRowNorOutboxEntry() = runBlocking {
        val set = SetEntry(UUID.randomUUID(), UUID.randomUUID(), 1, 50.0, 5)
        val s = session()
        runCatching {
            db.withTransaction {
                workouts.saveSession(s) // repository writes join the surrounding transaction
                throw IllegalStateException("boom")
            }
        }
        assertNull(db.workoutSessionDao().getById(s.id))
        assertTrue(db.outboxDao().getAll().isEmpty())
        assertNull(db.setDao().getById(set.id))
    }

    @Test
    fun repeatedEditsOfOneRowQueueASingleUpsert() = runBlocking {
        val s = session()
        workouts.saveSession(s)
        val we = WorkoutExercise(UUID.randomUUID(), s.id, null, "Squat")
        workouts.saveExercise(we)
        val set = SetEntry(UUID.randomUUID(), we.id, 1, 100.0, 5)
        listOf(1.0, 10.0, 102.0, 102.5).forEach { workouts.saveSet(set.copy(weightKg = it)) }

        val ops = db.outboxDao().getAll().filter { it.entityId == set.id }
        assertEquals(1, ops.size)
        assertTrue(ops.single().dataJson!!.contains("102.5"))
    }

    @Test
    fun deletesAreNeverCoalescedAway() = runBlocking {
        val s = session()
        workouts.saveSession(s)
        workouts.softDeleteSession(s.id)
        val ops = db.outboxDao().getAll().filter { it.entityId == s.id }.map { it.type }
        assertEquals(listOf("upsert", "delete"), ops)
    }

    // --- startWorkout: atomic snapshot ---------------------------------------------------------------

    private suspend fun planDay(): Pair<SplitDay, Exercise> {
        val bench = Exercise(UUID.randomUUID(), name = "Bench Press", muscleGroup = MuscleGroup.CHEST)
        exercises.save(bench)
        val split = Split(UUID.randomUUID(), owner, "PPL")
        splits.save(split)
        val day = SplitDay(UUID.randomUUID(), split.id, "Push")
        splits.saveDay(day)
        splits.saveDayExercise(SplitDayExercise(UUID.randomUUID(), day.id, bench.id, 0.0, targetSets = 3, targetReps = 8))
        db.outboxDao().deleteMany(db.outboxDao().getAll().map { it.opId })
        return day to bench
    }

    @Test
    fun startWorkoutSnapshotsThePlanAndPrefillsSetsInOneGo() = runBlocking {
        val (day, bench) = planDay()
        val started = workouts.startWorkout(day)

        val we = db.workoutExerciseDao().getBySession(started.id).first().single()
        assertEquals("Bench Press", we.exerciseName)
        assertEquals(bench.id, we.exerciseId)
        val sets = db.setDao().listByWorkoutExercise(we.id)
        assertEquals(listOf(1, 2, 3), sets.map { it.setNumber })
        assertTrue(sets.all { it.reps == 8 && it.weightKg == 0.0 })
        // 1 session + 1 exercise + 3 sets, all queued.
        assertEquals(5, db.outboxDao().getAll().size)
    }

    @Test
    fun editingThePlanNeverChangesAPastWorkout() = runBlocking {
        val (day, bench) = planDay()
        val started = workouts.startWorkout(day)
        exercises.save(bench.copy(name = "Flat Bench"))
        splits.softDeleteDay(day.id)

        val we = db.workoutExerciseDao().getBySession(started.id).first().single()
        assertEquals("Bench Press", we.exerciseName)
        assertEquals("Push", workouts.getSession(started.id)!!.dayName)
    }

    @Test
    fun startWorkoutStampsTheOwnerNotARandomId() = runBlocking {
        val (day, _) = planDay()
        val a = workouts.startWorkout(day)
        val b = workouts.startWorkout(day)
        assertEquals(a.userId, b.userId)
    }

    @Test
    fun adHocWorkoutStartsAtomicallyFromAnExerciseList() = runBlocking {
        val squat = Exercise(UUID.randomUUID(), name = "Squat", muscleGroup = MuscleGroup.LEGS)
        val row = Exercise(UUID.randomUUID(), name = "Barbell Row", muscleGroup = MuscleGroup.BACK)
        val started = workouts.startWorkout(null, "Quick Workout", listOf(squat, row))
        val rows = db.workoutExerciseDao().getBySession(started.id).first()
        assertEquals(listOf("Squat", "Barbell Row"), rows.map { it.exerciseName })
        assertEquals(3, db.outboxDao().getAll().size)
    }

    // --- history: one join, correct numbers -------------------------------------------------------------

    @Test
    fun historyIsOldestFirstAndIgnoresDeletedRows() = runBlocking {
        val bench = UUID.randomUUID()
        val old = workoutWith(bench, Instant.parse("2026-01-01T10:00:00Z"), 60.0 to 5)
        val recent = workoutWith(bench, Instant.parse("2026-02-01T10:00:00Z"), 70.0 to 5)
        val gone = workoutWith(bench, Instant.parse("2026-03-01T10:00:00Z"), 99.0 to 5)
        workouts.softDeleteSession(gone)
        workoutWith(UUID.randomUUID(), Instant.parse("2026-01-15T10:00:00Z"), 200.0 to 1) // other exercise

        val history = workouts.historyForExercise(bench)
        assertEquals(listOf(old, recent), history.map { it.session.id })
        assertEquals(70.0, history.last().topSet!!.weightKg, 0.0)
    }

    @Test
    fun historyCanExcludeTheCurrentSession() = runBlocking {
        val bench = UUID.randomUUID()
        workoutWith(bench, Instant.parse("2026-01-01T10:00:00Z"), 60.0 to 5)
        val current = workoutWith(bench, Instant.parse("2026-02-01T10:00:00Z"), 70.0 to 5)
        assertEquals(1, workouts.historyForExercise(bench, excludeSessionId = current).size)
    }

    @Test
    fun zeroKgSetsNeverBecomeTheTopSetOrAnE1rm() = runBlocking {
        val bench = UUID.randomUUID()
        workoutWith(bench, Instant.parse("2026-01-01T10:00:00Z"), 0.0 to 8, 0.0 to 8)
        workoutWith(bench, Instant.parse("2026-01-08T10:00:00Z"), 0.0 to 8, 60.0 to 5)

        val history = workouts.historyForExercise(bench)
        assertNull(history[0].e1rm)
        assertNull(history[0].topSet)
        assertEquals(60.0, history[1].topSet!!.weightKg, 0.0)
        assertEquals(60.0 * (1 + 5 / 30.0), history[1].e1rm!!, 1e-9)
    }

    @Test
    fun warmupSetsDoNotCountTowardTheTopSet() = runBlocking {
        val bench = UUID.randomUUID()
        workoutWith(bench, Instant.parse("2026-01-01T10:00:00Z"), 40.0 to 10, type = SetType.WARMUP)
        val row = workouts.historyForExercise(bench).single()
        assertNull(row.topSet)
        assertNull(row.e1rm)
        assertEquals(1, row.sets.size)
    }

    // --- built-in exercises --------------------------------------------------------------------------------

    @Test
    fun seedingTwiceDoesNotDuplicate() = runBlocking {
        exercises.seedBuiltinsIfNeeded()
        exercises.seedBuiltinsIfNeeded()
        assertEquals(5, exercises.observeAll().first().size)
    }

    @Test
    fun deletingEveryExerciseDoesNotBringBuiltinsBack() = runBlocking {
        exercises.seedBuiltinsIfNeeded()
        exercises.observeAll().first().forEach { exercises.softDelete(it.id) }
        exercises.seedBuiltinsIfNeeded()
        assertTrue(exercises.observeAll().first().isEmpty())
    }

    @Test
    fun twoDevicesSeedTheSameIds() = runBlocking {
        exercises.seedBuiltinsIfNeeded()
        val phone = exercises.observeAll().first().map { it.id }.toSet()

        val other = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val watch = ExerciseRepository(other)
            watch.seedBuiltinsIfNeeded()
            assertEquals(phone, watch.observeAll().first().map { it.id }.toSet())
        } finally {
            other.close()
        }
    }

    @Test
    fun seedingKeepsAUsersExistingBuiltinsInsteadOfAddingSecondCopies() = runBlocking {
        // An install that seeded before ids were deterministic has "Bench Press" under a random id.
        val legacy = Exercise(UUID.randomUUID(), name = "Bench Press", muscleGroup = MuscleGroup.CHEST, isCustom = false)
        exercises.save(legacy)
        exercises.seedBuiltinsIfNeeded()
        val all = exercises.observeAll().first()
        assertEquals(1, all.count { it.name == "Bench Press" })
        assertNotEquals(0, all.size)
        assertFalse(all.none { it.id == legacy.id })
    }
}
