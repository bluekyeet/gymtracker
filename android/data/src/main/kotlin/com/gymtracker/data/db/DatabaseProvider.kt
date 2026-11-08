package com.gymtracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.gymtracker.data.OwnerId

object DatabaseProvider {
    /**
     * v1 -> v2: split_days gained a `weekdays` column (comma separated weekday numbers, 1 = Monday).
     *
     * The column is added only when it is missing, so this is safe against databases that
     * were created while the schema was being settled during development.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            if (!database.hasColumn("split_days", "weekdays")) {
                database.execSQL("ALTER TABLE split_days ADD COLUMN weekdays TEXT")
            }
        }
    }

    /**
     * v2 -> v3: exercises gained a `customGroup` column, the free-form muscle group name
     * the user typed when creating their own group. Nullable, added only when missing.
     */
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            if (!database.hasColumn("exercises", "customGroup")) {
                database.execSQL("ALTER TABLE exercises ADD COLUMN customGroup TEXT")
            }
        }
    }

    /**
     * v3 -> v4: indices on every foreign-key and lookup column. Without them each cascade, each
     * "rows of this session" query and the exercise history scan whole tables. Index names follow
     * Room's `index_<table>_<column>` convention, which Room checks when it validates the schema.
     * Pure additions: no data is touched.
     */
    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(database: SupportSQLiteDatabase) {
            INDICES.forEach { (table, column) ->
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_$column` ON `$table` (`$column`)")
            }
        }
    }

    /** (table, column) pairs indexed from schema v4 on. Kept in step with the @Entity annotations. */
    internal val INDICES = listOf(
        "split_days" to "splitId",
        "split_day_exercises" to "splitDayId",
        "workout_sessions" to "startedAt",
        "workout_exercises" to "sessionId",
        "workout_exercises" to "exerciseId",
        "sets" to "workoutExerciseId"
    )

    internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

    private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean {
        query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    @Volatile
    private var instance: AppDatabase? = null

    /** The one place the real migrations are wired in; tests open a database file through this too. */
    internal fun build(context: Context, name: String): AppDatabase =
        Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    fun get(context: Context): AppDatabase {
        return instance ?: synchronized(this) {
            instance ?: build(context, "gymtracker.db").also {
                OwnerId.init(context)
                instance = it
            }
        }
    }
}
