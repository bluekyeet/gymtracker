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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.Validation
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

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

    LaunchedEffect(sessionId) { session = workoutRepo.getSession(sessionId) }

    val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(session?.dayName ?: "Workout", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Started ${session?.startedAt?.let { timeFormat.format(it) } ?: ""} · in progress",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.height(16.dp))

        if (exercises.isEmpty()) {
            Text(
                "This workout has no exercises. Add them to the split day first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        exercises.forEach { we ->
            WorkoutExerciseCard(
                workoutExercise = we,
                workoutRepo = workoutRepo,
                scope = scope
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (!finishing) {
                    finishing = true
                    scope.launch {
                        val current = workoutRepo.getSession(sessionId)
                        if (current != null && current.endedAt == null) {
                            workoutRepo.saveSession(current.copy(endedAt = java.time.Instant.now()))
                        }
                        nav.popBackStack()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = session != null
        ) {
            Text(if (finishing) "Finishing…" else "Finish workout", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { nav.popBackStack() },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue later") }
    }
}

@Composable
private fun WorkoutExerciseCard(
    workoutExercise: WorkoutExercise,
    workoutRepo: WorkoutRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val sets by workoutRepo.observeSets(workoutExercise.id).collectAsState(initial = emptyList())

    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(workoutExercise.exerciseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

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

    fun commit() {
        val weight = weightText.toDoubleOrNull()
        val reps = repsText.toIntOrNull()
        if (weight == null || !Validation.isValidWeightKg(weight)) return
        if (reps == null || !Validation.isValidReps(reps)) return
        scope.launch { workoutRepo.saveSet(set.copy(weightKg = weight, reps = reps)) }
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
            onValueChange = {
                weightText = it
                commit()
            },
            label = { Text("kg") },
            isError = weightInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = repsText,
            onValueChange = {
                repsText = it
                commit()
            },
            label = { Text("reps") },
            isError = repsInvalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = {
            scope.launch { workoutRepo.softDeleteSet(set.id) }
        }) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete set ${set.setNumber}")
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
