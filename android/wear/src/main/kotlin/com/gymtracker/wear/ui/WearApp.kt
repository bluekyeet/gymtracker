package com.gymtracker.wear.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
fun WearApp(onWorkoutActive: (Boolean) -> Unit = {}) {
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
    var selectedSplitForOptions by remember { mutableStateOf<Split?>(null) }
    var selectedDayForOptions by remember { mutableStateOf<SplitDay?>(null) }
    var selectedDayExerciseForEdit by remember { mutableStateOf<SplitDayExercise?>(null) }
    var selectedDayExerciseName by remember { mutableStateOf<String>("") }

    // Text input & Delete confirmation states
    var nameInputTitle by remember { mutableStateOf("") }
    var nameInputInitialValue by remember { mutableStateOf("") }
    var nameInputPresets by remember { mutableStateOf<List<String>>(emptyList()) }
    var nameInputConfirmAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var deleteConfirmTitle by remember { mutableStateOf("") }
    var deleteConfirmMessage by remember { mutableStateOf("") }
    var deleteConfirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Day input state (with Weekdays)
    var dayInputTitle by remember { mutableStateOf("") }
    var dayInputInitialName by remember { mutableStateOf("") }
    var dayInputInitialWeekdays by remember { mutableStateOf<List<Int>>(emptyList()) }
    var dayInputPresets by remember { mutableStateOf<List<String>>(emptyList()) }
    var dayInputConfirmAction by remember { mutableStateOf<((String, List<Int>) -> Unit)?>(null) }

    // System Keyboard launcher
    var onTextResultAction by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val remoteInputLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val results = RemoteInput.getResultsFromIntent(result.data)
            val text = results?.getCharSequence("input_text")?.toString()
            if (!text.isNullOrBlank()) {
                onTextResultAction?.invoke(text.trim())
            }
        }
    }

    fun launchSystemKeyboard(label: String, onResult: (String) -> Unit) {
        onTextResultAction = onResult
        try {
            val remoteInput = RemoteInput.Builder("input_text")
                .setLabel(label)
                .build()
            val intent = androidx.wear.input.RemoteInputIntentHelper.createActionRemoteInputIntent()
            androidx.wear.input.RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
            remoteInputLauncher.launch(intent)
        } catch (e: Exception) {
            // Fallback for emulator if remote input intent is unresolvable
        }
    }

    // Active session state
    var activeSession by remember { mutableStateOf<WorkoutSession?>(null) }
    var sessionExercises by remember { mutableStateOf<List<WorkoutExercise>>(emptyList()) }
    var currentExerciseIndex by remember { mutableIntStateOf(0) }
    val currentExercise = sessionExercises.getOrNull(currentExerciseIndex)

    // Keep screen on during active workout
    LaunchedEffect(activeSession) {
        onWorkoutActive(activeSession != null)
    }

    // Current exercise sets & input steppers
    var currentSets by remember { mutableStateOf<List<SetEntry>>(emptyList()) }
    var weightInput by remember { mutableDoubleStateOf(60.0) }
    var repsInput by remember { mutableIntStateOf(8) }
    var lastSessionSetsInfo by remember { mutableStateOf<List<SetEntry>>(emptyList()) }
    var targetSetsInfo by remember { mutableStateOf<Int?>(null) }
    var targetRepsInfo by remember { mutableStateOf<Int?>(null) }

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
        val exerciseLibId = currentExercise?.exerciseId
        if (exId != null) {
            val sets = workoutRepo.observeSets(exId).first()
            currentSets = sets

            // Load last session sets & plan targets for display info
            val sessionStarted = activeSession?.startedAt ?: java.time.Instant.now()
            val lastPair = if (exerciseLibId != null) {
                workoutRepo.lastSessionSets(exerciseLibId, sessionStarted)
            } else null
            lastSessionSetsInfo = lastPair?.second ?: emptyList()

            val dayExercises = if (exerciseLibId != null) {
                val dayId = activeSession?.splitDayId
                if (dayId != null) splitRepo.observeDayExercises(dayId).first() else emptyList()
            } else emptyList()
            val matchDe = dayExercises.firstOrNull { it.exerciseId == exerciseLibId }
            targetSetsInfo = matchDe?.targetSets
            targetRepsInfo = matchDe?.targetReps

            if (sets.isNotEmpty()) {
                // Already logged sets this session — pre-fill from last logged set
                val last = sets.last()
                weightInput = last.weightKg
                repsInput = last.reps
            } else {
                // No sets yet — use last session's weight, and target reps from split plan
                val lastWeight = lastSessionSetsInfo
                    .filter { it.setType != com.gymtracker.shared.domain.SetType.WARMUP }
                    .maxByOrNull { it.weightKg }?.weightKg

                if (lastWeight != null) weightInput = lastWeight
                if (matchDe?.targetReps != null) repsInput = matchDe.targetReps!!
            }
        } else {
            currentSets = emptyList()
            lastSessionSetsInfo = emptyList()
            targetSetsInfo = null
            targetRepsInfo = null
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
                onOpenHistory = {
                    navigateTo("history")
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
                        lastSessionSets = lastSessionSetsInfo,
                        targetSets = targetSetsInfo,
                        targetReps = targetRepsInfo,
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
                                if (serverClient.restTimerEnabled) {
                                    restSecondsLeft = 60
                                    restTimerRunning = true
                                    navigateTo("rest_timer")
                                }
                            }
                        },
                        onDeleteSet = { setId ->
                            scope.launch {
                                workoutRepo.softDeleteSet(setId)
                                currentSets = workoutRepo.observeSets(currentExercise.id).first()
                            }
                        },
                        onAddExercise = {
                            pickMode = "add"
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
                            navigateTo("finish_confirm")
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
                },
                onAddSplit = {
                    nameInputTitle = "Add New Split"
                    nameInputInitialValue = ""
                    nameInputPresets = listOf("Push Pull Legs", "Upper Lower", "Full Body", "3-Day Split", "4-Day Split", "Arnold Split")
                    nameInputConfirmAction = { newName ->
                        scope.launch {
                            val splits = splitRepo.observeAll().first()
                            val isFirst = splits.isEmpty()
                            val newSplit = Split(
                                id = UUID.randomUUID(),
                                userId = UUID.randomUUID(),
                                name = newName,
                                isActive = isFirst
                            )
                            splitRepo.save(newSplit)
                            if (isFirst) splitRepo.setActive(newSplit.id)
                            if (serverClient.serverUrl.isNotBlank()) triggerSync()
                        }
                    }
                    navigateTo("name_input")
                },
                onOptionsSplit = { split ->
                    selectedSplitForOptions = split
                    navigateTo("split_options")
                }
            )

            "split_options" -> {
                val split = selectedSplitForOptions
                if (split != null) {
                    SplitOptionsScreen(
                        split = split,
                        onSetActive = {
                            scope.launch {
                                splitRepo.setActive(split.id)
                                if (serverClient.serverUrl.isNotBlank()) triggerSync()
                            }
                            navigateBack()
                        },
                        onViewDays = {
                            selectedSplitId = split.id
                            navigateTo("split_days")
                        },
                        onRenameSplit = {
                            nameInputTitle = "Rename Split"
                            nameInputInitialValue = split.name
                            nameInputPresets = listOf("Push Pull Legs", "Upper Lower", "Full Body", "3-Day Split", "4-Day Split")
                            nameInputConfirmAction = { updatedName ->
                                scope.launch {
                                    splitRepo.save(split.copy(name = updatedName))
                                    if (serverClient.serverUrl.isNotBlank()) triggerSync()
                                }
                            }
                            navigateTo("name_input")
                        },
                        onDeleteSplit = {
                            deleteConfirmTitle = "Delete Split"
                            deleteConfirmMessage = "Delete '${split.name}' and all its days?"
                            deleteConfirmAction = {
                                scope.launch {
                                    splitRepo.softDeleteSplit(split.id)
                                    if (serverClient.serverUrl.isNotBlank()) triggerSync()
                                }
                                navigateBack()
                            }
                            navigateTo("confirm_delete")
                        }
                    )
                }
            }

            "split_days" -> SplitDaysScreen(
                splitId = selectedSplitId,
                splitRepo = splitRepo,
                onSelectDay = { dayId ->
                    selectedSplitDayId = dayId
                    navigateTo("day_exercises")
                },
                onAddDay = {
                    dayInputTitle = "Add Training Day"
                    dayInputInitialName = ""
                    dayInputInitialWeekdays = emptyList()
                    dayInputPresets = listOf("Push Day", "Pull Day", "Leg Day", "Upper Body", "Lower Body", "Chest & Triceps", "Back & Biceps", "Shoulders & Arms", "Cardio & Core")
                    dayInputConfirmAction = { newDayName, weekdays ->
                        val sId = selectedSplitId
                        if (sId != null) {
                            scope.launch {
                                val currentDays = splitRepo.observeDays(sId).first()
                                val newDay = SplitDay(
                                    id = UUID.randomUUID(),
                                    splitId = sId,
                                    name = newDayName,
                                    weekdays = weekdays,
                                    sortKey = currentDays.size.toDouble()
                                )
                                splitRepo.saveDay(newDay)
                                if (serverClient.serverUrl.isNotBlank()) triggerSync()
                            }
                        }
                    }
                    navigateTo("day_input")
                },
                onOptionsDay = { day ->
                    selectedDayForOptions = day
                    navigateTo("day_options")
                }
            )

            "day_options" -> {
                val day = selectedDayForOptions
                if (day != null) {
                    DayOptionsScreen(
                        day = day,
                        onViewExercises = {
                            selectedSplitDayId = day.id
                            navigateTo("day_exercises")
                        },
                        onRenameDay = {
                            dayInputTitle = "Edit Training Day"
                            dayInputInitialName = day.name
                            dayInputInitialWeekdays = day.weekdays
                            dayInputPresets = listOf("Push Day", "Pull Day", "Leg Day", "Upper Body", "Lower Body", "Arms & Shoulders")
                            dayInputConfirmAction = { updatedName, updatedWeekdays ->
                                scope.launch {
                                    splitRepo.saveDay(day.copy(name = updatedName, weekdays = updatedWeekdays))
                                    if (serverClient.serverUrl.isNotBlank()) triggerSync()
                                }
                            }
                            navigateTo("day_input")
                        },
                        onDeleteDay = {
                            deleteConfirmTitle = "Delete Day"
                            deleteConfirmMessage = "Delete '${day.name}' from this split?"
                            deleteConfirmAction = {
                                scope.launch {
                                    splitRepo.softDeleteDay(day.id)
                                    if (serverClient.serverUrl.isNotBlank()) triggerSync()
                                }
                                navigateBack()
                            }
                            navigateTo("confirm_delete")
                        }
                    )
                }
            }

            "day_exercises" -> DayExercisesScreen(
                dayId = selectedSplitDayId,
                splitRepo = splitRepo,
                exerciseRepo = exerciseRepo,
                onAddExercise = {
                    navigateTo("pick_day_exercise")
                },
                onEditExerciseTargets = { de, exName ->
                    selectedDayExerciseForEdit = de
                    selectedDayExerciseName = exName
                    navigateTo("edit_exercise_targets")
                },
                onRemoveExercise = { de, exName ->
                    deleteConfirmTitle = "Remove Exercise"
                    deleteConfirmMessage = "Remove '$exName' from this day?"
                    deleteConfirmAction = {
                        scope.launch {
                            splitRepo.softDeleteDayExercise(de.id)
                            if (serverClient.serverUrl.isNotBlank()) triggerSync()
                        }
                    }
                    navigateTo("confirm_delete")
                }
            )

            "edit_exercise_targets" -> {
                val de = selectedDayExerciseForEdit
                if (de != null) {
                    EditExerciseTargetsScreen(
                        exerciseName = selectedDayExerciseName,
                        initialSets = de.targetSets ?: 3,
                        initialReps = de.targetReps ?: 8,
                        onSaveTargets = { newSets, newReps ->
                            scope.launch {
                                val updated = de.copy(targetSets = newSets, targetReps = newReps)
                                splitRepo.saveDayExercise(updated)
                                if (serverClient.serverUrl.isNotBlank()) triggerSync()
                            }
                            navigateBack()
                        },
                        onCancel = { navigateBack() }
                    )
                }
            }

            "pick_day_exercise" -> AddExerciseToDayScreen(
                exerciseRepo = exerciseRepo,
                onLaunchKeyboard = ::launchSystemKeyboard,
                onSelectExercise = { selectedEx ->
                    val dayId = selectedSplitDayId
                    if (dayId != null) {
                        scope.launch {
                            val existing = splitRepo.observeDayExercises(dayId).first()
                            val newDe = SplitDayExercise(
                                id = UUID.randomUUID(),
                                splitDayId = dayId,
                                exerciseId = selectedEx.id,
                                sortKey = existing.size.toDouble(),
                                targetSets = 3,
                                targetReps = 8
                            )
                            splitRepo.saveDayExercise(newDe)
                            if (serverClient.serverUrl.isNotBlank()) triggerSync()
                        }
                    }
                    navigateBack()
                },
                onAddCustomExercise = {
                    nameInputTitle = "Custom Exercise Name"
                    nameInputInitialValue = ""
                    nameInputPresets = emptyList()
                    nameInputConfirmAction = { customName ->
                        scope.launch {
                            val newEx = Exercise(
                                id = UUID.randomUUID(),
                                name = customName,
                                isCustom = true
                            )
                            exerciseRepo.save(newEx)
                            val dayId = selectedSplitDayId
                            if (dayId != null) {
                                val existing = splitRepo.observeDayExercises(dayId).first()
                                val newDe = SplitDayExercise(
                                    id = UUID.randomUUID(),
                                    splitDayId = dayId,
                                    exerciseId = newEx.id,
                                    sortKey = existing.size.toDouble(),
                                    targetSets = 3,
                                    targetReps = 8
                                )
                                splitRepo.saveDayExercise(newDe)
                            }
                            if (serverClient.serverUrl.isNotBlank()) triggerSync()
                        }
                    }
                    navigateTo("name_input")
                }
            )

            "name_input" -> NameInputScreen(
                title = nameInputTitle,
                initialValue = nameInputInitialValue,
                presets = nameInputPresets,
                onLaunchKeyboard = ::launchSystemKeyboard,
                onConfirm = { name ->
                    nameInputConfirmAction?.invoke(name)
                    navigateBack()
                },
                onCancel = { navigateBack() }
            )

            "day_input" -> DayInputScreen(
                title = dayInputTitle,
                initialName = dayInputInitialName,
                initialWeekdays = dayInputInitialWeekdays,
                onLaunchKeyboard = ::launchSystemKeyboard,
                onConfirm = { name, weekdays ->
                    dayInputConfirmAction?.invoke(name, weekdays)
                    navigateBack()
                },
                onCancel = { navigateBack() }
            )

            "finish_confirm" -> FinishWorkoutScreen(
                sessionName = activeSession?.dayName ?: "Workout",
                onConfirm = {
                    scope.launch {
                        val session = activeSession
                        if (session != null) {
                            workoutRepo.saveSession(session.copy(endedAt = Instant.now()))
                            activeSession = null
                            sessionExercises = emptyList()
                        }
                        navigateBack()
                        navigateBack() // back past finish_confirm to home
                        if (serverClient.serverUrl.isNotBlank()) triggerSync()
                    }
                },
                onCancel = { navigateBack() }
            )

            "confirm_delete" -> ConfirmDeleteScreen(
                title = deleteConfirmTitle,
                message = deleteConfirmMessage,
                onConfirmDelete = {
                    deleteConfirmAction?.invoke()
                    navigateBack()
                },
                onCancel = { navigateBack() }
            )

            "history" -> HistoryScreen(
                workoutRepo = workoutRepo,
                onDeleteSession = { session ->
                    deleteConfirmTitle = "Delete Workout"
                    deleteConfirmMessage = "Delete '${session.dayName ?: "Workout"}'? This cannot be undone."
                    deleteConfirmAction = {
                        scope.launch {
                            workoutRepo.softDeleteSession(session.id)
                            if (serverClient.serverUrl.isNotBlank()) triggerSync()
                        }
                    }
                    navigateTo("confirm_delete")
                }
            )

            "settings" -> WatchSettingsScreen(
                serverClient = serverClient,
                httpClient = httpClient,
                onLaunchKeyboard = ::launchSystemKeyboard,
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
    onOpenHistory: () -> Unit,
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

        // 2. History
        item {
            Chip(
                onClick = onOpenHistory,
                label = { Text("History") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }

        // 3. Sync
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
    onSelectSplit: (UUID) -> Unit,
    onAddSplit: () -> Unit,
    onOptionsSplit: (Split) -> Unit
) {
    var splits by remember { mutableStateOf<List<Split>>(emptyList()) }

    LaunchedEffect(Unit) {
        splits = splitRepo.observeAll().first()
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Workout Splits", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        item {
            Chip(
                onClick = onAddSplit,
                label = { Text("+ Add Split", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        if (splits.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("No splits configured", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
            }
        } else {
            items(splits.size) { idx ->
                val split = splits[idx]
                Row(
                    modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip(
                        onClick = { onSelectSplit(split.id) },
                        label = { Text(split.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = if (split.isActive) { { Text("Active Split", color = MaterialTheme.colors.primary, fontSize = 10.sp) } } else null,
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    CompactButton(
                        onClick = { onOptionsSplit(split) },
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("⋮", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
fun HistoryScreen(
    workoutRepo: WorkoutRepository,
    onDeleteSession: (WorkoutSession) -> Unit
) {
    val sessions by workoutRepo.observeSessions().collectAsState(initial = emptyList())
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(java.time.ZoneId.systemDefault())
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("History", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        if (sessions.isEmpty()) {
            item {
                Text(
                    "No workouts yet",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        } else {
            items(sessions.size) { idx ->
                val session = sessions[idx]
                val inProgress = session.endedAt == null
                Row(
                    modifier = Modifier.fillMaxWidth(0.92f).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip(
                        onClick = {},
                        label = {
                            Text(
                                session.dayName ?: "Workout",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        secondaryLabel = {
                            Text(
                                if (inProgress) "In progress" else dateFormatter.format(session.startedAt),
                                fontSize = 10.sp,
                                color = if (inProgress) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    CompactButton(
                        onClick = { onDeleteSession(session) },
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.error)
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
fun SplitOptionsScreen(
    split: Split,
    onSetActive: () -> Unit,
    onViewDays: () -> Unit,
    onRenameSplit: () -> Unit,
    onDeleteSplit: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(split.name, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        item {
            Chip(
                onClick = onSetActive,
                label = { Text(if (split.isActive) "Active Split ✓" else "Set Active Split") },
                secondaryLabel = if (split.isActive) { { Text("Currently active", color = MaterialTheme.colors.primary, fontSize = 10.sp) } } else null,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Chip(
                onClick = onViewDays,
                label = { Text("View Training Days") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Chip(
                onClick = onRenameSplit,
                label = { Text("Rename Split") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Chip(
                onClick = onDeleteSplit,
                label = { Text("Delete Split", color = MaterialTheme.colors.error) },
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
fun SplitDaysScreen(
    splitId: UUID?,
    splitRepo: SplitRepository,
    onSelectDay: (UUID) -> Unit,
    onAddDay: () -> Unit,
    onOptionsDay: (SplitDay) -> Unit
) {
    var days by remember { mutableStateOf<List<SplitDay>>(emptyList()) }
    var splitName by remember { mutableStateOf("Training Days") }

    LaunchedEffect(splitId) {
        if (splitId != null) {
            val split = splitRepo.getSplit(splitId)
            if (split != null) splitName = split.name
            days = splitRepo.observeDays(splitId).first()
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(splitName, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        item {
            Chip(
                onClick = onAddDay,
                label = { Text("+ Add Day", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        if (days.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("No days in split", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
            }
        } else {
            items(days.size) { idx ->
                val day = days[idx]
                Row(
                    modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip(
                        onClick = { onSelectDay(day.id) },
                        label = { Text(day.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    CompactButton(
                        onClick = { onOptionsDay(day) },
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("⋮", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
fun DayOptionsScreen(
    day: SplitDay,
    onViewExercises: () -> Unit,
    onRenameDay: () -> Unit,
    onDeleteDay: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(day.name, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        item {
            Chip(
                onClick = onViewExercises,
                label = { Text("View Exercises") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Chip(
                onClick = onRenameDay,
                label = { Text("Edit Day & Weekdays") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Chip(
                onClick = onDeleteDay,
                label = { Text("Delete Day", color = MaterialTheme.colors.error) },
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
fun DayExercisesScreen(
    dayId: UUID?,
    splitRepo: SplitRepository,
    exerciseRepo: ExerciseRepository,
    onAddExercise: () -> Unit,
    onEditExerciseTargets: (SplitDayExercise, String) -> Unit,
    onRemoveExercise: (SplitDayExercise, String) -> Unit
) {
    var dayExercises by remember { mutableStateOf<List<SplitDayExercise>>(emptyList()) }
    var allExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
    var dayName by remember { mutableStateOf("Day Exercises") }

    LaunchedEffect(dayId) {
        if (dayId != null) {
            val day = splitRepo.getDay(dayId)
            if (day != null) dayName = day.name
            dayExercises = splitRepo.observeDayExercises(dayId).first()
            allExercises = exerciseRepo.observeAll().first()
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(dayName, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        item {
            Chip(
                onClick = onAddExercise,
                label = { Text("+ Add Exercise", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        if (dayExercises.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("No exercises in day", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
            }
        } else {
            items(dayExercises.size) { idx ->
                val de = dayExercises[idx]
                val ex = allExercises.firstOrNull { it.id == de.exerciseId }
                val exName = ex?.name ?: "Exercise"
                Row(
                    modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip(
                        onClick = { onEditExerciseTargets(de, exName) },
                        label = { Text(exName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            Text(
                                "${de.targetSets ?: 3} sets × ${de.targetReps ?: 8} reps",
                                color = MaterialTheme.colors.primary,
                                fontSize = 10.sp
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(4.dp))
                    CompactButton(
                        onClick = { onRemoveExercise(de, exName) },
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("✕", color = MaterialTheme.colors.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
fun EditExerciseTargetsScreen(
    exerciseName: String,
    initialSets: Int,
    initialReps: Int,
    onSaveTargets: (Int, Int) -> Unit,
    onCancel: () -> Unit
) {
    var sets by remember { mutableIntStateOf(initialSets) }
    var reps by remember { mutableIntStateOf(initialReps) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(exerciseName, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item {
            Spacer(Modifier.height(2.dp))
            Text("TARGET SETS", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactButton(onClick = { sets = (sets - 1).coerceAtLeast(1) }) {
                    Text("−", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text("$sets sets", style = MaterialTheme.typography.body1, fontWeight = FontWeight.Bold)
                CompactButton(onClick = { sets = (sets + 1).coerceAtMost(20) }) {
                    Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("TARGET REPS", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactButton(onClick = { reps = (reps - 1).coerceAtLeast(1) }) {
                    Text("−", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Text("$reps reps", style = MaterialTheme.typography.body1, fontWeight = FontWeight.Bold)
                CompactButton(onClick = { reps = (reps + 1).coerceAtMost(100) }) {
                    Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSaveTargets(sets, reps) },
                modifier = Modifier.fillMaxWidth(0.85f).height(36.dp)
            ) {
                Text("Save Changes", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            CompactChip(
                onClick = onCancel,
                label = { Text("Cancel", textAlign = TextAlign.Center) },
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun AddExerciseToDayScreen(
    exerciseRepo: ExerciseRepository,
    onLaunchKeyboard: (String, (String) -> Unit) -> Unit,
    onSelectExercise: (Exercise) -> Unit,
    onAddCustomExercise: () -> Unit
) {
    var exercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        exercises = exerciseRepo.observeAll().first()
    }

    val filtered = if (searchQuery.isBlank()) exercises else exercises.filter { it.name.contains(searchQuery, ignoreCase = true) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Add Exercise", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        item {
            Chip(
                onClick = {
                    onLaunchKeyboard("Search Exercise") { query ->
                        searchQuery = query
                    }
                },
                label = { Text(if (searchQuery.isBlank()) "🔍 Search Exercises..." else "🔍 $searchQuery", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        if (searchQuery.isNotBlank()) {
            item {
                CompactChip(
                    onClick = { searchQuery = "" },
                    label = { Text("Clear Search", textAlign = TextAlign.Center) },
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }
        item {
            Chip(
                onClick = onAddCustomExercise,
                label = { Text("+ Custom Exercise", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        if (filtered.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("No exercises found", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurfaceVariant)
            }
        } else {
            items(filtered.size) { idx ->
                val ex = filtered[idx]
                Chip(
                    onClick = { onSelectExercise(ex) },
                    label = { Text(ex.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
fun NameInputScreen(
    title: String,
    initialValue: String,
    presets: List<String>,
    onLaunchKeyboard: (String, (String) -> Unit) -> Unit,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var textValue by remember { mutableStateOf(initialValue) }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item {
            Spacer(Modifier.height(4.dp))
        }
        item {
            Chip(
                onClick = {
                    onLaunchKeyboard(title) { typed ->
                        textValue = typed
                    }
                },
                label = { Text(if (textValue.isBlank()) "⌨ Type Name..." else textValue, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (textValue.isBlank()) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.primary) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        if (textValue.isNotBlank()) {
            item {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { onConfirm(textValue.trim()) },
                    modifier = Modifier.fillMaxWidth(0.85f).height(36.dp)
                ) {
                    Text("Confirm", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("OR SELECT PRESET", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
        items(presets.size) { idx ->
            val preset = presets[idx]
            Chip(
                onClick = { onConfirm(preset) },
                label = { Text(preset, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            CompactChip(
                onClick = onCancel,
                label = { Text("Cancel", textAlign = TextAlign.Center) }
            )
        }
    }
}

@Composable
fun DayInputScreen(
    title: String,
    initialName: String,
    initialWeekdays: List<Int>,
    onLaunchKeyboard: (String, (String) -> Unit) -> Unit,
    onConfirm: (String, List<Int>) -> Unit,
    onCancel: () -> Unit
) {
    var dayName by remember { mutableStateOf(initialName) }
    // Use a List<Boolean> indexed 0=Mon..6=Sun so each item slot sees its own stable state slot
    var weekdayFlags by remember {
        mutableStateOf(List(7) { i -> initialWeekdays.contains(i + 1) })
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Chip(
                onClick = {
                    onLaunchKeyboard("Day Name") { typed -> dayName = typed }
                },
                label = {
                    Text(
                        if (dayName.isBlank()) "⌨ Type Day Name..." else dayName,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (dayName.isBlank()) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.primary
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text("WEEKDAYS", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
        // 7 explicit items — one per weekday — each reads its own index from the list
        val dayLabels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        for (i in 0..6) {
            item(key = i) {
                val label = dayLabels[i]
                val checked = weekdayFlags[i]
                ToggleChip(
                    checked = checked,
                    onCheckedChange = { on ->
                        weekdayFlags = weekdayFlags.toMutableList().also { it[i] = on }
                    },
                    label = { Text(label) },
                    toggleControl = {
                        Text(
                            if (checked) "✓" else "○",
                            fontWeight = FontWeight.Bold,
                            color = if (checked) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant
                        )
                    },
                    colors = ToggleChipDefaults.toggleChipColors(),
                    modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 1.dp)
                )
            }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Button(
                enabled = dayName.isNotBlank(),
                onClick = {
                    val selected = weekdayFlags.mapIndexedNotNull { i, on -> if (on) i + 1 else null }
                    onConfirm(dayName.trim(), selected)
                },
                modifier = Modifier.fillMaxWidth(0.85f).height(36.dp)
            ) {
                Text("Save Day", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            CompactChip(onClick = onCancel, label = { Text("Cancel", textAlign = TextAlign.Center) })
        }
    }
}

@Composable
fun ConfirmDeleteScreen(
    title: String,
    message: String,
    onConfirmDelete: () -> Unit,
    onCancel: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text(title, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, color = MaterialTheme.colors.error)
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }
        item {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirmDelete,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error),
                modifier = Modifier.fillMaxWidth(0.85f).height(36.dp)
            ) {
                Text("Delete", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colors.onError)
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            CompactChip(
                onClick = onCancel,
                label = { Text("Cancel", textAlign = TextAlign.Center) }
            )
        }
    }
}

@Composable
fun FinishWorkoutScreen(
    sessionName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        autoCentering = null,
        contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp, start = 10.dp, end = 10.dp)
    ) {
        item {
            Text("Finish Workout?", style = MaterialTheme.typography.title3, textAlign = TextAlign.Center, color = MaterialTheme.colors.primary)
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text(
                sessionName,
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                "Save and end this session?",
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }
        item {
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary),
                modifier = Modifier.fillMaxWidth(0.85f).height(36.dp)
            ) {
                Text("✓ Finish", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            CompactChip(
                onClick = onCancel,
                label = { Text("Keep Going", textAlign = TextAlign.Center) }
            )
        }
    }
}

@Composable
fun WatchSettingsScreen(
    serverClient: ServerClient,
    httpClient: HttpClient,
    onLaunchKeyboard: (String, (String) -> Unit) -> Unit,
    onGenerateQr: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var urlText by remember { mutableStateOf(serverClient.serverUrl) }
    var keyText by remember { mutableStateOf(serverClient.enrollmentKey) }
    var saveStatus by remember { mutableStateOf<String?>(null) }
    var isChecking by remember { mutableStateOf(false) }

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
            Chip(
                onClick = {
                    onLaunchKeyboard("Server URL") { typed ->
                        urlText = typed
                    }
                },
                label = { Text(if (urlText.isBlank()) "⌨ Type Server URL..." else urlText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (urlText.isBlank()) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.primary) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }

        item {
            Spacer(Modifier.height(2.dp))
            Text("ENROLLMENT KEY", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
        item {
            Spacer(Modifier.height(2.dp))
            Chip(
                onClick = {
                    onLaunchKeyboard("Enrollment Key") { typed ->
                        keyText = typed
                    }
                },
                label = { Text(if (keyText.isBlank()) "⌨ Type Enrollment Key..." else keyText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (keyText.isBlank()) MaterialTheme.colors.onSurfaceVariant else MaterialTheme.colors.primary) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 2.dp)
            )
        }

        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (isChecking) return@Button
                    scope.launch {
                        isChecking = true
                        saveStatus = "Connecting..."
                        val trimmed = urlText.trim()
                        val key = keyText.trim()
                        if (trimmed.isBlank()) {
                            saveStatus = "URL cannot be empty"
                            isChecking = false
                            return@launch
                        }
                        if (key.isBlank()) {
                            saveStatus = "Enrollment key cannot be empty"
                            isChecking = false
                            return@launch
                        }
                        val cleanUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) "http://$trimmed" else trimmed
                        try {
                            serverClient.checkHealth(cleanUrl, httpClient)
                            serverClient.enroll(cleanUrl, key, android.os.Build.MODEL, httpClient)
                            saveStatus = "Connected & enrolled!"
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
                Text(if (isChecking) "Checking..." else "Save & Enroll", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
            Spacer(Modifier.height(4.dp))
            Text("WORKOUT", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant)
        }
        item {
            var restOn by remember { mutableStateOf(serverClient.restTimerEnabled) }
            ToggleChip(
                checked = restOn,
                onCheckedChange = { on ->
                    restOn = on
                    serverClient.restTimerEnabled = on
                },
                label = { Text("Rest Timer") },
                secondaryLabel = { Text(if (restOn) "On after each set" else "Disabled", fontSize = 10.sp) },
                toggleControl = {
                    Text(
                        if (restOn) "✓" else "○",
                        fontWeight = FontWeight.Bold,
                        color = if (restOn) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant
                    )
                },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(0.88f).padding(vertical = 1.dp)
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
    lastSessionSets: List<SetEntry>,
    targetSets: Int?,
    targetReps: Int?,
    onWeightChange: (Double) -> Unit,
    onRepsChange: (Int) -> Unit,
    onPrevExercise: () -> Unit,
    onNextExercise: () -> Unit,
    onAddSet: () -> Unit,
    onDeleteSet: (UUID) -> Unit,
    onAddExercise: () -> Unit,
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

        // Target plan info or last session info summary card
        item {
            Spacer(Modifier.height(4.dp))
            Column(
                modifier = Modifier.fillMaxWidth(0.88f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (targetSets != null || targetReps != null) {
                    Text(
                        "Target: ${targetSets ?: "?"} sets × ${targetReps ?: "?"} reps",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.primary,
                        textAlign = TextAlign.Center
                    )
                }
                if (lastSessionSets.isNotEmpty()) {
                    val prevText = lastSessionSets.joinToString(", ") { "${it.weightKg}kg×${it.reps}" }
                    Text(
                        "Last: $prevText",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        "First time doing this exercise",
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
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
            Text(
                "Logged Sets: ${sets.size}",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.primary
            )
        }

        items(sets.size) { idx ->
            val s = sets[idx]
            Spacer(Modifier.height(2.dp))
            CompactChip(
                onClick = { onDeleteSet(s.id) },
                label = { Text("Set ${s.setNumber}: ${s.weightKg}kg × ${s.reps} (✕)", fontSize = 10.sp) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }
        item {
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                CompactButton(
                    onClick = onPrevExercise,
                    enabled = exerciseIndex > 0
                ) {
                    Text("‹", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                CompactButton(
                    onClick = onNextExercise,
                    enabled = exerciseIndex < totalExercises - 1
                ) {
                    Text("›", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
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
