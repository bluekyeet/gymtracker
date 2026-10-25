package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun WorkoutScreen(
    nav: NavController,
    sessionId: UUID,
    workoutRepo: WorkoutRepository,
    exerciseRepo: ExerciseRepository
) {
    val exercises by workoutRepo.observeExercises(sessionId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Workout", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        exercises.forEach { we ->
            val sets by workoutRepo.observeSets(we.id).collectAsState(initial = emptyList())
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(we.exerciseName, style = MaterialTheme.typography.titleMedium)
                    sets.forEach { s ->
                        Text("Set ${s.setNumber}: ${s.weightKg} kg x ${s.reps}")
                    }
                    Button(onClick = {
                        scope.launch {
                            val last = sets.lastOrNull()
                            val newSet = SetEntry(
                                id = UUID.randomUUID(),
                                workoutExerciseId = we.id,
                                setNumber = (sets.maxOfOrNull { it.setNumber } ?: 0) + 1,
                                weightKg = last?.weightKg ?: 0.0,
                                reps = last?.reps ?: 0,
                                setType = SetType.WORKING
                            )
                            workoutRepo.saveSet(newSet)
                        }
                    }) { Text("Add set") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Done") }
    }
}
