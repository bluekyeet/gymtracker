package com.gymtracker.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.gymtracker.data.db.DatabaseProvider
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.WorkoutRepository

@Composable
fun App() {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.get(context) }
    val exerciseRepo = remember { ExerciseRepository(db) }
    val splitRepo = remember { SplitRepository(db) }
    val workoutRepo = remember { WorkoutRepository(db) }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "today") {
        composable("today") { TodayScreen(nav, splitRepo, workoutRepo, exerciseRepo) }
        composable("splits") { SplitsScreen(nav, splitRepo, exerciseRepo) }
        composable("exercises") { ExercisesScreen(nav, exerciseRepo) }
        composable("history") { HistoryScreen(nav, workoutRepo) }
        composable("progress") { ProgressScreen(nav, workoutRepo, exerciseRepo) }
        composable("workout/{sessionId}") { back ->
            val id = back.arguments?.getString("sessionId") ?: return@composable
            WorkoutScreen(nav, java.util.UUID.fromString(id), workoutRepo, exerciseRepo)
        }
    }
}
