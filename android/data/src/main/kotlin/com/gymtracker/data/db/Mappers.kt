package com.gymtracker.data.db

import com.gymtracker.shared.domain.*
import java.util.UUID

fun ExerciseEntity.toDomain() = Exercise(id, userId, name, muscleGroup, equipment, isCustom, revision, createdAt, updatedAt, deletedAt)
fun Exercise.toEntity() = ExerciseEntity(id, userId, name, muscleGroup, equipment, isCustom, revision, createdAt, updatedAt, deletedAt)

fun SplitEntity.toDomain() = Split(id, userId, name, isActive, rotationPosition, revision, createdAt, updatedAt, deletedAt)
fun Split.toEntity() = SplitEntity(id, userId, name, isActive, rotationPosition, revision, createdAt, updatedAt, deletedAt)

fun SplitDayEntity.toDomain() = SplitDay(id, splitId, name, sortKey, revision, createdAt, updatedAt, deletedAt)
fun SplitDay.toEntity() = SplitDayEntity(id, splitId, name, sortKey, revision, createdAt, updatedAt, deletedAt)

fun SplitDayExerciseEntity.toDomain() = SplitDayExercise(id, splitDayId, exerciseId, sortKey, targetSets, targetReps, revision, createdAt, updatedAt, deletedAt)
fun SplitDayExercise.toEntity() = SplitDayExerciseEntity(id, splitDayId, exerciseId, sortKey, targetSets, targetReps, revision, createdAt, updatedAt, deletedAt)

fun WorkoutSessionEntity.toDomain() = WorkoutSession(id, userId, splitDayId, dayName, startedAt, endedAt, notes, revision, createdAt, updatedAt, deletedAt)
fun WorkoutSession.toEntity() = WorkoutSessionEntity(id, userId, splitDayId, dayName, startedAt, endedAt, notes, revision, createdAt, updatedAt, deletedAt)

fun WorkoutExerciseEntity.toDomain() = WorkoutExercise(id, sessionId, exerciseId, exerciseName, sortKey, revision, createdAt, updatedAt, deletedAt)
fun WorkoutExercise.toEntity() = WorkoutExerciseEntity(id, sessionId, exerciseId, exerciseName, sortKey, revision, createdAt, updatedAt, deletedAt)

fun SetEntity.toDomain() = SetEntry(id, workoutExerciseId, setNumber, weightKg, reps, rpe, setType, revision, createdAt, updatedAt, deletedAt)
fun SetEntry.toEntity() = SetEntity(id, workoutExerciseId, setNumber, weightKg, reps, rpe, setType, revision, createdAt, updatedAt, deletedAt)

fun BodyWeightEntity.toDomain() = BodyWeight(id, userId, date, weightKg, revision, createdAt, updatedAt, deletedAt)
fun BodyWeight.toEntity() = BodyWeightEntity(id, userId, date, weightKg, revision, createdAt, updatedAt, deletedAt)
