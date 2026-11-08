package com.gymtracker.shared.domain

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

object Calculations {
    fun estimatedOneRepMax(weightKg: Double, reps: Int): Double? {
        // A 0 kg set is a planned or bodyweight set, not a lift: it has no meaningful 1RM.
        if (weightKg <= 0.0 || reps !in 1..12) return null
        // Epley overstates a single (103.3 for 100 x 1); a one-rep set already is the max.
        if (reps == 1) return weightKg
        return weightKg * (1.0 + reps / 30.0)
    }

    fun volume(sets: List<SetEntry>): Double =
        sets.filter { it.setType != SetType.WARMUP && it.deletedAt == null }
            .sumOf { it.weightKg * it.reps }

    fun bestWeightForRepCount(sets: List<SetEntry>, reps: Int): Double? =
        sets.filter { it.reps == reps && it.deletedAt == null }
            .map { it.weightKg }
            .maxOrNull()

    fun bestEstimatedOneRepMax(sets: List<SetEntry>): Double? =
        sets.filter { it.deletedAt == null }
            .mapNotNull { estimatedOneRepMax(it.weightKg, it.reps) }
            .maxOrNull()

    fun weekStart(date: java.time.Instant, zoneId: ZoneId): java.time.Instant {
        val zdt = date.atZone(zoneId)
        val monday = zdt.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.atStartOfDay(zoneId).toInstant()
    }
}
