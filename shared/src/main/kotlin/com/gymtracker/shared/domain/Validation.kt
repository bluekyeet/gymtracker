package com.gymtracker.shared.domain

object Validation {
    fun isValidWeightKg(value: Double): Boolean =
        value in 0.0..1000.0 && hasAtMostTwoDecimals(value)

    fun isValidReps(value: Int): Boolean = value in 0..1000

    fun isValidRpe(value: Double?): Boolean {
        if (value == null) return true
        if (value < 1.0 || value > 10.0) return false
        val scaled = value * 2
        return scaled == kotlin.math.round(scaled)
    }

    /** `1.15 * 100` is 114.99999999999999 in doubles, so compare against the decimal text instead. */
    private fun hasAtMostTwoDecimals(value: Double): Boolean =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().scale() <= 2
}
