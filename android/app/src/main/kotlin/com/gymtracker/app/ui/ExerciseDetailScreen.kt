package com.gymtracker.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.Calculations
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.WorkoutSession
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

private data class HistoryRow(
    val session: WorkoutSession,
    val sets: List<SetEntry>,
    val topSet: SetEntry?,
    val e1rm: Double?
)

@Composable
fun ExerciseDetailScreen(
    nav: NavController,
    exerciseId: UUID,
    workoutRepo: WorkoutRepository,
    exerciseRepo: ExerciseRepository
) {
    var exercise by remember { mutableStateOf<Exercise?>(null) }
    var history by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(exerciseId) {
        loading = true
        exercise = exerciseRepo.getById(exerciseId)
        val sessions = workoutRepo.observeSessions().first()
        val rows = mutableListOf<HistoryRow>()
        sessions.forEach { s ->
            val forThis = workoutRepo.observeExercises(s.id).first().filter { it.exerciseId == exerciseId }
            if (forThis.isNotEmpty()) {
                val sets = forThis.flatMap { workoutRepo.observeSets(it.id).first() }
                if (sets.isNotEmpty()) {
                    rows += HistoryRow(
                        session = s,
                        sets = sets,
                        topSet = sets.filter { it.weightKg != null }.maxByOrNull { it.weightKg ?: 0.0 },
                        e1rm = Calculations.bestEstimatedOneRepMax(sets)
                    )
                }
            }
        }
        history = rows.sortedBy { it.session.startedAt }
        loading = false
    }

    LaunchedEffect(history) {
        if (history.size >= 2) {
            modelProducer.runTransaction {
                lineSeries {
                    series(history.map { (it.topSet?.weightKg ?: 0.0) })
                    series(history.map { it.e1rm ?: 0.0 })
                }
            }
        }
    }

    val shortDate = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
    val mediumDate = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())
    val chartLabels = history.map { shortDate.format(it.session.startedAt) }
    val xAxisFormatter = CartesianValueFormatter { value, _, _ ->
        chartLabels.getOrNull(value.roundToInt()) ?: ""
    }

    val bestWeight = history.mapNotNull { it.topSet?.weightKg }.maxOrNull()
    val bestE1rm = history.mapNotNull { it.e1rm }.maxOrNull()
    val setCount = history.sumOf { it.sets.size }
    val lastAt = history.lastOrNull()?.session?.startedAt

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text(
                    exercise?.name ?: "Exercise",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    buildList {
                        add(exercise?.muscleGroup?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "No muscle group")
                        add(exercise?.equipment?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "No equipment")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (loading) {
            Text("Loading history…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        // Stat cards
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Best weight", bestWeight?.let { formatKg(it) } ?: "—", Modifier.weight(1f))
            StatCard("Best 1RM", bestE1rm?.let { formatKg(it) } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Workouts", history.size.toString(), Modifier.weight(1f))
            StatCard("Sets", setCount.toString(), Modifier.weight(1f))
        }

        Spacer(Modifier.height(20.dp))

        // Chart
        Text("TOP SET & ESTIMATED 1RM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))

        if (history.size < 2) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Perform this exercise in 2 or more workouts to see a trend.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Top set", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(12.dp))
                        LegendDot(MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(4.dp))
                        Text("Est. 1RM", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(8.dp))

                    val yValues = history.flatMap { row ->
                        listOfNotNull(row.topSet?.weightKg, row.e1rm)
                    }
                    val yMin = yValues.minOrNull() ?: 0.0
                    val yMax = yValues.maxOrNull() ?: 1.0
                    val padding = ((yMax - yMin) * 0.2).coerceAtLeast(1.0)

                    CartesianChartHost(
                        chart = rememberCartesianChart(
                            rememberLineCartesianLayer(
                                axisValueOverrider = AxisValueOverrider.fixed(
                                    minX = null,
                                    maxX = null,
                                    minY = yMin - padding,
                                    maxY = yMax + padding
                                )
                            ),
                            startAxis = rememberStartAxis(),
                            bottomAxis = rememberBottomAxis(valueFormatter = xAxisFormatter)
                        ),
                        modelProducer = modelProducer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // History table
        Text("EVERY WORKOUT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))

        if (history.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Not logged yet. Add it to a split day and start a workout.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        HeaderCell("Date", 1.4f)
                        HeaderCell("Sets", 0.7f, TextAlign.Center)
                        HeaderCell("Top set", 1.3f, TextAlign.End)
                        HeaderCell("1RM", 1f, TextAlign.End)
                    }
                    history.sortedByDescending { it.session.startedAt }.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                        val top = row.topSet
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                mediumDate.format(row.session.startedAt),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1.4f)
                            )
                            Text(
                                row.sets.size.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(0.7f)
                            )
                            Text(
                                if (top != null) "${formatNumberPlain(top.weightKg ?: 0.0)} × ${top.reps}" else "—",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1.3f)
                            )
                            Text(
                                row.e1rm?.let { formatKg(it).removeSuffix(" kg") } ?: "—",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun RowScope.HeaderCell(text: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.weight(weight)
    )
}

private fun formatNumberPlain(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else String.format(java.util.Locale.US, "%.1f", value)
