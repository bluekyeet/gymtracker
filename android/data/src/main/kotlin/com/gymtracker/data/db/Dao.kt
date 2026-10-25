package com.gymtracker.data.db

import androidx.room.*
import com.gymtracker.shared.domain.SetType
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE deletedAt IS NULL ORDER BY name")
    fun getAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: UUID): ExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExerciseEntity)

    @Update
    suspend fun update(entity: ExerciseEntity)

    @Query("UPDATE exercises SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)
}

@Dao
interface SplitDao {
    @Query("SELECT * FROM splits WHERE deletedAt IS NULL ORDER BY name")
    fun getAll(): Flow<List<SplitEntity>>

    @Query("SELECT * FROM splits WHERE isActive = 1 AND deletedAt IS NULL LIMIT 1")
    suspend fun getActive(): SplitEntity?

    @Query("SELECT * FROM splits WHERE id = :id")
    suspend fun getById(id: UUID): SplitEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SplitEntity)

    @Update
    suspend fun update(entity: SplitEntity)

    @Query("UPDATE splits SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)

    @Query("UPDATE splits SET isActive = CASE WHEN id = :activeId THEN 1 ELSE 0 END WHERE deletedAt IS NULL")
    suspend fun setActive(activeId: UUID)
}

@Dao
interface SplitDayDao {
    @Query("SELECT * FROM split_days WHERE splitId = :splitId AND deletedAt IS NULL ORDER BY sortKey, id")
    fun getBySplit(splitId: UUID): Flow<List<SplitDayEntity>>

    @Query("SELECT * FROM split_days WHERE id = :id")
    suspend fun getById(id: UUID): SplitDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SplitDayEntity)

    @Update
    suspend fun update(entity: SplitDayEntity)

    @Query("UPDATE split_days SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)
}

@Dao
interface SplitDayExerciseDao {
    @Query("SELECT * FROM split_day_exercises WHERE splitDayId = :splitDayId AND deletedAt IS NULL ORDER BY sortKey, id")
    fun getBySplitDay(splitDayId: UUID): Flow<List<SplitDayExerciseEntity>>

    @Query("SELECT * FROM split_day_exercises WHERE id = :id")
    suspend fun getById(id: UUID): SplitDayExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SplitDayExerciseEntity)

    @Update
    suspend fun update(entity: SplitDayExerciseEntity)

    @Query("UPDATE split_day_exercises SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)
}

@Dao
interface WorkoutSessionDao {
    @Query("SELECT * FROM workout_sessions WHERE deletedAt IS NULL ORDER BY startedAt DESC")
    fun getAll(): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun getById(id: UUID): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE endedAt IS NULL AND deletedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getInProgress(): WorkoutSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkoutSessionEntity)

    @Update
    suspend fun update(entity: WorkoutSessionEntity)

    @Query("UPDATE workout_sessions SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)
}

@Dao
interface WorkoutExerciseDao {
    @Query("SELECT * FROM workout_exercises WHERE sessionId = :sessionId AND deletedAt IS NULL ORDER BY sortKey, id")
    fun getBySession(sessionId: UUID): Flow<List<WorkoutExerciseEntity>>

    @Query("SELECT * FROM workout_exercises WHERE id = :id")
    suspend fun getById(id: UUID): WorkoutExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkoutExerciseEntity)

    @Update
    suspend fun update(entity: WorkoutExerciseEntity)

    @Query("UPDATE workout_exercises SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)
}

@Dao
interface SetDao {
    @Query("SELECT * FROM sets WHERE workoutExerciseId = :workoutExerciseId AND deletedAt IS NULL ORDER BY setNumber")
    fun getByWorkoutExercise(workoutExerciseId: UUID): Flow<List<SetEntity>>

    @Query("SELECT * FROM sets WHERE id = :id")
    suspend fun getById(id: UUID): SetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SetEntity)

    @Update
    suspend fun update(entity: SetEntity)

    @Query("UPDATE sets SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)
}

@Dao
interface BodyWeightDao {
    @Query("SELECT * FROM body_weights WHERE deletedAt IS NULL ORDER BY date DESC")
    fun getAll(): Flow<List<BodyWeightEntity>>

    @Query("SELECT * FROM body_weights WHERE id = :id")
    suspend fun getById(id: UUID): BodyWeightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BodyWeightEntity)

    @Update
    suspend fun update(entity: BodyWeightEntity)

    @Query("UPDATE body_weights SET deletedAt = :deletedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: UUID, deletedAt: Instant, updatedAt: Instant)
}
