package com.gymtracker.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.Calculations
import com.gymtracker.shared.domain.SetEntry
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.data.AxisValueOverrider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

private data class ExerciseProgress(
    val bestWeight: Double?,
    val bestE1rm: Double?,
    val setCount: Int,
    val sessionCount: Int,
    val lastAt: Instant?
)

private data class SessionPoint(val at: Instant, val e1rm: Double?)

private enum class ChartPeriod(val label: String, val days: Long?) {
    P1M("1M", 30L),
    P3M("3M", 90L),
    P6M("6M", 180L),
    P1Y("1Y", 365L),
    ALL("All", null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProgressScreen(nav: NavController, workoutRepo: WorkoutRepository, exerciseRepo: ExerciseRepository) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf<UUID?>(null) }
    var period by remember { mutableStateOf(ChartPeriod.ALL) }
    var stats by remember { mutableStateOf<ExerciseProgress?>(null) }
    var points by remember { mutableStateOf<List<SessionPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(selected, period) {
        val exId = selected ?: return@LaunchedEffect
        loading = true
        val cutoff = period.days?.let { Instant.now().minusSeconds(it * 24 * 60 * 60) }
        val sessions = workoutRepo.observeSessions().first()
            .filter { cutoff == null || it.startedAt >= cutoff }
        val allSets = mutableListOf<SetEntry>()
        val chartPoints = mutableListOf<SessionPoint>()
        var last: Instant? = null
        sessions.forEach { s ->
            val forThis = workoutRepo.observeExercises(s.id).first().filter { it.exerciseId == exId }
            if (forThis.isNotEmpty()) {
                val sets = forThis.flatMap { workoutRepo.observeSets(it.id).first() }
                allSets += sets
                val prev = last
                if (prev == null || s.startedAt > prev) last = s.startedAt
                chartPoints += SessionPoint(s.startedAt, Calculations.bestEstimatedOneRepMax(sets))
            }
        }
        stats = ExerciseProgress(
            bestWeight = allSets.mapNotNull { it.weightKg }.maxOrNull(),
            bestE1rm = Calculations.bestEstimatedOneRepMax(allSets),
            setCount = allSets.size,
            sessionCount = chartPoints.size,
            lastAt = last
        )
        points = chartPoints.sortedBy { it.at }
        loading = false
    }

    // Feed the chart whenever the selected exercise changes.
    LaunchedEffect(points) {
        val usable = points.filter { it.e1rm != null }
        if (usable.size >= 2) {
            modelProducer.runTransaction {
                lineSeries {
                    series(
                        usable.mapIndexed { index, _ -> index.toDouble() },
                        usable.map { it.e1rm ?: 0.0 }
                    )
                }
            }
        }
    }

    val shortDate = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
    val chartPoints = points.filter { it.e1rm != null }
    val dateLabels = chartPoints.map { shortDate.format(it.at) }
    val xAxisFormatter = CartesianValueFormatter { value, _, _ ->
        dateLabels.getOrNull(value.roundToInt()) ?: ""
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Progress", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text("Pick an exercise to see your bests.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        if (exercises.isEmpty()) {
            Text(
                "No exercises yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            exercises.forEach { ex ->
                FilterChip(
                    selected = selected == ex.id,
                    onClick = { selected = ex.id },
                    label = { Text(ex.name) }
                )
            }
        }

        if (selected != null) {
            Spacer(Modifier.height(8.dp))
            Text("TIME PERIOD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartPeriod.entries.forEach { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = { period = p },
                        label = { Text(p.label) }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            selected == null -> Text(
                "Select an exercise above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            loading -> Text("Loading…")
            else -> {
                val s = stats
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            exercises.firstOrNull { it.id == selected }?.name ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(12.dp))
                        StatRow("Best weight", s?.bestWeight?.let { formatKg(it) } ?: "—")
                        StatRow("Best estimated 1RM", s?.bestE1rm?.let { formatKg(it) } ?: "—")
                        StatRow("Sets logged", "${s?.setCount ?: 0}")
                        StatRow("Workouts", "${s?.sessionCount ?: 0}")
                        StatRow("Last performed", s?.lastAt?.let { DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault()).format(it) } ?: "Never")
                    }
                }

                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Estimated 1RM over time", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("kg", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))

                if (dateLabels.size < 2) {
                    val message = when {
                        points.isEmpty() -> "Log this exercise in 2 or more workouts to see a trend."
                        chartPoints.isEmpty() -> "No workouts in this time period."
                        else -> "Log this exercise in 2 or more workouts to see a trend."
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            message,
                            Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val yValues = chartPoints.mapNotNull { it.e1rm }
                    val yMin = yValues.minOrNull() ?: 0.0
                    val yMax = yValues.maxOrNull() ?: 1.0
                    val padding = ((yMax - yMin) * 0.2).coerceAtLeast(1.0)
                    val overrider = AxisValueOverrider.fixed(
                        minX = null,
                        maxX = null,
                        minY = yMin - padding,
                        maxY = yMax + padding
                    )
                    CartesianChartHost(
                        chart = rememberCartesianChart(
                            rememberLineCartesianLayer(axisValueOverrider = overrider),
                            startAxis = rememberStartAxis(),
                            bottomAxis = rememberBottomAxis(valueFormatter = xAxisFormatter)
                        ),
                        modelProducer = modelProducer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

internal fun formatKg(value: Double): String {
    val rounded = kotlin.math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()} kg"
    } else {
        String.format(java.util.Locale.US, "%.1f kg", rounded)
    }
}
