package com.gymtracker.shared.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SetType { WORKING, WARMUP, DROP, FAILURE }

@Serializable
enum class MuscleGroup { CHEST, BACK, SHOULDERS, ARMS, LEGS, CORE, FULL_BODY, OTHER }

@Serializable
enum class Equipment { BARBELL, DUMBBELL, MACHINE, BODYWEIGHT, CABLE, KETTLEBELL, OTHER }
