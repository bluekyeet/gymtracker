package com.gymtracker.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.Split
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.SplitDayExercise
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * The schema is not exported, so these tests build the real current database, rewind it to the
 * previous version the way an installed app would be, and let Room run the real migration on open.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTests {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "migration-test-${UUID.randomUUID()}.db"

    @After
    fun cleanup() {
        context.deleteDatabase(name)
    }

    private fun indexNames(db: AppDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'index_%'").use {
            while (it.moveToNext()) names += it.getString(0)
        }
        return names
    }

    @Test
    fun freshDatabaseHasEveryIndexTheMigrationCreates() {
        val db = DatabaseProvider.build(context, name)
        try {
            val expected = DatabaseProvider.INDICES.map { (table, column) -> "index_${table}_$column" }.toSet()
            assertEquals(expected, indexNames(db).filter { it in expected }.toSet())
        } finally {
            db.close()
        }
    }

    @Test
    fun upgradingFromV3KeepsEveryRowAndAddsTheIndices() = runBlocking {
        // 1. A current database with data in every table that has children.
        val owner = UUID.randomUUID()
        val split = Split(UUID.randomUUID(), owner, "PPL")
        val day = SplitDay(UUID.randomUUID(), split.id, "Push", weekdays = listOf(1, 4))
        val exercise = Exercise(UUID.randomUUID(), name = "Bench Press", customGroup = "Chest")
        val planned = SplitDayExercise(UUID.randomUUID(), day.id, exercise.id, 0.0, 3, 8)
        val session = WorkoutSession(UUID.randomUUID(), owner, day.id, "Push")
        val workoutExercise = WorkoutExercise(UUID.randomUUID(), session.id, exercise.id, "Bench Press")
        val set = SetEntry(UUID.randomUUID(), workoutExercise.id, 1, 62.5, 8)

        val first = DatabaseProvider.build(context, name)
        first.exerciseDao().upsert(exercise.toEntity())
        first.splitDao().upsert(split.toEntity())
        first.splitDayDao().upsert(day.toEntity())
        first.splitDayExerciseDao().upsert(planned.toEntity())
        first.workoutSessionDao().upsert(session.toEntity())
        first.workoutExerciseDao().upsert(workoutExercise.toEntity())
        first.setDao().upsert(set.toEntity())
        first.close()

        // 2. Rewind it to v3: no indices, user_version 3.
        val path = context.getDatabasePath(name).path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            DatabaseProvider.INDICES.forEach { (table, column) -> raw.execSQL("DROP INDEX IF EXISTS `index_${table}_$column`") }
            raw.version = 3
        }

        // 3. Open it with the real migrations, as the app does after an update.
        val upgraded = DatabaseProvider.build(context, name)
        try {
            val expected = DatabaseProvider.INDICES.map { (table, column) -> "index_${table}_$column" }.toSet()
            assertTrue("missing: ${expected - indexNames(upgraded)}", indexNames(upgraded).containsAll(expected))

            assertEquals("Bench Press", upgraded.exerciseDao().getById(exercise.id)?.name)
            assertEquals("Chest", upgraded.exerciseDao().getById(exercise.id)?.customGroup)
            assertNotNull(upgraded.splitDao().getById(split.id))
            assertEquals("1,4", upgraded.splitDayDao().getById(day.id)?.weekdays)
            assertEquals(3, upgraded.splitDayExerciseDao().getById(planned.id)?.targetSets)
            assertNotNull(upgraded.workoutSessionDao().getById(session.id))
            assertEquals("Bench Press", upgraded.workoutExerciseDao().getById(workoutExercise.id)?.exerciseName)
            assertEquals(62.5, upgraded.setDao().getById(set.id)?.weightKg ?: 0.0, 0.0)
        } finally {
            upgraded.close()
        }
    }

    @Test
    fun cascadeStillWorksButNeverFromAnUpsert() = runBlocking {
        // Upserting a parent must not delete its children (the REPLACE trap from AGENTS.md).
        val db = DatabaseProvider.build(context, name)
        try {
            val owner = UUID.randomUUID()
            val split = Split(UUID.randomUUID(), owner, "PPL")
            val day = SplitDay(UUID.randomUUID(), split.id, "Push")
            val exercise = Exercise(UUID.randomUUID(), name = "Row")
            val planned = SplitDayExercise(UUID.randomUUID(), day.id, exercise.id)
            db.exerciseDao().upsert(exercise.toEntity())
            db.splitDao().upsert(split.toEntity())
            db.splitDayDao().upsert(day.toEntity())
            db.splitDayExerciseDao().upsert(planned.toEntity())

            db.splitDao().upsert(split.copy(name = "Renamed").toEntity())
            db.splitDayDao().upsert(day.copy(name = "Renamed too").toEntity())

            assertNotNull("children survive a parent upsert", db.splitDayExerciseDao().getById(planned.id))
            assertEquals("Renamed", db.splitDao().getById(split.id)?.name)
        } finally {
            db.close()
        }
    }
}
