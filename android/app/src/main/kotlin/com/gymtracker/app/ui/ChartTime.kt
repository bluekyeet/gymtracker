package com.gymtracker.app.ui

import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Helpers that put workout charts on a real time axis.
 *
 * The x value of a point is its calendar day (epoch day). Charts used to plot "point 0, 1, 2…", so
 * two workouts a day apart and two a month apart were drawn the same distance apart, which makes the
 * trend look different from what it was.
 */
internal object ChartTime {
    private val shortDate = DateTimeFormatter.ofPattern("d MMM")

    fun dayOf(at: Instant): Long = at.atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** "4 Oct" for an epoch-day x value. */
    val axisFormatter = CartesianValueFormatter { value, _, _ ->
        LocalDate.ofEpochDay(value.toLong()).format(shortDate)
    }

    /**
     * One point per calendar day, oldest first, keeping the best value when a day has several
     * workouts (Vico expects x to increase, and two points on one day would overlap anyway).
     */
    fun bestPerDay(points: List<Pair<Instant, Double>>): List<Pair<Long, Double>> =
        points.groupBy({ dayOf(it.first) }, { it.second })
            .map { (day, values) -> day to values.max() }
            .sortedBy { it.first }

    /**
     * Label spacing that gives about four labels however long the period is. Vico places labels in
     * multiples of the smallest step between points, so the spacing is measured in those steps.
     */
    fun labelPlacer(days: List<Long>): HorizontalAxis.ItemPlacer {
        val sorted = days.distinct().sorted()
        if (sorted.size < 2) return HorizontalAxis.ItemPlacer.default()
        val step = sorted.zipWithNext { a, b -> b - a }.reduce(::gcd).coerceAtLeast(1)
        val steps = (sorted.last() - sorted.first()) / step
        val spacing = ceil(steps / 4.0).toInt().coerceAtLeast(1)
        // Labels start at the first data point, not at the padded left edge where there is no room for text.
        val offset = ceil(padDays(sorted.first(), sorted.last()) / step.toDouble()).toInt()
        return HorizontalAxis.ItemPlacer.default(spacing = spacing, offset = offset, addExtremeLabelPadding = true)
    }

    /** x range with ~7% of room on each side so the first and last date labels are not clipped by the chart edge. */
    fun paddedRange(days: List<Long>): Pair<Double, Double> {
        val first = days.minOrNull() ?: return 0.0 to 1.0
        val last = days.maxOrNull() ?: first
        val pad = padDays(first, last)
        // The right side gets double: the last label is centred on a grid position that can fall past the last point.
        return (first - pad).toDouble() to (last + 2 * pad).toDouble()
    }

    private fun padDays(first: Long, last: Long): Long = ceil((last - first) * 0.07).toLong().coerceAtLeast(1)

    private fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)
}
