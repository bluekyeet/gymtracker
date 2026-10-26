package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Read-only summary of a completed workout: what was done, nothing editable.
 * Separate from WorkoutScreen, which is only for a workout still in progress.
 */
@Composable
fun WorkoutDetailScreen(nav: NavController, sessionId: UUID, workoutRepo: WorkoutRepository) {
    val exercises by workoutRepo.observeExercises(sessionId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<WorkoutSession?>(null) }
    var setsByExercise by remember { mutableStateOf<Map<UUID, List<SetEntry>>>(emptyMap()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { session = workoutRepo.getSession(sessionId) }
    LaunchedEffect(exercises) {
        setsByExercise = exercises.associate { it.id to workoutRepo.observeSets(it.id).first() }
    }

    val dateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy").withZone(ZoneId.systemDefault())
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SubScreenHeader(
            title = session?.dayName ?: "Workout",
            onBack = { nav.popBackStack() },
            actions = {
                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete workout", tint = MaterialTheme.colorScheme.error)
                }
            }
        )

        session?.let { s ->
            val minutes = s.endedAt?.let { Duration.between(s.startedAt, it).toMinutes() }
            Text(
                "${dateFormatter.format(s.startedAt)} · ${timeFormatter.format(s.startedAt)}" +
                    (minutes?.let { " · Completed · $it min" } ?: " · In progress"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(12.dp))

        if (exercises.isEmpty()) {
            Text(
                "No exercises were logged for this workout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                exercises.forEach { ex ->
                    ReadOnlyExerciseCard(ex, setsByExercise[ex.id].orEmpty())
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this workout?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        scope.launch {
                            workoutRepo.softDeleteSession(sessionId)
                            nav.popBackStack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ReadOnlyExerciseCard(exercise: WorkoutExercise, sets: List<SetEntry>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                exercise.exerciseName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            if (sets.isEmpty()) {
                Text(
                    "No sets logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                sets.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${s.setNumber}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(24.dp)
                        )
                        Text(
                            "${formatKg(s.weightKg)} × ${s.reps}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
