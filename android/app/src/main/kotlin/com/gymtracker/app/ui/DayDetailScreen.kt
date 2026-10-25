package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.shared.domain.SplitDayExercise
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun DayDetailScreen(nav: NavController, splitRepo: SplitRepository, exerciseRepo: ExerciseRepository, dayId: UUID) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    val dayExercises by splitRepo.observeDayExercises(dayId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Exercises for day", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        dayExercises.forEach { de ->
            val ex = exercises.firstOrNull { it.id == de.exerciseId }
            Text("${ex?.name ?: "Exercise"}")
        }
        Spacer(Modifier.height(8.dp))
        var expanded by remember { mutableStateOf(false) }
        Box {
            Button(onClick = { expanded = true }) { Text("Add exercise") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                exercises.forEach { ex ->
                    DropdownMenuItem(text = { Text(ex.name) }, onClick = {
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
                        expanded = false
                    })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = { nav.popBackStack() }) { Text("Back") }
    }
}
