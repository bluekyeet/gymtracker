package com.gymtracker.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.gymtracker.data.db.DatabaseProvider
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.MuscleGroup
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun WearApp() {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.get(context) }
    val exerciseRepo = remember { ExerciseRepository(db) }
    val workoutRepo = remember { WorkoutRepository(db) }
    val scope = rememberCoroutineScope()

    var exercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
    var screen by remember { mutableStateOf("home") }
    var currentWorkoutExerciseId by remember { mutableStateOf<UUID?>(null) }
    var currentExerciseName by remember { mutableStateOf("") }
    var sets by remember { mutableStateOf<List<SetEntry>>(emptyList()) }
    var weight by remember { mutableStateOf(0.0) }
    var reps by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val cur = exerciseRepo.observeAll().first()
        if (cur.isEmpty()) {
            val builtins = listOf(
                "Bench Press" to MuscleGroup.CHEST,
                "Squat" to MuscleGroup.LEGS,
                "Deadlift" to MuscleGroup.BACK
            )
            builtins.forEach { (name, mg) ->
                exerciseRepo.save(Exercise(id = UUID.randomUUID(), name = name, muscleGroup = mg, isCustom = false))
            }
        }
        exercises = exerciseRepo.observeAll().first()
    }

    LaunchedEffect(currentWorkoutExerciseId) {
        val id = currentWorkoutExerciseId
        if (id != null) {
            sets = workoutRepo.observeSets(id).first()
            val last = sets.lastOrNull()
            weight = last?.weightKg ?: 0.0
            reps = last?.reps ?: 0
        }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        if (screen == "home") {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Text("PowerTrack", style = MaterialTheme.typography.title2, textAlign = TextAlign.Center)
                }
                items(exercises.size) { idx ->
                    val ex = exercises[idx]
                    Button(
                        onClick = {
                            scope.launch {
                                val session = WorkoutSession(
                                    id = UUID.randomUUID(),
                                    userId = UUID.randomUUID(),
                                    dayName = ex.name,
                                    startedAt = java.time.Instant.now()
                                )
                                workoutRepo.saveSession(session)
                                val we = WorkoutExercise(
                                    id = UUID.randomUUID(),
                                    sessionId = session.id,
                                    exerciseId = ex.id,
                                    exerciseName = ex.name,
                                    sortKey = 0.0
                                )
                                workoutRepo.saveExercise(we)
                                currentWorkoutExerciseId = we.id
                                currentExerciseName = ex.name
                                screen = "workout"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 6.dp)
                    ) {
                        Text(ex.name, style = MaterialTheme.typography.button)
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(currentExerciseName, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Sets: ${sets.size}", style = MaterialTheme.typography.body2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (weight >= 2.5) weight -= 2.5 }) { Text("-", style = MaterialTheme.typography.title2) }
                    Text("${weight}kg", style = MaterialTheme.typography.body1)
                    Button(onClick = { weight += 2.5 }) { Text("+", style = MaterialTheme.typography.title2) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (reps > 0) reps-- }) { Text("-", style = MaterialTheme.typography.title2) }
                    Text("$reps", style = MaterialTheme.typography.body1)
                    Button(onClick = { reps++ }) { Text("+", style = MaterialTheme.typography.title2) }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val weId = currentWorkoutExerciseId ?: return@launch
                            val newSet = SetEntry(
                                id = UUID.randomUUID(),
                                workoutExerciseId = weId,
                                setNumber = (sets.maxOfOrNull { it.setNumber } ?: 0) + 1,
                                weightKg = weight,
                                reps = reps,
                                setType = SetType.WORKING
                            )
                            workoutRepo.saveSet(newSet)
                            sets = workoutRepo.observeSets(weId).first()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Text("Add Set", style = MaterialTheme.typography.button)
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { screen = "home" },
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Text("Done", style = MaterialTheme.typography.button)
                }
            }
        }
    }
}
