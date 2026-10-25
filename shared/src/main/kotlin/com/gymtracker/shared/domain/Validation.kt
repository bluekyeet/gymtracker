package com.gymtracker.shared.domain

object Validation {
    fun isValidWeightKg(value: Double): Boolean =
        value in 0.0..1000.0 && (value * 100).let { it == kotlin.math.round(it) }

    fun isValidReps(value: Int): Boolean = value in 0..1000

    fun isValidRpe(value: Double?): Boolean {
        if (value == null) return true
        if (value < 1.0 || value > 10.0) return false
        val scaled = value * 2
        return scaled == kotlin.math.round(scaled)
    }
}
