package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.SplitDayExercise
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun DayDetailScreen(
    nav: NavController,
    splitRepo: SplitRepository,
    exerciseRepo: ExerciseRepository,
    dayId: UUID
) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    val dayExercises by splitRepo.observeDayExercises(dayId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var newExerciseName by remember { mutableStateOf("") }
    var toRemove by remember { mutableStateOf<SplitDayExercise?>(null) }

    // Exercises already added to this day, in order, with their library entry.
    val rows = dayExercises.mapNotNull { de ->
        exercises.firstOrNull { it.id == de.exerciseId }?.let { de to it } ?: de to null
    }
    // Library entries not yet on this day.
    val available = exercises.filter { ex -> dayExercises.none { it.exerciseId == ex.id } }

    fun addExercise(ex: Exercise) {
        scope.launch {
            splitRepo.saveDayExercise(
                SplitDayExercise(
                    id = UUID.randomUUID(),
                    splitDayId = dayId,
                    exerciseId = ex.id,
                    sortKey = dayExercises.size.toDouble()
                )
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Day Exercises", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Exercises run in this order when you start the workout.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        rows.forEachIndexed { index, (de, ex) ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(32.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        Text(ex?.name ?: "Unknown exercise", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(ex?.muscleGroup?.name ?: "Removed from library", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { toRemove = de }) { Text("Remove") }
                }
            }
        }

        if (rows.isEmpty()) {
            Text(
                "No exercises yet. Add one below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))

        Box {
            Button(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Add from library") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (available.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Nothing left to add") },
                        onClick = { menuOpen = false }
                    )
                }
                available.forEach { ex ->
                    DropdownMenuItem(
                        text = { Text(ex.name) },
                        onClick = {
                            addExercise(ex)
                            menuOpen = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Or create a new one", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newExerciseName,
                onValueChange = { newExerciseName = it },
                label = { Text("New exercise") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val name = newExerciseName.trim()
                if (name.isNotBlank()) {
                    scope.launch {
                        val existing = exercises.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        val created = existing ?: Exercise(id = UUID.randomUUID(), name = name, isCustom = true)
                            .also { exerciseRepo.save(it) }
                        splitRepo.saveDayExercise(
                            SplitDayExercise(
                                id = UUID.randomUUID(),
                                splitDayId = dayId,
                                exerciseId = created.id,
                                sortKey = dayExercises.size.toDouble()
                            )
                        )
                    }
                    newExerciseName = ""
                }
            }) { Text("Add") }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    if (toRemove != null) {
        val name = rows.firstOrNull { it.first == toRemove }?.second?.name ?: "this exercise"
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove exercise?") },
            text = { Text("Remove \"$name\" from this day? The exercise stays in your library.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = toRemove
                        toRemove = null
                        if (target != null) {
                            scope.launch { splitRepo.softDeleteDayExercise(target.id) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = { OutlinedButton(onClick = { toRemove = null }) { Text("Cancel") } }
        )
    }
}
