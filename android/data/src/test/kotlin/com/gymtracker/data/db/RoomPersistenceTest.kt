package com.gymtracker.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gymtracker.shared.domain.Exercise
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
class RoomPersistenceTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun dataSurvivesInsert() = runBlocking {
        val ex = ExerciseEntity(id = UUID.randomUUID(), name = "Bench", isCustom = true)
        db.exerciseDao().upsert(ex)
        val loaded = db.exerciseDao().getById(ex.id)
        assertEquals("Bench", loaded?.name)
    }
}
