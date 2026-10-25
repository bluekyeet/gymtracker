package com.gymtracker.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.gymtracker.data.repository.WorkoutRepository
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(nav: NavController, workoutRepo: WorkoutRepository) {
    val sessions by workoutRepo.observeSessions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val dateFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy").withZone(ZoneId.systemDefault())
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${selected.size} workout${if (selected.size == 1) "" else "s"}?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            selected.forEach { workoutRepo.softDeleteSession(it) }
                        }
                        showDeleteDialog = false
                        selectMode = false
                        selected = emptySet()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("History", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${sessions.size} workout${if (sessions.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (selectMode) {
                TextButton(onClick = {
                    selected = if (selected.size == sessions.size) emptySet()
                    else sessions.map { it.id }.toSet()
                }) {
                    Text(if (selected.size == sessions.size) "Deselect all" else "Select all")
                }
                IconButton(
                    onClick = { showDeleteDialog = true },
                    enabled = selected.isNotEmpty()
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { selectMode = false; selected = emptySet() }) {
                    Text("Cancel")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (sessions.isEmpty()) {
            Text(
                "No workouts yet. Start one from the Today tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sessions, key = { it.id }) { s ->
                    val inProgress = s.endedAt == null
                    val isSelected = s.id in selected

                    Card(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectMode) {
                                        selected = if (isSelected) selected - s.id else selected + s.id
                                    } else {
                                        nav.navigate("workout/${s.id}")
                                    }
                                },
                                onLongClick = {
                                    if (!selectMode) {
                                        selectMode = true
                                        selected = setOf(s.id)
                                    }
                                }
                            ),
                        colors = if (isSelected)
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        else CardDefaults.cardColors()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (selectMode) {
                                Icon(
                                    if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        s.dayName ?: "Workout",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (inProgress) {
                                        Text(
                                            "In progress",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${dateFormatter.format(s.startedAt)} · ${timeFormatter.format(s.startedAt)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!inProgress && s.endedAt != null) {
                                    val minutes = java.time.Duration.between(s.startedAt, s.endedAt).toMinutes()
                                    Text(
                                        "Completed · ${minutes} min",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
