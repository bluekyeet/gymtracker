package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.Calculations
import com.gymtracker.shared.domain.SetEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ProgressScreen(nav: NavController, workoutRepo: WorkoutRepository, exerciseRepo: ExerciseRepository) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<UUID?>(null) }
    var prText by remember { mutableStateOf("Select an exercise") }

    LaunchedEffect(selected) {
        val exId = selected ?: return@LaunchedEffect
        scope.launch {
            val sessions = workoutRepo.observeSessions().first()
            val allSets = mutableListOf<SetEntry>()
            sessions.forEach { s ->
                val wes = workoutRepo.observeExercises(s.id).first()
                wes.forEach { we ->
                    if (we.exerciseId == exId) {
                        allSets += workoutRepo.observeSets(we.id).first()
                    }
                }
            }
            val bestWeight = allSets.mapNotNull { it.weightKg }.maxOrNull()
            val bestE1rm = Calculations.bestEstimatedOneRepMax(allSets)
            prText = "Best weight: ${bestWeight ?: 0.0} kg\nBest est. 1RM: ${bestE1rm ?: 0.0}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Progress", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        exercises.forEach { ex ->
            TextButton(onClick = { selected = ex.id }) { Text(ex.name) }
        }
        Spacer(Modifier.height(8.dp))
        Text(prText)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
