package com.gymtracker.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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

    fun get(context: Context): AppDatabase {
        return instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "gymtracker.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
