package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.MuscleGroup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ExercisesScreen(nav: NavController, exerciseRepo: ExerciseRepository) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val current = exerciseRepo.observeAll().first()
        if (current.isEmpty()) {
            val builtins = listOf(
                "Bench Press" to MuscleGroup.CHEST,
                "Squat" to MuscleGroup.LEGS,
                "Deadlift" to MuscleGroup.BACK,
                "Overhead Press" to MuscleGroup.SHOULDERS,
                "Barbell Row" to MuscleGroup.BACK
            )
            builtins.forEach { (name, mg) ->
                exerciseRepo.save(Exercise(id = UUID.randomUUID(), name = name, muscleGroup = mg, isCustom = false))
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Exercises", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("New exercise") })
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (newName.isNotBlank()) {
                    scope.launch {
                        exerciseRepo.save(Exercise(id = UUID.randomUUID(), name = newName, isCustom = true))
                        newName = ""
                    }
                }
            }) { Text("Add") }
        }
        Spacer(Modifier.height(8.dp))
        exercises.forEach { ex ->
            Text("${ex.name} (${ex.muscleGroup})")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
