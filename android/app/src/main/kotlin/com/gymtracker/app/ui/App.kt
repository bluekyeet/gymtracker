package com.gymtracker.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.gymtracker.data.sync.SyncScheduler

@Composable
fun App() {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.get(context) }
    val exerciseRepo = remember { ExerciseRepository(db) }
    val splitRepo = remember { SplitRepository(db) }
    val workoutRepo = remember { WorkoutRepository(db) }
    val nav = rememberNavController()

    LaunchedEffect(Unit) { exerciseRepo.seedBuiltinsIfNeeded() }
    LaunchedEffect(Unit) {
        SyncScheduler.schedulePeriodic(context)
        SyncScheduler.syncNow(context)
        // Phone only: sync the instant the internet comes back (chat-app style), not just every
        // 15 minutes. The watch deliberately does not do this — battery matters more there.
        SyncScheduler.watchForConnectivity(context)
    }
    // Sync every time the app is brought back to the foreground, not just on cold launch.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) SyncScheduler.syncNow(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val items = listOf(
        "today" to Icons.Filled.FitnessCenter,
        "splits" to Icons.Filled.List,
        "exercises" to Icons.Filled.Person,
        "history" to Icons.Filled.History,
        "progress" to Icons.Filled.ShowChart,
        "settings" to Icons.Filled.Settings
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by nav.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { (route, icon) ->
                    val selected = currentDestination?.hierarchy?.any { it.route == route } == true
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.18f else 1f,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 500f),
                        label = "navigation icon scale"
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                icon,
                                contentDescription = route,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                            )
                        },
                        label = { Text(route.replaceFirstChar { it.uppercase() }) },
                        selected = selected,
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
            NavHost(
                navController = nav,
                startDestination = "today",
                enterTransition = {
                    fadeIn(tween(220)) + slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.Start,
                        tween(280)
                    )
                },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = {
                    fadeIn(tween(220)) + slideIntoContainer(
                        AnimatedContentTransitionScope.SlideDirection.End,
                        tween(280)
                    )
                },
                popExitTransition = { fadeOut(tween(140)) }
            ) {
                composable("today") { TodayScreen(nav, splitRepo, workoutRepo, exerciseRepo) }
                composable("splits") { SplitsScreen(nav, splitRepo, exerciseRepo) }
                composable("exercises") { ExercisesScreen(nav, exerciseRepo) }
                composable("history") { HistoryScreen(nav, workoutRepo) }
                composable("progress") { ProgressScreen(nav, workoutRepo, exerciseRepo) }
                composable("settings") { SettingsScreen(nav) }
                composable("scan_device") { QrScannerScreen(nav) }
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
                composable("workout_detail/{sessionId}") { back ->
                    val id = back.arguments?.getString("sessionId") ?: return@composable
                    WorkoutDetailScreen(nav, java.util.UUID.fromString(id), workoutRepo)
                }
                composable("exercise/{exerciseId}") { back ->
                    val id = back.arguments?.getString("exerciseId") ?: return@composable
                    ExerciseDetailScreen(nav, java.util.UUID.fromString(id), workoutRepo, exerciseRepo)
                }
            }
        }
    }
}
