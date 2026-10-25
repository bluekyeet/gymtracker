package com.gymtracker.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.data.repository.SplitRepository
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.SplitDay
import com.gymtracker.shared.domain.SplitDayExercise
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayDetailScreen(
    nav: NavController,
    splitRepo: SplitRepository,
    exerciseRepo: ExerciseRepository,
    dayId: UUID
) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    val dayExercises by splitRepo.observeDayExercises(dayId).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var menuOpen by remember { mutableStateOf(false) }
    var newExerciseName by remember { mutableStateOf("") }
    var toRemove by remember { mutableStateOf<SplitDayExercise?>(null) }

    // Day header editing: name and assigned weekdays.
    var day by remember { mutableStateOf<SplitDay?>(null) }
    var nameState by remember { mutableStateOf("") }
    var weekdaysState by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }

    LaunchedEffect(dayId) {
        val d = splitRepo.getDay(dayId) ?: return@LaunchedEffect
        day = d
        nameState = d.name
        weekdaysState = d.weekdays.toSet()
        loaded = true
    }

    val dirty = loaded && day != null &&
        (nameState.trim() != day?.name || weekdaysState != day?.weekdays?.toSet())

    // Exercises already added to this day, in order, with their library entry.
    val rows = dayExercises.mapNotNull { de ->
        exercises.firstOrNull { it.id == de.exerciseId }?.let { de to it } ?: de to null
    }
    // Move an exercise up or down, then renumber the list so sort keys stay dense.
    fun move(from: Int, to: Int) {
        if (from == to || from !in rows.indices || to !in rows.indices) return
        val reordered = rows.map { it.first }.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        }
        scope.launch {
            reordered.forEachIndexed { index, de ->
                if (de.sortKey != index.toDouble()) {
                    splitRepo.saveDayExercise(de.copy(sortKey = index.toDouble()))
                }
            }
        }
    }

    // Library entries not yet on this day, grouped by muscle group.
    val available = exercises
        .filter { ex -> dayExercises.none { it.exerciseId == ex.id } }
        .groupBy { it.muscleGroup }
        .entries
        .sortedBy { it.key?.ordinal ?: Int.MAX_VALUE }
        .map { (group, list) ->
            (group?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Other") to
                list.sortedBy { it.name.lowercase() }
        }

    fun addExercise(ex: Exercise) {
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
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Edit Day", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Name the day, pick the weekdays it falls on, then set the exercise order below.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        // ---- Day settings -------------------------------------------------
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("DAY SETTINGS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = nameState,
                    onValueChange = { nameState = it },
                    label = { Text("Day name (e.g. Push Day)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Falls on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WEEKDAYS.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = weekdaysState.contains(idx + 1),
                            onClick = {
                                weekdaysState = if (weekdaysState.contains(idx + 1)) {
                                    weekdaysState - (idx + 1)
                                } else {
                                    weekdaysState + (idx + 1)
                                }
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            val target = day ?: return@Button
                            scope.launch {
                                val saved = target.copy(
                                    name = nameState.trim(),
                                    weekdays = weekdaysState.toList().sorted(),
                                    updatedAt = java.time.Instant.now()
                                )
                                splitRepo.saveDay(saved)
                                day = saved
                                savedFlash = true
                            }
                        },
                        enabled = dirty && nameState.isNotBlank()
                    ) { Text("Save day") }
                    if (savedFlash && !dirty) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Saved",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("EXERCISES IN ORDER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            "Use the arrows to reorder — this is the order in the workout.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(4.dp))

        rows.forEachIndexed { index, (de, ex) ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(32.dp)
                    )
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(enabled = ex != null) {
                                ex?.let { nav.navigate("exercise/${it.id}") }
                            }
                    ) {
                        Text(ex?.name ?: "Unknown exercise", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(ex?.muscleGroup?.name ?: "Removed from library", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { move(index, index - 1) }, enabled = index > 0) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${ex?.name ?: "exercise"} up")
                    }
                    IconButton(onClick = { move(index, index + 1) }, enabled = index < rows.lastIndex) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${ex?.name ?: "exercise"} down")
                    }
                    OutlinedButton(onClick = { toRemove = de }) { Text("Remove") }
                }
                RecommendedFields(
                    splitDayExercise = de,
                    onSave = { updated -> scope.launch { splitRepo.saveDayExercise(updated) } }
                )
            }
        }

        if (rows.isEmpty()) {
            Text(
                "No exercises yet. Add one below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))

        Box {
            Button(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Add from library") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (available.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Nothing left to add") },
                        onClick = { menuOpen = false }
                    )
                }
                available.forEach { (group, list) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                group.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = { menuOpen = false },
                        enabled = false
                    )
                    list.forEach { ex ->
                        DropdownMenuItem(
                            text = { Text(ex.name) },
                            onClick = {
                                addExercise(ex)
                                menuOpen = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Or create a new one", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newExerciseName,
                onValueChange = { newExerciseName = it },
                label = { Text("New exercise") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val name = newExerciseName.trim()
                if (name.isNotBlank()) {
                    scope.launch {
                        val existing = exercises.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        val created = existing ?: Exercise(id = UUID.randomUUID(), name = name, isCustom = true)
                            .also { exerciseRepo.save(it) }
                        splitRepo.saveDayExercise(
                            SplitDayExercise(
                                id = UUID.randomUUID(),
                                splitDayId = dayId,
                                exerciseId = created.id,
                                sortKey = dayExercises.size.toDouble()
                            )
                        )
                    }
                    newExerciseName = ""
                }
            }) { Text("Add") }
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = { nav.popBackStack() }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }

    if (toRemove != null) {
        val name = rows.firstOrNull { it.first == toRemove }?.second?.name ?: "this exercise"
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Remove exercise?") },
            text = { Text("Remove \"$name\" from this day? The exercise stays in your library.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = toRemove
                        toRemove = null
                        if (target != null) {
                            scope.launch { splitRepo.softDeleteDayExercise(target.id) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Remove") }
            },
            dismissButton = { OutlinedButton(onClick = { toRemove = null }) { Text("Cancel") } }
        )
    }
}

/** Recommended sets/reps for one exercise on this day. Values save as they become valid. */
@Composable
private fun RecommendedFields(
    splitDayExercise: SplitDayExercise,
    onSave: (SplitDayExercise) -> Unit
) {
    var setsText by remember(splitDayExercise.id) {
        mutableStateOf(splitDayExercise.targetSets?.toString() ?: "")
    }
    var repsText by remember(splitDayExercise.id) {
        mutableStateOf(splitDayExercise.targetReps?.toString() ?: "")
    }

    fun validSets(text: String): Boolean =
        text.isBlank() || (text.trim().toIntOrNull()?.let { it in 1..50 } ?: false)

    fun validReps(text: String): Boolean =
        text.isBlank() || (text.trim().toIntOrNull()?.let { it in 1..1000 } ?: false)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Target",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        OutlinedTextField(
            value = setsText,
            onValueChange = {
                setsText = it
                if (validSets(it)) {
                    onSave(splitDayExercise.copy(targetSets = it.trim().toIntOrNull()))
                }
            },
            label = { Text("Sets") },
            isError = !validSets(setsText),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = repsText,
            onValueChange = {
                repsText = it
                if (validReps(it)) {
                    onSave(splitDayExercise.copy(targetReps = it.trim().toIntOrNull()))
                }
            },
            label = { Text("Reps") },
            isError = !validReps(repsText),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(96.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "per set",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
