package com.gymtracker.data.repository

import com.gymtracker.data.db.AppDatabase
import com.gymtracker.data.db.toDomain
import com.gymtracker.data.db.toEntity
import com.gymtracker.shared.domain.Split
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.SplitDayExercise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class SplitRepository(private val db: AppDatabase) {
    fun observeAll(): Flow<List<Split>> = db.splitDao().getAll().map { it.map { e -> e.toDomain() } }

    suspend fun getActive(): Split? = db.splitDao().getActive()?.toDomain()

    suspend fun save(split: Split) = db.splitDao().upsert(split.toEntity())

    suspend fun softDeleteSplit(id: UUID) {
        val now = java.time.Instant.now()
        db.splitDao().softDelete(id, now, now)
    }

    fun observeDays(splitId: UUID): Flow<List<SplitDay>> =
        db.splitDayDao().getBySplit(splitId).map { it.map { e -> e.toDomain() } }

    suspend fun saveDay(day: SplitDay) = db.splitDayDao().upsert(day.toEntity())

    suspend fun softDeleteDay(id: UUID) {
        val now = java.time.Instant.now()
        db.splitDayDao().softDelete(id, now, now)
    }

    fun observeDayExercises(splitDayId: UUID): Flow<List<SplitDayExercise>> =
        db.splitDayExerciseDao().getBySplitDay(splitDayId).map { it.map { e -> e.toDomain() } }

    suspend fun saveDayExercise(e: SplitDayExercise) = db.splitDayExerciseDao().upsert(e.toEntity())

    suspend fun softDeleteDayExercise(id: UUID) {
        val now = java.time.Instant.now()
        db.splitDayExerciseDao().softDelete(id, now, now)
    }
}
