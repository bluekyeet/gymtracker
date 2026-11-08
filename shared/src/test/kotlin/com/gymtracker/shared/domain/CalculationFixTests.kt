package com.gymtracker.shared.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class CalculationFixTests {
    private fun set(weight: Double, reps: Int, type: SetType = SetType.WORKING) =
        SetEntry(UUID.randomUUID(), UUID.randomUUID(), 1, weight, reps, setType = type)

    @Test
    fun oneRepSetIsItsOwnMax() {
        assertEquals(100.0, Calculations.estimatedOneRepMax(100.0, 1)!!, 1e-9)
    }

    @Test
    fun zeroKgSetHasNoOneRepMax() {
        assertNull(Calculations.estimatedOneRepMax(0.0, 8))
        assertNull(Calculations.bestEstimatedOneRepMax(listOf(set(0.0, 8), set(0.0, 8))))
    }

    @Test
    fun plannedZeroKgSetsDoNotBeatRealLifts() {
        val best = Calculations.bestEstimatedOneRepMax(listOf(set(0.0, 10), set(60.0, 5)))
        assertEquals(60.0 * (1 + 5 / 30.0), best!!, 1e-9)
    }

    @Test
    fun weightsWithTwoDecimalsAreValidDespiteFloatingPoint() {
        listOf(1.15, 0.07, 2.3, 17.6, 62.55, 100.1, 999.99).forEach {
            assertTrue(Validation.isValidWeightKg(it), "$it should be valid")
        }
        assertFalse(Validation.isValidWeightKg(1.151))
        assertFalse(Validation.isValidWeightKg(Double.NaN))
    }
}
