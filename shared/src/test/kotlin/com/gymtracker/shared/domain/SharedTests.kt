package com.gymtracker.shared.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.ZoneId

class SharedTests {
    @Test
    fun weightValidation() {
        assertTrue(Validation.isValidWeightKg(0.0))
        assertTrue(Validation.isValidWeightKg(100.0))
        assertTrue(Validation.isValidWeightKg(100.25))
        assertFalse(Validation.isValidWeightKg(1000.01))
        assertFalse(Validation.isValidWeightKg(-1.0))
        assertFalse(Validation.isValidWeightKg(1.234))
    }

    @Test
    fun repsValidation() {
        assertTrue(Validation.isValidReps(0))
        assertTrue(Validation.isValidReps(1000))
        assertFalse(Validation.isValidReps(1001))
        assertFalse(Validation.isValidReps(-1))
    }

    @Test
    fun rpeValidation() {
        assertTrue(Validation.isValidRpe(null))
        assertTrue(Validation.isValidRpe(1.0))
        assertTrue(Validation.isValidRpe(10.0))
        assertTrue(Validation.isValidRpe(7.5))
        assertFalse(Validation.isValidRpe(0.5))
        assertFalse(Validation.isValidRpe(10.5))
        assertFalse(Validation.isValidRpe(7.25))
    }

    @Test
    fun unitConversion() {
        assertEquals(2.2046226218, Units.kgToLb(1.0), 1e-9)
        assertEquals(1.0, Units.lbToKg(2.2046226218), 1e-9)
    }

    @Test
    fun estimatedOneRepMax() {
        assertEquals(100.0 * (1 + 5 / 30.0), Calculations.estimatedOneRepMax(100.0, 5)!!, 1e-9)
        assertNull(Calculations.estimatedOneRepMax(100.0, 0))
        assertNull(Calculations.estimatedOneRepMax(100.0, 13))
    }

    @Test
    fun volume() {
        val sets = listOf(
            SetEntry(id = java.util.UUID.randomUUID(), workoutExerciseId = java.util.UUID.randomUUID(), setNumber = 1, weightKg = 100.0, reps = 5, setType = SetType.WORKING),
            SetEntry(id = java.util.UUID.randomUUID(), workoutExerciseId = java.util.UUID.randomUUID(), setNumber = 2, weightKg = 50.0, reps = 10, setType = SetType.WARMUP)
        )
        assertEquals(500.0, Calculations.volume(sets), 1e-9)
    }

    @Test
    fun personalRecord() {
        val sets = listOf(
            SetEntry(id = java.util.UUID.randomUUID(), workoutExerciseId = java.util.UUID.randomUUID(), setNumber = 1, weightKg = 100.0, reps = 5),
            SetEntry(id = java.util.UUID.randomUUID(), workoutExerciseId = java.util.UUID.randomUUID(), setNumber = 2, weightKg = 120.0, reps = 3)
        )
        assertEquals(100.0, Calculations.bestWeightForRepCount(sets, 5))
        assertNotNull(Calculations.bestEstimatedOneRepMax(sets))
    }

    @Test
    fun weeklyGrouping() {
        val zone = ZoneId.of("UTC")
        val monday = java.time.Instant.parse("2026-10-19T00:00:00Z")
        assertEquals(monday, Calculations.weekStart(java.time.Instant.parse("2026-10-21T12:00:00Z"), zone))
    }
}
