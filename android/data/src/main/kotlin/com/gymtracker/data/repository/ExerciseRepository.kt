package com.gymtracker.data.repository

import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Exercise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ExerciseRepository(private val db: AppDatabase) {
    fun observeAll(): Flow<List<Exercise>> = db.exerciseDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getById(id: UUID): Exercise? = db.exerciseDao().getById(id)?.toDomain()

    suspend fun save(exercise: Exercise) = db.exerciseDao().upsert(exercise.toEntity())

    suspend fun softDelete(id: UUID) {
        val now = java.time.Instant.now()
        db.exerciseDao().softDelete(id, now, now)
    }
}
