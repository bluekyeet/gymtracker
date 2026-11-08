package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.ExerciseHistoryRow
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.Validation
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.roundToInt

/**
 * One exercise at a time. The bottom bar moves between them and finishes the workout; above it
 * sits what was done the last time this exercise was performed, plus its recent trend.
 */
@Composable
fun WorkoutScreen(
    nav: NavController,
    sessionId: UUID,
    workoutRepo: WorkoutRepository
) {
    val exercises by workoutRepo.observeExercises(sessionId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<WorkoutSession?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf(0) }

    var showUnloggedDialog by remember { mutableStateOf(false) }
    var unloggedCount by remember { mutableStateOf(0) }

    var lastSession by remember { mutableStateOf<Pair<WorkoutSession, List<SetEntry>>?>(null) }
    var history by remember { mutableStateOf<List<ExerciseHistoryRow>>(emptyList()) }

    LaunchedEffect(sessionId) { session = workoutRepo.getSession(sessionId) }

    val current = exercises.getOrNull(index.coerceIn(0, (exercises.size - 1).coerceAtLeast(0)))

    LaunchedEffect(current?.id, session?.startedAt) {
        lastSession = null
        history = emptyList()
        val exerciseId = current?.exerciseId ?: return@LaunchedEffect
        val startedAt = session?.startedAt ?: return@LaunchedEffect
        lastSession = workoutRepo.lastSessionSets(exerciseId, startedAt)
        history = workoutRepo.historyForExercise(exerciseId, excludeSessionId = sessionId)
    }

    fun doFinish() {
        if (finishing) return
        finishing = true
        scope.launch {
            val currentSession = workoutRepo.getSession(sessionId)
            if (currentSession != null && currentSession.endedAt == null) {
                workoutRepo.saveSession(currentSession.copy(endedAt = java.time.Instant.now()))
            }
            nav.popBackStack()
        }
    }

    fun requestFinish() {
        scope.launch {
            var count = 0
            for (ex in exercises) {
                val sets = workoutRepo.observeSets(ex.id).first()
                if (sets.none { it.weightKg > 0.0 }) {
                    count++
                }
            }
            if (count > 0) {
                unloggedCount = count
                showUnloggedDialog = true
            } else {
                doFinish()
            }
        }
    }

    val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    val shortDate = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SubScreenHeader(
            title = session?.dayName ?: "Workout",
            onBack = { nav.popBackStack() },
            actions = {
                TextButton(
                    onClick = { requestFinish() },
                    enabled = session != null && !finishing
                ) {
                    Text("Finish", fontWeight = FontWeight.Bold)
                }
            }
        )

        Text(
            "Started ${session?.startedAt?.let { timeFormat.format(it) } ?: ""} · in progress",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )

        Spacer(Modifier.height(8.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            if (exercises.isEmpty()) {
                Text(
                    "This workout has no exercises. Add them to the split day first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "EXERCISE ${index + 1} OF ${exercises.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    current?.exerciseName ?: "",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))

                current?.let { WorkoutExerciseCard(it, workoutRepo, scope) }

                Spacer(Modifier.height(16.dp))

                LastSessionCard(lastSession, history, shortDate)

                Spacer(Modifier.height(24.dp))
            }
        }

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { index = (index - 1).coerceAtLeast(0) },
                enabled = index > 0,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) { Text("Previous") }
            Button(
                onClick = { index = (index + 1).coerceAtMost(exercises.lastIndex) },
                enabled = index < exercises.lastIndex,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
            ) { Text("Next") }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) { Text("Finish later") }

        if (showUnloggedDialog) {
            AlertDialog(
                onDismissRequest = { showUnloggedDialog = false },
                title = { Text("Finish workout?") },
                text = { Text("You have $unloggedCount exercise(s) with no sets logged. Finish anyway?") },
                confirmButton = {
                    Button(onClick = {
                        showUnloggedDialog = false
                        doFinish()
                    }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("Finish anyway")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showUnloggedDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

/** What was done the last time this exercise was performed, plus how the top set has trended. */
@Composable
private fun LastSessionCard(
    lastSession: Pair<WorkoutSession, List<SetEntry>>?,
    history: List<ExerciseHistoryRow>,
    shortDate: DateTimeFormatter
) {
    Text("LAST TIME", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (lastSession == null) {
                Text(
                    "First time doing this exercise — nothing to compare against yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val (session, sets) = lastSession
                Text(
                    "${shortDate.format(session.startedAt)} · ${sets.size} sets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                sets.forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${s.setNumber}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            "${formatKg(s.weightKg).removeSuffix(" kg")} × ${s.reps}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            val points = history
                .filter { (it.topSet?.weightKg ?: 0.0) > 0.0 }
                .takeLast(8)
            val daily = ChartTime.bestPerDay(points.map { it.session.startedAt to (it.topSet?.weightKg ?: 0.0) })
            if (daily.size < 2) {
                Text(
                    "Log this exercise in 2 or more workouts to see the trend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val modelProducer = remember(daily) { CartesianChartModelProducer() }
                LaunchedEffect(daily) {
                    modelProducer.runTransaction {
                        lineSeries { series(daily.map { it.first }, daily.map { it.second }) }
                    }
                }
                val yValues = points.mapNotNull { it.topSet?.weightKg }
                val yMin = yValues.minOrNull() ?: 0.0
                val yMax = yValues.maxOrNull() ?: 1.0
                val padding = ((yMax - yMin) * 0.2).coerceAtLeast(1.0)

                Text(
                    "TOP SET PER WORKOUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberLineCartesianLayer(
                            axisValueOverrider = AxisValueOverrider.fixed(
                                minX = ChartTime.paddedRange(daily.map { it.first }).first,
                                maxX = ChartTime.paddedRange(daily.map { it.first }).second,
                                minY = yMin - padding,
                                maxY = yMax + padding
                            )
                        ),
                        startAxis = rememberStartAxis(),
                        bottomAxis = rememberBottomAxis(
                            valueFormatter = ChartTime.axisFormatter,
                                guideline = null, // one line per day is just noise
                                tick = null,
                            itemPlacer = remember(daily) { ChartTime.labelPlacer(daily.map { it.first }) }
                        )
                    ),
                    modelProducer = modelProducer,
                        // Fit every point on screen. Left alone, a time axis is wider than the display and
                        // the chart turns into a sideways-scrolling strip showing only its first days.
                        scrollState = rememberVicoScrollState(scrollEnabled = false),
                        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
            }
        }
    }
}

@Composable
private fun WorkoutExerciseCard(
    workoutExercise: WorkoutExercise,
    workoutRepo: WorkoutRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val sets by workoutRepo.observeSets(workoutExercise.id).collectAsState(initial = emptyList())

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            if (sets.isEmpty()) {
                Text(
                    "No sets yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }

            sets.forEach { s ->
                SetRow(set = s, workoutRepo = workoutRepo, scope = scope)
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val last = sets.lastOrNull()
                        workoutRepo.saveSet(
                            SetEntry(
                                id = UUID.randomUUID(),
                                workoutExerciseId = workoutExercise.id,
                                setNumber = (sets.maxOfOrNull { it.setNumber } ?: 0) + 1,
                                weightKg = last?.weightKg ?: 0.0,
                                reps = last?.reps ?: 0,
                                setType = SetType.WORKING
                            )
                        )
                    }
                }) { Text("+ Add set") }
            }
        }
    }
}

@Composable
private fun SetRow(
    set: SetEntry,
    workoutRepo: WorkoutRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var weightText by remember(set.id) { mutableStateOf(formatNumber(set.weightKg)) }
    var repsText by remember(set.id) { mutableStateOf(set.reps.toString()) }

    // Every keystroke used to write the row and queue a sync op ("102.5" = five of each). Edits are
    // now saved once the user pauses, when the field loses focus, or when the row leaves the screen.
    val currentSet by rememberUpdatedState(set)
    val currentWeight by rememberUpdatedState(weightText)
    val currentReps by rememberUpdatedState(repsText)
    val removed = remember(set.id) { booleanArrayOf(false) }

    /** The set with the typed values applied, or null if nothing valid and new has been typed. */
    fun pendingEdit(): SetEntry? {
        if (removed[0]) return null
        val weight = currentWeight.toDoubleOrNull() ?: return null
        val reps = currentReps.toIntOrNull() ?: return null
        if (!Validation.isValidWeightKg(weight) || !Validation.isValidReps(reps)) return null
        if (weight == currentSet.weightKg && reps == currentSet.reps) return null
        return currentSet.copy(weightKg = weight, reps = reps)
    }

    LaunchedEffect(weightText, repsText) {
        delay(SET_EDIT_DEBOUNCE_MS)
        pendingEdit()?.let { workoutRepo.saveSet(it) }
    }
    DisposableEffect(set.id) {
        onDispose { pendingEdit()?.let { edit -> flushScope.launch { workoutRepo.saveSet(edit) } } }
    }

    val weightInvalid = weightText.isNotBlank() &&
        (weightText.toDoubleOrNull()?.let { !Validation.isValidWeightKg(it) } ?: true)
    val repsInvalid = repsText.isNotBlank() &&
        (repsText.toIntOrNull()?.let { !Validation.isValidReps(it) } ?: true)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${set.setNumber}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )
        OutlinedTextField(
            value = weightText,
            onValueChange = { weightText = it },
            label = { Text("kg") },
            isError = weightInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.width(96.dp).onFocusChanged { if (!it.isFocused) pendingEdit()?.let { e -> scope.launch { workoutRepo.saveSet(e) } } }
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = repsText,
            onValueChange = { repsText = it },
            label = { Text("reps") },
            isError = repsInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(96.dp).onFocusChanged { if (!it.isFocused) pendingEdit()?.let { e -> scope.launch { workoutRepo.saveSet(e) } } }
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {
            removed[0] = true // a pending edit must not resurrect the row after it is deleted
            scope.launch { workoutRepo.softDeleteSet(set.id) }
        }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete set ${set.setNumber}")
        }
    }
}

private const val SET_EDIT_DEBOUNCE_MS = 400L

/** Outlives the screen so an edit made just before leaving it is still saved. */
private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
