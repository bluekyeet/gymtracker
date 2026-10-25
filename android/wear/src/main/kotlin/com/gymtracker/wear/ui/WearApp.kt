package com.gymtracker.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.gymtracker.data.db.DatabaseProvider
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.WorkoutRepository
import androidx.wear.compose.material.Text

@Composable
fun WearApp() {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.get(context) }
    val splitRepo = remember { SplitRepository(db) }
    val workoutRepo = remember { WorkoutRepository(db) }
    val exerciseRepo = remember { ExerciseRepository(db) }
    Text("GymTracker Watch")
}
