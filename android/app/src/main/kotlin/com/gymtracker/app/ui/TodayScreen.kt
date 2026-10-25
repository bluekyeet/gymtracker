package com.gymtracker.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.shared.domain.SetEntry
import com.gymtracker.shared.domain.SetType
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.WorkoutExercise
import com.gymtracker.shared.domain.WorkoutSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun TodayScreen(
    nav: NavController,
    splitRepo: SplitRepository,
    workoutRepo: WorkoutRepository,
    exerciseRepo: ExerciseRepository
) {
    val splits by splitRepo.observeAll().collectAsState(initial = emptyList())
    val activeSplit = splits.firstOrNull { it.isActive } ?: splits.firstOrNull()
    val days by (activeSplit?.id?.let { splitRepo.observeDays(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var inProgress by remember { mutableStateOf<WorkoutSession?>(null) }
    var pendingDay by remember { mutableStateOf<SplitDay?>(null) }

    fun refreshInProgress() {
        scope.launch { inProgress = workoutRepo.getInProgress() }
    }

    LaunchedEffect(Unit) { refreshInProgress() }

    val now = LocalDate.now()
    val todayWeekday = now.dayOfWeek.value // 1 = Mon, ..., 7 = Sun
    val dateFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
    val formattedDate = now.format(dateFormatter)

    // Today's scheduled split day
    val todaysDay = days.firstOrNull { it.weekdays.contains(todayWeekday) }

    fun requestStart(day: SplitDay) {
        if (inProgress != null) {
            pendingDay = day
        } else {
            scope.launch { startWorkout(day, splitRepo, workoutRepo, exerciseRepo, nav) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Top Date & Active Split Header
        Text(
            text = formattedDate.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = activeSplit?.name?.uppercase() ?: "NO ACTIVE SPLIT",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(12.dp))

        // In-progress banner
        if (inProgress != null) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "In progress: ${inProgress?.dayName ?: "Workout"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Text(
                            "Started ${inProgress?.startedAt?.atZone(java.time.ZoneId.systemDefault())?.toLocalTime()?.toString()?.take(5) ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Button(onClick = {
                        val current = inProgress ?: return@Button
                        nav.navigate("workout/${current.id}")
                    }) { Text("Resume") }
                }
            }
        }

        // Giant Today's Day Name
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "TODAY'S WORKOUT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = todaysDay?.name ?: if (activeSplit != null) "REST DAY" else "CREATE A SPLIT",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (todaysDay != null) {
            val dayExercises by splitRepo.observeDayExercises(todaysDay.id).collectAsState(initial = emptyList())
            val allExercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())

            if (dayExercises.isNotEmpty()) {
                Text("EXERCISES FOR TODAY", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                dayExercises.forEach { de ->
                    val ex = allExercises.firstOrNull { it.id == de.exerciseId }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Text(
                            text = ex?.name ?: "Exercise",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Giant Start / Resume Workout Button
            Button(
                onClick = {
                    val current = inProgress
                    if (current != null) {
                        nav.navigate("workout/${current.id}")
                    } else {
                        requestStart(todaysDay)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    if (inProgress != null) "RESUME WORKOUT" else "START WORKOUT",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (activeSplit != null) {
            if (inProgress != null) {
                Button(
                    onClick = {
                        val current = inProgress ?: return@Button
                        nav.navigate("workout/${current.id}")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = MaterialTheme.shapes.large
                ) { Text("RESUME WORKOUT", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                Text("Rest day — nothing scheduled for today.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("No workout scheduled for today.", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
        } else {
            Button(
                onClick = { nav.navigate("splits") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go to Splits to Create One")
            }
            Spacer(Modifier.height(16.dp))
        }

        // Other Days / Start Another Day
        if (days.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("ALL SPLIT DAYS", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            days.forEach { day ->
                val isToday = day.id == todaysDay?.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(day.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = day.weekdays.map { WEEKDAYS[it - 1] }.joinToString(", ").ifEmpty { "Unassigned" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = { requestStart(day) }) {
                            Text(if (isToday) "Start" else "Start Day")
                        }
                    }
                }
            }
        }
    }

    // Only one workout at a time.
    val pending = pendingDay
    if (pending != null && inProgress != null) {
        val current = inProgress
        AlertDialog(
            onDismissRequest = { pendingDay = null },
            title = { Text("Finish ${current?.dayName ?: "this workout"} first?") },
            text = { Text("You already have a workout in progress. Only one workout can run at a time.") },
            confirmButton = {
                Button(onClick = {
                    pendingDay = null
                    scope.launch {
                        if (current != null && current.endedAt == null) {
                            workoutRepo.saveSession(current.copy(endedAt = java.time.Instant.now()))
                        }
                        startWorkout(pending, splitRepo, workoutRepo, exerciseRepo, nav)
                    }
                }) { Text("Finish & start") }
            },
            dismissButton = {
                Row {
                    OutlinedButton(onClick = { pendingDay = null }) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        pendingDay = null
                        if (current != null) nav.navigate("workout/${current.id}")
                    }) { Text("Resume") }
                }
            }
        )
    }
}

private suspend fun startWorkout(
    day: SplitDay,
    splitRepo: SplitRepository,
    workoutRepo: WorkoutRepository,
    exerciseRepo: ExerciseRepository,
    nav: NavController
) {
    val session = WorkoutSession(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        splitDayId = day.id,
        dayName = day.name,
        startedAt = java.time.Instant.now()
    )
    workoutRepo.saveSession(session)
    val splitExercises = splitRepo.observeDayExercises(day.id).first()
    splitExercises.forEach { se ->
        val ex = se.exerciseId?.let { exerciseRepo.getById(it) }
        val workoutExerciseId = UUID.randomUUID()
        workoutRepo.saveExercise(
            WorkoutExercise(
                id = workoutExerciseId,
                sessionId = session.id,
                exerciseId = se.exerciseId,
                exerciseName = ex?.name ?: "Exercise",
                sortKey = se.sortKey
            )
        )
        // Pre-fill the recommended sets so the workout opens ready to log.
        val targetSets = se.targetSets
        if (targetSets != null && targetSets in 1..50) {
            repeat(targetSets) { index ->
                workoutRepo.saveSet(
                    SetEntry(
                        id = UUID.randomUUID(),
                        workoutExerciseId = workoutExerciseId,
                        setNumber = index + 1,
                        weightKg = 0.0,
                        reps = se.targetReps ?: 0,
                        setType = SetType.WORKING
                    )
                )
            }
        }
    }
    nav.navigate("workout/${session.id}")
}
