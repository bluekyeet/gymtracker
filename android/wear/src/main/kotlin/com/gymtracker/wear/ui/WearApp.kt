package com.gymtracker.wear.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.gymtracker.data.db.DatabaseProvider
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.data.repository.WorkoutRepository
import com.gymtracker.data.sync.ServerClient
import com.gymtracker.data.sync.SyncManager
import com.gymtracker.data.sync.SyncResult
import com.gymtracker.shared.domain.*
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun WearApp() {
    val context = LocalContext.current
    val db = remember { DatabaseProvider.get(context) }
    val exerciseRepo = remember { ExerciseRepository(db) }
    val splitRepo = remember { SplitRepository(db) }
    val workoutRepo = remember { WorkoutRepository(db) }
    val serverClient = remember { ServerClient(context, db) }
    val syncManager = remember { SyncManager(context, db) }
    val scope = rememberCoroutineScope()

    val httpClient = remember {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    coerceInputValues = true
                })
            }
        }
    }

    // Screen navigation stack: hardware back button / swipe gesture pops screen, closes only on root "home"
    var screenStack by remember { mutableStateOf(listOf("home")) }
    val currentScreen = screenStack.last()

    fun navigateTo(screen: String) {
        screenStack = screenStack + screen
    }

    fun navigateBack() {
        if (screenStack.size > 1) {
            screenStack = screenStack.dropLast(1)
        }
    }

    // Hardware back navigation handler
    BackHandler(enabled = screenStack.size > 1) {
        navigateBack()
    }

    // Screen specific states
    var pickMode by remember { mutableStateOf("add") } // "add" or "swap"
    var selectedSplitId by remember { mutableStateOf<UUID?>(null) }
    var selectedSplitDayId by remember { mutableStateOf<UUID?>(null) }

    // Active session state
    var activeSession by remember { mutableStateOf<WorkoutSession?>(null) }
    var sessionExercises by remember { mutableStateOf<List<WorkoutExercise>>(emptyList()) }
    var currentExerciseIndex by remember { mutableIntStateOf(0) }
    val currentExercise = sessionExercises.getOrNull(currentExerciseIndex)

    // Current exercise sets & input steppers
    var currentSets by remember { mutableStateOf<List<SetEntry>>(emptyList()) }
    var weightInput by remember { mutableDoubleStateOf(60.0) }
    var repsInput by remember { mutableIntStateOf(8) }

    // Rest timer state
    var restSecondsLeft by remember { mutableIntStateOf(60) }
    var restTimerRunning by remember { mutableStateOf(false) }

    // Sync state
    var isSyncing by remember { mutableStateOf(false) }
    var syncStatusMessage by remember { mutableStateOf<String?>(null) }
    var lastSyncTime by remember { mutableStateOf(serverClient.lastSyncTime) }

    fun triggerSync() {
        if (isSyncing) return
        scope.launch {
            isSyncing = true
            syncStatusMessage = "Syncing..."
            when (val res = syncManager.performFullSync(serverClient, httpClient)) {
                is SyncResult.Success -> {
                    syncStatusMessage = "Sync complete"
                    lastSyncTime = serverClient.lastSyncTime
                }
                is SyncResult.NetworkError -> {
                    syncStatusMessage = "Server unavailable"
                }
                is SyncResult.AuthError -> {
                    syncStatusMessage = "Auth expired"
                }
                is SyncResult.OtherError -> {
                    syncStatusMessage = res.message.take(20)
                }
            }
            isSyncing = false
        }
    }

    // Seed built-in exercises, load in-progress session on start, and auto-sync
    LaunchedEffect(Unit) {
        exerciseRepo.seedBuiltinsIfNeeded()
        val inProgress = workoutRepo.getInProgress()
        if (inProgress != null) {
            activeSession = inProgress
            val exList = workoutRepo.observeExercises(inProgress.id).first()
            sessionExercises = exList
            if (exList.isNotEmpty()) {
                currentExerciseIndex = 0
            }
        }
        if (serverClient.serverUrl.isNotBlank()) {
            triggerSync()
        }
    }

    // Watch for set changes when current exercise changes
    LaunchedEffect(currentExercise?.id) {
        val exId = currentExercise?.id
        if (exId != null) {
            val sets = workoutRepo.observeSets(exId).first()
            currentSets = sets
            val last = sets.lastOrNull()
            if (last != null) {
                weightInput = last.weightKg
                repsInput = last.reps
            }
        } else {
            currentSets = emptyList()
        }
    }

    // Rest timer countdown
    LaunchedEffect(restTimerRunning, restSecondsLeft) {
        if (restTimerRunning && restSecondsLeft > 0) {
            delay(1000L)
            restSecondsLeft--
            if (restSecondsLeft == 0) {
                restTimerRunning = false
                triggerHapticBuzz(context)
            }
        }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) {
        when (currentScreen) {
            "home" -> HomeScreen(
                activeSession = activeSession,
                splitRepo = splitRepo,
                exerciseRepo = exerciseRepo,
                isSyncing = isSyncing,
                syncStatusMessage = syncStatusMessage,
                lastSyncTime = lastSyncTime,
                onSync = { triggerSync() },
                onResumeWorkout = {
                    navigateTo("workout")
                },
                onStartWorkout = { dayName, exercises ->
                    scope.launch {
                        val session = WorkoutSession(
                            id = UUID.randomUUID(),
                            userId = UUID.randomUUID(),
                            dayName = dayName,
                            startedAt = Instant.now()
                        )
                        workoutRepo.saveSession(session)
                        exercises.forEachIndexed { idx, ex ->
                            workoutRepo.saveExercise(
                                WorkoutExercise(
                                    id = UUID.randomUUID(),
                                    sessionId = session.id,
                                    exerciseId = ex.id,
                                    exerciseName = ex.name,
                                    sortKey = idx.toDouble()
                                )
                            )
                        }
                        activeSession = session
                        val exList = workoutRepo.observeExercises(session.id).first()
                        sessionExercises = exList
                        currentExerciseIndex = 0
                        navigateTo("workout")
                    }
                },
                onOpenEditSplits = {
                    navigateTo("edit_splits")
                },
                onOpenSettings = {
                    navigateTo("settings")
                }
            )

            "workout" -> {
                if (currentExercise == null) {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        autoCentering = null,
                        contentPadding = PaddingValues(top = 28.dp, bottom = 36.dp, start = 12.dp, end = 12.dp)
                    ) {
                        item {
                            Text("No Exercises", style = MaterialTheme.typography.title3)
                        }
                        item {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                pickMode = "add"
                                navigateTo("pick_exercise")
                            }) {
                                Text("+ Add Exercise")
                            }
                        }
                    }
                } else {
                    WorkoutLoggingScreen(
                        exercise = currentExercise,
                        exerciseIndex = currentExerciseIndex,
                        totalExercises = sessionExercises.size,
                        sets = currentSets,
                        weight = weightInput,
                        reps = repsInput,
                        onWeightChange = { weightInput = (it * 10.0).coerceAtLeast(0.0).let { Math.round(it) / 10.0 } },
                        onRepsChange = { repsInput = it.coerceAtLeast(0) },
                        onPrevExercise = {
                            if (currentExerciseIndex > 0) currentExerciseIndex--
                        },
                        onNextExercise = {
                            if (currentExerciseIndex < sessionExercises.size - 1) currentExerciseIndex++
                        },
                        onAddSet = {
                            scope.launch {
                                val weId = currentExercise.id
                                val newSet = SetEntry(
                                    id = UUID.randomUUID(),
                                    workoutExerciseId = weId,
                                    setNumber = (currentSets.maxOfOrNull { it.setNumber } ?: 0) + 1,
                                    weightKg = weightInput,
                                    reps = repsInput,
                                    setType = SetType.WORKING
                                )
                                workoutRepo.saveSet(newSet)
                                currentSets = workoutRepo.observeSets(weId).first()
                                restSecondsLeft = 60
                                restTimerRunning = true
                                navigateTo("rest_timer")
                            }
                        },
                        onAddExercise = {
                            pickMode = "add"
                            navigateTo("pick_exercise")
                        },
                        onSwapExercise = {
                            pickMode = "swap"
                            navigateTo("pick_exercise")
                        },
                        onRemoveExercise = {
                            scope.launch {
                                workoutRepo.softDeleteExercise(currentExercise.id)
                                val updated = workoutRepo.observeExercises(activeSession!!.id).first()
                                sessionExercises = updated
                                if (currentExerciseIndex >= updated.size) {
                                    currentExerciseIndex = (updated.size - 1).coerceAtLeast(0)
                                }
                            }
                        },
                        onFinishWorkout = {
                            scope.launch {
                                val session = activeSession
                                if (session != null) {
                                    workoutRepo.saveSession(session.copy(endedAt = Instant.now()))
                                    activeSession = null
                                    sessionExercises = emptyList()
                                }
                                navigateBack()
                                if (serverClient.serverUrl.isNotBlank()) {
                                    triggerSync()
                                }
                            }
                        }
                    )
                }
            }

            "rest_timer" -> RestTimerScreen(
                secondsLeft = restSecondsLeft,
                onAdjustTime = { delta ->
                    restSecondsLeft = (restSecondsLeft + delta).coerceAtLeast(5)
                },
                onSkip = {
                    restTimerRunning = false
                    navigateBack()
                }
            )

            "pick_exercise" -> ExercisePickerScreen(
                exerciseRepo = exerciseRepo,
                onSelectExercise = { selected ->
                    scope.launch {
                        val session = activeSession ?: return@launch
                        if (pickMode == "swap" && currentExercise != null) {
                            val swapped = currentExercise.copy(
                                exerciseId = selected.id,
                                exerciseName = selected.name
                            )
                            workoutRepo.saveExercise(swapped)
                        } else {
                            val newWe = WorkoutExercise(
                                id = UUID.randomUUID(),
                                sessionId = session.id,
                                exerciseId = selected.id,
                                exerciseName = selected.name,
                                sortKey = sessionExercises.size.toDouble()
                            )
                            workoutRepo.saveExercise(newWe)
                        }
                        val updated = workoutRepo.observeExercises(session.id).first()
                        sessionExercises = updated
                        if (pickMode == "add") {
                            currentExerciseIndex = updated.size - 1
                        }
                        navigateBack()
                    }
                }
            )

            "edit_splits" -> EditSplitsScreen(
                splitRepo = splitRepo,
                onSelectSplit = { splitId ->
                    selectedSplitId = splitId
                    navigateTo("split_days")
                }
            )

            "split_days" -> SplitDaysScreen(
                splitId = selectedSplitId,
                splitRepo = splitRepo,
                onSelectDay = { dayId ->
                    selectedSplitDayId = dayId
                    navigateTo("day_exercises")
                }
            )

            "day_exercises" -> DayExercisesScreen(
                dayId = selectedSplitDayId,
                splitRepo = splitRepo,
                exerciseRepo = exerciseRepo
            )

            "settings" -> WatchSettingsScreen(
                serverClient = serverClient,
                httpClient = httpClient,
                onGenerateQr = {
                    navigateTo("qr_pairing")
                }
            )

            "qr_pairing" -> QrPairingScreen(
                serverClient = serverClient
            )
        }
    }
}

@Composable
fun HomeScreen(
    activeSession: WorkoutSession?,
    splitRepo: SplitRepository,
    exerciseRepo: ExerciseRepository,
    isSyncing: Boolean,
    syncStatusMessage: String?,
    lastSyncTime: String?,
    onSync: () -> Unit,
    onResumeWorkout: () -> Unit,
    onStartWorkout: (String, List<Exercise>) -> Unit,
    onOpenEditSplits: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var activeSplit by remember { mutableStateOf<Split?>(null) }
    var activeSplitDays by remember { mutableStateOf<List<SplitDay>>(emptyList()) }
    var allExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
    var todayDayExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }

    val todayDate = remember {
        val now = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("EEE, MMM d")
        now.format(formatter).uppercase()
    }

    val todayWeekday = remember { LocalDate.now().dayOfWeek.value } // 1 = Mon ... 7 = Sun

    LaunchedEffect(Unit) {
        val split = splitRepo.getActive() ?: splitRepo.observeAll().first().firstOrNull()
        activeSplit = split
        if (split != null) {
            val days = splitRepo.observeDays(split.id).first()
            activeSplitDays = days
            val todayDay = days.firstOrNull { it.weekdays.contains(todayWeekday) } ?: days.firstOrNull()
            if (todayDay != null) {
                val des = splitRepo.observeDayExercises(todayDay.id).first()
                todayDayExercises = des.mapNotNull { it.exerciseId?.let { id -> exerciseRepo.getById(id) } }
            }
        }
        allExercises = exerciseRepo.observeAll().first()
    }

    val todayScheduledDay = activeSplitDays.firstOrNull { it.weekdays.contains(todayWeekday) } ?: activeSplitDays.firstOrNull()

    // autoCentering = null and small top padding pulls the date and POWERTRACK right under TimeText
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 26.dp, bottom = 36.dp, start = 10.dp, end = 10.dp)
    ) {
        // Date & Profile Header (tightly positioned right under the top curved time)
        item {
            Text(
                todayDate,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        item {
            Text(
                "POWERTRACK",
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        // ========================================================
        // TODAY'S WORKOUT CARD (SEPARATE STATUS AND WORKOUT NAME)
        // ========================================================
        item {
            Spacer(Modifier.height(4.dp))
            Card(
                onClick = {
                    if (activeSession != null) {
                        onResumeWorkout()
                    } else if (todayScheduledDay != null) {
                        onStartWorkout(todayScheduledDay.name, todayDayExercises.ifEmpty { allExercises.take(3) })
                    } else {
                        onStartWorkout("Quick Workout", allExercises.take(3))
                    }
                },
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 1. Small prominent section heading
                    Text(
                        "TODAY'S WORKOUT",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(2.dp))

                    // 2. Status on its own line using smaller, secondary text
                    Text(
                        text = if (activeSession != null) "In Progress" else "Scheduled",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    // 3. Workout or exercise name beneath it using larger, bolder text (wraps naturally)
                    Text(
                        text = (if (activeSession != null) activeSession.dayName else todayScheduledDay?.name) ?: "Workout",
                        style = MaterialTheme.typography.title2,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (todayDayExercises.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = todayDayExercises.take(3).joinToString(" • ") { it.name },
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // 4. Primary Action Button
                    Button(
                        onClick = {
                            if (activeSession != null) {
                                onResumeWorkout()
                            } else if (todayScheduledDay != null) {
                                onStartWorkout(todayScheduledDay.name, todayDayExercises.ifEmpty { allExercises.take(3) })
                            } else {
                                onStartWorkout("Quick Workout", allExercises.take(3))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.primaryButtonColors()
                    ) {
                        Text(
                            if (activeSession != null) "RESUME WORKOUT" else "START WORKOUT",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        // =========================================================
        // SCROLL DOWN → MENU
        // =========================================================
        item {
            Spacer(Modifier.height(8.dp))
            Text("MENU", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }

        // 1. Edit Splits & Days
        item {
            Chip(
                onClick = onOpenEditSplits,
                label = { Text("Edit Splits & Days") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }

        // 2. Sync
        item {
            Chip(
                onClick = {
                    if (!isSyncing) {
                        onSync()
                    }
                },
                enabled = !isSyncing,
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 2.dp,
                                indicatorColor = MaterialTheme.colors.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Syncing...", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("Sync", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                secondaryLabel = {
                    val statusText = when {
                        isSyncing -> "In progress"
                        syncStatusMessage != null -> syncStatusMessage
                        lastSyncTime != null -> "Last: $lastSyncTime"
                        else -> "Tap to sync"
                    }
                    Text(
                        statusText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = when {
                            syncStatusMessage?.contains("unavailable", ignoreCase = true) == true ||
                            syncStatusMessage?.contains("expired", ignoreCase = true) == true ||
                            syncStatusMessage?.contains("error", ignoreCase = true) == true -> MaterialTheme.colors.error
                            syncStatusMessage?.contains("complete", ignoreCase = true) == true -> MaterialTheme.colors.primary
                            else -> MaterialTheme.colors.onSurfaceVariant
                        }
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }

        // 3. Settings (includes Device Pairing)
        item {
            Chip(
                onClick = onOpenSettings,
                label = { Text("Settings") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun EditSplitsScreen(
    splitRepo: SplitRepository,
    onSelectSplit: (UUID) -> Unit
) {
    var splits by remember { mutableStateOf<List<Split>>(emptyList()) }

    LaunchedEffect(Unit) {
        splits = splitRepo.observeAll().first()
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 26.dp, bottom = 36.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Workout Splits", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        if (splits.isEmpty()) {
            item {
                Text("No splits configured", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
            }
        } else {
            items(splits.size) { idx ->
                val split = splits[idx]
                Chip(
                    onClick = { onSelectSplit(split.id) },
                    label = { Text(split.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = if (split.isActive) { { Text("Active Split", color = MaterialTheme.colors.primary) } } else null,
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
                )
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun SplitDaysScreen(
    splitId: UUID?,
    splitRepo: SplitRepository,
    onSelectDay: (UUID) -> Unit
) {
    var days by remember { mutableStateOf<List<SplitDay>>(emptyList()) }

    LaunchedEffect(splitId) {
        if (splitId != null) {
            days = splitRepo.observeDays(splitId).first()
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 26.dp, bottom = 36.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Training Days", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        if (days.isEmpty()) {
            item {
                Text("No days in split", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
            }
        } else {
            items(days.size) { idx ->
                val day = days[idx]
                Chip(
                    onClick = { onSelectDay(day.id) },
                    label = { Text(day.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
                )
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DayExercisesScreen(
    dayId: UUID?,
    splitRepo: SplitRepository,
    exerciseRepo: ExerciseRepository
) {
    var dayExercises by remember { mutableStateOf<List<SplitDayExercise>>(emptyList()) }
    var allExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(dayId) {
        if (dayId != null) {
            dayExercises = splitRepo.observeDayExercises(dayId).first()
            allExercises = exerciseRepo.observeAll().first()
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 26.dp, bottom = 36.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Day Exercises", style = MaterialTheme.typography.title3)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        if (dayExercises.isEmpty()) {
            item {
                Text("No exercises in day", style = MaterialTheme.typography.body2)
            }
        } else {
            items(dayExercises.size) { idx ->
                val de = dayExercises[idx]
                val ex = allExercises.firstOrNull { it.id == de.exerciseId }
                Row(
                    modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(ex?.name ?: "Exercise", style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
                    CompactButton(
                        onClick = {
                            scope.launch {
                                splitRepo.softDeleteDayExercise(de.id)
                                if (dayId != null) {
                                    dayExercises = splitRepo.observeDayExercises(dayId).first()
                                }
                            }
                        }
                    ) {
                        Text("X", color = MaterialTheme.colors.error)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun WatchSettingsScreen(
    serverClient: ServerClient,
    httpClient: HttpClient,
    onGenerateQr: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var urlText by remember { mutableStateOf(serverClient.serverUrl.ifBlank { "http://10.0.2.2:8081" }) }
    var saveStatus by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var showHostPresets by remember { mutableStateOf(false) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 26.dp, bottom = 36.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Spacer(Modifier.height(2.dp))
            Text("SERVER URL", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
        item {
            Spacer(Modifier.height(2.dp))
            Card(
                onClick = { showHostPresets = !showHostPresets },
                modifier = Modifier.fillMaxWidth(0.88f)
            ) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = urlText,
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (showHostPresets) "Close options" else "Tap to change host",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
        }

        if (showHostPresets) {
            item {
                Spacer(Modifier.height(2.dp))
                Text("Select Host Preset:", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
            }
            item {
                CompactChip(
                    onClick = { urlText = "http://10.0.2.2:8081"; showHostPresets = false },
                    label = { Text("10.0.2.2:8081 (Emulator)") },
                    modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 1.dp)
                )
            }
            item {
                CompactChip(
                    onClick = { urlText = "http://192.168.1.100:8081"; showHostPresets = false },
                    label = { Text("192.168.1.100:8081 (LAN)") },
                    modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 1.dp)
                )
            }
            item {
                CompactChip(
                    onClick = { urlText = "http://localhost:8081"; showHostPresets = false },
                    label = { Text("localhost:8081") },
                    modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 1.dp)
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (isChecking) return@Button
                    scope.launch {
                        isChecking = true
                        saveStatus = "Verifying connection..."
                        val trimmed = urlText.trim()
                        if (trimmed.isBlank()) {
                            saveStatus = "URL cannot be empty"
                            isChecking = false
                            return@launch
                        }
                        val cleanUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) "http://$trimmed" else trimmed
                        serverClient.serverUrl = cleanUrl
                        try {
                            val health = serverClient.checkHealth(cleanUrl, httpClient)
                            saveStatus = "Connected! (Server status: ${health.status})"
                        } catch (e: Exception) {
                            saveStatus = "Connection failed: ${e.localizedMessage ?: "Unreachable"}"
                        } finally {
                            isChecking = false
                        }
                    }
                },
                enabled = !isChecking,
                modifier = Modifier.fillMaxWidth(0.85f).height(36.dp)
            ) {
                Text(if (isChecking) "Checking..." else "Save / Confirm URL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (saveStatus != null) {
            item {
                Text(
                    saveStatus!!,
                    style = MaterialTheme.typography.caption2,
                    color = if (saveStatus?.contains("Connected", ignoreCase = true) == true) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                    textAlign = TextAlign.Center
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Chip(
                onClick = onGenerateQr,
                label = { Text("Generate QR Code", textAlign = TextAlign.Center) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 2.dp)
            )
        }

        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun QrPairingScreen(
    serverClient: ServerClient
) {
    val deviceId = serverClient.deviceId ?: remember {
        val newId = UUID.randomUUID().toString()
        serverClient.deviceId = newId
        newId
    }
    val serverUrl = serverClient.serverUrl.ifBlank { "http://10.0.2.2:8081" }
    val pairingPayload = remember(serverUrl, deviceId) {
        "powertrack://pair?url=$serverUrl&deviceId=$deviceId"
    }

    val qrBitmap = remember(pairingPayload) {
        generateQrBitmap(pairingPayload, 160)
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Device Pairing", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Text(
                "Scan with phone Settings",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color.White),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Pairing QR Code",
                        modifier = Modifier.size(112.dp)
                    )
                }
            }
        }
        item {
            Spacer(Modifier.height(2.dp))
            Text(
                "Code: ${deviceId.take(8).uppercase()}",
                style = MaterialTheme.typography.caption1,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun WorkoutLoggingScreen(
    exercise: WorkoutExercise,
    exerciseIndex: Int,
    totalExercises: Int,
    sets: List<SetEntry>,
    weight: Double,
    reps: Int,
    onWeightChange: (Double) -> Unit,
    onRepsChange: (Int) -> Unit,
    onPrevExercise: () -> Unit,
    onNextExercise: () -> Unit,
    onAddSet: () -> Unit,
    onAddExercise: () -> Unit,
    onSwapExercise: () -> Unit,
    onRemoveExercise: () -> Unit,
    onFinishWorkout: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                val delta = if (event.verticalScrollPixels > 0) -0.5 else 0.5
                onWeightChange(weight + delta)
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 24.dp, bottom = 36.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(
                exercise.exerciseName,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        item {
            Text(
                "Exercise ${exerciseIndex + 1} of $totalExercises",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CompactButton(
                    onClick = onPrevExercise,
                    enabled = exerciseIndex > 0
                ) {
                    Text("<")
                }
                CompactButton(
                    onClick = onNextExercise,
                    enabled = exerciseIndex < totalExercises - 1
                ) {
                    Text(">")
                }
            }
        }

        item {
            Text(
                "Logged Sets: ${sets.size}",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.primary
            )
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.88f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onWeightChange(weight - 2.5) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text("${weight}kg", style = MaterialTheme.typography.title3)
                Button(
                    onClick = { onWeightChange(weight + 2.5) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.88f),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onRepsChange(reps - 1) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text("$reps reps", style = MaterialTheme.typography.body1)
                Button(
                    onClick = { onRepsChange(reps + 1) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = onAddSet,
                modifier = Modifier.fillMaxWidth(0.85f).height(40.dp)
            ) {
                Text("LOG SET", fontWeight = FontWeight.Bold)
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.85f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                CompactChip(
                    onClick = onAddExercise,
                    label = { Text("+Add", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f)
                )
                CompactChip(
                    onClick = onSwapExercise,
                    label = { Text("Swap", fontSize = 10.sp) },
                    modifier = Modifier.weight(1f)
                )
                CompactChip(
                    onClick = onRemoveExercise,
                    label = { Text("Del", fontSize = 10.sp) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Spacer(Modifier.height(6.dp))
            CompactChip(
                onClick = onFinishWorkout,
                label = { Text("Finish Workout", textAlign = TextAlign.Center) },
                colors = ChipDefaults.primaryChipColors(backgroundColor = MaterialTheme.colors.error),
                modifier = Modifier.fillMaxWidth(0.85f).padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
fun RestTimerScreen(
    secondsLeft: Int,
    onAdjustTime: (Int) -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Rest Timer", style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("${secondsLeft}s", style = MaterialTheme.typography.display1, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(onClick = { onAdjustTime(-15) }) {
                Text("-15")
            }
            CompactButton(onClick = { onAdjustTime(15) }) {
                Text("+15")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(0.7f).height(36.dp)
        ) {
            Text("Skip")
        }
    }
}

@Composable
fun ExercisePickerScreen(
    exerciseRepo: ExerciseRepository,
    onSelectExercise: (Exercise) -> Unit
) {
    var exercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }

    LaunchedEffect(Unit) {
        exercises = exerciseRepo.observeAll().first()
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 26.dp, bottom = 36.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Select Exercise", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        items(exercises.size) { idx ->
            val ex = exercises[idx]
            Chip(
                onClick = { onSelectExercise(ex) },
                label = { Text(ex.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Spacer(Modifier.height(16.dp))
        }
    }
}

fun generateQrBitmap(content: String, sizePx: Int = 200): Bitmap? {
    return try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (_: Exception) {
        null
    }
}

fun triggerHapticBuzz(context: Context) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
            )
        }
    } catch (_: Exception) {}
}
