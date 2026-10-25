package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

@Composable
fun TodayScreen(
    nav: NavController,
    splitRepo: SplitRepository,
    workoutRepo: WorkoutRepository,
    exerciseRepo: com.gymtracker.data.repository.ExerciseRepository
) {
    val splits by splitRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedSplitId by remember { mutableStateOf<UUID?>(null) }
    val activeSplit = splits.firstOrNull { it.isActive } ?: splits.firstOrNull()
    val splitId = selectedSplitId ?: activeSplit?.id
    val days by (splitId?.let { splitRepo.observeDays(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Today", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { nav.navigate("splits") }) { Text("Splits") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { nav.navigate("exercises") }) { Text("Exercises") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { nav.navigate("history") }) { Text("History") }
        }
        Spacer(Modifier.height(16.dp))
        Text("Active split: ${activeSplit?.name ?: "None"}")
        Spacer(Modifier.height(8.dp))
        days.forEach { day ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(day.name, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        scope.launch {
                            val session = WorkoutSession(
                                id = UUID.randomUUID(),
                                userId = UUID.randomUUID(),
                                splitDayId = day.id,
                                dayName = day.name,
                                startedAt = java.time.Instant.now()
                            )
                            workoutRepo.saveSession(session)
                            // copy exercises
                            val splitExercises = splitRepo.observeDayExercises(day.id).first()
                            // need to fetch synchronously; use first
                            splitExercises.forEachIndexed { idx, se ->
                                // resolve exercise name
                                val ex = se.exerciseId?.let { exerciseRepo.getById(it) }
                                workoutRepo.saveExercise(
                                    com.gymtracker.shared.domain.WorkoutExercise(
                                        id = UUID.randomUUID(),
                                        sessionId = session.id,
                                        exerciseId = se.exerciseId,
                                        exerciseName = ex?.name ?: "Exercise",
                                        sortKey = se.sortKey
                                    )
                                )
                            }
                            nav.navigate("workout/${session.id}")
                        }
                    }) { Text("Start workout") }
                }
            }
        }
        if (days.isEmpty()) {
            Text("No days. Create a split first.")
        }
    }
}

private fun Double.ifNaN(): Double? = if (isNaN()) null else this
