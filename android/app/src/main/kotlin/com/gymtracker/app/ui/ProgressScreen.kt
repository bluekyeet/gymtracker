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
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.fuzzyScore
import com.gymtracker.shared.domain.groupName
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.core.cartesian.Zoom
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
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
        // One history query for the exercise instead of one query per session and per exercise in it.
        val rows = workoutRepo.historyForExercise(exId)
            .filter { cutoff == null || it.session.startedAt >= cutoff }
        // Sets at 0 kg are planned-but-unlogged (or bodyweight) sets, and warm-ups are not working
        // weight: neither counts toward bests.
        val logged = rows.flatMap { it.sets }.filter { it.weightKg > 0.0 && it.setType != SetType.WARMUP }
        val chartPoints = rows.map { SessionPoint(it.session.startedAt, it.e1rm) }
        stats = ExerciseProgress(
            bestWeight = logged.maxOfOrNull { it.weightKg },
            bestE1rm = Calculations.bestEstimatedOneRepMax(logged),
            setCount = logged.size,
            sessionCount = rows.count { row -> row.sets.any { it.weightKg > 0.0 } },
            lastAt = rows.lastOrNull { row -> row.sets.any { it.weightKg > 0.0 } }?.session?.startedAt
        )
        points = chartPoints.sortedBy { it.at }
        loading = false
    }

    // One point per calendar day, placed at its real date.
    val chartPoints = points.filter { it.e1rm != null }
    val chartDays = remember(points) {
        ChartTime.bestPerDay(points.mapNotNull { p -> p.e1rm?.let { p.at to it } })
    }

    // Feed the chart whenever the selected exercise changes.
    LaunchedEffect(chartDays) {
        if (chartDays.size >= 2) {
            modelProducer.runTransaction {
                lineSeries { series(chartDays.map { it.first }, chartDays.map { it.second }) }
            }
        }
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

        var menuOpen by remember { mutableStateOf(false) }
        var exerciseQuery by remember { mutableStateOf("") }
        // Fuzzy on the name or the group, best match first.
        val matched = if (exerciseQuery.isBlank()) {
            exercises
        } else {
            exercises
                .mapNotNull { ex ->
                    val best = listOfNotNull(
                        fuzzyScore(exerciseQuery, ex.name),
                        ex.groupName?.let { fuzzyScore(exerciseQuery, it) }
                    ).maxOrNull()
                    best?.let { it to ex }
                }
                .sortedByDescending { it.first }
                .map { it.second }
        }

        ExposedDropdownMenuBox(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it }
        ) {
            OutlinedTextField(
                value = exercises.firstOrNull { it.id == selected }?.name ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Exercise") },
                placeholder = { Text("Select an exercise") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.exposedDropdownSize()
            ) {
                DropdownMenuItem(
                    text = {
                        OutlinedTextField(
                            value = exerciseQuery,
                            onValueChange = { exerciseQuery = it },
                            label = { Text("Search") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    onClick = {}
                )
                if (matched.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No match", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        onClick = { menuOpen = false }
                    )
                }
                matched.forEach { ex ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(ex.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    ex.groupName ?: "No muscle group",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        onClick = {
                            selected = ex.id
                            exerciseQuery = ""
                            menuOpen = false
                        }
                    )
                }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                exercises.firstOrNull { it.id == selected }?.name ?: "",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { selected?.let { nav.navigate("exercise/$it") } }) {
                                Text("History")
                            }
                        }
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

                if (chartDays.size < 2) {
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
                        minX = ChartTime.paddedRange(chartDays.map { it.first }).first,
                        maxX = ChartTime.paddedRange(chartDays.map { it.first }).second,
                        minY = yMin - padding,
                        maxY = yMax + padding
                    )
                    CartesianChartHost(
                        chart = rememberCartesianChart(
                            rememberLineCartesianLayer(axisValueOverrider = overrider),
                            startAxis = rememberStartAxis(),
                            bottomAxis = rememberBottomAxis(
                                valueFormatter = ChartTime.axisFormatter,
                                guideline = null, // one line per day is just noise
                                tick = null,
                                itemPlacer = remember(chartDays) { ChartTime.labelPlacer(chartDays.map { it.first }) }
                            )
                        ),
                        modelProducer = modelProducer,
                        // Fit every point on screen. Left alone, a time axis is wider than the display and
                        // the chart turns into a sideways-scrolling strip showing only its first days.
                        scrollState = rememberVicoScrollState(scrollEnabled = false),
                        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
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
