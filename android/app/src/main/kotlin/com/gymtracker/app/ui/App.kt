package com.gymtracker.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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

    LaunchedEffect(Unit) { exerciseRepo.seedBuiltinsIfNeeded() }
    val items = listOf(
        "today" to Icons.Filled.FitnessCenter,
        "splits" to Icons.Filled.ViewList,
        "exercises" to Icons.Filled.Person,
        "history" to Icons.Filled.History,
        "progress" to Icons.Filled.ShowChart
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by nav.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { (route, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = route) },
                        label = { Text(route.replaceFirstChar { it.uppercase() }) },
                        selected = currentDestination?.hierarchy?.any { it.route == route } == true,
                        onClick = {
                            nav.navigate(route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            NavHost(navController = nav, startDestination = "today") {
                composable("today") { TodayScreen(nav, splitRepo, workoutRepo, exerciseRepo) }
                composable("splits") { SplitsScreen(nav, splitRepo, exerciseRepo) }
                composable("exercises") { ExercisesScreen(exerciseRepo) }
                composable("history") { HistoryScreen(nav, workoutRepo) }
                composable("progress") { ProgressScreen(nav, workoutRepo, exerciseRepo) }
                composable("split/{splitId}") { back ->
                    val id = back.arguments?.getString("splitId") ?: return@composable
                    SplitDetailScreen(nav, splitRepo, java.util.UUID.fromString(id))
                }
                composable("day/{dayId}") { back ->
                    val id = back.arguments?.getString("dayId") ?: return@composable
                    DayDetailScreen(nav, splitRepo, exerciseRepo, java.util.UUID.fromString(id))
                }
                composable("workout/{sessionId}") { back ->
                    val id = back.arguments?.getString("sessionId") ?: return@composable
                    WorkoutScreen(nav, java.util.UUID.fromString(id), workoutRepo)
                }
            }
        }
    }
}
