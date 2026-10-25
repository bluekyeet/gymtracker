package com.gymtracker.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.ExerciseRepository
import com.gymtracker.shared.domain.Exercise
import com.gymtracker.shared.domain.MuscleGroup
import kotlinx.coroutines.launch
import java.util.UUID

private data class ExerciseSection(val title: String, val exercises: List<Exercise>)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExercisesScreen(nav: NavController, exerciseRepo: ExerciseRepository) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<MuscleGroup?>(null) }
    var newName by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Exercise?>(null) }

    val visible = exercises.filter {
        it.name.contains(query.trim(), ignoreCase = true) &&
            (groupFilter == null || it.muscleGroup == groupFilter)
    }

    // One section per muscle group, in enum order, exercises alphabetical inside.
    val sections = visible
        .groupBy { it.muscleGroup }
        .entries
        .sortedBy { it.key?.ordinal ?: Int.MAX_VALUE }
        .map { (group, list) ->
            ExerciseSection(
                title = group?.name?.lowercase()?.replaceFirstChar { c -> c.uppercase() } ?: "Other",
                exercises = list.sortedBy { it.name.lowercase() }
            )
        }

    val presentGroups = exercises
        .mapNotNull { it.muscleGroup }
        .distinct()
        .sortedBy { it.ordinal }

    fun addExercise() {
        val name = newName.trim()
        if (name.isBlank()) return
        if (exercises.any { it.name.equals(name, ignoreCase = true) }) {
            newName = ""
            return
        }
        scope.launch {
            exerciseRepo.save(Exercise(id = UUID.randomUUID(), name = name, isCustom = true))
            newName = ""
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Exercise Library", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "${exercises.size} exercise${if (exercises.size == 1) "" else "s"}",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("New exercise") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { addExercise() }, enabled = newName.isNotBlank()) { Text("Add") }
        }

        if (exercises.size >= 6) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        if (exercises.isNotEmpty() && presentGroups.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = groupFilter == null,
                    onClick = { groupFilter = null },
                    label = { Text("All") }
                )
                presentGroups.forEach { g ->
                    FilterChip(
                        selected = groupFilter == g,
                        onClick = { groupFilter = if (groupFilter == g) null else g },
                        label = { Text(g.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            exercises.isEmpty() -> Text(
                "No exercises yet. They are created automatically on first launch.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            visible.isEmpty() -> Text(
                "No exercise matches \"${query.ifBlank { groupFilter?.name ?: "" }}\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sections.forEach { section ->
                    item(key = "header-${section.title}") {
                        Text(
                            text = section.title.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(section.exercises, key = { it.id }) { ex ->
                        Card(
                            Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate("exercise/${ex.id}") }
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(ex.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = ex.muscleGroup?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "No muscle group",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                OutlinedButton(onClick = { toDelete = ex }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete exercise?") },
            text = { Text("\"${toDelete?.name}\" is removed from your library. Past workouts keep their record of it.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = toDelete
                        toDelete = null
                        if (target != null) {
                            scope.launch { exerciseRepo.softDelete(target.id) }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}
