package com.gymtracker.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.gymtracker.shared.domain.fuzzyFilter
import com.gymtracker.shared.domain.fuzzyScore
import com.gymtracker.shared.domain.groupName
import com.gymtracker.shared.domain.label
import com.gymtracker.shared.domain.resolveGroup
import kotlinx.coroutines.launch
import java.util.UUID

private data class ExerciseSection(val title: String, val exercises: List<Exercise>)

/** Built-in groups in enum order, the user's own groups after them, each alphabetical. */
private fun compareGroups(a: String, b: String): Int {
    val aOrder = MuscleGroup.values().firstOrNull { it.label.equals(a, ignoreCase = true) }?.ordinal ?: Int.MAX_VALUE
    val bOrder = MuscleGroup.values().firstOrNull { it.label.equals(b, ignoreCase = true) }?.ordinal ?: Int.MAX_VALUE
    if (aOrder != bOrder) return aOrder - bOrder
    return a.lowercase().compareTo(b.lowercase())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExercisesScreen(nav: NavController, exerciseRepo: ExerciseRepository) {
    val exercises by exerciseRepo.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Exercise?>(null) }

    // Fuzzy match on the name or the group, so "ch" finds everything in Chest.
    val visible = exercises
        .filter { groupFilter == null || it.groupName == groupFilter }
        .filter { query.isBlank() || fuzzyScore(query, it.name) != null || fuzzyScore(query, it.groupName ?: "") != null }
        .fuzzyFilter(query) { it.name }

    val presentGroups = exercises.mapNotNull { it.groupName }.distinct()
        .sortedWith(Comparator { a, b -> compareGroups(a, b) })

    val sections = visible
        .groupBy { it.groupName ?: "Other" }
        .entries
        .sortedWith(Comparator { a, b -> compareGroups(a.key, b.key) })
        .map { (group, list) ->
            ExerciseSection(title = group, exercises = list.sortedBy { it.name.lowercase() })
        }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Exercise Library", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${exercises.size} exercise${if (exercises.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New exercise")
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        if (presentGroups.isNotEmpty()) {
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
                        label = { Text(g) }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            exercises.isEmpty() -> Text(
                "No exercises yet. Tap + to add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            visible.isEmpty() -> Text(
                "No exercise matches \"${query.ifBlank { groupFilter ?: "" }}\".",
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
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ex.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = ex.groupName ?: "No muscle group",
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

    if (creating) {
        NewExerciseDialog(
            exercises = exercises,
            onConfirm = { name, groupLabel ->
                scope.launch {
                    if (exercises.none { it.name.equals(name, ignoreCase = true) }) {
                        val (builtIn, custom) = resolveGroup(groupLabel)
                        exerciseRepo.save(
                            Exercise(
                                id = UUID.randomUUID(),
                                name = name,
                                muscleGroup = builtIn,
                                customGroup = custom,
                                isCustom = true
                            )
                        )
                    }
                    creating = false
                }
            },
            onDismiss = { creating = false }
        )
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
